#!/usr/bin/env python3
"""Verify actual hybrid APK weights, native bytes, permissions and signing."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
ROOT = Path(__file__).resolve().parents[2]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--version-code', help='Expected versionCode; defaults to app/build.gradle')
    parser.add_argument('--version-name', help='Expected versionName; defaults to app/build.gradle')
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads((ROOT/'DEPENDENCIES.json').read_text())
    native = json.loads((ROOT/'verification/whisper-native-build.json').read_text())
    runtime_native = {}
    for dependency in metadata['runtime']:
        coordinate = dependency.get('coordinate')
        if not coordinate:
            continue
        group, artifact, version = coordinate.removesuffix('@aar').split(':')
        matches = list((Path.home()/'.gradle/caches/modules-2/files-2.1'/group/artifact/version).rglob('*.aar'))
        assert len(matches) == 1, 'Expected one exact cached runtime artifact'
        assert sha(matches[0].read_bytes()) == dependency['sha256'], 'Runtime AAR pin mismatch'
        with zipfile.ZipFile(matches[0]) as source:
            for name in source.namelist():
                if name.startswith('jni/') and name.endswith('.so'):
                    runtime_native['lib/'+name.removeprefix('jni/')] = sha(source.read(name))
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'Duplicate APK members'
        assert not any(n.endswith('/jfk.wav') or n.endswith('/test.wav') for n in names), 'Test fixture in release'
        # Whisper medium and small are downloaded by the app at runtime: never bundled, and the
        # app's pinned URL, size and SHA-256 must match DEPENDENCIES.json exactly.
        installer = (ROOT/'app/src/main/java/app/nottheomi/ai/ModelInstaller.java').read_text()
        for key, prefix in [('model', 'MODEL'), ('small_model', 'SMALL')]:
            model = metadata[key]
            assert 'assets/'+model['filename'] not in names, 'Downloaded model bundled in the APK: '+model['filename']
            assert re.search(prefix + r'_SHA256 =\s*"' + model['sha256'] + '"', installer), 'App SHA-256 differs from pin: '+key
            assert f"{prefix}_BYTES = {model['bytes']}L" in installer, 'App size differs from pin: '+key
            assert model['url'].endswith('/'+model['filename']) and f'BASE_URL + "{model["filename"]}"' in installer, 'App URL differs: '+key
            assert f'REVISION = "{model["revision"]}"' in installer and '/resolve/" + REVISION' in installer, 'App revision differs: '+key
        assert args.apk.stat().st_size < 120 * 1024 * 1024, 'GVoice APK unexpectedly large (bundled models?)'
        for key in ['vad_model', 'preview_model']:
            model = metadata[key]
            member = 'assets/'+model['filename']
            info = archive.getinfo(member)
            assert info.file_size == model['bytes'], 'Wrong model length'
            with archive.open(member) as source:
                digest = hashlib.sha256()
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    digest.update(block)
                assert digest.hexdigest() == model['sha256'], 'Wrong model bytes'
            if key == 'vad_model':
                assert info.compress_type == zipfile.ZIP_STORED, 'Whisper model compressed'
        for name in ['whisper.cpp-MIT.txt','whisper-model-MIT.txt','whisper-silero-vad-MIT.txt','vosk-license.txt','jna-license.txt','concentus-license.txt','omi-license.txt']:
            assert len(archive.read('assets/licenses/'+name)) > 100, 'Missing license'
        abis = sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')})
        assert abis and set(abis) <= {'arm64-v8a','x86_64'}, 'Unintended ABI'
        actual_native = {}
        for abi in abis:
            whisper = 'lib/'+abi+'/libnottheomi-whisper.so'
            expected = {name:value for name,value in runtime_native.items() if name.startswith('lib/'+abi+'/')}
            expected[whisper] = native['abis'][abi]['sha256']
            if abi in native.get('dotprod', {}):
                expected['lib/'+abi+'/libnottheomi-whisper-dotprod.so'] = native['dotprod'][abi]['sha256']
            assert abi != 'arm64-v8a' or 'lib/arm64-v8a/libnottheomi-whisper-dotprod.so' in expected, 'Fast arm64 build missing'
            actual = {name:sha(archive.read(name)) for name in names if name.startswith('lib/'+abi+'/') and name.endswith('.so')}
            assert actual == expected, 'APK native libraries differ from pinned inputs'
            actual_native.update(actual)
        dex = b'\n'.join(archive.read(name) for name in names if name.endswith('.dex'))
        for class_name in ['Lorg/vosk/Recognizer;','Lcom/sun/jna/Native;','Lapp/nottheomi/ai/RefinementJobService;','Lapp/nottheomi/ai/RefinementEngine;','Lapp/nottheomi/ai/PreviewRecognizer;']:
            assert class_name.encode() in dex, 'Missing runtime class '+class_name
    tools = args.sdk/'build-tools/34.0.0'
    def run(*command):
        return subprocess.check_output([str(x) for x in command], text=True)
    badging = run(tools/'aapt','dump','badging',args.apk)
    permissions = run(tools/'aapt','dump','permissions',args.apk)
    manifest = run(tools/'aapt','dump','xmltree',args.apk,'AndroidManifest.xml')
    signature = run(tools/'apksigner','verify','--verbose','--print-certs',args.apk)
    run(tools/'zipalign','-c','-p','4',args.apk)
    assert "package: name='br.gabriel.omitarefas'" in badging
    gradle = (ROOT/'app/build.gradle').read_text()
    code_match = re.search(r"appVersionCode'\) \?: '(\d+)'", gradle)
    name_match = re.search(r"appVersionName'\) \?: '([^']+)'", gradle)
    assert code_match and name_match, 'Missing canonical Gradle release version'
    code = args.version_code or code_match.group(1)
    name = args.version_name or name_match.group(1)
    assert f"versionCode='{code}'" in badging and f"versionName='{name}'" in badging
    assert 'application-debuggable' not in badging
    # Network is only for the two pinned model downloads, over HTTPS.
    assert 'android.permission.INTERNET' in permissions
    assert 'networkSecurityConfig' in manifest, 'Network security config missing'
    assert 'android.permission.BIND_JOB_SERVICE' in manifest and 'RefinementJobService' in manifest
    assert 'RefinementService' in manifest, 'Foreground transcription service missing'
    # The only exported data surface: transcripts for same-signer apps (Sentient), read-only.
    assert 'br.gabriel.omitarefas.permission.READ_TRANSCRIPTS' in permissions
    provider = manifest[manifest.index('TranscriptProvider'):][:600]
    assert 'android:permission' in provider and 'READ_TRANSCRIPTS' in provider, 'Transcript provider unguarded'
    assert re.search(r'protectionLevel\(0x01010009\)=\(type 0x11\)0x2', manifest), 'Permission must be signature-level'
    assert 'Verified using v2 scheme' in signature
    with args.apk.open('rb') as source:
        digest = hashlib.file_digest(source,'sha256').hexdigest()
    print(json.dumps({'result':'PASS_HYBRID_RELEASE_PACKAGE','artifact':str(args.apk.resolve()),'sha256':digest,'bytes':args.apk.stat().st_size,'abis':abis,'native_sha256':actual_native,'model_sha256':{key:metadata[key]['sha256'] for key in ['model','preview_model']},'internet_permission':True,'whisper_models':'downloaded at runtime, pinned','signature_verified':True,'alignment_verified':True}))


if __name__ == '__main__':
    main()
