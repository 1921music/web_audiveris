#!/usr/bin/env python3
"""Archive both verified OCR builds and compare their actual XML without requiring LSTM parity."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

root, mirror, revision = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3]
git = ['git', '-c', 'safe.directory=' + str(root), '-C', str(root)]
if subprocess.check_output(git + ['rev-parse', revision]).decode().strip() != revision:
    raise ValueError('Expected full source commit')
paths = ['dev/teavm-audit/' + name for name in ['package_engine.py', 'package_engine_ocr.py',
    'package_engine_offline.py', 'engine.init.gradle']]
paths += [p.relative_to(root).as_posix() for folder in ['wasm-compat/host', 'wasm-compat/web', 'wasm-compat/third-party']
          for p in sorted((root / folder).rglob('*')) if p.is_file()]
paths += ['wasm-compat/' + name for name in ['package.json', 'package-lock.json']]
paths += ['wasm-compat/tools/' + name for name in ['verify-bundled-ocr.cjs', 'verify-engine-ui.cjs',
    'verify-engine-offline.cjs', 'verify-engine-workers.cjs']]
hashes = {}
for name in paths:
    tested = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
    committed = subprocess.check_output(git + ['show', revision + ':' + name]).replace(b'\r\n', b'\n')
    if tested != committed:
        raise AssertionError('Tested source differs from commit: ' + name)
    hashes[name] = hashlib.sha256(tested).hexdigest()
limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
cpu, memory, swap = [(limits / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
if (cpu, memory, swap) != ('800000 100000', '8589934592', '0'):
    raise AssertionError('Resource limits changed')
tracked = root / 'wasm-compat/ocr-backend-results'
tracked.mkdir(parents=True, exist_ok=True)
records = {}

def tree(element):
    return element.tag, sorted(element.attrib.items()), element.text or '', [tree(child) for child in element]

def xml_summary(path):
    element = ET.parse(path).getroot()
    return {'notes': len(element.findall('.//note')), 'elements': sum(1 for _ in element.iter()),
        'words': [item.text for item in element.findall('.//words')],
        'rehearsals': [item.text for item in element.findall('.//rehearsal')]}

for backend in ['tesseract-js', 'tesseract-wasm']:
    source = mirror / 'build/wasm-engine/backends' / backend
    package, offline = source / 'package', source / 'offline'
    manifest = json.loads((package / 'ocr/assets.json').read_text())
    for folder in ['wasm-compat/host', 'wasm-compat/web']:
        for path in (mirror / folder).iterdir():
            if not path.is_file() or path.name == 'offline-bootstrap.js':
                continue
            packaged = package / ('host' if folder.endswith('/host') else '') / path.name
            if packaged.read_bytes().replace(b'\r\n', b'\n') != path.read_bytes().replace(b'\r\n', b'\n'):
                raise AssertionError('Packaged source differs: ' + str(path))
    if manifest['backend'] != backend:
        raise AssertionError('Backend manifest differs')
    integrity = json.loads((package / 'ocr/integrity.json').read_text())
    for name, value in integrity.items():
        if hashlib.sha256((package / 'ocr' / name).read_bytes()).hexdigest() != value['sha256']:
            raise AssertionError('OCR asset changed: ' + name)
    # A switched build contains precisely the selected runtime/model plus notices.
    if backend == 'tesseract-wasm' and (package / 'ocr/worker.min.js').exists():
        raise AssertionError('Tesseract.js runtime leaked into alternative build')
    if backend == 'tesseract-js' and (package / 'ocr/lib.js').exists():
        raise AssertionError('Alternative runtime leaked into default build')
    build = json.loads((offline / 'build.json').read_text())
    if hashlib.sha256((offline / 'audiveris-offline.html').read_bytes()).hexdigest() != build['htmlSha256']:
        raise AssertionError('Tested HTML changed')
    if any(value['encoding'] != 'gzip' for name, value in build['assetEncoding'].items()
           if Path(name).suffix in {'.wasm', '.ttf', '.otf'}):
        raise AssertionError('Wasm/font gzip encoding missing')
    checks = {name: json.loads((source / name / 'verification.json').read_text())
        for name in ['ui', 'offline', 'ocr-checks', 'workers', 'local-ocr-workers']}
    if any(check['backend'] != backend for check in checks.values()):
        raise AssertionError('Verification belongs to another backend')
    if checks['offline']['networkRequests'] or not checks['offline']['browserNetworkDisabled'] or checks['offline']['serverUsed']:
        raise AssertionError('Offline networking check failed')
    if checks['workers']['concurrentWorkers'] != 2:
        raise AssertionError('Concurrent worker verification missing')
    if backend == 'tesseract-js':
        for name in ['ui', 'offline', 'workers']:
            if not json.loads((source / name / 'xml-comparison.json').read_text())['exact']:
                raise AssertionError('Default backend lost JVM parity')
    destination = root / 'wsl-artifacts/wasm-ocr-backends' / backend
    raw = root / 'wsl-artifacts/wasm-components/ocr-backends' / backend
    shutil.copytree(package, destination / 'package', dirs_exist_ok=True)
    (destination / 'offline').mkdir(parents=True, exist_ok=True)
    shutil.copy2(offline / 'audiveris-offline.html', destination / 'offline/audiveris-offline.html')
    for name, check in checks.items():
        target = tracked / backend / name
        target.mkdir(parents=True, exist_ok=True)
        (target / 'verification.json').write_text(json.dumps(check, indent=2) + '\n')
        # Explicit allowlist prevents stale comparison reports being exported for LSTM.
        for path in (source / name).iterdir():
            if path.suffix in {'.musicxml', '.png'}:
                folder = raw / name; folder.mkdir(parents=True, exist_ok=True); shutil.copy2(path, folder / path.name)
    shutil.copy2(offline / 'build.json', tracked / backend / 'offline/build.json')
    shutil.copy2(package / 'ocr/integrity.json', tracked / backend / 'ocr-integrity.json')
    sizes = {name: {'bytes': (package / 'ocr' / name).stat().st_size,
        'gzipBytes': len(gzip.compress((package / 'ocr' / name).read_bytes(), compresslevel=9, mtime=0))}
        for name in integrity}
    comparisons = []
    for name, ids in [('ui', ['00']), ('offline', ['00']), ('workers', ['00', '04'])]:
        for page in ids:
            actual = source / name / (page + '-0.musicxml')
            native = mirror / 'build/wasm-engine/native-export' / (page + '-0.jaxb.musicxml')
            comparisons.append({'run': name, 'page': page, 'jvmExact': tree(ET.parse(actual).getroot()) == tree(ET.parse(native).getroot()),
                'actual': xml_summary(actual), 'jvm': xml_summary(native)})
    # HTTP and file:// must give the same result for each backend.
    if tree(ET.parse(source / 'ui/00-0.musicxml').getroot()) != tree(ET.parse(offline / '00-0.musicxml').getroot()):
        raise AssertionError('HTTP and offline recognition differ')
    records[backend] = {'manifest': manifest, 'ocrFiles': sizes, 'ocrBytes': sum(value['bytes'] for value in sizes.values()),
        'offlineHtmlBytes': build['htmlBytes'], 'offlineHtmlSha256': build['htmlSha256'],
        'engineSha256': build['engineSha256'], 'httpRecognitionMs': checks['ui']['recognitionMs'],
        'offlineRecognitionMs': checks['offline']['recognitionMs'], 'httpAndOfflineSameXml': True,
        'comparisons': comparisons}

if records['tesseract-js']['engineSha256'] != records['tesseract-wasm']['engineSha256']:
    raise AssertionError('OCR choice changed engine Wasm')
report = {'sourceCommit': revision, 'sourceSha256': hashes, 'cpuMax': cpu, 'memoryMaxBytes': int(memory),
    'swapMaxBytes': int(swap), 'memoryEvents': (limits / 'memory.events').read_text().strip(),
    'timingMethod': 'one capped browser run per backend; not a controlled benchmark',
    'coverage': 'MerryGoRound pages 00 and 04; synthetic text; two-enclosure fixture; Chromium only',
    'backends': records}
(tracked / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({backend: {key: row[key] for key in ['ocrBytes', 'offlineHtmlBytes', 'httpRecognitionMs']}
                  for backend, row in records.items()}))
