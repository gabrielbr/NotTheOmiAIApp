#!/usr/bin/env python3
"""Build GMind's CPU-only Android llama.cpp JNI (libgmind-llama.so); same recipe as Whisper.

Run prepare_llama.py first. Builds never download anything. Default builds both supported ABIs.
--verify-reproducible performs an independent clean rebuild and requires byte-identical output.
The Qwen model is not bundled: GMind downloads it on request and checks the pins in
prepare_llama.py (mirrored in LocalModel.java).
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from prepare_llama import (ROOT, CACHE, SOURCE, COMMIT, TREE, REPOSITORY, QWEN_URL, QWEN_SHA, QWEN_BYTES,
                           sha, verify_source)

NDK_VERSION = "27.2.12479018"
ABIS = ("arm64-v8a", "x86_64")
LIBRARY = "libgmind-llama.so"
# arm64 only: dot-product, half-precision and int8 matrix-multiply instructions (e.g. Tensor G4),
# loaded at runtime by LlamaNative when every core has them.
FAST_LIBRARY = "libgmind-llama-fast.so"
FAST_ARCH = "-march=armv8.2-a+dotprod+fp16+i8mm"
CPP = ROOT / "sentient/src/main/cpp"
RECEIPT = ROOT / "verification/llama-native-build.json"
LICENSE = ROOT / "sentient/src/main/assets/licenses/llama.cpp-MIT.txt"
EXPORTS = {f"Java_br_gabriel_sentient_LlamaNative_{m}" for m in ("load", "generate", "close", "pinFastCores")}


def run(command, env=None, log=None):
    result = subprocess.run([str(x) for x in command], env=env, cwd=ROOT,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if log:
        log.write_text(result.stdout)
    if result.returncode:
        print(result.stdout[-6000:])
        raise subprocess.CalledProcessError(result.returncode, command)
    return result.stdout


def native_inputs():
    paths = sorted(p for p in CPP.rglob("*") if p.is_file())
    paths += [ROOT / "scripts/prepare_llama.py", ROOT / "scripts/build_llama.py",
              ROOT / "sentient/src/main/java/br/gabriel/sentient/LlamaNative.java"]
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in paths}


def audit_elf(library, abi, tools):
    header = run([tools / "llvm-readelf", "-h", library])
    expected = "AArch64" if abi == "arm64-v8a" else "Advanced Micro Devices X86-64"
    if expected not in header:
        raise ValueError("Wrong ELF machine")
    dynamic = run([tools / "llvm-readelf", "-d", library])
    needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
    if not set(needed) <= {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}:
        raise ValueError(f"Unexpected runtime dependency: {needed}")
    symbols = run([tools / "llvm-nm", "--dynamic", "--defined-only", library])
    names = {line.split()[-1] for line in symbols.splitlines() if line.strip()}
    if names != EXPORTS:
        raise ValueError(f"Unexpected exported symbols: {names}")
    imports = run([tools / "llvm-nm", "--dynamic", "--undefined-only", library])
    imported = {line.split()[-1].split("@")[0] for line in imports.splitlines() if line.strip()}
    forbidden = {"socket", "connect", "send", "recv", "getaddrinfo", "curl_easy_init", "SSL_new"}
    if imported & forbidden:
        raise ValueError("Network symbols linked into CPU-only library")
    phdr = run([tools / "llvm-readelf", "--program-headers", "--wide", library])
    load = [line for line in phdr.splitlines() if line.strip().startswith("LOAD")]
    if not load or any(int(line.split()[-1], 16) < 16384 for line in load):
        raise ValueError("JNI does not support 16KiB ELF alignment")
    if "TEXTREL" in dynamic or re.search(r"GNU_STACK.*RWE", phdr):
        raise ValueError("Unsafe ELF relocation/stack flags")
    return {"machine": expected, "needed": sorted(needed), "exports": sorted(names),
            "network_imports": [], "load_alignment_min": 16384,
            "sha256": sha(library), "bytes": library.stat().st_size}


def build_one(abi, directory, ndk, jobs, env, variant="portable"):
    directory.mkdir(parents=True, exist_ok=True)
    command = ["cmake", "-S", str(CPP), "-B", str(directory), "-G", "Unix Makefiles",
               f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
               f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-26",
               "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
               "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -g0",
               "-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG -g0", f"-DLLAMA_SOURCE={SOURCE}", f"-DGMIND_VARIANT={variant}"]
    run(command, env, directory / "configure.log")
    compile_command = ["cmake", "--build", str(directory), "--target", "gmind-llama", "--parallel", str(jobs)]
    run(compile_command, env, directory / "build.log")
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    library = directory / (FAST_LIBRARY if variant == "fast" else LIBRARY)
    if not library.is_file():
        raise ValueError("Expected JNI output was not built")
    run([tools / "llvm-strip", "--strip-unneeded", library])
    entries = json.loads((directory / "compile_commands.json").read_text())
    commands = "\n".join(entry["command"] for entry in entries)
    prohibited = ["-march=native", "-mcpu=native", "+sve", "-mavx", "-mfma", "-mf16c", "-mbmi"]
    if variant == "portable":
        prohibited += ["+dotprod", "+fp16", "+i8mm"]
    if any(flag in commands for flag in prohibited):
        raise ValueError("Unsafe distributed CPU flags")
    if variant == "portable" and abi == "arm64-v8a" and "-march=armv8-a" not in commands:
        raise ValueError("Portable ARM baseline missing")
    if variant == "fast" and (abi != "arm64-v8a" or FAST_ARCH not in commands):
        raise ValueError("fast build must be arm64 with exactly " + FAST_ARCH)
    summary = audit_elf(library, abi, tools)
    summary.update({"cpu_baseline": "armv8.2-a + dotprod + fp16 + i8mm (chosen at runtime when the CPU has them)"
                    if variant == "fast" else "armv8-a (NEON), no optional dotprod/fp16/i8mm" if abi == "arm64-v8a"
                    else "NDK x86_64 baseline, no optional AVX/FMA/F16C/BMI",
                    "compile_commands_sha256": sha(directory / "compile_commands.json")})
    return library, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABIS, action="append")
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--jobs", type=int, default=6)
    parser.add_argument("--verify-reproducible", action="store_true")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk"))))
    ndk = (args.ndk or sdk / "ndk" / NDK_VERSION).resolve()
    properties = ndk / "source.properties"
    if not properties.is_file() or not re.search(r"Pkg.Revision\s*=\s*27\.2\.12479018\s*$", properties.read_text(), re.M):
        raise SystemExit("Install/select pinned Android NDK 27.2.12479018")
    verify_source()
    if LICENSE.read_bytes() != (SOURCE / "LICENSE").read_bytes():
        raise ValueError("Bundled llama.cpp license differs from the pinned source")
    inputs = native_inputs()
    env = os.environ.copy()
    env.update({"SOURCE_DATE_EPOCH": "0", "TZ": "UTC", "LC_ALL": "C", "GIT_CEILING_DIRECTORIES": str(SOURCE.parent)})
    for variable in ("CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "CC", "CXX"):
        env.pop(variable, None)
    results, artifacts, fast = {}, {}, {}
    abis = list(dict.fromkeys(args.abi or ABIS))
    builds = [(abi, "portable") for abi in abis] + [("arm64-v8a", "fast")] * ("arm64-v8a" in abis)
    for abi, variant in builds:
        print(f"Building CPU-only {abi} ({variant})", flush=True)
        name = abi if variant == "portable" else abi + "-fast"
        library, result = build_one(abi, CACHE / "llama-android-build" / name, ndk, args.jobs, env, variant)
        if args.verify_reproducible:
            with tempfile.TemporaryDirectory(prefix="llama-rebuild-", dir=CACHE) as temporary:
                rebuilt, _ = build_one(abi, Path(temporary), ndk, args.jobs, env, variant)
                if sha(rebuilt) != sha(library):
                    raise ValueError(f"Independent rebuild differs for {abi} ({variant})")
            result["independent_rebuild_byte_identical"] = True
        else:
            result["independent_rebuild_byte_identical"] = None
        if variant == "portable": results[abi], artifacts[abi] = result, library
        else: fast[abi] = (result, library)
        print(json.dumps({"abi": abi, "variant": variant, "sha256": result["sha256"], "bytes": result["bytes"]}), flush=True)
    if native_inputs() != inputs:
        raise ValueError("Native inputs changed during compilation; retry a stable build")
    for abi, library in artifacts.items():
        dest = ROOT / "sentient/src/main/jniLibs" / abi / LIBRARY
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(library, dest)
        if sha(dest) != results[abi]["sha256"]:
            raise ValueError("Copied library differs")
        results[abi]["path"] = dest.relative_to(ROOT).as_posix()
    for abi, (result, library) in fast.items():
        dest = ROOT / "sentient/src/main/jniLibs" / abi / FAST_LIBRARY
        shutil.copyfile(library, dest)
        if sha(dest) != result["sha256"]:
            raise ValueError("Copied fast library differs")
        result["path"] = dest.relative_to(ROOT).as_posix()
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    receipt = {"schema_version": 1, "source_repository": REPOSITORY, "source_commit": COMMIT, "source_tree": TREE,
               "license_sha256": sha(LICENSE), "model_not_bundled": True,
               "downloaded_model": {"url": QWEN_URL, "sha256": QWEN_SHA, "bytes": QWEN_BYTES},
               "ndk": NDK_VERSION, "android_api": 26, "cxx_runtime": "c++_static",
               "cmake_version": run(["cmake", "--version"]).splitlines()[0],
               "clang_version": run([tools / "clang", "--version"]).splitlines()[0],
               "cpu_only": True, "network_backend": False, "dynamic_backends": False,
               "native_input_sha256": inputs, "abis": results,
               "fast": {abi: result for abi, (result, _) in fast.items()},
               "verification_boundary": "Cross-compiled and ELF-audited; the same JNI runs a pinned tiny model on the "
                                        "host (tests/llama-native). On-device answers are a separate gate."}
    RECEIPT.parent.mkdir(exist_ok=True)
    RECEIPT.write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Verified receipt: {RECEIPT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
