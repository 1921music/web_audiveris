"""Archive TEXTS recognition with explicitly supplied native cleanup/OCR/font fixtures."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    source = mirror / 'build/wasm-compat/texts-continuation-probe'
    read = lambda path: json.loads(path.read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report = read(source / 'verification.json')
    assert report['workers'] == report['nodeInstances'] == 2
    assert report['checks'] == report['pages'] * 4 and report['originalTextWords'] > 0
    assert report['originalSystemProcessing'] and report['asynchronousReplyBoundary'] and report['duplicateAndLateRepliesRejected']
    assert report['maxDifference'] <= 1e-8 and sha(source / 'texts-continuation.wasm') == report['wasmSha256']
    assert (source / 'diagnostics.tsv').read_text().splitlines() == ['severity\tmethod\tmessage']
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
    source_hashes = {}
    for name in sorted(set(sources)):
        data = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
        assert data == subprocess.check_output(git + ['show', revision + ':' + name]).replace(b'\r\n', b'\n'), name
        source_hashes[name] = hashlib.sha256(data).hexdigest()
    limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    cpu, memory, swap = [(limits / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert (cpu, memory, swap) == ('800000 100000', '8589934592', '0')
    relative = 'wsl-artifacts/wasm-components/texts-continuation'
    raw = root / relative; tracked = root / 'wasm-compat/texts-continuation-results'
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    def copy(origin, name):
        destination = raw / name; destination.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(origin, destination)
    for name in ['verification.json', 'diagnostics.tsv', 'texts-continuation.wasm', 'texts-continuation.wasm-runtime.js']:
        copy(source / name, name)
    for name in ['jvm-reference.json', 'jvm-rejections.json', 'coverage.json', 'font-measurements.json', 'default-font.txt', 'symbol-geometry.json', 'point-widths.json']:
        copy(source / 'native' / name, 'native/' + name)
    rows = read(source / 'native/jvm-reference.json'); assert len(rows) == report['pages']
    prior_path = mirror / 'build/wasm-compat/cue-beams-default-original-probe/jvm-reference.json'
    prior = {row['id']: row['result'] for row in read(prior_path)['pages']}
    prefix_rows = rows + read(source / 'native/jvm-rejections.json')
    for row in prefix_rows:
        expected = prior[row['id']]
        assert {key: row['result'][key] for key in expected} == expected, 'Earlier CUE result changed: ' + row['id']
    copy(prior_path, 'native/cue-baseline.json')
    for row in rows:
        identifier = row['id']; assert identifier.isdigit()
        folder = source / 'native' / identifier; meta = read(folder / 'metadata.json')
        assert (folder / 'cleaned.gray').stat().st_size == meta['width'] * meta['height']
        assert read(folder / 'original-lines.json') == read(folder / 'imported-lines.json')
        assert read(folder / 'expected.json') == row['result']
        for name in ['metadata.json', 'cleaned.gray', 'ocr-result.json', 'padded.png', 'expected.json', 'original-lines.json', 'imported-lines.json']:
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
    for name in ['verification.json', 'diagnostics.tsv']:
        shutil.copyfile(source / name, tracked / name)
    for destination in [raw, tracked]:
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['pages', 'checks', 'originalTextWords', 'maxDifference', 'wasmBytes']}))


if __name__ == '__main__':
    main()
