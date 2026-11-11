#!/usr/bin/env python3
"""Package optional, explicitly configured English OCR separately from the engine core."""
import hashlib
import json
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1]).resolve()
modules = root / 'wasm-compat/node_modules'
output = root / 'build/wasm-engine/package/ocr'
output.mkdir(parents=True, exist_ok=True)


def copy(source, name):
    target = output / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


for name in ['tesseract.esm.min.js', 'worker.min.js', 'tesseract.min.js.LICENSE.txt', 'worker.min.js.LICENSE.txt']:
    copy(modules / 'tesseract.js/dist' / name, name)
for extension in ['js', 'wasm']:
    copy(modules / 'tesseract.js-core' / ('tesseract-core-simd.' + extension), 'tesseract-core-simd.' + extension)
copy(modules / '@tesseract.js-data/eng/4.0.0/eng.traineddata.gz', 'models/eng.traineddata.gz')
for package in ['tesseract.js', 'tesseract.js-core', '@tesseract.js-data/eng']:
    for name in ['LICENSE', 'LICENSE.md', 'LICENSE.txt']:
        source = modules / package / name
        if source.exists():
            copy(source, 'licenses/' + package.replace('/', '-') + '-' + name)
(output / 'assets.json').write_text(json.dumps({'library': 'tesseract.esm.min.js', 'worker': 'worker.min.js',
    'core': 'tesseract-core-simd.js', 'models': 'models/', 'languages': ['eng']}, indent=2) + '\n')
files = {str(p.relative_to(output)): {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
         for p in sorted(output.rglob('*')) if p.is_file() and p.name != 'integrity.json'}
(output / 'integrity.json').write_text(json.dumps(files, indent=2) + '\n')
print(json.dumps({'directory': str(output), 'bytes': sum(p['bytes'] for p in files.values()), 'files': len(files)}))
