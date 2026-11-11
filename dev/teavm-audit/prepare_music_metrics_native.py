"""Add a separate native metrics candidate without changing the verified mask helper."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from prepare_music_mask_native import main as prepare_masks


def main():
    prepare_masks()
    root = Path(sys.argv[1]); base = root / 'build/wasm-compat/freetype-native'
    library = base / 'native-build/libfreetype.a'; output = base / 'libmusic-metrics.so'
    subprocess.run(['cc', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-I' + str(base / 'freetype-2.14.3/include'),
                    str(root / 'dev/teavm-audit/music-metrics-native.c'), str(library),
                    '-Wl,-Bsymbolic', '-lm', '-o', str(output)], check=True)
    names = ['music-mask-native.c', 'music-metrics-native.c', 'music-metrics-functions.h',
             'prepare_music_metrics_native.py', 'prepare_music_mask_native.py']
    report = {'sourceSha256': {name: hashlib.sha256((root / 'dev/teavm-audit' / name).read_bytes()).hexdigest() for name in names},
              'helperSha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'isolatedStaticFreeType': True}
    (base / 'metrics-build.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
