#!/usr/bin/env python3
"""Compile/run the offline task finder checks without Android, models or network."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='nottheomi-tasks-host-') as work:
    sources = [ROOT/'app/src/main/java/app/nottheomi/ai/TaskExtractor.java', ROOT/'tests/tasks/TaskExtractorHostTest.java']
    subprocess.run(['javac','--release','17','-d',work,*map(str,sources)],check=True)
    subprocess.run(['java','-cp',work,'app.nottheomi.ai.TaskExtractorHostTest'],check=True)
