#!/usr/bin/env python3
"""Verify a signed GMind (Sentient module) APK: exact permission allow-list, no computer-control surface,
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
    'android.permission.REQUEST_INSTALL_PACKAGES',  # in-app updates; Android confirms each install
    'br.gabriel.omitarefas.permission.READ_TRANSCRIPTS',
    # Gemini Nano through ML Kit: bind Android's AICore service; ML Kit checks the network state.
    'com.google.android.apps.aicore.service.BIND_SERVICE',
    'android.permission.ACCESS_NETWORK_STATE',
    # AndroidX's own signature permission guarding its non-exported dynamic receivers.
    'br.gabriel.sentient.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',
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
        cached = Path.home()/'.gradle/caches/modules-2/files-2.1'/group/artifact/version
        matches = list(cached.rglob(f'{artifact}-{version}.aar')) or list(cached.rglob(f'{artifact}-{version}.jar'))
        assert len(matches) == 1, 'Expected one exact cached runtime artifact for ' + dependency['coordinate']
        assert sha(matches[0].read_bytes()) == dependency['sha256'], 'Runtime AAR pin mismatch'
        with zipfile.ZipFile(matches[0]) as source:
            for name in source.namelist():
                abi = name.split('/')[1] if name.startswith('jni/') else None
                if abi in ('arm64-v8a', 'x86_64') and name.endswith('.so'):
                    expected['lib/'+name.removeprefix('jni/')] = sha(source.read(name))
    return expected


def llama_receipt():
    """libgmind-llama.so (and the arm64 fast build) as built and ELF-audited by scripts/build_llama.py."""
    receipt = json.loads((ROOT/'verification/llama-native-build.json').read_text())
    assert receipt['model_not_bundled'] and receipt['cpu_only'] and not receipt['network_backend']
    assert 'arm64-v8a' in receipt.get('fast', {}), 'Fast arm64 llama build missing'
    libraries = {f"lib/{abi}/libgmind-llama.so": entry['sha256'] for abi, entry in receipt['abis'].items()}
    libraries.update({f"lib/{abi}/libgmind-llama-fast.so": entry['sha256'] for abi, entry in receipt['fast'].items()})
    return libraries


def exported_activities(manifest, element='activity'):
    """Names of {element}s whose android:exported is true, from `aapt dump xmltree` output."""
    exported = set()
    for block in manifest.split('E: ' + element)[1:]:
        block = block.split('E: ')[0]  # the activity's own attributes come before any child element
        name = re.search(r'A: android:name\([^)]*\)="([^"]+)"', block).group(1)
        flag = re.search(r'A: android:exported\([^)]*\)=\(type 0x12\)(0x[0-9a-f]+)', block)
        if flag and flag.group(1) != '0x0':
            exported.add(name)
    return exported


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
    assert "application-label:'GMind'" in badging
    assert 'application-debuggable' not in badging
    if args.version_code:
        assert f"versionCode='{args.version_code}'" in badging
    if args.version_name:
        assert f"versionName='{args.version_name}'" in badging
    requested = set(re.findall(r"uses-permission: name='([^']+)'", badging))
    assert requested == ALLOWED_PERMISSIONS, f'Unexpected permissions: {sorted(requested ^ ALLOWED_PERMISSIONS)}'
    for forbidden in FORBIDDEN:
        assert forbidden not in manifest, 'Computer-control surface present: ' + forbidden
    # ML Kit and AndroidX start through internal providers; none may be exported.
    assert not exported_activities(manifest, 'provider'), 'Sentient must not export a provider'
    # ML Kit's usage-logging upload backend is removed in the manifest: nothing is sent.
    assert 'CctBackendFactory' not in manifest, 'ML Kit usage logging would upload'
    assert exported_activities(manifest) == {'br.gabriel.sentient.SentientActivity'}, \
        'Only the launcher activity may be exported: ' + str(sorted(exported_activities(manifest)))
    assert 'SyncJobService' in manifest and 'android.permission.BIND_JOB_SERVICE' in manifest
    # WhatsApp and Signal capture: one notification listener the user enables in Settings; no extra permission.
    assert 'MessagesListenerService' in manifest and 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' in manifest
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
        assert actual == {**pinned_native(), **llama_receipt()}, 'APK native libraries differ from pinned AARs / llama receipt'
        assert not any(n.endswith('.gguf') or n.endswith('.bin') for n in names), 'A model must not be bundled'
        for name in ['sqlcipher-android-BSD.txt', 'androidx-sqlite-Apache-2.0.txt', 'ubuntu-font-licence.txt', 'anthropic-sdk-java-MIT.txt', 'okhttp-jackson-kotlin-Apache-2.0.txt', 'llama.cpp-MIT.txt', 'google-ml-kit-terms.txt']:
            assert len(archive.read('assets/licenses/'+name)) > 100, 'Missing license ' + name
        dex = b'\n'.join(archive.read(n) for n in names if n.endswith('.dex'))
        for class_name in ['Lbr/gabriel/sentient/SyncJobService;', 'Lbr/gabriel/sentient/plugin/SourcePlugin;', 'Lbr/gabriel/sentient/ItemActivity;', 'Lbr/gabriel/sentient/SettingsActivity;', 'Lbr/gabriel/sentient/SourceActivity;', 'Lbr/gabriel/sentient/AboutActivity;', 'Lbr/gabriel/sentient/LicensesActivity;', 'Lbr/gabriel/sentient/MessagesListenerService;', 'Lbr/gabriel/sentient/AskActivity;', 'Lbr/gabriel/sentient/LlamaNative;', 'Lbr/gabriel/sentient/NanoBackend;', 'Lcom/google/mlkit/genai/prompt/java/GenerativeModelFutures;', 'Lcom/anthropic/client/okhttp/AnthropicOkHttpClient;',
                           'Lnet/zetetic/database/sqlcipher/SQLiteDatabase;']:
            assert class_name.encode() in dex, 'Missing runtime class ' + class_name
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    print(json.dumps({'result': 'PASS_SENTIENT_RELEASE_PACKAGE', 'artifact': str(args.apk.resolve()),
                      'sha256': digest, 'permissions': sorted(requested), 'abis': abis,
                      'signer_sha256': signer, 'same_signer_as_omi': bool(args.omi_apk)}))


if __name__ == '__main__':
    main()
