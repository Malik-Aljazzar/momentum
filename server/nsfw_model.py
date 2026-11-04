"""The content filter's image model (server/moderation.py): where it comes from and where it lives.

Falconsai's ViT "nsfw_image_detection" (Apache-2.0, labels normal / nsfw), converted to ONNX and quantized to
int8 by onnx-community (87 MB; about 0.1 s per picture on two CPU cores). Pinned to a revision and checked
against its SHA-256. Only the standard library here: the Dockerfile runs this file on its own (an early, cached
layer), so the model isn't downloaded again on every deploy.

    python server/nsfw_model.py [directory]     download it (default: MOMENTUM_NSFW_MODEL's directory)
"""
import hashlib
import os
import sys
import urllib.request
from pathlib import Path

REVISION = "1ceb3c7fe1e9f3f2507e6df577437f23a9149fd5"
URL = f"https://huggingface.co/onnx-community/nsfw_image_detection-ONNX/resolve/{REVISION}/onnx/model_int8.onnx"
SHA256 = "bdc941491cd09ae75c5f40b038a5cd64482fa4e2b5297747323c5cda31303920"
FILE = "nsfw_image_detection_int8.onnx"
# local development: next to the repo's other caches (not in a deploy, not in git)
LOCAL = Path(__file__).resolve().parent.parent / ".cache" / "models" / FILE


def path():
    return Path(os.environ.get("MOMENTUM_NSFW_MODEL") or LOCAL)


def download(target=None):
    target = Path(target) if target else path()
    if target.exists() and _sha256(target) == SHA256:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_suffix(".part")
    with urllib.request.urlopen(URL, timeout=120) as res, open(part, "wb") as out:
        while chunk := res.read(1 << 20):
            out.write(chunk)
    if _sha256(part) != SHA256:
        part.unlink()
        raise SystemExit(f"{URL}: unexpected SHA-256")
    part.replace(target)
    return target


def _sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


if __name__ == "__main__":
    print(download(Path(sys.argv[1]) / FILE if len(sys.argv) > 1 else None))
