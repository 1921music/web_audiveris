"""Archive original note-head recognition and full graph/glyph evidence."""
import hashlib
import json
import shutil
import subprocess


def export_heads(root, build, artifacts, raw_path, provenance):
    source = build / 'wasm-compat/heads-probe'
    original = build / 'wasm-compat/heads-original-probe'
    portable = build / 'wasm-compat/heads-portable-probe'
    raw = artifacts / 'heads-probe'
    results = root / 'wasm-compat/heads-results'
    raw.mkdir(parents=True, exist_ok=True); results.mkdir(parents=True, exist_ok=True)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    read = lambda path: json.loads(path.read_text(encoding='utf-8'))
    def write(path, data):
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    if read(original / 'jvm-reference.json') != read(portable / 'jvm-reference.json'):
        raise AssertionError('Original and portable HEADS JVM results differ')
    verification = read(source / 'verification.json')
    if not verification['fullSampleSet']:
        raise AssertionError('HEADS smoke subset must not be archived as full sample verification')
    if not verification['grayThroughHeads'] or not verification['kernelContract'] or not verification['previewOptionRejectedExplicitly']:
        raise AssertionError('Incomplete HEADS verification')
    for name in ['heads.wasm', 'heads.wasm-runtime.js', 'wasm-node-results.json', 'wasm-worker-results.json',
                 'verification.json', 'diagnostics.tsv', 'kernel-jvm.json', 'calibration-jvm.json']:
        shared = build / 'wasm-compat/beam-probe' if name in ['kernel-jvm.json', 'calibration-jvm.json'] else source
        shutil.copyfile(shared / name, raw / name)
    for name in ['verification.json', 'diagnostics.tsv', 'kernel-jvm.json', 'calibration-jvm.json']:
        shutil.copyfile(raw / name, results / name)
    shutil.copyfile(original / 'jvm-reference.json', raw / 'jvm-original.json')
    shutil.copyfile(portable / 'jvm-reference.json', raw / 'jvm-portable.json')
    for name in ['font-measurements.json', 'symbol-geometry.json', 'point-widths.json']:
        for destination in [raw, results]:
            shutil.copyfile(portable / name, destination / name)
    shutil.copyfile(root / 'app/res/basic-classifier.zip', raw / 'model.zip')
    template_source = build / 'wasm-compat/template-original-probe'
    templates = raw / 'templates'; templates.mkdir(exist_ok=True)
    shutil.copyfile(template_source / 'reference.json', templates / 'reference.json')
    for row in verification['templateCatalogs']:
        name = row['family'] + '-' + str(row['pointSize']) + '.atpl'
        if sha(template_source / name) != row['sha256']:
            raise AssertionError('HEADS template input differs: ' + name)
        shutil.copyfile(template_source / name, templates / name)
    shutil.copytree(original / 'head-pixels', raw / 'head-pixels', dirs_exist_ok=True)
    shutil.copytree(original / 'distance-pixels', raw / 'distance-pixels', dirs_exist_ok=True)
    distance_tables = []
    for path in sorted((original / 'distance-pixels').glob('*.short')):
        if path.read_bytes() != (portable / 'distance-pixels' / path.name).read_bytes():
            raise AssertionError('Original/raw JVM distance table differs: ' + path.name)
        distance_tables.append({'id': path.stem, 'bytes': path.stat().st_size, 'sha256': sha(path)})
    shutil.copytree(build / 'wasm-compat/pixel-probe/samples', artifacts / 'pixel-probe/samples', dirs_exist_ok=True)
    reference = {}
    for name in ['heads', 'template', 'drum', 'distance', 'ledger', 'beam', 'stem', 'header', 'scale', 'bracket', 'neural']:
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
    names += ['sheet/ledger/' + name for name in ['LedgersFilter', 'LedgersBuilder', 'LedgersStep']]
    names += ['sheet/note/' + name for name in ['NoteHeadsBuilder', 'HeadSeedTally', 'HeadSeedScale', 'HeadSpotsBuilder', 'HeadsStep']]
    names += ['sig/inter/HeadInter', 'image/Template']
    names += ['glyph/GlyphFactory', 'run/RunTableFactory', 'math/Population']
    for name in names:
        relative = 'app/src/main/java/org/audiveris/omr/' + name + '.java'
        baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + relative]).replace(b'\r\n', b'\n')
        if baseline != (root / relative).read_bytes().replace(b'\r\n', b'\n'):
            raise AssertionError('Unexpected recognition algorithm source change: ' + relative)
        unchanged[relative] = hashlib.sha256(baseline).hexdigest()
    name = 'app/src/main/java/org/audiveris/omr/sheet/beam/BlackHeadSizer.java'
    baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + name]).decode().replace('\r\n', '\n')
    old = 'final MusicFont font = MusicFont.getHeadFont(family, sheet.getScale(), 0);\n        final MusicFontScale musicFontScale = font.buildMusicFontScale(w);'
    new = 'final MusicFontScale musicFontScale = org.audiveris.omr.ui.symbol.SymbolGeometry.musicFontScale(family, w);'
    if baseline.count(old) != 1 or baseline.replace(old, new) != (root / name).read_text(encoding='utf-8'):
        raise AssertionError('BlackHeadSizer changed beyond the font-acquisition seam')
    reference['unchangedSourceSha256'] = unchanged
    name = 'app/src/main/java/org/audiveris/omr/sheet/ledger/LedgersPostAnalysis.java'
    baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + name]).decode().replace('\r\n', '\n')
    start = baseline.index('        logger.debug(\n                "{}",\n                String.format(\n                        "Filter ')
    end = baseline.index('\n\n', start)
    old = baseline[start:end]
    new = '''        logger.debug(
                "Filter minDeltaAboveRatio:{} maxDeltaAboveRatio:{}"
                        + " minDeltaBelowRatio:{} maxDeltaBelowRatio:{}"
                        + " minHeightRatio:{} maxHeightRatio:{}",
                minDeltaAboveRatio,
                maxDeltaAboveRatio,
                minDeltaBelowRatio,
                maxDeltaBelowRatio,
                minHeightRatio,
                maxHeightRatio);'''
    if baseline.count(old) != 1 or baseline.replace(old, new) != (root / name).read_text(encoding='utf-8'):
        raise AssertionError('LedgersPostAnalysis changed beyond parameterized debug logging')
    reference['ledgerLoggingChange'] = 'Only eager nullable float formatting changed to SLF4J parameterized debug logging; recognition unchanged'
    reference['blackHeadSizerChange'] = 'Only font acquisition delegates to SymbolGeometry; native interpolation checked across six families'
    reference['reflectionConstructors'] = ['StaffFilament(int)', 'StraightFilament(int)', 'BeamBeamRelation()']
    relative = 'app/src/main/java/org/audiveris/omr/sheet/note/DistancesBuilder.java'
    baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + relative]).decode().replace('\r\n', '\n')
    current = (root / relative).read_text(encoding='utf-8')
    def body(source, marker):
        start = source.index(marker); end = source.index('{', start) + 1; depth = 1
        while depth:
            depth += (source[end] == '{') - (source[end] == '}'); end += 1
        return source[start:end]
    protected = ['private void paintLines (', 'private static class TablePainter', 'private static class ImagePainter']
    reference['unchangedDistanceSourceBodies'] = {}
    for marker in protected:
        before, after = body(baseline, marker), body(current, marker)
        if before != after:
            raise AssertionError('Distance painting/neutralization changed: ' + marker)
        reference['unchangedDistanceSourceBodies'][marker] = hashlib.sha256(before.encode()).hexdigest()
    old = '''        final BufferedImage image = rawBuf.getBufferedImage();

        // "Erase" staff lines, ledgers, stems with background value in image
        paintLines(new ImagePainter(image)); // This modifies (shared) buffer

        // Table of distances to relevant foreground.
        final ByteProcessor buffer = new ByteProcessor(image);'''
    new = '''        // Table of distances to relevant foreground.
        final ByteProcessor buffer = erasedBuffer(rawBuf);'''
    if body(baseline, 'public DistanceTable buildDistances (').replace(old, new) != body(current, 'public DistanceTable buildDistances ('):
        raise AssertionError('Distance transformation changed beyond raster acquisition')
    reference['distanceRasterBoundary'] = 'Native image preparation extracted; Wasm chooses raw run/pixel preparation. Chamfer transform, paintLines and table neutralization unchanged.'
    pixels = [{'id': path.stem, 'bytes': path.stat().st_size, 'sha256': sha(path)}
              for path in sorted((original / 'head-pixels').glob('*.binary'))]
    comparison = {'originalAndPortableJvmExact': True, 'originalSha256': sha(original / 'jvm-reference.json'),
                  'portableSha256': sha(portable / 'jvm-reference.json'), 'headPixels': pixels,
                  'relationsCompared': True, 'postHeadsRelationsCompared': True, 'headGlyphRunsCompared': True,
                  'headSpotsLiveWeakCacheChecked': True, 'errorLogsRejected': True, 'distanceTables': distance_tables,
                  'originalAndRawJvmDistanceTablesExact': True}
    evidence = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
        'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal',
        'sourceSha256', 'applicationSourceChanges']}
    evidence.update({'rawArtifacts': raw_path + '/heads-probe',
        'artifactSha256': {p.name: sha(p) for p in sorted(raw.iterdir()) if p.is_file()},
        'limitation': verification['limitation']})
    for destination in [results, raw]:
        write(destination / 'jvm-comparison.json', comparison)
        write(destination / 'reference-sources.json', reference)
        write(destination / 'inputs.json', read(build / 'wasm-compat/pixel-probe/samples/manifest.json'))
        write(destination / 'provenance.json', evidence)
    provenance['headsVerification'] = verification
