"""Archive physical music-font metric conformance and explicitly unsupported fallback cases."""
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
    source = mirror / 'build/wasm-compat/music-metrics-wasm'; base = mirror / 'build/wasm-compat/freetype-native'
    read = lambda path: json.loads(path.read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report, native, build = [read(source / name) for name in ['verification.json', 'comparison.json', 'build.json']]
    assert report['nodeInstances'] == report['workers'] == 2 and report['exactValues']
    assert report['examinedCases'] == native['cases'] == 6966
    assert report['supportedCases'] == native['portableCases'] == 3483 and native['portableDifferences'] == 0
    assert report['unsupportedCases'] == 3483 and native['mappingDifferences'] == 0
    assert report['inputGuardsPerInstance'] == 5 and report['cacheLimitGuard']
    assert report['evictionNodeCheck']['checked'] == 18 and report['evictionNodeCheck']['stats']['cacheLimit'] == 1
    assert sha(source / 'jvm-reference.json') == build['nativeReferenceSha256'] == report['nativeReferenceSha256']
    assert sha(source / 'music-metrics.wasm') == build['wasmSha256'] == report['wasmSha256']
    assert sha(source / 'music-metrics.mjs') == report['glueSha256']
    for row in report['nodeRows'] + report['workerRows']:
        assert row['checked'] == row['unsupported'] == 3483 and row['guards'] == 5
        assert row['stats']['cacheLimit'] == 4096 and row['stats']['cachedMeasurements'] <= 4096
        assert row['stats']['cachedGlyphMetrics'] <= 4096 and row['stats']['linearMemoryBytes'] <= build['maximumLinearMemoryBytes']
        assert row['valueSha256'] == report['nodeRows'][0]['valueSha256']
    names = ['app/src/main/java/org/audiveris/omr/ui/symbol', 'app/src/main/java/org/audiveris/omr/glyph/Shape.java',
             'app/src/main/java/org/audiveris/omr/sig/inter/BeatUnitInter.java',
             'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/MusicMetricsOracle.java',
             'wasm-compat/host/freetype-music-metrics.mjs', 'wasm-compat/tools/verify-music-metrics.cjs',
             'wasm-compat/third-party/freetype/NOTICE.md',
             'dev/teavm-audit/music-paint.init.gradle', 'dev/teavm-audit/export_music_metrics.py']
    names += ['dev/teavm-audit/' + name for name in ['music-metrics-native.c', 'music-metrics-wasm.c', 'music-metrics-functions.h',
              'music-mask-native.c', 'music-mask-wasm.c', 'prepare_music_metrics_native.py', 'prepare_music_mask_native.py',
              'build_music_metrics_wasm.py', 'build_music_mask_wasm.py']]
    names += ['app/res/' + name for name in native['fontSha256']]
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    archive = subprocess.check_output(git + ['archive', revision] + names); hashes = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as files:
        for entry in files:
            if not entry.isfile():
                continue
            data = files.extractfile(entry).read()
            directory = root if entry.name in ['dev/teavm-audit/export_music_metrics.py', 'wasm-compat/third-party/freetype/NOTICE.md'] else mirror
            actual = (directory / entry.name).read_bytes()
            if not entry.name.endswith(('.otf', '.ttf', '.png')):
                data = data.replace(b'\r\n', b'\n'); actual = actual.replace(b'\r\n', b'\n')
            assert actual == data, entry.name
            hashes[entry.name] = hashlib.sha256(data).hexdigest()
    for name, digest in build['sourceSha256'].items():
        assert hashes['dev/teavm-audit/' + name] == digest
    native_build = read(base / 'metrics-build.json')
    assert native_build['isolatedStaticFreeType'] and sha(base / 'libmusic-metrics.so') == native_build['helperSha256']
    for name, digest in native_build['sourceSha256'].items():
        assert hashes['dev/teavm-audit/' + name] == digest
    assert hashes['wasm-compat/host/freetype-music-metrics.mjs'] == report['hostSha256']
    for name, digest in native['fontSha256'].items():
        assert hashes['app/res/' + name] == sha(source / 'fonts' / name) == report['fontSha256'][name] == digest
    cg = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    limits = [(cg / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert limits == ['800000 100000', '8589934592', '0']
    relative = 'wsl-artifacts/wasm-components/music-metrics'; raw = root / relative; tracked = root / 'wasm-compat/music-metrics-results'
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    for path in source.rglob('*'):
        if path.is_file():
            target = raw / path.relative_to(source); target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(path, target)
    shutil.copyfile(base / 'metrics-build.json', raw / 'native-build.json')
    shutil.copyfile(base / 'libmusic-metrics.so', raw / 'libmusic-metrics.so')
    shutil.copyfile(base / 'freetype-2.14.3.tar.xz', raw / 'freetype-2.14.3.tar.xz')
    shutil.copyfile(root / 'wasm-compat/third-party/freetype/NOTICE.md', raw / 'NOTICE.md')
    for name, origin in [('freetype-music-metrics.mjs', mirror / 'wasm-compat/host/freetype-music-metrics.mjs'),
                          ('verify-music-metrics.cjs', mirror / 'wasm-compat/tools/verify-music-metrics.cjs')]:
        shutil.copyfile(origin, raw / name)
    sdk = Path('/home/dev/tools/emsdk/upstream/emscripten')
    shutil.copyfile(sdk / 'LICENSE', raw / 'emscripten-LICENSE')
    shutil.copyfile(sdk / 'system/lib/libc/musl/COPYRIGHT', raw / 'musl-COPYRIGHT')
    artifacts = {str(path.relative_to(raw)): sha(path) for path in sorted(raw.rglob('*')) if path.is_file() and path.name != 'provenance.json'}
    provenance = {'sourceCommit': revision, 'sourceSha256': hashes, 'rawArtifacts': relative, 'artifactSha256': artifacts,
        'cpuMax': limits[0], 'memoryMaxBytes': int(limits[1]), 'memorySwapMaxBytes': int(limits[2]),
        'memoryEventsLocal': (cg / 'memory.events.local').read_text().strip(), 'limitation': report['limitation']}
    for name in ['verification.json', 'comparison.json', 'build.json', 'native-build.json']:
        shutil.copyfile(raw / name, tracked / name)
    for directory in [raw, tracked]:
        (directory / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['supportedCases', 'unsupportedCases', 'wasmBytes']}))


if __name__ == '__main__':
    main()
