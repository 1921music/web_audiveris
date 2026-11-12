#!/usr/bin/env python3
"""Create a self-contained file:// UI with compressed embedded assets and Blob Workers."""
import base64
import gzip
import hashlib
import json
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
source = root / 'build/wasm-engine/package'
output = root / 'build/wasm-engine/offline'
output.mkdir(parents=True, exist_ok=True)
if not (source / 'ocr/models/eng.traineddata.gz').is_file():
    raise ValueError('Build packageWorkerOcr first')
payloads, names, file_hashes, encodings = {}, {}, {}, {}
for path in sorted(source.rglob('*')):
    if not path.is_file():
        continue
    name = path.relative_to(source).as_posix()
    if name in {'index.html', 'app.css', 'offline-bootstrap.js'}:
        continue
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    file_hashes[name] = digest
    names.setdefault(digest, []).append(name)
    if digest not in payloads:
        compressed = gzip.compress(data, compresslevel=9, mtime=0)
        use_gzip = path.suffix.lower() in {'.wasm', '.ttf', '.otf'} or len(compressed) + 32 < len(data)
        payloads[digest] = {'encoding': 'gzip' if use_gzip else 'identity',
            'data': base64.b64encode(compressed if use_gzip else data).decode('ascii')}
    stored = payloads[digest]
    encodings[name] = {'encoding': stored['encoding'], 'originalBytes': len(data),
        'storedBytes': len(base64.b64decode(stored['data'])), 'base64Characters': len(stored['data'])}
    if path.suffix.lower() in {'.wasm', '.ttf', '.otf'} and stored['encoding'] != 'gzip':
        raise AssertionError('Wasm and fonts must be gzip-compressed before Base64 encoding')
html = (source / 'index.html').read_text()
html = html.replace('<link rel="stylesheet" href="./app.css">', '<style>' + (source / 'app.css').read_text() + '</style>')
html = html.replace('<script type="module" src="./app.mjs"></script>', '')
html = html.replace('class="brand" href="./"', 'class="brand" href="#"')
html = html.replace('Audiveris · ブラウザで動く楽譜認識エンジン', 'Audiveris · オフライン版（単一HTML）')
embedded = json.dumps({'payloads': payloads, 'payloadNames': names}, separators=(',', ':'))
bootstrap = (root / 'wasm-compat/web/offline-bootstrap.js').read_text()
html = html.replace('</body>', '<script type="application/json" id="audiveris-embedded-assets">' + embedded
    + '</script>\n<script>\n' + bootstrap + '\n</script>\n</body>')
target = output / 'audiveris-offline.html'
target.write_text(html, encoding='utf-8')
report = {'ocrBackend': json.loads((source / 'ocr/assets.json').read_text()).get('backend', 'tesseract-js'),
    'htmlBytes': target.stat().st_size, 'htmlSha256': hashlib.sha256(target.read_bytes()).hexdigest(),
    'embeddedFiles': len(file_hashes), 'uniquePayloads': len(payloads), 'assetSha256': file_hashes,
    'networkRequired': False, 'engineSha256': file_hashes['engine.wasm'], 'assetEncoding': encodings}
(output / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({key: value for key, value in report.items() if key not in {'assetSha256', 'assetEncoding'}}))
