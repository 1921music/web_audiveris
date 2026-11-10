"""Archive HEADERS parity evidence without checking large numeric fixtures into Git."""
import hashlib
import json
import shutil
import subprocess


def export_header(root, build, artifacts, raw_path, provenance):
    source = build / 'wasm-compat/header-probe'
    raw = artifacts / 'header-probe'
    results = root / 'wasm-compat/header-results'
    raw.mkdir(parents=True, exist_ok=True)
    results.mkdir(parents=True, exist_ok=True)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    read = lambda path: json.loads(path.read_text(encoding='utf-8'))
    def write(path, data):
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

    original = build / 'wasm-compat/header-original-probe/jvm-reference.json'
    current = source / 'jvm-reference.json'
    if read(original) != read(current):
        raise AssertionError('Native and injected HEADERS JVM results differ')
    verification = read(source / 'verification.json')
    if not verification['originalHeaderClassesPinned'] or not verification['grayThroughHeaders']:
        raise AssertionError('Incomplete HEADERS verification')
    for name in ['header.wasm', 'header.wasm-runtime.js', 'jvm-reference.json', 'symbol-geometry.json',
                 'font-measurements.json', 'wasm-node-results.json', 'wasm-worker-results.json',
                 'verification.json', 'diagnostics.tsv', 'gray-copy.json']:
        shutil.copyfile(source / name, raw / name)
    shutil.copyfile(original, raw / 'jvm-original.json')
    shutil.copyfile(root / 'app/res/basic-classifier.zip', raw / 'model.zip')
    for name in ['verification.json', 'diagnostics.tsv', 'gray-copy.json', 'symbol-geometry.json', 'font-measurements.json']:
        shutil.copyfile(source / name, results / name)
    shutil.copytree(build / 'wasm-compat/pixel-probe/samples', artifacts / 'pixel-probe/samples', dirs_exist_ok=True)
    reference = {}
    for name in ['header', 'scale', 'bracket', 'neural']:
        folder = build / ('wasm-compat/upstream-' + name)
        shutil.copytree(folder, artifacts / ('upstream-' + name), dirs_exist_ok=True)
        metadata = read(folder / 'source.json')
        metadata['classSha256'] = {str(p.relative_to(folder / 'classes')): sha(p)
                                   for p in sorted((folder / 'classes').rglob('*.class'))}
        reference[name] = metadata
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    unchanged = {}
    for name in ['sheet/header/HeaderBuilder', 'sheet/key/KeyColumn',
                 'sheet/key/KeyBuilder', 'sheet/key/KeyExtractor', 'sheet/time/HeaderTimeBuilder',
                 'sheet/time/TimeBuilder', 'sheet/time/TimeColumn', 'sheet/time/HeaderTimeColumn',
                 'math/HiLoPeakFinder', 'sig/inter/AbstractPitchedInter']:
        relative = 'app/src/main/java/org/audiveris/omr/' + name + '.java'
        baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + relative]).replace(b'\r\n', b'\n')
        if baseline != (root / relative).read_bytes().replace(b'\r\n', b'\n'):
            raise AssertionError('Unexpected HEADERS algorithm source change: ' + relative)
        unchanged[relative] = hashlib.sha256(baseline).hexdigest()
    reference['unchangedSourceSha256'] = unchanged
    comparison = {'nativeAndInjectedJvmExact': True, 'originalResultSha256': sha(original),
                  'injectedResultSha256': sha(current), 'prefixSnapshotCasesPerInstance': 528}
    manifest = read(build / 'wasm-compat/pixel-probe/samples/manifest.json')
    evidence = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
        'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal',
        'sourceSha256', 'applicationSourceChanges']}
    evidence.update({'rawArtifacts': raw_path + '/header-probe',
        'artifactSha256': {p.name: sha(p) for p in sorted(raw.iterdir()) if p.is_file()},
        'limitation': verification['limitation'], 'prefixCopySites': 8, 'rawGrayCopySites': 4})
    for folder in [raw, results]:
        write(folder / 'reference-sources.json', reference)
        write(folder / 'jvm-comparison.json', comparison)
        write(folder / 'inputs.json', manifest)
        write(folder / 'provenance.json', evidence)
    provenance['headerVerification'] = verification
