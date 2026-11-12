#!/usr/bin/env python3
"""Assemble the standalone, page-independent engine assets (no JVM needed at runtime)."""
import hashlib
import json
import pathlib
import shutil
import sys

root = pathlib.Path(sys.argv[1]).resolve()
assets = root / 'build/wasm-engine'
target = assets / 'package'
if target.is_symlink() or target.resolve() != target or target.parent != assets:
    raise ValueError('Refusing redirected engine output')
if target.exists():
    shutil.rmtree(target)
target.mkdir(parents=True, exist_ok=True)


def copy(source, name):
    destination = target / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


copy(assets / 'engine/engine.wasm', 'engine.wasm')
copy(assets / 'engine/engine.wasm-runtime.js', 'engine.wasm-runtime.js')
for name in ['music-symbols.mjs', 'music-symbols.wasm', 'recipes.json']:
    copy(assets / 'fonts' / name, 'fonts/' + name)
for source in (assets / 'fonts/fonts').iterdir():
    if source.is_file():
        copy(source, 'fonts/fonts/' + source.name)
copy(assets / 'font-data/intrinsics.json', 'fonts/intrinsics.json')
copy(assets / 'text-fonts/font-resources.json', 'text-fonts/font-resources.json')
for source in (assets / 'text-fonts/fonts').iterdir():
    if source.is_file():
        copy(source, 'text-fonts/fonts/' + source.name)
copy(root / 'app/res/basic-classifier.zip', 'basic-classifier.zip')
copy(root / 'LICENSE', 'licenses/Audiveris-AGPL.txt')
for name in ['FTL.TXT', 'GPLv2.TXT', 'LICENSE.TXT', 'text-font-COPYRIGHT']:
    copy(assets / 'fonts' / name, 'licenses/' + name)
for source in (root / 'wasm-compat/third-party').rglob('*'):
    if source.is_file():
        copy(source, 'licenses/' + str(source.relative_to(root / 'wasm-compat/third-party')))
for source in (root / 'wasm-compat/host').iterdir():
    if source.suffix in ['.mjs', '.js', '.cjs']:
        copy(source, 'host/' + source.name)
for source in (root / 'wasm-compat/web').iterdir():
    if source.is_file() and source.name != 'offline-bootstrap.js':
        copy(source, source.name)
manifest = {
    'format': 1,
    'engine': 'engine.wasm',
    'runtime': 'engine.wasm-runtime.js',
    'classifier': 'basic-classifier.zip',
    'fontFactory': 'fonts/music-symbols.mjs',
    'fontWasm': 'fonts/music-symbols.wasm',
    'recipes': 'fonts/recipes.json',
    'intrinsics': 'fonts/intrinsics.json',
    'textFonts': 'text-fonts/font-resources.json',
    'musicFonts': {p.name: 'fonts/fonts/' + p.name for p in sorted((assets / 'fonts/fonts').iterdir()) if p.is_file()},
    'textFamilies': json.loads((assets / 'fonts/font-resources.json').read_text())['textFamilies'],
}
(target / 'assets.json').write_text(json.dumps(manifest, indent=2) + '\n')
files = {str(p.relative_to(target)): {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
         for p in sorted(target.rglob('*')) if p.is_file() and p.name != 'integrity.json'}
(target / 'integrity.json').write_text(json.dumps(files, indent=2) + '\n')
print(json.dumps({'directory': str(target), 'files': len(files), 'bytes': sum(p['bytes'] for p in files.values())}))
