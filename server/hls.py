"""Adaptive streaming for post and story videos, the way Instagram does it.

On upload a video becomes one H.264 MP4 at once (server/media.py, `Media.key`): it plays everywhere and is all that
older Android apps know. Afterwards the background job (`run`) encodes it again, as an HLS ladder in HEVC: up to
three renditions (`RUNGS`: 720p / 480p / 360p on the short side, never above the source), each one fragmented MP4
that its playlist addresses by byte ranges (`/media` answers Range requests), plus a master playlist
(`Media.hls_key`; every file in `Media.hls_files`). The player picks the rendition that fits the connection and
switches while it plays; HEVC needs ~40 % less than H.264 for the same picture. Clients play `hls` where they can and
fall back to `url` (no HLS / HEVC, or the stream fails): web/src/ui/video.tsx = android/.../ui/Video.kt.

The newest videos go first; old ones are done afterwards, a video gets MAX_TRIES. ffmpeg runs at a low priority, so
uploads (which encode the MP4 while the player waits) win the CPU.
"""
import asyncio
import logging
import os
import re
import secrets
import tempfile
from pathlib import Path

from sqlalchemy import or_, select

from .blobs import BlobUnavailable
from .db import Media

log = logging.getLogger(__name__)

# (short side, highest video bitrate, audio bitrate); the CRF decides below the cap: calm scenes need far less
RUNGS = [(720, 1000, 96), (480, 550, 64), (360, 300, 64)]
CRF = 28
PRESET = "fast"
SEGMENT_SECONDS = 4
KEYFRAME_SECONDS = 2          # same keyframes in every rendition: the player can switch at every segment
MAX_FPS = 30
MAX_TRIES = 3
TIMEOUT = 1800
IDLE_SECONDS = 30
PLAYLIST_TYPE = "application/vnd.apple.mpegurl"


class StreamError(Exception):
    pass


def files_of(md):
    """Every blob key of a media item's stream."""
    return [k for k in (md.hls_files or "").split("\n") if k]


def forget(md):
    """The stream doesn't fit the video any more (text burned in): its keys, to delete; the job makes a new one."""
    old = files_of(md)
    md.hls_key, md.hls_files, md.hls_tries = None, None, 0
    return old


async def drop(blobs, keys):
    for k in keys:
        try:
            await blobs.delete(k)
        except BlobUnavailable:
            pass


def ladder(width, height):
    """[(short side, kbit/s video, kbit/s audio)]: the top one at most the source's size, the next ones at least
    ~20 % smaller than the one above."""
    short = min(width, height) if width and height else RUNGS[0][0]
    top = min(short, RUNGS[0][0])
    out = [(top - top % 2, *RUNGS[0][1:])]
    for rung in RUNGS[1:]:
        if rung[0] <= out[-1][0] * .8:
            out.append(rung)
    return out


def _ffmpeg():
    from .media import _ffmpeg as exe
    return exe()


def _low_priority():
    os.nice(15)


async def _run(args, cwd):
    """ffmpeg at a low priority; killed when the job is cancelled (shutdown) or takes too long."""
    kw = {"preexec_fn": _low_priority} if os.name == "posix" else {}
    proc = await asyncio.create_subprocess_exec(_ffmpeg(), *args, cwd=cwd, stdin=asyncio.subprocess.DEVNULL,
                                                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE, **kw)
    try:
        _, err = await asyncio.wait_for(proc.communicate(), TIMEOUT)
    except BaseException:
        proc.kill()
        await proc.wait()
        raise
    return proc.returncode, err.decode(errors="replace")


def command(width, height, fps, audio):
    """ffmpeg's arguments: one decode, a scaled copy per rung, HEVC + AAC, HLS with one fMP4 file per rung."""
    rungs = ladder(width, height)
    n = len(rungs)
    rate = f"fps={MAX_FPS}," if fps > MAX_FPS + .5 else ""
    graph = f"[0:v]{rate}split={n}" + "".join(f"[s{i}]" for i in range(n)) + "".join(
        f";[s{i}]scale='if(gt(iw,ih),-2,{side})':'if(gt(iw,ih),{side},-2)'[v{i}]" for i, (side, _, _) in enumerate(rungs))
    args = ["-hide_banner", "-loglevel", "error", "-y", "-i", "in.mp4", "-filter_complex", graph]
    for i in range(n):
        args += ["-map", f"[v{i}]"] + (["-map", "0:a:0"] if audio else [])
    args += ["-c:v", "libx265", "-tag:v", "hvc1", "-preset", PRESET, "-crf", str(CRF), "-pix_fmt", "yuv420p",
             "-force_key_frames", f"expr:gte(t,n_forced*{KEYFRAME_SECONDS})", "-forced-idr", "1",
             "-x265-params", "log-level=error:scenecut=0:open-gop=0:keyint=600:min-keyint=1"]
    for i, (_, video, sound) in enumerate(rungs):
        args += [f"-maxrate:v:{i}", f"{video}k", f"-bufsize:v:{i}", f"{video * 2}k"]
        if audio:
            args += [f"-b:a:{i}", f"{sound}k"]
    if audio:
        args += ["-c:a", "aac"]
    args += ["-f", "hls", "-hls_time", str(SEGMENT_SECONDS), "-hls_playlist_type", "vod",
             "-hls_segment_type", "fmp4", "-hls_flags", "single_file+independent_segments",
             "-master_pl_name", "master.m3u8",
             "-var_stream_map", " ".join(f"v:{i},a:{i}" if audio else f"v:{i}" for i in range(n)),
             "-hls_segment_filename", "out/v%v.mp4", "out/v%v.m3u8"]
    return args


async def build(data):
    """An MP4 -> {file name: (bytes, content type)} of its stream (master.m3u8, v0.m3u8, v0.mp4, ...)."""
    with tempfile.TemporaryDirectory() as tmp:
        Path(tmp, "in.mp4").write_bytes(data)
        Path(tmp, "out").mkdir()
        _, info = await _run(["-hide_banner", "-i", "in.mp4"], tmp)         # "no output file" - only the probe
        size = re.search(r"Video:.*?, (\d{2,5})x(\d{2,5})[ ,]", info)
        if not size:
            raise StreamError("no video")
        fps = re.search(r"Video:.*?(\d+(?:\.\d+)?) fps", info)
        args = command(int(size[1]), int(size[2]), float(fps[1]) if fps else 30.0, "Audio:" in info)
        code, err = await _run(args, tmp)
        out = Path(tmp, "out")
        if code != 0 or not (out / "master.m3u8").exists():
            raise StreamError(err[-500:])
        return {p.name: (p.read_bytes(), PLAYLIST_TYPE if p.suffix == ".m3u8" else "video/mp4")
                for p in sorted(out.iterdir())}


def pending():
    """Post and story videos without their stream yet, newest first."""
    return (select(Media).where(Media.kind == "video", Media.hls_key.is_(None), Media.hls_tries < MAX_TRIES,
                                Media.encrypted.is_(False), Media.key.like("media/%"),
                                or_(Media.post_id.is_not(None), Media.story_id.is_not(None)))
            .order_by(Media.id.desc()).limit(1))


async def encode_next(db, blobs):
    """Makes the stream of the next video waiting for one. False when none is waiting (or the store is down)."""
    from .storage import QuotaExceeded
    async with db.session() as s:
        md = await s.scalar(pending())
        if md is None:
            return False
        md.hls_tries += 1
        media_id, key = md.id, md.key
        await s.commit()
    try:
        obj = await blobs.get(key)
    except BlobUnavailable:
        async with db.session() as s:           # not the video's fault: the try doesn't count
            md = await s.get(Media, media_id)
            if md is not None:
                md.hls_tries -= 1
                await s.commit()
        return False
    if obj is None:
        return True
    try:
        files = await build(obj[0])
    except (StreamError, OSError, asyncio.TimeoutError) as e:
        log.warning("no stream for video %s: %s", media_id, e)
        return True
    # a new folder each time: the files are cached forever
    folder = key.rsplit(".", 1)[0] + "_hls" + secrets.token_hex(3)
    keys = []
    try:
        for name, (body, ctype) in files.items():
            keys.append(f"{folder}/{name}")
            await blobs.put(keys[-1], body, ctype)
    except (BlobUnavailable, QuotaExceeded):
        await drop(blobs, keys)
        return True
    async with db.session() as s:
        md = await s.get(Media, media_id)
        if md is None or md.key != key:          # deleted or replaced (text burned in) meanwhile
            await drop(blobs, keys)
            return True
        old = files_of(md)
        md.hls_key, md.hls_files = f"{folder}/master.m3u8", "\n".join(keys)
        await s.commit()
    await drop(blobs, old)
    return True


async def encode_all(db, blobs):
    """Every waiting video (tests, the e2e server); how many were done."""
    n = 0
    while await encode_next(db, blobs):
        n += 1
    return n


async def run(db, blobs):
    """The background job of the real server."""
    while True:
        try:
            busy = await encode_next(db, blobs)
        except Exception:
            log.exception("making a video stream failed")
            busy = False
        await asyncio.sleep(1 if busy else IDLE_SECONDS)
