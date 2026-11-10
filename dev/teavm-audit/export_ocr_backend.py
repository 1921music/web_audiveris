"""Archive the actual Tesseract.js/Worker/Wasm-boundary fixture and pinned local assets."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    profile = sys.argv[4]
    native_comparison = len(sys.argv) == 6 and sys.argv[5] == '--native'
    if len(sys.argv) > 5 and not native_comparison:
        raise ValueError('Optional argument: --native')
    if native_comparison and profile != 'legacy':
        raise ValueError('Native comparison currently covers the legacy profile')
    if profile not in ['legacy', 'lstm']:
        raise ValueError('Choose legacy or lstm profile')
    folder = profile + '-word-metadata'
    source = mirror / 'build/wasm-compat/tesseract-js-ocr' / folder
    read = lambda p: json.loads(p.read_text(encoding='utf-8'))
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    report = read(source / 'verification.json')
    for key in ['workerResultsExact', 'queuedInputSnapshotsOwned', 'pngPixelsExact', 'nodeWorkerReused',
                'allBrowserAssetsLocal', 'wasmRequestAndReplyIntegrated', 'wasmBlankAndModelFailureDistinct',
                'nativeWordMetadata', 'wordBaselineAvailable', 'metadataAlignmentGuard', 'metadataIteratorReleasedOnFailure']:
        if not report[key]:
            raise AssertionError('Incomplete OCR verification: ' + key)
    if report['workers'] != 2 or report['version'] != '7.0.0' or report['characters'] != 23 or report['oem'] != (0 if profile == 'legacy' else 1):
        raise AssertionError('Unexpected OCR fixture/version')
    if profile == 'legacy' and not report['fontAttributesAvailable']:
        raise AssertionError('Legacy OCR font metadata was lost')
    sources = [
        'wasm-compat/host/gray8-png.mjs', 'wasm-compat/host/tesseract-js-ocr.mjs',
        'wasm-compat/host/tesseract-word-metadata.mjs', 'wasm-compat/host/tesseract-core-word-metadata.js',
        'wasm-compat/host/tesseract-word-metadata-node.cjs',
        'wasm-compat/package.json', 'wasm-compat/package-lock.json',
        'wasm-compat/tools/verify-tesseract-js-ocr.cjs',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/OcrBackendFixtureOracle.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/OcrTransportProbe.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/OcrTransport.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/OcrExchange.java',
        'dev/teavm-audit/audit.init.gradle']
    if native_comparison:
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/NativeOcrBackendOracle.java',
                    'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/OcrTransportOracle.java',
                    'wasm-compat/tools/compare-native-ocr.py', 'app/build.gradle']
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    source_hashes = {}
    for name in sources:
        data = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
        committed = subprocess.check_output(git + ['show', revision + ':' + name]).replace(b'\r\n', b'\n')
        if data != committed:
            raise AssertionError('OCR compiled/tested source revision mismatch: ' + name)
        source_hashes[name] = hashlib.sha256(data).hexdigest()
    raw_relative = 'wsl-artifacts/wasm-components/tesseract-js-ocr/' + folder
    raw = root / raw_relative; tracked = root / 'wasm-compat/tesseract-js-ocr-results' / folder
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    for name in ['fixture.json', 'input.gray', 'native.png', 'host.png', 'results.json', 'verification.json']:
        shutil.copyfile(source / name, raw / name)
    if native_comparison:
        native = mirror / 'build/wasm-compat/tesseract-js-ocr/native-legacy'
        comparison = read(native / 'comparison.json')
        if comparison['adapterResultsSha256'] != sha(source / 'results.json') or not all(mode['exact'] for mode in comparison['modes']):
            raise AssertionError('Stale or unsuccessful native OCR comparison')
        for destination in [raw, tracked]:
            target = destination / 'native'; target.mkdir(parents=True, exist_ok=True)
            for name in ['comparison.json', 'psm-3.json', 'psm-6.json']:
                shutil.copyfile(native / name, target / name)
    modules = mirror / 'wasm-compat/node_modules'
    for url, expected in report['assets'].items():
        if url in ['/ocr-transport.wasm', '/ocr-transport.wasm-runtime.js']:
            path = mirror / 'build/wasm-compat/ocr-transport-probe' / url[1:]
        elif url in ['/host/gray8-png.mjs', '/host/tesseract-js-ocr.mjs', '/host/tesseract-word-metadata.mjs', '/host/tesseract-core-word-metadata.js']:
            path = mirror / 'wasm-compat' / url[1:]
        elif url in ['/ocr-assets/tesseract.min.js', '/ocr-assets/worker.min.js']:
            path = modules / 'tesseract.js/dist' / Path(url).name
        elif url in ['/core-assets/tesseract-core' + ('-' + report['coreFlavor'] if report['coreFlavor'] else '') + suffix for suffix in ['.js', '.wasm']]:
            path = modules / 'tesseract.js-core' / Path(url).name
        elif url == '/models/eng.traineddata.gz':
            path = modules / '@tesseract.js-data/eng' / ('4.0.0' if profile == 'legacy' else '4.0.0_best_int') / 'eng.traineddata.gz'
        else:
            raise AssertionError('Unexpected asset path: ' + url)
        if path.stat().st_size != expected['bytes'] or sha(path) != expected['sha256']:
            raise AssertionError('Tested OCR asset changed: ' + url)
        target = raw / 'assets' / url[1:]; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    shutil.copyfile(mirror / 'wasm-compat/host/tesseract-word-metadata-node.cjs', raw / 'assets/host/tesseract-word-metadata-node.cjs')
    packages = {}
    for package in ['tesseract.js', 'tesseract.js-core', '@tesseract.js-data/eng']:
        metadata = read(modules / package / 'package.json')
        packages[package] = {key: metadata[key] for key in ['version', 'license'] if key in metadata}
        destination = raw / 'packages' / package; destination.mkdir(parents=True, exist_ok=True)
        for path in (modules / package).iterdir():
            if path.is_file() and (path.name == 'package.json' or 'license' in path.name.lower() or 'notice' in path.name.lower()):
                shutil.copyfile(path, destination / path.name)
    limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    cpu, memory, swap = [(limits / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    if (cpu, memory, swap) != ('800000 100000', '8589934592', '0'):
        raise AssertionError('Unexpected development resource limits')
    evidence = {'sourceCommit': revision, 'sourceSha256': source_hashes, 'rawArtifacts': raw_relative,
        'cpuMax': cpu, 'allowedCpus': (limits / 'cpuset.cpus.effective').read_text().strip(),
        'memoryMaxBytes': int(memory), 'memorySwapMaxBytes': int(swap),
        'memoryEventsLocal': (limits / 'memory.events.local').read_text().strip(), 'packages': packages,
        'artifactSha256': {str(p.relative_to(raw)): sha(p) for p in sorted(raw.rglob('*')) if p.is_file() and p.name != 'provenance.json'},
        'limitation': report['limitation']}
    for destination in [raw, tracked]:
        if destination != raw:
            for name in ['fixture.json', 'verification.json']:
                shutil.copyfile(source / name, destination / name)
        (destination / 'provenance.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['version', 'engineVersions', 'words', 'characters', 'wasmRequestAndReplyIntegrated']}))


if __name__ == '__main__':
    main()
