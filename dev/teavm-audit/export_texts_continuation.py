"""Archive TEXTS recognition with explicitly supplied native cleanup/OCR/font fixtures."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    ttf = '--ttf' in sys.argv[4:]
    live = ttf or '--live' in sys.argv[4:]
    source = mirror / 'build/wasm-compat/texts-continuation-probe'
    module = mirror / 'build/wasm-compat/texts-font-backed-probe' if ttf else source
    module_name = 'texts-font-backed' if ttf else 'texts-continuation'
    native = source / ('live-ocr/jvm' if live else 'native')
    verification = module / 'verification.json' if ttf else source / ('live-ocr/verification.json' if live else 'verification.json')
    read = lambda path: json.loads(path.read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report = read(verification)
    checkpoint = module / 'node-verification.json'
    if 'nodeCheckpointSha256' in report:
        assert sha(checkpoint) == report['nodeCheckpointSha256']
        saved = read(checkpoint)
        assert saved['verifierHash'] == report['verifierSha256']
        assert saved['checks'] == report['pages'] * 2
    assert report.get('liveOcr', False) == live
    assert report.get('ordinaryFontsFromFiles', False) == ttf
    if ttf:
        assert report['suppliedOrdinaryFontMeasurements'] == 0 and report['fontMeasurements'] == 2
        assert report['fontResourcesSealed'] and len(report['textFontMeasurementCalls']) == 4
        assert all(value > 0 for value in report['textFontMeasurementCalls'])
    assert report['workers'] == report['nodeInstances'] == 2
    assert report['checks'] == report['pages'] * 4 and report['originalTextWords'] > 0
    assert report['originalSystemProcessing'] and report['asynchronousReplyBoundary'] and report['duplicateAndLateRepliesRejected']
    assert report['maxDifference'] <= 1e-8 and sha(module / (module_name + '.wasm')) == report['wasmSha256']
    assert (module / 'diagnostics.tsv').read_text().splitlines() == ['severity\tmethod\tmessage']
    for name, expected in report['inputSha256'].items():
        path = (source / name).resolve(); assert path.is_relative_to(mirror.resolve())
        assert sha(path) == expected, 'Verified TEXTS input changed: ' + name
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    changed = subprocess.check_output(git + ['diff', '--name-only', '-z',
        '04697c74fae431ef8436a9429386957a5cb25993', revision, '--', 'app/src/main/java']).decode().split('\0')
    sources = [name for name in changed if name.endswith('.java')] + [
        'wasm-compat/src/main/java/org/audiveris/omr/text/WorkerTextsBridge.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/HeadlessPatches.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/HeadlessTextFontGeometry.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/OcrExchange.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/OcrImageFrame.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/OcrTransport.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/TextsContinuationProbe.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/TextsContinuationOracle.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/NativeOcrBackendOracle.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/RecordingTextFontGeometry.java',
        'wasm-compat/tools/verify-texts-continuation.cjs', 'dev/teavm-audit/audit.init.gradle']
    if live:
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/LiveTextsOracle.java',
            'wasm-compat/src/test/java/org/audiveris/omr/text/DirectTextsSystems.java',
            'wasm-compat/tools/compare-score-ocr.cjs', 'wasm-compat/host/tesseract-js-ocr.mjs',
            'wasm-compat/package-lock.json',
            'wasm-compat/host/gray8-png.mjs', 'wasm-compat/host/tesseract-word-metadata.mjs',
            'wasm-compat/host/tesseract-word-metadata-node.cjs', 'wasm-compat/host/tesseract-core-word-metadata.js']
    if ttf:
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/' + name + '.java' for name in ['FontBackedTextsProbe', 'RecordedTextFontsOracle']]
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/' + name + '.java' for name in ['CompilerPatches', 'FontMetricsPatches']]
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/' + name + '.java' for name in ['TrueTypeFaceMetrics', 'TrueTypeGlyphBounds', 'PortableTextGeometry', 'FontMetricsRuntime', 'HeadlessLogging']]
    source_hashes = {}
    for name in sorted(set(sources)):
        data = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
        assert data == subprocess.check_output(git + ['show', revision + ':' + name]).replace(b'\r\n', b'\n'), name
        source_hashes[name] = hashlib.sha256(data).hexdigest()
    limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    cpu, memory, swap = [(limits / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert (cpu, memory, swap) == ('800000 100000', '8589934592', '0')
    name = 'texts-font-backed' if ttf else 'texts-live-ocr' if live else 'texts-continuation'
    relative = 'wsl-artifacts/wasm-components/' + name
    raw = root / relative; tracked = root / ('wasm-compat/' + name + '-results')
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    def copy(origin, name):
        destination = raw / name; destination.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(origin, destination)
    copy(verification, 'verification.json')
    if 'nodeCheckpointSha256' in report:
        copy(checkpoint, 'node-verification.json')
    for name in ['diagnostics.tsv', module_name + '.wasm', module_name + '.wasm-runtime.js']:
        copy(module / name, name)
    native_names = ['jvm-reference.json', 'jvm-rejections.json', 'coverage.json', 'default-font.txt', 'symbol-geometry.json', 'point-widths.json']
    if not ttf:
        native_names += ['font-measurements.json']
    for name in native_names:
        copy(native / name, 'native/' + name)
    rows = read(native / 'jvm-reference.json'); assert len(rows) == report['pages']
    prior_path = mirror / 'build/wasm-compat/cue-beams-default-original-probe/jvm-reference.json'
    prior = {row['id']: row['result'] for row in read(prior_path)['pages']}
    prefix_rows = rows + read(native / 'jvm-rejections.json')
    for row in prefix_rows:
        expected = prior[row['id']]
        assert {key: row['result'][key] for key in expected} == expected, 'Earlier CUE result changed: ' + row['id']
    copy(prior_path, 'native/cue-baseline.json')
    for row in rows:
        identifier = row['id']; assert identifier.isdigit()
        folder = native / identifier; meta = read(folder / 'metadata.json')
        assert (folder / 'cleaned.gray').stat().st_size == meta['width'] * meta['height']
        if not live:
            assert read(folder / 'original-lines.json') == read(folder / 'imported-lines.json')
        assert read(folder / 'expected.json') == row['result']
        names = ['metadata.json', 'cleaned.gray', 'ocr-result.json', 'expected.json']
        if not live:
            names += ['padded.png', 'original-lines.json', 'imported-lines.json']
        for name in names:
            copy(folder / name, 'native/' + identifier + '/' + name)
        copy(mirror / 'build/wasm-compat/pixel-probe/samples' / (identifier + '.binary'), 'inputs/' + identifier + '.binary')
    copy(mirror / 'app/res/basic-classifier.zip', 'model.zip')
    copy(mirror / 'build/wasm-compat/cue-beams-default-portable-probe/font-measurements.json', 'serifs.json')
    template_root = mirror / 'build/wasm-compat/template-original-probe'
    required = {key for row in rows for key in row['result'].get('templatesUsed', [])}
    catalogs = [row for row in read(template_root / 'reference.json')['catalogs'] if row['family'] + ':' + str(row['pointSize']) in required]
    assert len(catalogs) == len(required)
    for row in catalogs:
        path = template_root / (row['file'] + '.atpl'); assert sha(path) == row['sha256']; copy(path, 'templates/' + path.name)
    copy(template_root / 'reference.json', 'templates/reference.json')
    if ttf:
        fonts = mirror / 'build/wasm-compat/recorded-text-fonts'
        resources = read(fonts / 'font-resources.json')
        for name in ['font-resources.json', 'music-measurements.json']:
            copy(fonts / name, 'text-fonts/' + name)
        for face in resources['faces']:
            path = fonts / 'fonts' / face['file']; assert sha(path) == face['sha256']
            copy(path, 'text-fonts/fonts/' + face['file'])
        copy(Path('/usr/share/doc/fonts-dejavu-core/copyright'), 'text-fonts/fonts/copyright')
    if live:
        diagnostic = read(source / 'live-ocr/comparison.json')
        assert diagnostic['workers'] == 2 and diagnostic['workerResultsExact'] and len(diagnostic['pages']) == len(rows)
        for name in ['comparison.json', 'node-results.json', 'worker-results.json']:
            copy(source / 'live-ocr' / name, 'live-ocr/' + name)
        for row in rows:
            copy(source / 'native' / row['id'] / 'ocr-result.json', 'native-ocr/' + row['id'] + '.json')
        for url, info in report['ocrAssets'].items():
            assert url.startswith('/') and '..' not in Path(url).parts
            matches = [source / name for name, digest in report['inputSha256'].items() if digest == info['sha256']]
            assert matches and matches[0].stat().st_size == info['bytes'], url
            copy(matches[0], 'ocr' + url)
        copy(mirror / 'wasm-compat/host/tesseract-word-metadata-node.cjs', 'ocr/host/tesseract-word-metadata-node.cjs')
        for package in ['tesseract.js', 'tesseract.js-core', '@tesseract.js-data/eng']:
            for path in (mirror / 'wasm-compat/node_modules' / package).iterdir():
                if path.is_file() and (path.name == 'package.json' or 'license' in path.name.lower() or 'notice' in path.name.lower()):
                    copy(path, 'ocr/packages/' + package + '/' + path.name)
    artifact_hashes = {str(path.relative_to(raw)): sha(path) for path in sorted(raw.rglob('*')) if path.is_file() and path.name != 'provenance.json'}
    input_artifacts = {name: [file for file, digest in artifact_hashes.items() if digest == expected]
                       for name, expected in report['inputSha256'].items()}
    assert all(input_artifacts.values()), 'A verified input was not included in the archive'
    provenance = {'sourceCommit': revision, 'sourceSha256': source_hashes, 'rawArtifacts': relative,
        'priorCuePrefixRegression': {'pages': len(prefix_rows), 'exact': True, 'referenceSha256': sha(prior_path)},
        'cpuMax': cpu, 'memoryMaxBytes': int(memory), 'memorySwapMaxBytes': int(swap),
        'memoryEventsLocal': (limits / 'memory.events.local').read_text().strip(),
        'artifactSha256': artifact_hashes, 'verifiedInputArtifacts': input_artifacts,
        'limitation': report['limitation']}
    shutil.copyfile(verification, tracked / 'verification.json')
    shutil.copyfile(module / 'diagnostics.tsv', tracked / 'diagnostics.tsv')
    if live:
        shutil.copyfile(source / 'live-ocr/comparison.json', tracked / 'ocr-comparison.json')
    for destination in [raw, tracked]:
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['pages', 'checks', 'originalTextWords', 'maxDifference', 'wasmBytes']}))


if __name__ == '__main__':
    main()
