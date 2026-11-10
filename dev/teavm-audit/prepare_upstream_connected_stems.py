"""Materialize unchanged stem connection recognition classes for the JVM reference."""
import hashlib
import json
import subprocess
import sys
import os
import re
from pathlib import Path
from zipfile import ZipFile

root, output = map(Path, sys.argv[1:])
commit = '04697c74fae431ef8436a9429386957a5cb25993'
sources = {}
for name in ['StemsRetriever', 'StemBuilder', 'BeamLinker', 'HeadLinker', 'StemLinker', 'StemHalfLinker', 'StemItem', 'HeadCorner', 'StemsStep', 'HeadStemsCleaner']:
    relative = f'app/src/main/java/org/audiveris/omr/sheet/stem/{name}.java'
    source = subprocess.check_output(['git', '-c', f'safe.directory={root}', '-C', str(root),
                                      'show', f'{commit}:{relative}']).replace(b'\r\n', b'\n')
    target = output / f'src/org/audiveris/omr/sheet/stem/{name}.java'
    target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(source)
    sources[relative] = hashlib.sha256(source).hexdigest()
jdk=Path(os.environ['JAVA_HOME'])
sort_info=subprocess.check_output([str(jdk / 'bin/javap'), '-sysinfo', 'java.util.TimSort']).decode()
sort_class_sha=re.search(r'SHA-256 checksum ([0-9a-f]{64})',sort_info).group(1)
with ZipFile(jdk / 'lib/src.zip') as archive:
    sort_source=archive.read('java.base/java/util/TimSort.java')
(output / 'source.json').write_text(json.dumps({'commit': commit, 'sourceSha256': sources,
    'jdkTimSortClassSha256': sort_class_sha,
    'jdkTimSortSourceSha256': hashlib.sha256(sort_source).hexdigest(),
    'jdkRelease': (jdk / 'release').read_text()}, indent=2) + '\n')
