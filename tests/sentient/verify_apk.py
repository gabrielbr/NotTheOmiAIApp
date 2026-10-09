#!/usr/bin/env python3
"""Verify a signed Sentient APK: exact permission allow-list, no computer-control surface,
pinned ABIs, and (with --omi-apk) the same signer as Omi Tarefas, which the
signature-protected transcript provider requires."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]

ALLOWED_PERMISSIONS = {
    'android.permission.INTERNET',
    'android.permission.RECEIVE_BOOT_COMPLETED',
    'br.gabriel.omitarefas.permission.READ_TRANSCRIPTS',
}
# Computer control is out of scope: no accessibility, overlay, input or screen capture.
FORBIDDEN = ['BIND_ACCESSIBILITY_SERVICE', 'SYSTEM_ALERT_WINDOW', 'INJECT_EVENTS',
             'MEDIA_PROJECTION', 'accessibilityservice']


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pinned_native():
    """Native libraries expected in the APK, from the hash-pinned AARs in Gradle's cache."""
    expected = {}
    for dependency in json.loads((ROOT/'DEPENDENCIES.json').read_text())['sentient_runtime']:
        group, artifact, version = dependency['coordinate'].removesuffix('@aar').split(':')
        matches = list((Path.home()/'.gradle/caches/modules-2/files-2.1'/group/artifact/version).rglob('*.aar'))
        assert len(matches) == 1, 'Expected one exact cached runtime artifact for ' + dependency['coordinate']
        assert sha(matches[0].read_bytes()) == dependency['sha256'], 'Runtime AAR pin mismatch'
        with zipfile.ZipFile(matches[0]) as source:
            for name in source.namelist():
                abi = name.split('/')[1] if name.startswith('jni/') else None
                if abi in ('arm64-v8a', 'x86_64') and name.endswith('.so'):
                    expected['lib/'+name.removeprefix('jni/')] = sha(source.read(name))
    return expected


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--version-code')
    parser.add_argument('--version-name')
    parser.add_argument('--omi-apk', type=Path, help='Signed Omi Tarefas APK that must share the signer')
    args = parser.parse_args()
    tools = args.sdk/'build-tools/34.0.0'

    def run(*command):
        return subprocess.check_output([str(x) for x in command], text=True)

    badging = run(tools/'aapt', 'dump', 'badging', args.apk)
    manifest = run(tools/'aapt', 'dump', 'xmltree', args.apk, 'AndroidManifest.xml')
    signature = run(tools/'apksigner', 'verify', '--verbose', '--print-certs', args.apk)
    run(tools/'zipalign', '-c', '-p', '4', args.apk)
    assert "package: name='br.gabriel.sentient'" in badging
    assert "application-label:'Sentient'" in badging
    assert 'application-debuggable' not in badging
    if args.version_code:
        assert f"versionCode='{args.version_code}'" in badging
    if args.version_name:
        assert f"versionName='{args.version_name}'" in badging
    requested = set(re.findall(r"uses-permission: name='([^']+)'", badging))
    assert requested == ALLOWED_PERMISSIONS, f'Unexpected permissions: {sorted(requested ^ ALLOWED_PERMISSIONS)}'
    for forbidden in FORBIDDEN:
        assert forbidden not in manifest, 'Computer-control surface present: ' + forbidden
    assert 'E: provider' not in manifest, 'Sentient must not export a provider'
    assert 'SyncJobService' in manifest and 'android.permission.BIND_JOB_SERVICE' in manifest
    assert 'Verified using v2 scheme' in signature
    signer = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', signature).group(1)
    if args.omi_apk:
        omi = run(tools/'apksigner', 'verify', '--print-certs', args.omi_apk)
        assert signer in omi, 'Sentient and Omi Tarefas must be signed with the same key'
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        abis = sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')})
        assert abis == ['arm64-v8a', 'x86_64'], 'Unintended ABI set'
        actual = {n: sha(archive.read(n)) for n in names if n.startswith('lib/') and n.endswith('.so')}
        assert actual == pinned_native(), 'APK native libraries differ from pinned AARs'
        for name in ['sqlcipher-android-BSD.txt', 'androidx-sqlite-Apache-2.0.txt']:
            assert len(archive.read('assets/licenses/'+name)) > 100, 'Missing license ' + name
        dex = b'\n'.join(archive.read(n) for n in names if n.endswith('.dex'))
        for class_name in ['Lbr/gabriel/sentient/SyncJobService;', 'Lbr/gabriel/sentient/plugin/SourcePlugin;',
                           'Lnet/zetetic/database/sqlcipher/SQLiteDatabase;']:
            assert class_name.encode() in dex, 'Missing runtime class ' + class_name
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    print(json.dumps({'result': 'PASS_SENTIENT_RELEASE_PACKAGE', 'artifact': str(args.apk.resolve()),
                      'sha256': digest, 'permissions': sorted(requested), 'abis': abis,
                      'signer_sha256': signer, 'same_signer_as_omi': bool(args.omi_apk)}))


if __name__ == '__main__':
    main()
