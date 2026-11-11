"""Build the optional music-metrics API on the verified FreeType source/toolchain."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from build_music_mask_wasm import main as build_masks


def main():
    build_masks()
    root = Path(sys.argv[1]); base = root / 'build/wasm-compat/freetype-native'
    output = root / 'build/wasm-compat/music-metrics-wasm'; output.mkdir(parents=True, exist_ok=True)
    names = ['_music_mask_memory_open', '_music_mask_close', '_music_mask_version', '_music_metrics_family', '_music_metrics_full_name',
             '_music_metrics_glyph', '_music_metrics_measure', '_malloc', '_free']
    subprocess.run(['emcc', str(root / 'dev/teavm-audit/music-metrics-wasm.c'), str(base / 'wasm-build/libfreetype.a'),
                    '-I' + str(base / 'freetype-2.14.3/include'), '-Oz', '-flto', '-sMODULARIZE=1', '-sEXPORT_ES6=1',
                    '-sENVIRONMENT=web,worker,node', '-sFILESYSTEM=0', '-sALLOW_MEMORY_GROWTH=1',
                    '-sINITIAL_MEMORY=16777216', '-sMAXIMUM_MEMORY=134217728', '-sSTACK_SIZE=1048576', '-sMALLOC=emmalloc',
                    '-sEXPORTED_FUNCTIONS=' + json.dumps(names),
                    '-sEXPORTED_RUNTIME_METHODS=' + json.dumps(['HEAPU8', 'HEAP32', 'HEAPF64', 'UTF8ToString']),
                    '-o', str(output / 'music-metrics.mjs')], check=True)
    native = root / 'build/wasm-compat/music-metrics-native'
    report = json.loads((native / 'comparison.json').read_text()); assert report['portableDifferences'] == 0
    for name in ['jvm-reference.json', 'comparison.json']:
        shutil.copyfile(native / name, output / name)
    mask = root / 'build/wasm-compat/music-mask-wasm'
    (output / 'fonts').mkdir(exist_ok=True)
    for name in report['fontSha256']:
        shutil.copyfile(mask / 'fonts' / name, output / 'fonts' / name)
    for name in ['LICENSE.TXT', 'FTL.TXT', 'GPLv2.TXT']:
        shutil.copyfile(mask / name, output / name)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    sources = ['music-metrics-wasm.c', 'music-metrics-functions.h', 'music-mask-wasm.c', 'music-mask-native.c', 'build_music_metrics_wasm.py']
    result = {'sourceSha256': {name: sha(root / 'dev/teavm-audit' / name) for name in sources},
              'wasmBytes': (output / 'music-metrics.wasm').stat().st_size, 'wasmSha256': sha(output / 'music-metrics.wasm'),
              'maximumLinearMemoryBytes': 134217728, 'nativeReferenceSha256': sha(output / 'jvm-reference.json')}
    (output / 'build.json').write_text(json.dumps(result, indent=2) + '\n'); print(json.dumps(result))


if __name__ == '__main__':
    main()
