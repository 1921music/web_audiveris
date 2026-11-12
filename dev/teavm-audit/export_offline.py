#!/usr/bin/env python3
"""Archive the tested single HTML file and its gzip/Base64/source verification."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

root, mirror, revision = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3]
source = mirror / 'build/wasm-engine/offline'
build = json.loads((source / 'build.json').read_text())
verification = json.loads((source / 'verification.json').read_text())
comparison = json.loads((source / 'xml-comparison.json').read_text())
if not comparison['exact'] or verification['protocol'] != 'file:' or verification['networkRequests']:
    raise AssertionError('Offline XML or file/network verification failed')
if verification['serverUsed'] or verification['specialFileAccessFlags'] or not verification['browserNetworkDisabled']:
    raise AssertionError('Offline build required a server, network or nonstandard browser flags')
if not all(verification[key] for key in ['initialization', 'cancelAndRetry', 'imageRecognition', 'musicXmlDownloaded']):
    raise AssertionError('Offline lifecycle verification failed')
for name, encoding in build['assetEncoding'].items():
    if Path(name).suffix.lower() in {'.wasm', '.ttf', '.otf'} and encoding['encoding'] != 'gzip':
        raise AssertionError('Wasm/font was not gzip-compressed before Base64: ' + name)
html = source / 'audiveris-offline.html'
if hashlib.sha256(html.read_bytes()).hexdigest() != build['htmlSha256']:
    raise AssertionError('Tested HTML changed')
git = ['git', '-c', 'safe.directory=' + str(root), '-C', str(root)]
if subprocess.check_output(git + ['rev-parse', revision]).decode().strip() != revision:
    raise ValueError('Expected a full source commit')
paths = ['wasm-compat/web/offline-bootstrap.js', 'dev/teavm-audit/package_engine_offline.py',
         'wasm-compat/tools/verify-engine-offline.cjs', 'wasm-compat/web/index.html',
         'wasm-compat/web/app.mjs', 'wasm-compat/web/app.css']
hashes = {}
for name in paths:
    tested = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
    committed = subprocess.check_output(git + ['show', revision + ':' + name]).replace(b'\r\n', b'\n')
    if tested != committed:
        raise AssertionError('Tested source differs from commit: ' + name)
    hashes[name] = hashlib.sha256(tested).hexdigest()
limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
cpu, memory, swap = [(limits / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
if cpu != '800000 100000' or memory != '8589934592' or swap != '0':
    raise AssertionError('Resource ceilings changed')
package = root / 'wsl-artifacts/wasm-engine-offline'
raw = root / 'wsl-artifacts/wasm-components/offline'
tracked = root / 'wasm-compat/offline-results'
for folder in [package, raw, tracked]:
    folder.mkdir(parents=True, exist_ok=True)
shutil.copy2(html, package / html.name)
if hashlib.sha256((package / html.name).read_bytes()).hexdigest() != build['htmlSha256']:
    raise AssertionError('Exported HTML differs')
for name in ['build.json', 'verification.json', 'xml-comparison.json']:
    shutil.copy2(source / name, tracked / name)
    shutil.copy2(source / name, raw / name)
for name in ['00-0.musicxml', 'completed-desktop.png']:
    shutil.copy2(source / name, raw / name)
provenance = {'sourceCommit': revision, 'sourceSha256': hashes, 'htmlSha256': build['htmlSha256'],
    'engineSha256': build['engineSha256'], 'htmlBytes': build['htmlBytes'],
    'file': 'wsl-artifacts/wasm-engine-offline/audiveris-offline.html',
    'rawArtifacts': 'wsl-artifacts/wasm-components/offline', 'cpuMax': cpu,
    'memoryMaxBytes': int(memory), 'swapMaxBytes': int(swap), 'memoryEvents': (limits / 'memory.events').read_text().strip()}
(tracked / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
print(json.dumps({'sourceCommit': revision, 'htmlBytes': build['htmlBytes'], 'fileProtocolVerified': True, 'musicXmlExact': True}))
