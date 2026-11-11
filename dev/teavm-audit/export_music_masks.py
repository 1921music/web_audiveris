"""Archive the standalone FreeType mask candidate and its independent native comparison."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    source = mirror / 'build/wasm-compat/music-mask-wasm'
    native_base = mirror / 'build/wasm-compat/freetype-native'
    read = lambda path: json.loads(path.read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report, native, build, native_build = [read(path) for path in [source / 'verification.json',
        source / 'native-verification.json', source / 'build.json', native_base / 'build.json']]
    assert report['nodeInstances'] == report['workers'] == 2 and report['cases'] == native['cases'] == 2280
    assert report['nativeComparedPixels'] == 58368000 and report['nodePixelsExact'] and report['workerPixelSha256Exact']
    assert native['linkedFreeTypeVersion'] == [2, 14, 3] and native_build['isolatedStaticFreeType']
    assert sha(native_base / 'libmusic-mask.so') == native_build['helperSha256']
    assert sha(Path(native_build['library'])) == native_build['librarySha256']
    assert native['candidates'][3]['differentCases'] == native['candidates'][3]['differentPixels'] == 0
    assert native['candidates'][3]['noHinting'] and native['candidates'][3]['roundOrigin'] and native['nativeIntermediatePixels'] > 0
    assert all(row['differentCases'] > 0 for row in native['candidates'][:3])
    assert sha(source / 'jvm-pixels.binary') == native['nativePixelsSha256'] == report['pixelSha256']
    assert sha(source / 'glyph-programs.json') == native['glyphProgramsSha256'] == report['glyphProgramsSha256']
    assert sha(source / 'music-masks.wasm') == build['wasmSha256'] == report['wasmSha256']
    assert sha(source / 'music-masks.mjs') == report['glueSha256']
    assert report['inputGuardsPerInstance'] == 4
    assert len(report['nodeRows']) == len(report['workerRows']) == 2
    for row in report['nodeRows'] + report['workerRows']:
        assert row['guards'] == 4 and row['cachedMasks'] * 2 == native['cachedMasks']
        assert row['linearMemoryBytes'] <= build['maximumLinearMemoryBytes'] == 134217728
    assert all(row['pixelSha256'] == report['pixelSha256'] and row['dom'] == 'undefined' for row in report['workerRows'])
    names = ['app/src/main/java/org/audiveris/omr/ui/symbol', 'app/src/main/java/org/audiveris/omr/glyph/Shape.java',
             'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/MusicMaskTrace.java',
             'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/GraphicsDelegate.java',
             'dev/teavm-audit/audit.init.gradle', 'dev/teavm-audit/music-paint.init.gradle',
             'dev/teavm-audit/music-mask-native.c', 'dev/teavm-audit/music-mask-wasm.c',
             'dev/teavm-audit/prepare_music_mask_native.py', 'dev/teavm-audit/build_music_mask_wasm.py',
             'dev/teavm-audit/export_music_masks.py', 'dev/wsl/install-emsdk.sh',
             'wasm-compat/tools/music-mask-runner.mjs', 'wasm-compat/tools/verify-music-masks.cjs']
    names += ['app/res/' + name for name in native['fontSha256']]
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    archive = subprocess.check_output(git + ['archive', revision] + names); hashes = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as files:
        for entry in files:
            if not entry.isfile():
                continue
            data = files.extractfile(entry).read()
            directory = root if entry.name in ['dev/teavm-audit/export_music_masks.py', 'dev/wsl/install-emsdk.sh'] else mirror
            actual = (directory / entry.name).read_bytes()
            if entry.name.endswith(('.java', '.gradle', '.py', '.properties', '.uxf', '.c', '.mjs', '.cjs', '.sh')):
                data = data.replace(b'\r\n', b'\n'); actual = actual.replace(b'\r\n', b'\n')
            assert actual == data, entry.name
            hashes[entry.name] = hashlib.sha256(data).hexdigest()
    for name, digest in build['sourceSha256'].items():
        assert hashes[name] == digest
    assert hashes['dev/teavm-audit/music-mask-native.c'] == native_build['helperSourceSha256']
    assert hashes['wasm-compat/tools/music-mask-runner.mjs'] == report['runnerSha256']
    for name, digest in native['fontSha256'].items():
        assert sha(source / 'fonts' / name) == hashes['app/res/' + name] == digest == report['fontSha256'][name]
    sdk = Path('/home/dev/tools/emsdk')
    sdk_revision = subprocess.check_output(['git', '-C', str(sdk), 'rev-parse', 'HEAD'], text=True).strip()
    assert sdk_revision == 'e4fe26ef59168ff44f4c23c466e497bf60b3411e'
    cg = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    limits = [(cg / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert limits == ['800000 100000', '8589934592', '0']
    relative = 'wsl-artifacts/wasm-components/music-masks'; raw = root / relative; tracked = root / 'wasm-compat/music-mask-results'
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    for path in source.rglob('*'):
        if path.is_file():
            target = raw / path.relative_to(source); target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(path, target)
    shutil.copyfile(native_base / 'build.json', raw / 'native-build.json')
    shutil.copyfile(native_base / 'libmusic-mask.so', raw / 'libmusic-mask.so')
    shutil.copyfile(native_base / 'freetype-2.14.3.tar.xz', raw / 'freetype-2.14.3.tar.xz')
    for name in ['music-mask-runner.mjs', 'verify-music-masks.cjs']:
        shutil.copyfile(mirror / 'wasm-compat/tools' / name, raw / name)
    for name, path in [('emscripten-LICENSE', sdk / 'upstream/emscripten/LICENSE'),
                       ('musl-COPYRIGHT', sdk / 'upstream/emscripten/system/lib/libc/musl/COPYRIGHT')]:
        shutil.copyfile(path, raw / name)
    artifacts = {str(path.relative_to(raw)): sha(path) for path in sorted(raw.rglob('*')) if path.is_file() and path.name != 'provenance.json'}
    provenance = {'sourceCommit': revision, 'sourceSha256': hashes, 'rawArtifacts': relative, 'artifactSha256': artifacts,
        'emsdkVersion': '4.0.20', 'emsdkRevision': sdk_revision, 'nativeGlyphLayoutSupplied': True, 'engineIntegrated': False,
        'cpuMax': limits[0], 'memoryMaxBytes': int(limits[1]), 'memorySwapMaxBytes': int(limits[2]),
        'memoryEventsLocal': (cg / 'memory.events.local').read_text().strip(), 'limitation': report['limitation']}
    for name in ['verification.json', 'native-verification.json', 'build.json', 'native-build.json']:
        shutil.copyfile(raw / name, tracked / name)
    for directory in [raw, tracked]:
        (directory / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['cases', 'nativeComparedPixels', 'wasmBytes', 'wasmGzipBytes']}))


if __name__ == '__main__':
    main()
