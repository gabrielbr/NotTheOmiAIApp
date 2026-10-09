#!/usr/bin/env python3
"""Run Sentient's plain-Java store, ingest, sync and search code against real SQLite.

No Android or phone needed. Downloads one pinned, hash-verified test-only jar (xerial
sqlite-jdbc, which bundles SQLite with FTS5) into .cache/host on first use.
"""
from pathlib import Path
import hashlib
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
JAR_URL = 'https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar'
JAR_SHA256 = 'a3f53a2aa15ae9425a9e793bbe9c8e5288febeb4b65ef5c1a4e80d4c2045cf08'
JAR = ROOT/'.cache/host/sqlite-jdbc-3.50.3.0.jar'
PURE = ['Db', 'Schema', 'Ingest', 'Sources', 'SyncRunner', 'Search', 'Items', 'OmiTranscripts', 'WhatsAppMessages']


def jar():
    if not JAR.exists() or hashlib.sha256(JAR.read_bytes()).hexdigest() != JAR_SHA256:
        JAR.parent.mkdir(parents=True, exist_ok=True)
        data = urllib.request.urlopen(JAR_URL, timeout=120).read()
        if hashlib.sha256(data).hexdigest() != JAR_SHA256:
            raise SystemExit('sqlite-jdbc hash mismatch')
        JAR.write_bytes(data)
    return JAR


def main():
    classpath = str(jar())
    sources = sorted((ROOT/'plugin-api/src/main/java').rglob('*.java'))
    sources += [ROOT/f'sentient/src/main/java/br/gabriel/sentient/{name}.java' for name in PURE]
    sources += [ROOT/'tests/sentient/JdbcDb.java', ROOT/'tests/sentient/SentientHostTest.java']
    with tempfile.TemporaryDirectory(prefix='sentient-host-') as work:
        subprocess.run(['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', classpath, '-d', work,
                        *map(str, sources)], check=True)
        subprocess.run(['java', '-cp', work + ':' + classpath, 'br.gabriel.sentient.SentientHostTest'], check=True)


if __name__ == '__main__':
    main()
