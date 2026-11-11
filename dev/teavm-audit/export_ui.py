#!/usr/bin/env python3
"""Publish the tested UI package and compact verification, keeping score images outside Git."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

root, mirror, revision = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3]
source = mirror / 'build/wasm-engine'
report = json.loads((source / 'ui/verification.json').read_text())
comparison = json.loads((source / 'ui/xml-comparison.json').read_text())
if not comparison['exact'] or not all(report[key] for key in ['imageSelection', 'invalidFileRejected', 'preview',
        'cancelAndRetry', 'progress', 'musicXmlDownloaded', 'recognitionFailureRecoverable', 'staleDownloadsCleared',
        'responsiveNoHorizontalOverflow']):
    raise AssertionError('UI verification did not pass')
git = ['git', '-c', 'safe.directory=' + str(root), '-C', str(root)]
if subprocess.check_output(git + ['rev-parse', revision]).decode().strip() != revision:
    raise ValueError('Expected a full source commit')
paths = ['wasm-compat/web/index.html', 'wasm-compat/web/app.css', 'wasm-compat/web/app.mjs',
         'wasm-compat/tools/verify-engine-ui.cjs', 'dev/teavm-audit/package_engine.py']
hashes = {}
for name in paths:
    built = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
    committed = subprocess.check_output(git + ['show', revision + ':' + name]).replace(b'\r\n', b'\n')
    if built != committed:
        raise AssertionError('Tested source differs from commit: ' + name)
    hashes[name] = hashlib.sha256(built).hexdigest()
limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
cpu, memory, swap = [(limits / key).read_text().strip() for key in ['cpu.max', 'memory.max', 'memory.swap.max']]
if cpu != '800000 100000' or memory != '8589934592' or swap != '0':
    raise AssertionError('Resource limits changed')
package, raw, tracked = root / 'wsl-artifacts/wasm-engine', root / 'wsl-artifacts/wasm-components/ui', root / 'wasm-compat/ui-results'
expected = {p.relative_to(source / 'package') for p in (source / 'package').rglob('*') if p.is_file()}
if package.exists() and {p.relative_to(package) for p in package.rglob('*') if p.is_file()} - expected:
    raise ValueError('Archive the previous package before exporting; refusing to retain stale files')
shutil.copytree(source / 'package', package, dirs_exist_ok=True)
shutil.copytree(source / 'ui', raw, dirs_exist_ok=True)
tracked.mkdir(parents=True, exist_ok=True)
for name in ['verification.json', 'xml-comparison.json']:
    shutil.copy2(source / 'ui' / name, tracked / name)
for manifest in ['integrity.json', 'ocr/integrity.json']:
    data = json.loads((package / manifest).read_text())
    for name, record in data.items():
        if hashlib.sha256((package / manifest).parent.joinpath(name).read_bytes()).hexdigest() != record['sha256']:
            raise AssertionError('Exported file hash mismatch: ' + name)
provenance = {'sourceCommit': revision, 'sourceSha256': hashes, 'package': 'wsl-artifacts/wasm-engine',
    'rawArtifacts': 'wsl-artifacts/wasm-components/ui', 'engineSha256': hashlib.sha256((package / 'engine.wasm').read_bytes()).hexdigest(),
    'cpuMax': cpu, 'allowedCpus': (limits / 'cpuset.cpus.effective').read_text().strip(),
    'memoryMaxBytes': int(memory), 'swapMaxBytes': int(swap), 'memoryEvents': (limits / 'memory.events').read_text().strip()}
(tracked / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
print(json.dumps({'sourceCommit': revision, 'imageToDownload': True, 'musicXmlExact': comparison['exact']}))
