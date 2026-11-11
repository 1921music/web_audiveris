"""Build a bounded, memory-only FreeType candidate, independently of the TeaVM engine."""
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile


def main():
    root = Path(sys.argv[1]); base = root / 'build/wasm-compat/freetype-native'
    output = root / 'build/wasm-compat/music-mask-wasm'; output.mkdir(parents=True, exist_ok=True)
    archive = base / 'freetype-2.14.3.tar.xz'
    expected = '36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f'
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    assert sha(archive) == expected
    with tarfile.open(archive) as files:
        files.extractall(base, filter='data')
    source = base / 'freetype-2.14.3'; build = base / 'wasm-build'
    compiler = subprocess.check_output(['emcc', '--version'], text=True)
    assert re.search(r'\b4\.0\.20\b', compiler.splitlines()[0]), compiler
    subprocess.run(['emcmake', 'cmake', '-S', str(source), '-B', str(build), '-G', 'Ninja',
                    '-DCMAKE_BUILD_TYPE=MinSizeRel', '-DBUILD_SHARED_LIBS=OFF', '-DCMAKE_C_FLAGS=-Oz -flto',
                    '-DFT_DISABLE_ZLIB=ON', '-DFT_DISABLE_BZIP2=ON', '-DFT_DISABLE_PNG=ON',
                    '-DFT_DISABLE_HARFBUZZ=ON', '-DFT_DISABLE_BROTLI=ON'], check=True)
    subprocess.run(['cmake', '--build', str(build), '--parallel', '8'], check=True)
    library = list(build.rglob('libfreetype.a')); assert len(library) == 1
    functions = ['_music_mask_memory_open', '_music_mask_close', '_music_mask_render', '_music_mask_version', '_malloc', '_free']
    subprocess.run(['emcc', str(root / 'dev/teavm-audit/music-mask-wasm.c'), str(library[0]),
                    '-I' + str(source / 'include'), '-Oz', '-flto', '-sMODULARIZE=1', '-sEXPORT_ES6=1',
                    '-sENVIRONMENT=web,worker,node', '-sFILESYSTEM=0', '-sALLOW_MEMORY_GROWTH=1',
                    '-sINITIAL_MEMORY=16777216', '-sMAXIMUM_MEMORY=134217728', '-sSTACK_SIZE=1048576',
                    '-sMALLOC=emmalloc', '-sEXPORTED_FUNCTIONS=' + json.dumps(functions),
                    '-sEXPORTED_RUNTIME_METHODS=' + json.dumps(['HEAPU8', 'HEAP32']),
                    '-o', str(output / 'music-masks.mjs')], check=True)
    trace = root / 'build/wasm-compat/music-mask-trace'; report = json.loads((trace / 'verification.json').read_text())
    assert report['cases'] == 2280 and report['candidates'][3]['differentCases'] == 0
    for name in ['verification.json', 'jvm-pixels.binary', 'glyph-programs.json']:
        shutil.copyfile(trace / name, output / ('native-' + name if name == 'verification.json' else name))
    (output / 'fonts').mkdir(exist_ok=True)
    for name, digest in report['fontSha256'].items():
        path = root / 'app/res' / name; assert sha(path) == digest
        shutil.copyfile(path, output / 'fonts' / name)
    for name in ['LICENSE.TXT', 'docs/FTL.TXT', 'docs/GPLv2.TXT']:
        shutil.copyfile(source / name, output / Path(name).name)
    source_hashes = {name: sha(root / name) for name in ['dev/teavm-audit/music-mask-native.c',
                     'dev/teavm-audit/music-mask-wasm.c', 'dev/teavm-audit/build_music_mask_wasm.py']}
    result = {'freeTypeSourceSha256': expected, 'compiler': compiler, 'sourceSha256': source_hashes,
              'wasmBytes': (output / 'music-masks.wasm').stat().st_size,
              'wasmSha256': sha(output / 'music-masks.wasm'), 'maximumLinearMemoryBytes': 134217728,
              'limitation': 'Standalone glyph-mask candidate; no TeaVM engine integration or portable music layout is established.'}
    (output / 'build.json').write_text(json.dumps(result, indent=2) + '\n'); print(json.dumps(result))


if __name__ == '__main__':
    main()
