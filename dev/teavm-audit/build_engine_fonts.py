#!/usr/bin/env python3
"""Build standalone font assets directly from pinned sources, without prior probe outputs."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request

root = Path(sys.argv[1]).resolve()
base = root / 'build/wasm-engine/freetype'
output = root / 'build/wasm-engine/fonts'
definitions = root / 'build/wasm-engine/music-definitions'
base.mkdir(parents=True, exist_ok=True)
output.mkdir(parents=True, exist_ok=True)
archive = base / 'freetype-2.14.3.tar.xz'
expected = '36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f'
old_cache = root / 'build/wasm-compat/freetype-native' / archive.name
if not archive.exists():
    if old_cache.exists():
        shutil.copyfile(old_cache, archive)
    else:
        with urllib.request.urlopen('https://download.savannah.gnu.org/releases/freetype/' + archive.name, timeout=60) as response:
            data = response.read(8 * 1024 * 1024)
        if len(data) >= 8 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('FreeType archive failed verification')
        archive.write_bytes(data)
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise ValueError('FreeType archive failed verification')
with tarfile.open(archive) as files:
    files.extractall(base, filter='data')
source = base / 'freetype-2.14.3'
compiler = subprocess.check_output(['emcc', '--version'], text=True)
if not re.search(r'\b4\.0\.20\b', compiler.splitlines()[0]):
    raise ValueError('Expected Emscripten 4.0.20')
subprocess.run(['emcmake', 'cmake', '-S', str(source), '-B', str(base / 'wasm-build'), '-G', 'Ninja',
                '-DCMAKE_BUILD_TYPE=MinSizeRel', '-DBUILD_SHARED_LIBS=OFF', '-DCMAKE_C_FLAGS=-Oz -flto',
                '-DFT_DISABLE_ZLIB=ON', '-DFT_DISABLE_BZIP2=ON', '-DFT_DISABLE_PNG=ON',
                '-DFT_DISABLE_HARFBUZZ=ON', '-DFT_DISABLE_BROTLI=ON'], check=True)
subprocess.run(['cmake', '--build', str(base / 'wasm-build'), '--parallel', '8'], check=True)
exports = ['_music_mask_memory_open', '_music_mask_close', '_music_mask_version', '_music_mask_render',
           '_music_metrics_family', '_music_metrics_full_name', '_music_metrics_glyph', '_music_metrics_measure',
           '_music_symbol_glyph', '_music_symbol_metrics', '_music_symbol_outline', '_malloc', '_free']
subprocess.run(['emcc', str(root / 'dev/teavm-audit/music-symbols-wasm.c'), str(base / 'wasm-build/libfreetype.a'),
                '-I' + str(source / 'include'), '-Oz', '-flto', '-sMODULARIZE=1', '-sEXPORT_ES6=1',
                '-sENVIRONMENT=web,worker,node', '-sFILESYSTEM=0', '-sALLOW_MEMORY_GROWTH=1',
                '-sINITIAL_MEMORY=16777216', '-sMAXIMUM_MEMORY=134217728', '-sSTACK_SIZE=1048576', '-sMALLOC=emmalloc',
                '-sEXPORTED_FUNCTIONS=' + json.dumps(exports),
                '-sEXPORTED_RUNTIME_METHODS=' + json.dumps(['HEAPU8', 'HEAP32', 'HEAPF64', 'UTF8ToString']),
                '-o', str(output / 'music-symbols.mjs')], check=True)
for name in ['recipes.json', 'inventory.json', 'font-resources.json']:
    shutil.copyfile(definitions / name, output / name)
(output / 'fonts').mkdir(exist_ok=True)
inventory = json.loads((definitions / 'inventory.json').read_text())
for name, digest in inventory['musicFontSha256'].items():
    data = (root / 'app/res' / name).read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError('Music font changed: ' + name)
    (output / 'fonts' / name).write_bytes(data)
resources = json.loads((definitions / 'font-resources.json').read_text())
for name, record in resources['fonts'].items():
    data = (definitions / 'fonts' / name).read_bytes()
    if hashlib.sha256(data).hexdigest() != record['sha256']:
        raise ValueError('Text symbol font changed: ' + name)
    (output / 'fonts' / name).write_bytes(data)
for name in ['LICENSE.TXT', 'docs/FTL.TXT', 'docs/GPLv2.TXT']:
    shutil.copyfile(source / name, output / Path(name).name)
shutil.copyfile('/usr/share/doc/fonts-dejavu-core/copyright', output / 'text-font-COPYRIGHT')
result = {'freeTypeSourceSha256': expected, 'wasmBytes': (output / 'music-symbols.wasm').stat().st_size,
          'wasmSha256': hashlib.sha256((output / 'music-symbols.wasm').read_bytes()).hexdigest(),
          'maximumLinearMemoryBytes': 134217728, 'pageSpecificResources': 0}
(output / 'build.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))
