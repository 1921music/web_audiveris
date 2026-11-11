#!/usr/bin/env python3
"""Archive the verified standalone package and compact, revision-bound verification evidence."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

root, mirror, revision = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3]
source = mirror / 'build/wasm-engine'
tracked = root / 'wasm-compat/engine-results'
raw = root / 'wsl-artifacts/wasm-components/engine'
package = root / 'wsl-artifacts/wasm-engine'
sha = lambda data: hashlib.sha256(data).hexdigest()
git = ['git', '-c', 'safe.directory=' + str(root), '-C', str(root)]
if subprocess.check_output(git + ['rev-parse', revision]).decode().strip() != revision:
    raise ValueError('Expected a full source commit')
for name in ['engine', 'workers']:
    if not json.loads((source / name / 'xml-comparison.json').read_text())['exact']:
        raise AssertionError('MusicXML differs: ' + name)
workers = json.loads((source / 'workers/verification.json').read_text())
if workers['concurrentWorkers'] != 2 or not all(workers.get(key) for key in ['bundledOcr', 'abortDuringInit', 'abortDuringRecognition', 'rejectedBusy']):
    raise AssertionError('Worker lifecycle checks did not pass')
local = json.loads((source / 'local-ocr/verification.json').read_text())
if sum(row['layout'] == 'SINGLE_BLOCK' for row in local['requests']) < 2 or not local['staleChecks'] or not local['duplicateChecks']:
    raise AssertionError('Missing positive local OCR coverage')
if (source / 'engine/diagnostics.tsv').read_text().splitlines() != ['severity\tmethod\tmessage']:
    raise AssertionError('Strict compilation diagnostics remain')
if (source / 'engine/engine.wasm').read_bytes() != (source / 'package/engine.wasm').read_bytes():
    raise AssertionError('Packaged engine differs from compiled engine')
archive = subprocess.check_output(git + ['archive', revision, '--', 'app/src/main/java', 'app/res',
    'wasm-compat/src/main', 'wasm-compat/src/test/java', 'wasm-compat/host', 'wasm-compat/tools', 'dev/teavm-audit', 'dev/wsl/source-commit.init.gradle'])
sources = {}
text_suffixes = {'.java', '.js', '.mjs', '.cjs', '.py', '.gradle', '.c', '.h', '.xml', '.json', '.md',
    '.properties', '.txt', '.html', '.css', '.svg', '.xsd', '.dtd', '.sh'}
with tarfile.open(fileobj=io.BytesIO(archive)) as files:
    for entry in files:
        if not entry.isfile():
            continue
        name = entry.name
        if name.startswith('dev/teavm-audit/'):
            relative = Path(name).relative_to('dev/teavm-audit')
            if not (relative.parts[0] == 'src' or (len(relative.parts) == 1 and
                    (relative.suffix in {'.gradle', '.c', '.h', '.xml'} or relative.name in {
                        'build_engine_fonts.py', 'build_music_symbols_wasm.py', 'package_engine.py', 'package_engine_ocr.py'}))):
                continue  # Historical analysis results and archive scripts are not build inputs.
        compiled = (mirror / name).read_bytes()
        committed = files.extractfile(entry).read()
        if Path(name).suffix in text_suffixes or '/META-INF/services/' in name:
            compiled = compiled.replace(b'\r\n', b'\n')
            committed = committed.replace(b'\r\n', b'\n')
        if compiled != committed:
            raise AssertionError('Mirror differs from source revision: ' + name)
        sources[name] = sha(compiled)
limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
cpu, memory, swap = [(limits / key).read_text().strip() for key in ['cpu.max', 'memory.max', 'memory.swap.max']]
if cpu != '800000 100000' or memory != '8589934592' or swap != '0':
    raise AssertionError('Resource ceilings changed')
tracked.mkdir(parents=True, exist_ok=True)
raw.mkdir(parents=True, exist_ok=True)
if package.exists():
    expected_files = {p.relative_to(source / 'package') for p in (source / 'package').rglob('*') if p.is_file()}
    old_files = {p.relative_to(package) for p in package.rglob('*') if p.is_file()}
    if old_files - expected_files:
        raise ValueError('Archive the previous package directory before exporting; refusing to mix stale files')
package.mkdir(parents=True, exist_ok=True)
shutil.copytree(source / 'package', package, dirs_exist_ok=True)
for folder in ['engine', 'workers', 'local-ocr', 'native-export', 'font-data', 'text-fonts']:
    shutil.copytree(source / folder, raw / folder, dirs_exist_ok=True)
reports = {
    'node.json': source / 'engine/verification.json',
    'node-musicxml.json': source / 'engine/xml-comparison.json',
    'workers.json': source / 'workers/verification.json',
    'worker-musicxml.json': source / 'workers/xml-comparison.json',
    'local-ocr.json': source / 'local-ocr/verification.json',
    'diagnostics.tsv': source / 'engine/diagnostics.tsv',
    'font-backend.json': source / 'fonts/build.json',
    'inputs.json': mirror / 'build/wasm-compat/pixel-probe/samples/manifest.json',
}
for name, path in reports.items():
    shutil.copyfile(path, tracked / name)
core_files = [p for p in (source / 'package').rglob('*') if p.is_file() and 'ocr' not in p.relative_to(source / 'package').parts]
ocr_files = [p for p in (source / 'package/ocr').rglob('*') if p.is_file()]
sizes = {'engineWasmBytes': (source / 'package/engine.wasm').stat().st_size,
    'fontWasmBytes': (source / 'package/fonts/music-symbols.wasm').stat().st_size,
    'coreBytes': sum(p.stat().st_size for p in core_files),
    'coreGzipBytes': sum(len(gzip.compress(p.read_bytes(), compresslevel=9, mtime=0)) for p in core_files),
    'optionalOcrBytes': sum(p.stat().st_size for p in ocr_files)}
provenance = {'sourceCommit': revision, 'sourceSha256': sources,
    'sourceSelection': 'Application resources/main, compatibility main/test/host/tools, audit compiler/C sources and engine build inputs; excludes historical reports and archive scripts',
    'package': 'wsl-artifacts/wasm-engine', 'rawArtifacts': 'wsl-artifacts/wasm-components/engine',
    'engineSha256': sha((source / 'package/engine.wasm').read_bytes()), 'sizes': sizes,
    'cpuMax': cpu, 'allowedCpus': (limits / 'cpuset.cpus.effective').read_text().strip(),
    'memoryMaxBytes': int(memory), 'swapMaxBytes': int(swap),
    'memoryEvents': (limits / 'memory.events').read_text().strip(),
    'reportsSha256': {name: sha(path.read_bytes()) for name, path in reports.items()}}
for output in [tracked, raw]:
    (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
print(json.dumps({'sourceCommit': revision, 'sourceFiles': len(sources), 'sizes': sizes}))
