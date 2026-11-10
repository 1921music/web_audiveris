"""Materialize the pinned, unmodified scale algorithm as a JVM comparison source."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

root, output = map(Path, sys.argv[1:])
commit = '04697c74fae431ef8436a9429386957a5cb25993'
name = 'app/src/main/java/org/audiveris/omr/sheet/ScaleBuilder.java'
source = subprocess.check_output(['git', '-c', f'safe.directory={root}', '-C', str(root), 'show', f'{commit}:{name}']).replace(b'\r\n', b'\n')
target = output / 'src/org/audiveris/omr/sheet/ScaleBuilder.java'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(source)
(output / 'source.json').write_text(json.dumps({'commit': commit, 'path': name, 'sha256': hashlib.sha256(source).hexdigest()}, indent=2) + '\n')
