"""Archive STEM_SEEDS parity and the ImageJ detached-image evidence."""
import hashlib
import json
import shutil
import subprocess


def export_stem(root, build, artifacts, raw_path, provenance):
    source = build / 'wasm-compat/stem-probe'
    original = build / 'wasm-compat/stem-original-probe'
    portable = build / 'wasm-compat/stem-portable-probe'
    raw = artifacts / 'stem-probe'
    results = root / 'wasm-compat/stem-results'
    raw.mkdir(parents=True, exist_ok=True); results.mkdir(parents=True, exist_ok=True)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    read = lambda path: json.loads(path.read_text(encoding='utf-8'))
    def write(path, data):
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    if read(original / 'jvm-reference.json') != read(portable / 'jvm-reference.json'):
        raise AssertionError('Original and portable STEM_SEEDS JVM results differ')
    verification = read(source / 'verification.json')
    if not verification['grayThroughStemSeeds'] or not verification['debugImageOptionRejectedExplicitly']:
        raise AssertionError('Incomplete STEM_SEEDS verification')
    for name in ['stem.wasm', 'stem.wasm-runtime.js', 'wasm-node-results.json', 'wasm-worker-results.json',
                 'verification.json', 'diagnostics.tsv']:
        shutil.copyfile(source / name, raw / name)
    for name in ['verification.json', 'diagnostics.tsv']:
        shutil.copyfile(source / name, results / name)
    shutil.copyfile(original / 'jvm-reference.json', raw / 'jvm-original.json')
    shutil.copyfile(portable / 'jvm-reference.json', raw / 'jvm-portable.json')
    for name in ['font-measurements.json', 'symbol-geometry.json']:
        for destination in [raw, results]:
            shutil.copyfile(portable / name, destination / name)
    shutil.copyfile(root / 'app/res/basic-classifier.zip', raw / 'model.zip')
    shutil.copyfile(build / 'wasm-compat/header-probe/gray-copy.json', raw / 'gray-copy.json')
    shutil.copyfile(raw / 'gray-copy.json', results / 'gray-copy.json')
    image_j = read(raw / 'gray-copy.json')
    if not image_j['getBufferedImageDetached'] or not image_j['bufferedImageConstructorShares']:
        raise AssertionError('ImageJ pixel ownership contract changed')
    shutil.copytree(original / 'stem-buffers', raw / 'stem-buffers', dirs_exist_ok=True)
    shutil.copytree(build / 'wasm-compat/pixel-probe/samples', artifacts / 'pixel-probe/samples', dirs_exist_ok=True)
    reference = {}
    for name in ['stem', 'header', 'scale', 'bracket', 'neural']:
        folder = build / ('wasm-compat/upstream-' + name)
        shutil.copytree(folder, artifacts / ('upstream-' + name), dirs_exist_ok=True)
        metadata = read(folder / 'source.json')
        metadata['classSha256'] = {str(p.relative_to(folder / 'classes')): sha(p)
                                   for p in sorted((folder / 'classes').rglob('*.class'))}
        reference[name] = metadata
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    unchanged = {}
    for name in ['sheet/stem/StemScaler', 'sheet/stem/StemChecker', 'sheet/stem/VerticalsBuilder', 'sheet/PageCleaner']:
        relative = 'app/src/main/java/org/audiveris/omr/' + name + '.java'
        baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + relative]).replace(b'\r\n', b'\n')
        if baseline != (root / relative).read_bytes().replace(b'\r\n', b'\n'):
            raise AssertionError('Unexpected stem algorithm source change: ' + relative)
        unchanged[relative] = hashlib.sha256(baseline).hexdigest()
    reference['unchangedSourceSha256'] = unchanged
    detached = []
    for path in sorted((original / 'stem-buffers').glob('*.binary')):
        before = build / 'wasm-compat/header-probe/no-staff' / path.name
        if before.read_bytes() != path.read_bytes():
            raise AssertionError('Pinned StemScaler modifies recognition pixels: ' + path.name)
        detached.append({'id': path.stem, 'bytes': path.stat().st_size, 'sha256': sha(path), 'unchanged': True})
    detachment = {'imageJ': '1.54p', 'imageJJarSha256': image_j['imageJJarSha256'],
        'pages': detached, 'comparedBytes': sum(p['bytes'] for p in detached),
        'scope': 'Native StemScaler returns the unchanged NO_STAFF processor; getBufferedImage exports a detached copy',
        'debugImageOption': 'Original Constant.Boolean read at runtime; true fails explicitly until a host debug raster backend exists'}
    trace = build / 'wasm-compat/stem-trace-probe'
    if (trace / 'raster-commands.json').exists():
        research = artifacts / 'research'; research.mkdir(parents=True, exist_ok=True)
        for name in ['raster-commands.json', 'canvas-comparison.json', 'center-comparison.json']:
            if (trace / name).exists():
                shutil.copyfile(trace / name, research / name)
        write(research / 'scope.json', {'scope': 'Experiments apply the otherwise detached drawing to recognition pixels; their differences are not Canvas precision measurements',
            'productionBackendChanged': False})
    evidence = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
        'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal',
        'sourceSha256', 'applicationSourceChanges']}
    evidence.update({'rawArtifacts': raw_path + '/stem-probe',
        'artifactSha256': {p.name: sha(p) for p in sorted(raw.iterdir()) if p.is_file()},
        'limitation': verification['limitation'], 'originalAndPortableJvmExact': True})
    for destination in [results, raw]:
        write(destination / 'detachment-verification.json', detachment)
        write(destination / 'reference-sources.json', reference)
        write(destination / 'inputs.json', read(build / 'wasm-compat/pixel-probe/samples/manifest.json'))
        write(destination / 'provenance.json', evidence)
    provenance['stemVerification'] = verification
