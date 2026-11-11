"""Prepare a native-only helper with a pinned, isolated FreeType implementation."""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import urllib.request


def main():
    root = Path(sys.argv[1]); base = root / 'build/wasm-compat/freetype-native'
    base.mkdir(parents=True, exist_ok=True)
    archive = base / 'freetype-2.14.3.tar.xz'
    url = 'https://download.savannah.gnu.org/releases/freetype/freetype-2.14.3.tar.xz'
    expected = '36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f'
    if not archive.exists():
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(8 * 1024 * 1024)
        assert len(data) < 8 * 1024 * 1024 and hashlib.sha256(data).hexdigest() == expected
        archive.write_bytes(data)
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected
    # Restore verified sources instead of trusting a prior extraction.
    with tarfile.open(archive) as files:
        files.extractall(base, filter='data')
    jdk = Path(os.environ['JAVA_HOME']); jdk_library = jdk / 'lib/libfreetype.so'
    ft = ctypes.CDLL(str(jdk_library)); handle = ctypes.c_void_p()
    assert ft.FT_Init_FreeType(ctypes.byref(handle)) == 0
    version = [ctypes.c_int() for _ in range(3)]
    ft.FT_Library_Version(handle, *[ctypes.byref(part) for part in version])
    assert ft.FT_Done_FreeType(handle) == 0
    assert [part.value for part in version] == [2, 14, 3]
    build = base / 'native-build'
    subprocess.run(['cmake', '-S', str(base / 'freetype-2.14.3'), '-B', str(build), '-G', 'Ninja',
                    '-DCMAKE_BUILD_TYPE=MinSizeRel', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
                    '-DBUILD_SHARED_LIBS=OFF', '-DCMAKE_C_FLAGS=-fvisibility=hidden',
                    '-DFT_DISABLE_ZLIB=ON', '-DFT_DISABLE_BZIP2=ON', '-DFT_DISABLE_PNG=ON',
                    '-DFT_DISABLE_HARFBUZZ=ON', '-DFT_DISABLE_BROTLI=ON'], check=True)
    subprocess.run(['cmake', '--build', str(build), '--parallel', '8'], check=True)
    libraries = list(build.rglob('libfreetype.a')); assert len(libraries) == 1
    library = libraries[0]
    output = base / 'libmusic-mask.so'; source = root / 'dev/teavm-audit/music-mask-native.c'
    subprocess.run(['cc', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-I' + str(base / 'freetype-2.14.3/include'), str(source),
                    str(library), '-Wl,-Bsymbolic', '-lm', '-o', str(output)], check=True)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report = {'sourceUrl': url, 'sourceArchiveSha256': expected, 'freeTypeVersion': [2, 14, 3],
              'library': str(library), 'librarySha256': sha(library), 'helperSha256': sha(output),
              'jdkLibrarySha256': sha(jdk_library), 'isolatedStaticFreeType': True,
              'helperSourceSha256': sha(source), 'limitation': 'Native JVM comparison only; no Wasm library is built.'}
    (base / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
