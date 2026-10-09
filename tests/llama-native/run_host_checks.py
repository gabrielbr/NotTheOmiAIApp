#!/usr/bin/env python3
"""Build GMind's llama JNI for the host and run it on the pinned tiny test model.

Proves the JNI bridge (load, streaming in complete UTF-8 chunks, stop, limits, errors, close)
against the real pinned llama.cpp. Not an answer-quality test; never packaged.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from prepare_llama import SOURCE, CACHE, TEST_MODEL, TEST_MODEL_SHA, sha, verify_source  # noqa: E402


def run(args, **kwargs):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, text=True, capture_output=True, **kwargs)
    if result.returncode:
        print(result.stdout[-4000:], result.stderr[-4000:], sep="\n")
        raise subprocess.CalledProcessError(result.returncode, args)
    return result.stdout


def main():
    verify_source()
    model = CACHE / "llama-test" / TEST_MODEL
    if not model.is_file() or sha(model) != TEST_MODEL_SHA:
        raise SystemExit("Run scripts/prepare_llama.py first")
    build = CACHE / "llama-host-build"
    run(["cmake", "-S", ROOT / "sentient/src/main/cpp", "-B", build, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release",
         "-DGMIND_HOST_TEST=ON", f"-DLLAMA_SOURCE={SOURCE}"])
    run(["cmake", "--build", build, "--target", "gmind-llama"])
    library = build / "libgmind-llama.so"
    with tempfile.TemporaryDirectory(prefix="llama-host-") as work:
        run(["javac", "--release", "17", "-d", work, ROOT / "sentient/src/main/java/br/gabriel/sentient/LlamaNative.java",
             ROOT / "tests/llama-native/LlamaNativeHostTest.java"])
        print(run(["java", "-cp", work, "br.gabriel.sentient.LlamaNativeHostTest", library, model]).strip())


if __name__ == "__main__":
    main()
