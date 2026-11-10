"""Archive both original CUE_BEAMS policies, including active small-head recognition."""
import hashlib
import json
import shutil
import subprocess


def export_cue_beams(root, build, artifacts, raw_path, provenance):
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    read = lambda p: json.loads(p.read_text(encoding='utf-8'))
    def write(p, value):
        p.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    results = root / 'wasm-compat/cue-beams-results'
    results.mkdir(parents=True, exist_ok=True)
    reference = build / 'wasm-compat/upstream-cue-beams'
    shutil.copytree(reference, artifacts / 'upstream-cue-beams', dirs_exist_ok=True)
    reference_metadata = read(reference / 'source.json')
    reference_metadata['classSha256'] = {str(p.relative_to(reference / 'classes')): sha(p)
        for p in sorted((reference / 'classes').rglob('*.class'))}
    relative = 'app/src/main/java/org/audiveris/omr/sheet/beam/CueBeamsStep.java'
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + relative]).replace(b'\r\n', b'\n')
    if baseline != (root / relative).read_bytes().replace(b'\r\n', b'\n'):
        raise AssertionError('CUE_BEAMS recognition source changed')
    reference_metadata['unchangedSourceSha256'] = {relative: hashlib.sha256(baseline).hexdigest()}
    verifications = {}
    for mode in ['default', 'enabled']:
        source = build / ('wasm-compat/cue-beams-' + mode + '-probe')
        original = build / ('wasm-compat/cue-beams-' + mode + '-original-probe')
        portable = build / ('wasm-compat/cue-beams-' + mode + '-portable-probe')
        raw = artifacts / ('cue-beams-' + mode + '-probe')
        tracked = results / mode
        raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
        verification = read(source / 'verification.json')
        for flag in ['fullSampleSet', 'workerResultsComparedPerImage', 'grayThroughCueBeams',
                     'kernelContract', 'previewOptionRejectedExplicitly', 'debugImageOptionRejectedExplicitly',
                     'originalCueAlgorithms']:
            if not verification[flag]:
                raise AssertionError('Incomplete CUE_BEAMS evidence: ' + mode + '/' + flag)
        if verification['diagnosticTraceEnabled'] or verification['cueMode'] != mode:
            raise AssertionError('Wrong CUE_BEAMS configuration: ' + mode)
        if mode == 'enabled' and not any(p['cueActive'] and p['cueBeams'] > 0 for p in verification['samples']):
            raise AssertionError('Active cue-beam recognition has no positive sample')
        if read(original / 'jvm-reference.json') != read(portable / 'jvm-reference.json'):
            raise AssertionError('Original/portable JVM CUE_BEAMS results differ: ' + mode)
        if sha(source / 'cue-beams.wasm') != verification['wasmSha256']:
            raise AssertionError('Verified CUE_BEAMS binary changed')
        if (source / 'diagnostics.tsv').read_text().splitlines() != ['severity\tmethod\tmessage']:
            raise AssertionError('CUE_BEAMS compilation has diagnostics')
        for name in ['cue-beams.wasm', 'cue-beams.wasm-runtime.js', 'diagnostics.tsv',
                     'verification.json', 'wasm-node-results.json', 'wasm-worker-results.json']:
            shutil.copyfile(source / name, raw / name)
        for name in ['verification.json', 'diagnostics.tsv']:
            shutil.copyfile(raw / name, tracked / name)
        for label, directory in [('original', original), ('portable', portable)]:
            shutil.copyfile(directory / 'jvm-reference.json', raw / ('jvm-' + label + '.json'))
        for name in ['head-pixels', 'distance-pixels']:
            shutil.copytree(original / name, raw / name, dirs_exist_ok=True)
            for p in sorted((original / name).iterdir()):
                if p.is_file() and p.read_bytes() != (portable / name / p.name).read_bytes():
                    raise AssertionError('Original/portable raster differs: ' + mode + '/' + p.name)
        for name in ['font-measurements.json', 'symbol-geometry.json', 'point-widths.json']:
            shutil.copyfile(portable / name, raw / name)
        for name in ['model.zip', 'stem-sort-reference.txt']:
            shutil.copyfile(artifacts / 'reduction-probe' / name, raw / name)
        shutil.copytree(artifacts / 'reduction-probe/templates', raw / 'templates', dirs_exist_ok=True)
        comparison = {'originalAndPortableJvmExact': True,
            'originalSha256': sha(original / 'jvm-reference.json'),
            'portableSha256': sha(portable / 'jvm-reference.json'),
            'cueMode': mode, 'activePositiveSampleChecked': mode == 'enabled',
            'originalAndPortableRastersExact': True, 'workerResultsComparedPerImage': True}
        evidence = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
            'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal',
            'sourceSha256', 'applicationSourceChanges']}
        evidence.update({'rawArtifacts': raw_path + '/cue-beams-' + mode + '-probe',
            'sharedInputs': raw_path + '/reduction-probe',
            'artifactSha256': {p.name: sha(p) for p in sorted(raw.iterdir()) if p.is_file()},
            'limitation': verification['limitation']})
        for destination in [raw, tracked]:
            write(destination / 'jvm-comparison.json', comparison)
            write(destination / 'reference-sources.json', reference_metadata)
            write(destination / 'provenance.json', evidence)
        verifications[mode] = verification
    texts = build / 'wasm-compat/texts-baseline-probe'
    texts_raw = artifacts / 'texts-baseline-probe'; texts_raw.mkdir(exist_ok=True)
    for name in ['diagnostics.tsv', 'dependency-paths.tsv']:
        shutil.copyfile(texts / name, texts_raw / name)
    write(results / 'texts-baseline.json', {'compiled': False,
        'diagnostics': len((texts / 'diagnostics.tsv').read_text().splitlines()) - 1,
        'runtimeVerified': False, 'rawArtifacts': raw_path + '/texts-baseline-probe',
        'limitation': 'Original TEXTS entry dependency discovery only; raster cleanup, font metrics and asynchronous OCR remain to be implemented'})
    provenance['cueBeamsVerification'] = verifications
