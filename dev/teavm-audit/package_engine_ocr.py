#!/usr/bin/env python3
"""Package optional, explicitly configured English OCR separately from the engine core."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('root', type=Path)
parser.add_argument('--backend', choices=['tesseract-js', 'tesseract-wasm'], default='tesseract-js')
args = parser.parse_args()
root = args.root.resolve()
modules = root / 'wasm-compat/node_modules'
output = root / 'build/wasm-engine/package/ocr'
if any(parent.is_symlink() for parent in [output, *output.parents] if parent != root.parent):
    raise ValueError('Refusing symlinked package output')
if output.resolve() != root / 'build/wasm-engine/package/ocr':
    raise ValueError('Unexpected OCR output path')
# Rebuilding with another backend must never retain the previous runtime/model.
if output.exists():
    shutil.rmtree(output)
output.mkdir(parents=True, exist_ok=True)


def copy(source, name):
    target = output / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


if args.backend == 'tesseract-js':
    for name in ['tesseract.esm.min.js', 'worker.min.js', 'tesseract.min.js.LICENSE.txt', 'worker.min.js.LICENSE.txt']:
        copy(modules / 'tesseract.js/dist' / name, name)
    for extension in ['js', 'wasm']:
        copy(modules / 'tesseract.js-core' / ('tesseract-core-simd.' + extension), 'tesseract-core-simd.' + extension)
    model_variant = '4.0.0'
    packages = ['tesseract.js', 'tesseract.js-core', '@tesseract.js-data/eng']
    manifest = {'backend': args.backend, 'library': 'tesseract.esm.min.js', 'worker': 'worker.min.js',
        'core': 'tesseract-core-simd.js', 'models': 'models/', 'languages': ['eng'], 'engineMode': 'legacy'}
else:
    for name in ['lib.js', 'tesseract-core.wasm', 'tesseract-core-fallback.wasm']:
        copy(modules / 'tesseract-wasm/dist' / name, name)
    model_variant = '4.0.0_best_int'
    packages = ['tesseract-wasm', '@tesseract.js-data/eng']
    manifest = {'backend': args.backend, 'library': 'lib.js', 'core': 'tesseract-core.wasm',
        'fallbackCore': 'tesseract-core-fallback.wasm', 'model': 'models/eng.traineddata.gz',
        'languages': ['eng'], 'engineMode': 'lstm', 'metadataPolicy': 'lstm-plain-sans; native-characters; native-line-baseline; skip-missing-baseline'}
copy(modules / '@tesseract.js-data/eng' / model_variant / 'eng.traineddata.gz', 'models/eng.traineddata.gz')
manifest['modelPackage'] = '@tesseract.js-data/eng@1.0.0/' + model_variant
manifest['versions'] = {name: json.loads((modules / name / 'package.json').read_text())['version'] for name in packages}
for package in packages:
    for name in ['LICENSE', 'LICENSE.md', 'LICENSE.txt']:
        source = modules / package / name
        if source.exists():
            copy(source, 'licenses/' + package.replace('/', '-') + '-' + name)
(output / 'assets.json').write_text(json.dumps(manifest, indent=2) + '\n')
files = {str(p.relative_to(output)): {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
         for p in sorted(output.rglob('*')) if p.is_file() and p.name != 'integrity.json'}
(output / 'integrity.json').write_text(json.dumps(files, indent=2) + '\n')
print(json.dumps({'directory': str(output), 'backend': args.backend, 'bytes': sum(p['bytes'] for p in files.values()), 'files': len(files)}))
