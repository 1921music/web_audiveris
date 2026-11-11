"""Archive native/Wasm span comparisons and original TEXTS drawing observations."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    probe = mirror / 'build/wasm-compat/gray-span-probe'
    trace = mirror / 'build/wasm-compat/texts-cleanup-trace/verification.json'
    read = lambda path: json.loads(path.read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report, drawing = read(probe / 'verification.json'), read(trace)
    assert report['nodeInstances'] == report['workers'] == 2 and report['paths'] == 1024
    assert report['nativeComparedPixels'] == 1780736 and report['normalizationModes'] == 2
    assert all(report[key] for key in ['nativeJvmPixelsExact', 'nodePixelsExact', 'workerPixelSha256Exact', 'wasmPerPathHashesExact'])
    assert report['inputGuardsPerInstance'] == 4 and sha(probe / 'gray-span.wasm') == report['wasmSha256']
    assert sha(probe / 'jvm-pixels.binary') == report['pixelSha256']
    assert (probe / 'diagnostics.tsv').read_text().splitlines() == ['severity\tmethod\tmessage']
    assert len(drawing['pages']) == 5 and len(drawing['rejectedBeforeTexts']) == 3
    assert drawing['comparedPixels'] == 43474400 and drawing['originalCleanupPixelsExact'] and drawing['recordedOperationsReplayExact']
    for row in drawing['pages']:
        candidate = row['rasterCandidates']; assert candidate['unsupportedOperations'] == 0
        assert candidate['spanFill'] == {'beforeGlyphPassDifferentPixels': 0, 'finalDifferentPixels': 0}
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    changed = subprocess.check_output(git + ['diff', '--name-only', '-z', '04697c74fae431ef8436a9429386957a5cb25993', revision, '--', 'app/src/main/java']).decode().split('\0')
    sources = [name for name in changed if name.endswith('.java')]
    sources += ['app/src/main/java/org/audiveris/omr/' + name + '.java' for name in ['text/SheetScanner', 'sheet/PageCleaner']]
    sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/GraySpanRaster.java']
    sources += ['wasm-compat/src/jdk/java/org/audiveris/wasm/compat/platform/' + name + '.java' for name in ['GrayPathRaster', 'StrokePaths']]
    sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/' + name + '.java' for name in ['GraySpanProbe', 'GraySpanOracle', 'GrayPathProbe', 'TextsCleanupTrace', 'GraphicsDelegate', 'PixelSheetProbe', 'CueBeamsProbe']]
    sources += ['dev/teavm-audit/audit.init.gradle', 'wasm-compat/tools/verify-gray-span.cjs', 'wasm-compat/third-party/openjdk/LICENSE', 'wasm-compat/third-party/openjdk/NOTICE.md']
    source_hashes = {}
    for name in sorted(set(sources)):
        data = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
        assert data == subprocess.check_output(git + ['show', revision + ':' + name]).replace(b'\r\n', b'\n'), name
        source_hashes[name] = hashlib.sha256(data).hexdigest()
    script_name = 'dev/teavm-audit/export_span_cleanup.py'
    script = Path(__file__).read_bytes().replace(b'\r\n', b'\n')
    assert script == subprocess.check_output(git + ['show', revision + ':' + script_name]).replace(b'\r\n', b'\n')
    source_hashes[script_name] = hashlib.sha256(script).hexdigest()
    cg = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    limits = [(cg / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert limits == ['800000 100000', '8589934592', '0']
    relative = 'wsl-artifacts/wasm-components/gray-span'; raw = root / relative
    tracked = root / 'wasm-compat/gray-span-results'; raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    for name in ['verification.json', 'diagnostics.tsv', 'gray-span.wasm', 'gray-span.wasm-runtime.js', 'jvm-reference.txt', 'jvm-pixels.binary', 'jvm-runtime.txt']:
        shutil.copyfile(probe / name, raw / name)
    shutil.copyfile(trace, raw / 'texts-cleanup.json')
    inputs = {'samples': mirror / 'build/wasm-compat/pixel-probe/samples', 'native': mirror / 'build/wasm-compat/texts-continuation-probe/native'}
    for name, digest in drawing['inputSha256'].items():
        prefix, suffix = name.split('/', 1); source = (inputs[prefix] / suffix).resolve()
        assert source.is_relative_to(inputs[prefix].resolve()) and sha(source) == digest, name
        destination = raw / 'cleanup-inputs' / name; destination.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, destination)
    for name in ['LICENSE', 'NOTICE.md']:
        shutil.copyfile(mirror / 'wasm-compat/third-party/openjdk' / name, raw / name)
    artifacts = {str(path.relative_to(raw)): sha(path) for path in sorted(raw.rglob('*')) if path.is_file() and path.name != 'provenance.json'}
    provenance = {'sourceCommit': revision, 'sourceSha256': source_hashes, 'rawArtifacts': relative,
        'cpuMax': limits[0], 'memoryMaxBytes': int(limits[1]), 'memorySwapMaxBytes': int(limits[2]),
        'memoryEventsLocal': (cg / 'memory.events.local').read_text().strip(), 'artifactSha256': artifacts,
        'limitation': 'Drawing replay matches native pixels; the portable renderer is not yet connected to the cleanup traversal'}
    for name in ['verification.json', 'diagnostics.tsv', 'texts-cleanup.json']:
        shutil.copyfile(raw / name, tracked / name)
    for destination in [raw, tracked]:
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({'spanPaths': report['paths'], 'cleanupPages': len(drawing['pages']), 'cleanupPixels': drawing['comparedPixels'], 'wasmBytes': report['wasmBytes']}))


if __name__ == '__main__':
    main()
