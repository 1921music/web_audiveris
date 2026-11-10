"""Archive beam recognition, intermediate pixels, font calibration and kernel evidence."""
import hashlib
import json
import shutil
import subprocess


def export_beam(root, build, artifacts, raw_path, provenance):
    source = build / 'wasm-compat/beam-probe'
    original = build / 'wasm-compat/beam-original-probe'
    portable = build / 'wasm-compat/beam-portable-probe'
    raw = artifacts / 'beam-probe'
    results = root / 'wasm-compat/beam-results'
    raw.mkdir(parents=True, exist_ok=True); results.mkdir(parents=True, exist_ok=True)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    read = lambda path: json.loads(path.read_text(encoding='utf-8'))
    def write(path, data):
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    if read(original / 'jvm-reference.json') != read(portable / 'jvm-reference.json'):
        raise AssertionError('Original and portable BEAMS JVM results differ')
    verification = read(source / 'verification.json')
    if not verification['grayThroughBeams'] or not verification['kernelContract'] or not verification['previewOptionRejectedExplicitly']:
        raise AssertionError('Incomplete beam verification')
    for name in ['beam.wasm', 'beam.wasm-runtime.js', 'wasm-node-results.json', 'wasm-worker-results.json',
                 'verification.json', 'diagnostics.tsv', 'kernel-jvm.json', 'calibration-jvm.json']:
        shutil.copyfile(source / name, raw / name)
    for name in ['verification.json', 'diagnostics.tsv', 'kernel-jvm.json', 'calibration-jvm.json']:
        shutil.copyfile(source / name, results / name)
    shutil.copyfile(original / 'jvm-reference.json', raw / 'jvm-original.json')
    shutil.copyfile(portable / 'jvm-reference.json', raw / 'jvm-portable.json')
    for name in ['font-measurements.json', 'symbol-geometry.json', 'point-widths.json']:
        for destination in [raw, results]:
            shutil.copyfile(portable / name, destination / name)
    shutil.copyfile(root / 'app/res/basic-classifier.zip', raw / 'model.zip')
    shutil.copytree(original / 'head-pixels', raw / 'head-pixels', dirs_exist_ok=True)
    shutil.copytree(build / 'wasm-compat/pixel-probe/samples', artifacts / 'pixel-probe/samples', dirs_exist_ok=True)
    reference = {}
    for name in ['beam', 'stem', 'header', 'scale', 'bracket', 'neural']:
        folder = build / ('wasm-compat/upstream-' + name)
        shutil.copytree(folder, artifacts / ('upstream-' + name), dirs_exist_ok=True)
        metadata = read(folder / 'source.json')
        metadata['classSha256'] = {str(p.relative_to(folder / 'classes')): sha(p)
                                   for p in sorted((folder / 'classes').rglob('*.class'))}
        reference[name] = metadata
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    unchanged = {}
    names = ['sheet/beam/' + name for name in ['SpotsBuilder', 'BeamsBuilder', 'MultipleRestsBuilder', 'BeamStructure', 'BeamItem', 'BeamLine']]
    names += ['image/' + name for name in ['AbstractGrayFilter', 'GaussianGrayFilter', 'MedianGrayFilter', 'MorphoProcessor', 'StructureElement']]
    names += ['glyph/GlyphFactory', 'run/RunTableFactory', 'math/Population']
    for name in names:
        relative = 'app/src/main/java/org/audiveris/omr/' + name + '.java'
        baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + relative]).replace(b'\r\n', b'\n')
        if baseline != (root / relative).read_bytes().replace(b'\r\n', b'\n'):
            raise AssertionError('Unexpected beam algorithm source change: ' + relative)
        unchanged[relative] = hashlib.sha256(baseline).hexdigest()
    name = 'app/src/main/java/org/audiveris/omr/sheet/beam/BlackHeadSizer.java'
    baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + name]).decode().replace('\r\n', '\n')
    old = 'final MusicFont font = MusicFont.getHeadFont(family, sheet.getScale(), 0);\n        final MusicFontScale musicFontScale = font.buildMusicFontScale(w);'
    new = 'final MusicFontScale musicFontScale = org.audiveris.omr.ui.symbol.SymbolGeometry.musicFontScale(family, w);'
    if baseline.count(old) != 1 or baseline.replace(old, new) != (root / name).read_text(encoding='utf-8'):
        raise AssertionError('BlackHeadSizer changed beyond the font-acquisition seam')
    reference['unchangedSourceSha256'] = unchanged
    reference['blackHeadSizerChange'] = 'Only font acquisition delegates to SymbolGeometry; native interpolation checked across six families'
    reference['reflectionConstructors'] = ['StaffFilament(int)', 'StraightFilament(int)', 'BeamBeamRelation()']
    pixels = [{'id': path.stem, 'bytes': path.stat().st_size, 'sha256': sha(path)}
              for path in sorted((original / 'head-pixels').glob('*.binary'))]
    comparison = {'originalAndPortableJvmExact': True, 'originalSha256': sha(original / 'jvm-reference.json'),
                  'portableSha256': sha(portable / 'jvm-reference.json'), 'headPixels': pixels,
                  'relationsCompared': True, 'errorLogsRejected': True}
    evidence = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
        'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal',
        'sourceSha256', 'applicationSourceChanges']}
    evidence.update({'rawArtifacts': raw_path + '/beam-probe',
        'artifactSha256': {p.name: sha(p) for p in sorted(raw.iterdir()) if p.is_file()},
        'limitation': verification['limitation']})
    for destination in [results, raw]:
        write(destination / 'jvm-comparison.json', comparison)
        write(destination / 'reference-sources.json', reference)
        write(destination / 'inputs.json', read(build / 'wasm-compat/pixel-probe/samples/manifest.json'))
        write(destination / 'provenance.json', evidence)
    provenance['beamVerification'] = verification
