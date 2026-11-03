"""Server-side game settings (not editable in the app).

    python -m server.admin step <username> <momentum per milestone>
    python -m server.admin rate <value>          # override the next daily rate once (normally adapts by itself)
    python -m server.admin limit                 # show today's daily limit
    python -m server.admin recompute             # forget today's frozen limit (recomputed on next use)
    python -m server.admin reports [new|todo|done|all]  # bug reports / feature requests (default: todo)
    python -m server.admin report-done <id>      # mark a report as done
    python -m server.admin report-status <id> new|todo|done
    python -m server.admin report-note <id> <text>   # the owner's (reworded) task for a report
    python -m server.admin mail-test <address>   # a test e-mail with the server's MAIL_* config (server/mail.py)
    python -m server.admin issues              # send changed reports to their GitHub issues now (server/github.py)
    python -m server.admin grant <username>      # may use the admin app; prints the authenticator key (2FA)
    python -m server.admin revoke <username>     # no admin any more, its admin sessions end
    python -m server.admin totp-reset <username> # new authenticator key (lost phone), admin sessions end

Report status: new = just filed, agents only implement it after the owner explicitly says so (then it
becomes todo, with a reworded task in its note); todo = implement it; done = finished.
    python -m server.admin bake-overlays         # burn the texts of older posts into their photos / videos
    python -m server.admin dedupe-decay [--apply]  # double daily decays / automatic taps (report #27): list, or undo the extras
"""
import asyncio
import os
import sys

from sqlalchemy import select

from . import limits, security
from .db import Database, User, utcnow


async def set_milestone_step(s, username, step):
    user = await s.scalar(select(User).where(User.username == username.lower()))
    if user is None:
        raise SystemExit(f"unknown user {username}")
    user.settings.milestone_step = max(100, int(step))
    return user


async def main(argv):
    from .app import DEFAULT_DB
    db = Database(os.environ.get("MOMENTUM_DB", DEFAULT_DB))
    await db.create_all()
    async with db.session() as s:
        if argv[:1] == ["step"] and len(argv) == 3:
            user = await set_milestone_step(s, argv[1], argv[2])
            print(f"{user.username}: first milestone at {user.settings.milestone_step}, then doubling")
        elif argv[:1] == ["rate"] and len(argv) == 2:
            await limits.set_rate(s, float(argv[1]))
            print(f"rate = {float(argv[1])} for the next computed limit; it adapts from there")
        elif argv[:1] == ["recompute"]:
            from sqlalchemy import delete
            from .db import DailyCap
            await s.execute(delete(DailyCap).where(DailyCap.day == limits.server_day()))
            print("today's limit will be recomputed")
        elif argv[:1] == ["reports"]:
            from .db import Report
            base = os.environ.get("PUBLIC_URL", "https://momentum.mal-zeta.de")
            which = argv[1] if len(argv) > 1 else "todo"
            q = select(Report).order_by(Report.id)
            if which != "all":
                q = q.where(Report.status == which)
            from .github import GitHub
            gh = GitHub()
            for r in await s.scalars(q):
                print(f"#{r.id} [{r.kind}] {r.status} · {(r.platform or '?').upper()} · {r.created_at:%Y-%m-%d %H:%M} · {r.username} · {r.app_version} · {r.device}"
                      + (f" · {gh.issue_url(r)}" if gh.issue_url(r) else ""))
                print("   " + r.text.replace("\n", "\n   "))
                if r.note:
                    print("   NOTE: " + r.note.replace("\n", "\n   "))
                for key in filter(None, r.media_keys.split("\n")):
                    print(f"   image: {base}/{key}")
                print()
        elif argv[:1] == ["issues"]:
            from .github import GitHub
            gh = GitHub()
            if not gh.enabled:
                raise SystemExit("set GITHUB_REPO and GITHUB_TOKEN in .env")
            print(f"{await gh.sync(s)} issue(s) sent to {gh.repo}")
        elif argv[:1] == ["report-done"] and len(argv) == 2:
            from .db import Report
            r = await s.get(Report, int(argv[1]))
            if r is None:
                raise SystemExit("unknown report")
            r.status, r.status_at = "done", utcnow()
            print(f"#{r.id} done")
        elif argv[:1] == ["report-status"] and len(argv) == 3 and argv[2] in ("new", "todo", "done"):
            from .db import Report
            r = await s.get(Report, int(argv[1]))
            if r is None:
                raise SystemExit("unknown report")
            r.status, r.status_at = argv[2], utcnow()
            print(f"#{r.id} {r.status}")
        elif argv[:1] == ["mail-test"] and len(argv) == 2:
            from .mail import MailError, Mailer, render
            mailer = Mailer()
            if not mailer.enabled:
                raise SystemExit("set MAIL_TENANT_ID, MAIL_CLIENT_ID, MAIL_CLIENT_SECRET and MAIL_FROM in .env")
            try:
                await mailer.send(argv[1], *render("test", "de"))
            except MailError as e:
                raise SystemExit(f"not sent: {e}")
            print(f"sent from {mailer.sender} to {argv[1]}")
        elif argv[:1] == ["report-note"] and len(argv) == 3:
            from .db import Report
            r = await s.get(Report, int(argv[1]))
            if r is None:
                raise SystemExit("unknown report")
            r.note = argv[2].strip()[:4000]
            print(f"#{r.id} note: {r.note}")
        elif argv[:1] in (["grant"], ["revoke"], ["totp-reset"]) and len(argv) == 2:
            from sqlalchemy import delete
            from .db import AuthToken
            user = await s.scalar(select(User).where(User.username == argv[1].lower()))
            if user is None:
                raise SystemExit(f"unknown user {argv[1]}")
            if argv[0] != "grant" or not user.totp_secret:
                await s.execute(delete(AuthToken).where(AuthToken.user_id == user.id, AuthToken.scope == "admin"))
            user.admin = argv[0] != "revoke"
            if argv[0] == "totp-reset" or (argv[0] == "grant" and not user.totp_secret):
                user.totp_secret, user.totp_last_step = security.new_totp_secret(), 0
                key = " ".join(user.totp_secret[i:i + 4] for i in range(0, len(user.totp_secret), 4))
                print("Add this key to an authenticator app (Google Authenticator, Aegis, 1Password ...):")
                print(f"   {key}")
                print(f"   {security.totp_uri(user.username, user.totp_secret)}")
            print(f"{user.username}: admin {'on' if user.admin else 'off'}")
        elif argv[:1] == ["bake-overlays"]:
            from .blobs import MinioStore
            from .db import Media
            from .posts import bake_overlay
            blobs = MinioStore.from_env()
            await blobs.setup()
            done = failed = 0
            for md in list(await s.scalars(select(Media).where(Media.overlay.is_not(None), Media.post_id.is_not(None)))):
                print(f"media {md.id} (post {md.post_id}): {md.key} -> ", end="")
                # the originals stay (a way back: point the key at them again)
                if await bake_overlay(blobs, md, delete_old=False):
                    print(md.key)
                    done += 1
                else:
                    print("failed, stays live text")
                    failed += 1
                await s.commit()
            print(f"{done} burned in, {failed} left as live text")
        elif argv[:1] == ["dedupe-decay"]:
            from sqlalchemy import func as f
            from .db import Event
            from .service import undo
            apply = "--apply" in argv
            # the same user, the same booking time: a night's decay (or an automatic tap) booked twice
            groups = (await s.execute(select(Event.user_id, Event.kind, Event.created_at, Event.auto_from_rule_id, f.count())
                                      .where(Event.undone.is_(False), (Event.kind == "daily") | Event.auto_from_rule_id.is_not(None))
                                      .group_by(Event.user_id, Event.kind, Event.created_at, Event.auto_from_rule_id)
                                      .having(f.count() > 1))).all()
            for uid, kind, at, auto, n in groups:
                events = list(await s.scalars(select(Event).where(
                    Event.user_id == uid, Event.kind == kind, Event.created_at == at, Event.undone.is_(False),
                    Event.auto_from_rule_id.is_(None) if auto is None else Event.auto_from_rule_id == auto).order_by(Event.id)))
                user = await s.get(User, uid)
                print(f"{user.username if user else uid}: {kind} at {at:%Y-%m-%d %H:%M} x{n} -> undo {[e.id for e in events[1:]]} "
                      f"({sum(e.delta for e in events[1:]):+.0f})")
                if apply and user is not None:
                    for e in events[1:]:
                        await undo(s, user, e)
            print(f"{len(groups)} doubled" + (", undone" if apply else " (dry run: --apply undoes them)"))
        elif argv[:1] == ["limit"]:
            c = await limits.cap_for(s, limits.server_day())
            print(f"today {c.day}: limit (rate) {c.cap:.0f} · yesterday's average growth {c.average:.1f} of {c.players} players")
        else:
            print(__doc__)
            return
        await s.commit()
    await db.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))
