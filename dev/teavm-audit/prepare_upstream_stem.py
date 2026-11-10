"""Materialize unchanged stem algorithms for the independent JVM reference."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

root, output = map(Path, sys.argv[1:])
commit = '04697c74fae431ef8436a9429386957a5cb25993'
sources = {}
for name in ['StemScaler', 'StemChecker', 'VerticalsBuilder']:
    relative = f'app/src/main/java/org/audiveris/omr/sheet/stem/{name}.java'
    source = subprocess.check_output(['git', '-c', f'safe.directory={root}', '-C', str(root),
                                      'show', f'{commit}:{relative}']).replace(b'\r\n', b'\n')
    target = output / f'src/org/audiveris/omr/sheet/stem/{name}.java'
    target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(source)
    sources[relative] = hashlib.sha256(source).hexdigest()
(output / 'source.json').write_text(json.dumps({'commit': commit, 'sourceSha256': sources}, indent=2) + '\n')
