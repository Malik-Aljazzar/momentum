"""Bug reports / feature requests from the app as issues of the public GitHub repo.

One way: the server is the truth. Every report gets an issue (title = its kind and first line, body = the
text, the owner's note as the task, platform / app version / device); it never carries the reporter's name
or the screenshots - those stay in the admin app. Labels say what it is (bug / feature), where it came
from (android / pwa) and how far it is (status: new / todo / done); done closes the issue, back to todo or
new reopens it. The loop (`SYNC_SECONDS`) compares each report with what it last sent (`Report.issue_sync`,
a hash of the issue's content) and only talks to GitHub for what changed.

Config in the server's .env: GITHUB_REPO ("owner/name") and GITHUB_TOKEN (a token with issues: write on
that repo); either empty = off. `python -m server.admin issues` runs one round by hand.
"""
import asyncio
import hashlib
import json
import logging
import os
import urllib.error
import urllib.request

from sqlalchemy import select

from .db import Report

API = "https://api.github.com"
SYNC_SECONDS = 60
# name -> (colour, description); made in the repo on the first round
LABELS = {
    "bug": ("d73a4a", "Something isn't working"),
    "feature": ("a2eeef", "A wish from the app"),
    "android": ("3ddc84", "Reported from the Android app"),
    "pwa": ("5a67d8", "Reported from the iOS / web app (PWA)"),
    "status: new": ("ededed", "Just filed, waiting for the owner"),
    "status: todo": ("fbca04", "Approved: to be implemented"),
    "status: done": ("0e8a16", "Implemented"),
}
KINDS = {"bug": "Bug report", "feature": "Feature request"}
PLATFORMS = {"android": "Android", "pwa": "PWA"}
log = logging.getLogger(__name__)


def issue_of(r):
    """What the report's issue should look like: title, body, labels, state."""
    text = r.text.strip()
    first = (text.splitlines() or [""])[0].strip()
    if len(first) > 70:
        first = first[:69].rstrip() + "…"
    kind = KINDS.get(r.kind, r.kind)
    head = [f"**{kind} #{r.id}**"]
    if r.platform in PLATFORMS:
        head.append(PLATFORMS[r.platform])
    if r.app_version:
        head.append(f"app {r.app_version}")
    if r.device:
        head.append(r.device)
    lines = [" · ".join(head)]
    if r.created_at:
        lines.append(f"_filed {r.created_at:%Y-%m-%d %H:%M} UTC_")
    lines += [""] + ["> " + l for l in text.splitlines()]
    if r.note:
        lines += ["", "### Task", r.note.strip()]
    shots = len([k for k in r.media_keys.split("\n") if k])
    if shots:
        lines += ["", f"_{shots} screenshot{'s' if shots != 1 else ''} (in the admin app)_"]
    labels = [r.kind] + ([r.platform] if r.platform in PLATFORMS else []) + [f"status: {r.status}"]
    return dict(title=f"{kind} #{r.id}: {first}" if first else f"{kind} #{r.id}", body="\n".join(lines),
                labels=labels, state="closed" if r.status == "done" else "open")


def digest_of(issue):
    return hashlib.sha256(json.dumps(issue, sort_keys=True).encode()).hexdigest()[:24]


class GitHub:
    """The repo's issues. fetch(method, path, body) -> parsed JSON; tests pass a fake."""

    def __init__(self, repo=None, token=None, fetch=None, pause=1.0):
        self.repo = (repo if repo is not None else os.environ.get("GITHUB_REPO", "")).strip()
        self.token = (token if token is not None else os.environ.get("GITHUB_TOKEN", "")).strip()
        self.fetch = fetch or self._fetch
        self.pause = pause                   # between writes: GitHub throttles bursts of created content
        self._labels_ok = False

    @property
    def enabled(self):
        return bool(self.repo and self.token)

    def _fetch(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(API + path, data, method=method, headers={
            "Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "momentum-server",
            **({"Content-Type": "application/json"} if data else {})})
        with urllib.request.urlopen(req, timeout=20) as res:
            raw = res.read()
            return json.loads(raw) if raw else {}

    async def call(self, method, path, body=None):
        return await asyncio.to_thread(self.fetch, method, path, body)

    async def ensure_labels(self):
        if self._labels_ok:
            return
        have = {l["name"] for l in await self.call("GET", f"/repos/{self.repo}/labels?per_page=100")}
        for name, (color, desc) in LABELS.items():
            if name in have:
                continue
            try:
                await self.call("POST", f"/repos/{self.repo}/labels", dict(name=name, color=color, description=desc))
            except urllib.error.HTTPError as e:
                if e.code != 422:            # 422: made in the meantime
                    raise
        self._labels_ok = True

    async def sync(self, s):
        """Creates / updates the issues of every report whose content changed since it was last sent.
        Returns how many were sent (0 when off). Each report is committed on its own: a failure in the
        middle never makes a second issue for the same report."""
        if not self.enabled:
            return 0
        sent = 0
        for r in list(await s.scalars(select(Report).order_by(Report.id))):
            want = issue_of(r)
            digest = digest_of(want)
            if r.issue_number and r.issue_sync == digest:
                continue
            await self.ensure_labels()
            if r.issue_number:
                patch = dict(want, state_reason="completed") if want["state"] == "closed" else want
                await self.call("PATCH", f"/repos/{self.repo}/issues/{r.issue_number}", patch)
            else:
                res = await self.call("POST", f"/repos/{self.repo}/issues", {k: want[k] for k in ("title", "body", "labels")})
                r.issue_number = int(res["number"])
                if want["state"] == "closed":
                    await self.call("PATCH", f"/repos/{self.repo}/issues/{r.issue_number}",
                                    dict(state="closed", state_reason="completed"))
            r.issue_sync = digest
            await s.commit()
            sent += 1
            if self.pause:
                await asyncio.sleep(self.pause)
        return sent

    def issue_url(self, r):
        return f"https://github.com/{self.repo}/issues/{r.issue_number}" if self.repo and r.issue_number else None
