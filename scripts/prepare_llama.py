#!/usr/bin/env python3
"""Fetch the pinned llama.cpp source (for GMind's on-device answers) and a tiny test model.

The source is pinned by git commit AND tree id and fetched with `git fetch --depth 1`, so the
same bytes arrive in CI and in sandboxes that only allow git reads. The tiny test model is for
host JNI tests only and never ships. The Qwen model GMind answers with is NOT downloaded here:
the app downloads it on request and checks these same pins (QWEN_*), mirrored in LocalModel.java.
"""
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".cache"
COMMIT = "50182a53fa2c26bd2a7fc31d855231effdc2f4ad"  # tag b10900, 2026-09-10
TREE = "49b684a22b6199a162e82dc29c42e37ee370109d"
REPOSITORY = "https://github.com/ggml-org/llama.cpp"
SOURCE = CACHE / "llama.cpp-50182a5"

TEST_MODEL_REVISION = "499bc8821c6b12b4e53c5bffcb21ec206f212d81"
TEST_MODEL = "stories260K.gguf"
TEST_MODEL_URL = f"https://huggingface.co/ggml-org/models-moved/resolve/{TEST_MODEL_REVISION}/tinyllamas/{TEST_MODEL}"
TEST_MODEL_SHA = "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d"
TEST_MODEL_BYTES = 1185376

QWEN_REVISION = "91cad51170dc346986eccefdc2dd33a9da36ead9"
QWEN_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
QWEN_URL = f"https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/{QWEN_REVISION}/{QWEN_FILE}"
QWEN_SHA = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
QWEN_BYTES = 1117320736
QWEN_LICENSE = "Apache-2.0"


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git(*args, cwd):
    return subprocess.run(["git", *args], cwd=cwd, check=True, text=True, capture_output=True).stdout.strip()


def verify_source():
    """The checkout must be exactly the pinned commit and tree, with no local changes."""
    if git("rev-parse", "HEAD", cwd=SOURCE) != COMMIT or git("rev-parse", "HEAD^{tree}", cwd=SOURCE) != TREE:
        raise ValueError("llama.cpp source is not the pinned commit/tree")
    if git("status", "--porcelain", "--untracked-files=no", cwd=SOURCE):
        raise ValueError("llama.cpp source has local modifications")
    if not (SOURCE / "include/llama.h").is_file():
        raise ValueError("llama.cpp source incomplete")


def fetch_source():
    if SOURCE.is_dir():
        try:
            verify_source()
            return
        except (ValueError, subprocess.CalledProcessError):
            shutil.rmtree(SOURCE)
    CACHE.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="llama-src-", dir=CACHE) as temporary:
        work = Path(temporary) / "src"
        work.mkdir()
        git("init", "-q", ".", cwd=work)
        git("fetch", "-q", "--depth", "1", REPOSITORY, COMMIT, cwd=work)
        git("-c", "advice.detachedHead=false", "checkout", "-q", "FETCH_HEAD", cwd=work)
        work.rename(SOURCE)
    verify_source()


def fetch_test_model():
    target = CACHE / "llama-test" / TEST_MODEL
    if target.is_file() and sha(target) == TEST_MODEL_SHA:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    data = urllib.request.urlopen(TEST_MODEL_URL, timeout=120).read()
    if len(data) != TEST_MODEL_BYTES or hashlib.sha256(data).hexdigest() != TEST_MODEL_SHA:
        raise ValueError("Test model hash mismatch")
    target.write_bytes(data)
    return target


def main():
    fetch_source()
    model = fetch_test_model()
    print(f"llama.cpp {COMMIT[:7]} (tree {TREE[:7]}) at {SOURCE.relative_to(ROOT)}; test model {model.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
