"""Archive verified thin-path drawing and its packed-backend integration checks."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    probe = mirror / 'build/wasm-compat/gray-thin-probe'
    read = lambda path: json.loads(path.read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report, native = read(probe / 'verification.json'), read(probe / 'jvm-verification.json')
    packed_probe = mirror / 'build/wasm-compat/packed-thin-probe'
    packed = read(packed_probe / 'verification.json')
    assert report['nodeInstances'] == report['workers'] == 2 and report['paths'] == native['cases'] == 9506
    assert report['nativeComparedPixels'] == 16530934 and report['normalizationModes'] == 2
    assert report['packedBackendDefaultCases'] == native['packedBackendDefaultCases'] == 4753
    assert report['packedBackendGuardCases'] == native['packedBackendGuardCases'] == 2
    assert all(report[key] for key in ['nativeJvmPixelsExact', 'nodePixelsExact', 'workerPixelSha256Exact', 'wasmPerPathHashesExact', 'packedBackendPixelsExact'])
    assert report['inputGuardsPerInstance'] == 4 and report['nativeEndCaps'] == report['nativeLineJoins'] == 3
    assert sha(probe / 'gray-thin.wasm') == report['wasmSha256'] and sha(probe / 'jvm-pixels.binary') == report['pixelSha256']
    assert (probe / 'diagnostics.tsv').read_text().splitlines() == ['severity\tmethod\tmessage']
    assert packed['packedBackendInWasm'] and packed['paths'] == 4753 and packed['normalizationModes'] == 1
    assert packed['nodeInstances'] == packed['workers'] == 2 and packed['inputGuardsPerInstance'] == 2
    assert packed['nativeComparedPixels'] == 8265467 and packed['nodePixelsExact'] and packed['workerPixelSha256Exact']
    assert sha(packed_probe / 'packed-thin.wasm') == packed['wasmSha256']
    assert (packed_probe / 'diagnostics.tsv').read_text().splitlines() == ['severity\tmethod\tmessage']
    full = (probe / 'jvm-pixels.binary').read_bytes(); subset = hashlib.sha256(); size = 47 * 37
    for index in range(4753):
        subset.update(full[index * 2 * size:(index * 2 + 1) * size])
    assert subset.hexdigest() == packed['pixelSha256']
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    sources = ['dev/teavm-audit/audit.init.gradle', 'wasm-compat/tools/verify-gray-thin.cjs',
        'app/src/main/java/org/audiveris/omr/image/PageRaster.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/PackedPageRaster.java',
        'wasm-compat/src/jdk/java/org/audiveris/wasm/compat/platform/GrayThinRaster.java',
        'wasm-compat/third-party/openjdk/LICENSE', 'wasm-compat/third-party/openjdk/NOTICE.md']
    sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/' + name + '.java' for name in ['GrayThinProbe', 'GrayThinOracle', 'GrayPathProbe', 'PackedThinProbe']]
    sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/' + name + '.java' for name in ['CompilerPatches', 'CompilerSubstitutions', 'HeadlessPatches', 'StrokePathPatches']]
    sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/' + name + '.java' for name in ['StrokeRuntime', 'GraySpanRaster']]
    sources += ['wasm-compat/src/jdk/java/org/audiveris/wasm/compat/platform/' + name + '.java' for name in ['GrayPathRaster', 'StrokePaths']]
    source_hashes = {}
    for name in sources:
        data = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
        assert data == subprocess.check_output(git + ['show', revision + ':' + name]).replace(b'\r\n', b'\n'), name
        source_hashes[name] = hashlib.sha256(data).hexdigest()
    script_name = 'dev/teavm-audit/export_thin_raster.py'; data = Path(__file__).read_bytes().replace(b'\r\n', b'\n')
    assert data == subprocess.check_output(git + ['show', revision + ':' + script_name]).replace(b'\r\n', b'\n')
    source_hashes[script_name] = hashlib.sha256(data).hexdigest()
    cg = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    limits = [(cg / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert limits == ['800000 100000', '8589934592', '0']
    relative = 'wsl-artifacts/wasm-components/gray-thin'; raw = root / relative
    tracked = root / 'wasm-compat/gray-thin-results'; raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    names = ['verification.json', 'diagnostics.tsv', 'gray-thin.wasm', 'gray-thin.wasm-runtime.js', 'jvm-reference.txt', 'jvm-pixels.binary', 'jvm-runtime.txt', 'jvm-verification.json']
    for name in names:
        shutil.copyfile(probe / name, raw / name)
    (raw / 'packed').mkdir(exist_ok=True)
    for name in ['verification.json', 'diagnostics.tsv', 'packed-thin.wasm', 'packed-thin.wasm-runtime.js']:
        shutil.copyfile(packed_probe / name, raw / 'packed' / name)
    for name in ['LICENSE', 'NOTICE.md']:
        shutil.copyfile(mirror / 'wasm-compat/third-party/openjdk' / name, raw / name)
    artifacts = {str(path.relative_to(raw)): sha(path) for path in sorted(raw.rglob('*')) if path.is_file() and path.name != 'provenance.json'}
    provenance = {'sourceCommit': revision, 'sourceSha256': source_hashes, 'rawArtifacts': relative,
        'cpuMax': limits[0], 'memoryMaxBytes': int(limits[1]), 'memorySwapMaxBytes': int(limits[2]),
        'memoryEventsLocal': (cg / 'memory.events.local').read_text().strip(), 'artifactSha256': artifacts, 'limitation': report['limitation']}
    for name in ['verification.json', 'diagnostics.tsv', 'jvm-verification.json']:
        shutil.copyfile(raw / name, tracked / name)
    shutil.copyfile(packed_probe / 'verification.json', tracked / 'packed-verification.json')
    for destination in [raw, tracked]:
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({'paths': report['paths'], 'pixels': report['nativeComparedPixels'], 'packedBackendCases': report['packedBackendDefaultCases'], 'wasmBytes': report['wasmBytes']}))


if __name__ == '__main__':
    main()
