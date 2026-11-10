"""Compare unchanged engine roots before/after explicit Worker platform substitutions."""
import argparse
import csv
import hashlib
import gzip
import json
import shutil
import subprocess
from pathlib import Path
from compare_compat import compare, rows
from export_results import export
from export_header import export_header


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def application_changes(root):
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    changed = subprocess.check_output(git + ['diff', '--name-only', '-z',
        '04697c74fae431ef8436a9429386957a5cb25993', '--', 'app/src/main/java']).decode().split('\0')
    untracked = subprocess.check_output(git + ['ls-files', '--others', '--exclude-standard', '-z',
        '--', 'app/src/main/java']).decode().split('\0')
    return {name: hashlib.sha256((root / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest()
            for name in sorted(set(changed + untracked)) if name.endswith('.java')}


def calls(folder, selected):
    plain = folder / 'calls.tsv'
    stream = plain.open(encoding='utf-8', newline='') if plain.exists() else gzip.open(folder / 'calls.tsv.gz', 'rt', encoding='utf-8', newline='')
    with stream:
        return {(row['caller'], row['callee']) for row in csv.DictReader(stream, delimiter='\t') if row['caller'] in selected}


def route_report(before, after, destination):
    old = {row['target']: row for row in rows(before / 'excluded-class-paths.tsv')}
    new = {row['target']: row for row in rows(after / 'excluded-class-paths.tsv')}
    data = {'org.audiveris.omr.ui.Colors', 'org.audiveris.omr.sheet.ui.SheetTab',
            'org.audiveris.omr.ui.selection.SelectionHint', 'org.audiveris.omr.ui.selection.MouseMovement',
            'org.audiveris.omr.sig.ui.UITask$OpKind'}
    render = {'org.audiveris.omr.sheet.SheetDiff', 'org.audiveris.omr.sheet.ui.SheetResultPainter'}
    adapters = {'org.audiveris.omr.log.LogUtil', 'org.audiveris.omr.step.ui.StepMonitoring'}
    with destination.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(['class', 'before', 'after', 'category', 'usedMethodsAfter', 'representativeRoot', 'representativePath', 'evidence'])
        for target in sorted(old.keys() | new.keys()):
            row = new.get(target, old.get(target)); state = new.get(target, {}).get('targetType', 'not-linked')
            category = ('no-execution-path' if state == 'type-only' else 'retained-data' if target in data
                        else 'retained-rendering-output' if target in render else 'adapted-platform-boundary' if target in adapters
                        else 'non-ui-boundary-or-diagnostics' if '.ui.' not in target and not target.endswith(('CheckBoard', 'CheckPanel'))
                        else 'presentation-review')
            if state == 'not-linked':
                category = 'cut-by-boundary-substitutions'
            inferred = int(row['inferredEdges'])
            evidence = 'type-link-only' if row['targetType'] == 'type-only' else ('representative-declared-reference-path' if inferred == 0
                        else 'representative-fast-path-includes-inferred-edges; not proof of runtime reachability')
            writer.writerow([target, old.get(target, {}).get('targetType', 'not-linked'), state, category,
                             new.get(target, {}).get('usedMethodCount', '0'), row['root'], row['path'], evidence])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('repository', type=Path)
    parser.add_argument('build', type=Path)
    parser.add_argument('tool_commit')
    parser.add_argument('--stage', type=int, default=1)
    parser.add_argument('--pixels', action='store_true')
    parser.add_argument('--ordered-maps', action='store_true')
    parser.add_argument('--models', action='store_true')
    parser.add_argument('--pixel-sheet', action='store_true')
    parser.add_argument('--rounding', action='store_true')
    parser.add_argument('--staff', action='store_true')
    parser.add_argument('--grid', action='store_true')
    parser.add_argument('--clean-grid', action='store_true')
    parser.add_argument('--classifier', action='store_true')
    parser.add_argument('--header', action='store_true')
    parser.add_argument('--allow-added-roots', action='store_true')
    args = parser.parse_args()
    root, build = args.repository, args.build
    if args.stage < 1:
        raise ValueError('stage must be positive')
    baseline = 'wsl-artifacts/wasm-compat/stage5/audit' if args.stage == 1 else f'wsl-artifacts/wasm-headless/stage{args.stage - 1}/audit'
    before = root / baseline
    after = build / 'teavm-audit/fast-selected-compat-headless'
    results = root / 'wasm-compat/headless-results'
    raw_path = f'wsl-artifacts/wasm-headless/stage{args.stage}'
    artifacts = root / raw_path
    results.mkdir(parents=True, exist_ok=True)
    comparison = compare(before, after, args.allow_added_roots)
    if args.stage > 1 and not (results / 'stage1-comparison.json').exists() and (results / 'comparison.json').exists():
        shutil.copyfile(results / 'comparison.json', results / 'stage1-comparison.json')
    export(after, artifacts / 'summary', artifacts / 'audit', args.tool_commit)
    for name in ['diagnostic-summary.json', 'apis.tsv', 'diagnostics.tsv', 'coverage.properties', 'root-selection.json', 'exclusion-summary.json', 'provenance.json']:
        shutil.copyfile(artifacts / 'summary' / name, results / name)
    shutil.copyfile(after / 'modified-methods.tsv', results / 'modified-methods.tsv')
    shutil.copyfile(after / 'modified-methods.tsv', artifacts / 'audit/modified-methods.tsv')
    for destination in [results, artifacts / 'audit']:
        shutil.copyfile(after / 'post-boundary-checks.json', destination / 'post-boundary-checks.json')
    shutil.copytree(build / 'wasm-compat/headless-probe', artifacts / 'probe', dirs_exist_ok=True)
    shutil.copyfile(artifacts / 'probe/verification.json', results / 'verification.json')
    shutil.copyfile(artifacts / 'probe/jvm-verification.json', results / 'jvm-verification.json')
    if (artifacts / 'probe/model-verification.json').exists():
        shutil.copyfile(artifacts / 'probe/model-verification.json', results / 'model-verification.json')
    modified = {row['method'] for row in rows(after / 'modified-methods.tsv')}
    # These methods are intentionally not replaced, including the recognition overload of impact.
    protected = {
        'org.audiveris.omr.sheet.grid.GridStep.doit(Lorg/audiveris/omr/sheet/Sheet;)V',
        'org.audiveris.omr.sheet.rhythm.RhythmsStep.impact(Ljava/util/Collection;)V',
        'org.audiveris.omr.sig.inter.HeadInter.getNeededLedgerSegments(Ljava/awt/geom/Point2D;Lorg/audiveris/omr/sheet/Staff;)Ljava/util/List;',
        'org.audiveris.omr.score.PartwiseBuilder.processLogicalPart(Lorg/audiveris/omr/score/LogicalPart;Lorg/audiveris/proxymusic/ScorePartwise$Part;)V',
        'org.audiveris.omr.step.DeltaStep.doit(Lorg/audiveris/omr/sheet/Sheet;)V'}
    roots = {row['method'] for row in rows(after / 'roots.tsv')}
    if protected - roots or protected & modified:
        raise AssertionError('Recognition/output preservation guard failed')
    write(results / 'preserved-methods.json', {'rootedAndNotTransformed': sorted(protected),
        'contextOnlyAdaptations': ['OctaveShiftInter.setGlyph: sheet acquisition only; super setter and geometry kept',
                                  'TemplateFactory.buildDecoratedImage: compiler verifies instruction identity/order except opacity/stroke references'],
        'limitation': 'Confirms root coverage and absence from the compiler transformation manifest; not an end-to-end recognition test'})
    route_report(before, after, results / 'class-routes.tsv')
    before_calls, after_calls = calls(before, modified), calls(after, modified)
    with (results / 'changed-calls.tsv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(['change', 'caller', 'callee'])
        for change, edges in [('removed', before_calls - after_calls), ('added', after_calls - before_calls)]:
            writer.writerows((change, caller, callee) for caller, callee in sorted(edges))
    old_exclusion = json.loads((before / 'exclusion-summary.json').read_text())
    new_exclusion = json.loads((after / 'exclusion-summary.json').read_text())
    comparison['platformSubstitutionsEnabled'] = True
    comparison['modifiedMethods'] = len(modified)
    comparison['removedDirectCallsFromModifiedMethods'] = len(before_calls - after_calls)
    comparison['addedDirectCallsFromModifiedMethods'] = len(after_calls - before_calls)
    comparison['excludedClassesLinked'] = {'before': old_exclusion['excludedLinkedClasses'], 'after': new_exclusion['excludedLinkedClasses']}
    comparison['excludedUsedMethods'] = {'before': old_exclusion['excludedUsedMethods'], 'after': new_exclusion['excludedUsedMethods']}
    comparison['usedMethodsInsideExcludedClasses'] = {
        'before': old_exclusion['excludedUsedMethodsByRule']['class-excluded'],
        'after': new_exclusion['excludedUsedMethodsByRule']['class-excluded']}
    comparison['remainingClassBreakdown'] = {
        'withUsedMethods': new_exclusion['excludedClassesWithUsedMethods'],
        'typeOnly': new_exclusion['excludedTypeOnlyClasses']}
    comparison['strictExclusionPass'] = new_exclusion['strictExclusionPass']
    write(results / 'comparison.json', comparison)
    write(results / f'stage{args.stage}-comparison.json', comparison)
    provenance = json.loads((results / 'provenance.json').read_text())
    music = build / 'wasm-compat/music-data'
    if (music / 'music-data.json').exists():
        shutil.copytree(music, artifacts / 'music-data', dirs_exist_ok=True)
        shutil.copyfile(music / 'music-data.json', results / 'music-data.json')
        provenance['musicData'] = json.loads((music / 'music-data.json').read_text())
    render = music
    if (render / 'render-ops.json').exists():
        shutil.copyfile(render / 'render-ops.json', results / 'render-ops.json')
        provenance['renderOps'] = json.loads((render / 'render-ops.json').read_text())
    provenance.update({'headlessPlatformEnabled': True, 'comparisonBaseline': baseline,
                       'applicationSourceChanges': application_changes(root),
                       'applicationSourceChangeNotes': 'Java sources changed/added against the application baseline, including configuration, scale input and memory-model seams; see README for coverage',
                       'rawArtifacts': raw_path,
                       'probeSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                       for p in sorted((artifacts / 'probe').iterdir()) if p.is_file()},
                       'sourceSha256': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
                                        for p in sorted((root / 'wasm-compat/src').rglob('*')) if p.is_file()}})
    if args.pixels:
        pixel_build = build / 'wasm-compat/pixel-probe'
        pixel_raw = artifacts / 'pixel-probe'
        pixel_results = root / 'wasm-compat/pixel-results'
        pixel_raw.mkdir(parents=True, exist_ok=True); pixel_results.mkdir(parents=True, exist_ok=True)
        for name in ['pixels.wasm', 'pixels.wasm-runtime.js', 'jvm-reference.json', 'verification.json']:
            shutil.copyfile(pixel_build / name, pixel_raw / name)
        verification = json.loads((pixel_build / 'verification.json').read_text())
        shutil.copyfile(pixel_build / 'verification.json', pixel_results / 'verification.json')
        sample_manifest = pixel_build / 'samples/manifest.json'
        if verification['samplePixels']:
            shutil.copytree(pixel_build / 'samples', pixel_raw / 'samples', dirs_exist_ok=True)
            shutil.copyfile(sample_manifest, pixel_results / 'samples.json')
        if verification.get('upstreamScaleCompared'):
            for name in ['scale-upstream.json', 'scale-current.json']:
                shutil.copyfile(pixel_build / 'samples' / name, pixel_results / name)
            original = build / 'wasm-compat/upstream-scale'
            oracle_source = json.loads((original / 'source.json').read_text())
            oracle_source['classSha256'] = {str(p.relative_to(original / 'classes')): hashlib.sha256(p.read_bytes()).hexdigest()
                                           for p in sorted((original / 'classes').rglob('*.class'))}
            write(pixel_results / 'scale-upstream-source.json', oracle_source)
            write(pixel_raw / 'scale-upstream-source.json', oracle_source)
        pixel_provenance = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
                            'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal',
                            'sourceSha256', 'applicationSourceChanges']}
        pixel_provenance.update({'imageJ': '1.54p', 'rawArtifacts': raw_path + '/pixel-probe',
                                'artifactSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in pixel_raw.iterdir() if p.is_file()},
                                'limitation': 'Pixel operations and optional scale estimation verified; not staff detection, full recognition or a complete engine size estimate'})
        write(pixel_results / 'provenance.json', pixel_provenance)
        write(pixel_raw / 'provenance.json', pixel_provenance)
        provenance['pixelVerification'] = verification
    if args.ordered_maps:
        map_build = build / 'wasm-compat/ordered-map-probe'
        map_raw = artifacts / 'ordered-map-probe'
        map_results = root / 'wasm-compat/ordered-map-results'
        map_raw.mkdir(parents=True, exist_ok=True); map_results.mkdir(parents=True, exist_ok=True)
        for name in ['ordered-map.wasm', 'ordered-map.wasm-runtime.js', 'jvm-reference.txt', 'index-reference.txt', 'set-reference.txt', 'verification.json']:
            shutil.copyfile(map_build / name, map_raw / name)
        for name in ['jvm-reference.txt', 'index-reference.txt', 'set-reference.txt', 'verification.json']:
            shutil.copyfile(map_build / name, map_results / name)
        map_provenance = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
            'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal', 'sourceSha256']}
        map_provenance.update({'rawArtifacts': raw_path + '/ordered-map-probe',
            'artifactSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(map_raw.iterdir()) if p.is_file()},
            'limitation': 'Single Java execution lane per module; exercised ordered-map and original BasicIndex operations only, not shared-memory concurrency'})
        for target in [map_results, map_raw]:
            write(target / 'provenance.json', map_provenance)
        provenance['orderedMapVerification'] = json.loads((map_build / 'verification.json').read_text())
    if args.models:
        for name in ['font-defaults', 'sheet-model']:
            model_build = build / f'wasm-compat/{name}-probe'
            model_raw = artifacts / f'{name}-probe'
            model_results = root / 'wasm-compat/model-results' / name
            model_raw.mkdir(parents=True, exist_ok=True); model_results.mkdir(parents=True, exist_ok=True)
            for filename in [f'{name}.wasm', f'{name}.wasm-runtime.js', 'jvm-reference.txt', 'verification.json', 'diagnostics.tsv']:
                shutil.copyfile(model_build / filename, model_raw / filename)
            for filename in ['jvm-reference.txt', 'verification.json', 'diagnostics.tsv']:
                shutil.copyfile(model_build / filename, model_results / filename)
            model_provenance = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
                'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal', 'sourceSha256', 'applicationSourceChanges']}
            model_provenance.update({'rawArtifacts': raw_path + f'/{name}-probe',
                'artifactSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(model_raw.iterdir()) if p.is_file()},
                'limitation': 'Configuration and model initialization only; no attached raster, staff detection or full recognition'})
            for target in [model_results, model_raw]:
                write(target / 'provenance.json', model_provenance)
            provenance[name + 'Verification'] = json.loads((model_build / 'verification.json').read_text())
    if args.pixel_sheet:
        sheet_build = build / 'wasm-compat/pixel-sheet-probe'
        sheet_raw = artifacts / 'pixel-sheet-probe'
        sheet_results = root / 'wasm-compat/pixel-sheet-results'
        sheet_raw.mkdir(parents=True, exist_ok=True); sheet_results.mkdir(parents=True, exist_ok=True)
        for filename in ['pixel-sheet.wasm', 'pixel-sheet.wasm-runtime.js', 'jvm-reference.json', 'verification.json', 'diagnostics.tsv']:
            shutil.copyfile(sheet_build / filename, sheet_raw / filename)
        for filename in ['jvm-reference.json', 'verification.json', 'diagnostics.tsv']:
            shutil.copyfile(sheet_build / filename, sheet_results / filename)
        sample_build = build / 'wasm-compat/pixel-probe/samples'
        # Keep the same relative sample path used by the verification script.
        shutil.copytree(sample_build, artifacts / 'pixel-probe/samples', dirs_exist_ok=True)
        shutil.copyfile(sample_build / 'manifest.json', sheet_results / 'samples.json')
        shutil.copyfile(sample_build / 'scale-upstream.json', sheet_results / 'scale-upstream.json')
        original = build / 'wasm-compat/upstream-scale'
        original_source = json.loads((original / 'source.json').read_text())
        original_source['classSha256'] = {str(p.relative_to(original / 'classes')): hashlib.sha256(p.read_bytes()).hexdigest()
                                        for p in sorted((original / 'classes').rglob('*.class'))}
        write(sheet_results / 'scale-upstream-source.json', original_source)
        write(sheet_raw / 'scale-upstream-source.json', original_source)
        sheet_provenance = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
            'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal', 'sourceSha256', 'applicationSourceChanges']}
        sheet_provenance.update({'rawArtifacts': raw_path + '/pixel-sheet-probe',
            'artifactSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(sheet_raw.iterdir()) if p.is_file()},
            'limitation': 'Raw binary pixels through the original Sheet/ScaleBuilder path; derived sources, staff detection, full recognition and encoding remain unverified'})
        for target in [sheet_results, sheet_raw]:
            write(target / 'provenance.json', sheet_provenance)
        provenance['pixelSheetVerification'] = json.loads((sheet_build / 'verification.json').read_text())
    if args.rounding:
        rounding_build = build / 'wasm-compat/rounding-probe'
        rounding_raw = artifacts / 'rounding-probe'
        rounding_results = root / 'wasm-compat/rounding-results'
        rounding_raw.mkdir(parents=True, exist_ok=True); rounding_results.mkdir(parents=True, exist_ok=True)
        names = ['jvm-reference.txt', 'jvm-checksum.txt', 'jvm-verification.json', 'verification.json', 'baseline-comparison.json', 'diagnostics.tsv']
        for name in ['rounding.wasm', 'rounding.wasm-runtime.js'] + names:
            shutil.copyfile(rounding_build / name, rounding_raw / name)
        for name in names:
            shutil.copyfile(rounding_build / name, rounding_results / name)
        baseline_raw = artifacts / 'rounding-baseline'; baseline_raw.mkdir(parents=True, exist_ok=True)
        for name in ['rounding.wasm', 'rounding.wasm-runtime.js', 'diagnostics.tsv']:
            shutil.copyfile(build / 'wasm-compat/rounding-baseline' / name, baseline_raw / name)
        rounding_provenance = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
            'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal', 'sourceSha256']}
        rounding_provenance.update({'rawArtifacts': raw_path + '/rounding-probe', 'stockArtifacts': raw_path + '/rounding-baseline',
            'artifactSha256': {str(p.relative_to(artifacts)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for folder in [rounding_raw, baseline_raw] for p in sorted(folder.iterdir()) if p.is_file()}})
        for target in [rounding_results, rounding_raw]:
            write(target / 'provenance.json', rounding_provenance)
        provenance['roundingVerification'] = json.loads((rounding_build / 'verification.json').read_text())
    if args.staff:
        staff_build = build / 'wasm-compat/staff-probe'
        staff_raw = artifacts / 'staff-probe'
        staff_results = root / 'wasm-compat/staff-results'
        staff_raw.mkdir(parents=True, exist_ok=True); staff_results.mkdir(parents=True, exist_ok=True)
        names = ['jvm-reference.json', 'wasm-node-results.json', 'wasm-worker-results.json', 'verification.json', 'supplier-reference.txt', 'diagnostics.tsv']
        for name in ['staff.wasm', 'staff.wasm-runtime.js'] + names:
            shutil.copyfile(staff_build / name, staff_raw / name)
        for name in names:
            shutil.copyfile(staff_build / name, staff_results / name)
        sample_build = build / 'wasm-compat/pixel-probe/samples'
        shutil.copytree(sample_build, artifacts / 'pixel-probe/samples', dirs_exist_ok=True)
        shutil.copyfile(sample_build / 'manifest.json', staff_results / 'samples.json')
        original = build / 'wasm-compat/upstream-scale'
        scale_source = json.loads((original / 'source.json').read_text())
        scale_source['classSha256'] = {str(p.relative_to(original / 'classes')): hashlib.sha256(p.read_bytes()).hexdigest()
                                     for p in sorted((original / 'classes').rglob('*.class'))}
        algorithm_dirs = ['app/src/main/java/org/audiveris/omr/' + name for name in ['sheet/grid', 'glyph/dynamic', 'lag', 'math', 'run']]
        changed_algorithms = [name for name in provenance['applicationSourceChanges'] if any(name.startswith(folder + '/') for folder in algorithm_dirs)]
        if changed_algorithms:
            raise AssertionError('Staff reference algorithm sources changed: ' + ', '.join(changed_algorithms))
        algorithm_hashes = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
                            for folder in algorithm_dirs for p in sorted((root / folder).rglob('*.java'))}
        reference = {'scale': scale_source, 'unchangedAlgorithmSourceSha256': algorithm_hashes,
            'scope': 'Pinned upstream ScaleBuilder plus unchanged grid/dynamic/lag/math/run algorithm sources; shared current model runtime, not an entirely pristine upstream application'}
        write(staff_results / 'reference-sources.json', reference); write(staff_raw / 'reference-sources.json', reference)
        staff_provenance = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
            'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal', 'sourceSha256', 'applicationSourceChanges']}
        staff_provenance.update({'rawArtifacts': raw_path + '/staff-probe',
            'artifactSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(staff_raw.iterdir()) if p.is_file()},
            'limitation': 'Gray/binary input through horizontal staff retrieval; barline/system completion, OCR, symbols and MusicXML are unverified'})
        for target in [staff_results, staff_raw]:
            write(target / 'provenance.json', staff_provenance)
        provenance['staffVerification'] = json.loads((staff_build / 'verification.json').read_text())
    if args.grid or args.clean_grid:
        grid_name = 'clean-grid' if args.clean_grid else 'grid'
        grid_build = build / f'wasm-compat/{grid_name}-probe'
        grid_raw = artifacts / f'{grid_name}-probe'
        grid_results = root / f'wasm-compat/{grid_name}-results'
        grid_raw.mkdir(parents=True, exist_ok=True); grid_results.mkdir(parents=True, exist_ok=True)
        names = ['jvm-reference.json', 'wasm-node-results.json', 'wasm-worker-results.json',
                 'verification.json', 'font-measurements.json', 'diagnostics.tsv']
        if args.clean_grid:
            names.append('raster-clipping.json')
        for name in [grid_name + '.wasm', grid_name + '.wasm-runtime.js'] + names:
            shutil.copyfile(grid_build / name, grid_raw / name)
        for name in names:
            shutil.copyfile(grid_build / name, grid_results / name)
        sample_build = build / 'wasm-compat/pixel-probe/samples'
        shutil.copytree(sample_build, artifacts / 'pixel-probe/samples', dirs_exist_ok=True)
        shutil.copyfile(sample_build / 'manifest.json', grid_results / 'samples.json')
        shutil.copyfile(build / 'wasm-compat/staff-probe/supplier-reference.txt', grid_raw / 'supplier-reference.txt')
        reference = json.loads((root / 'wasm-compat/staff-results/reference-sources.json').read_text())
        for name, digest in reference['unchangedAlgorithmSourceSha256'].items():
            if hashlib.sha256((root / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() != digest:
                raise AssertionError('Grid algorithm source changed: ' + name)
        original = build / 'wasm-compat/upstream-bracket'
        reference['bracket'] = json.loads((original / 'source.json').read_text())
        reference['bracket']['classSha256'] = {str(p.relative_to(original / 'classes')): hashlib.sha256(p.read_bytes()).hexdigest()
                                              for p in sorted((original / 'classes').rglob('*.class'))}
        reference['fontSha256'] = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in sorted((root / 'app/res').iterdir()) if p.suffix in ['.otf', '.ttf']}
        reference['scope'] = 'Pinned original ScaleBuilder and BracketInter plus unchanged grid/dynamic/lag/math/run algorithms; shared current model runtime'
        if args.clean_grid:
            picture_name = 'app/src/main/java/org/audiveris/omr/sheet/Picture.java'
            original_picture = subprocess.check_output(['git', '-c', f'safe.directory={root}', '-C', str(root),
                'show', f"{provenance['applicationBaseline']}:{picture_name}"]).decode().replace('\r\n', '\n')
            current_picture = (root / picture_name).read_text(encoding='utf-8')
            def raster_method(source):
                start = source.index('    private ByteProcessor buildNoStaffBuffer ()')
                brace = source.index('{', start); end, depth = brace + 1, 1
                while depth:
                    depth += (source[end] == '{') - (source[end] == '}'); end += 1
                return source[start:end]
            original_body, current_body = map(raster_method, [original_picture, current_picture])
            if original_body != current_body:
                raise AssertionError('Original Java2D staff-free raster oracle changed')
            reference['unchangedStaffFreeRasterMethod'] = {'path': picture_name,
                'method': 'buildNoStaffBuffer()', 'sha256': hashlib.sha256(original_body.encode()).hexdigest()}
        write(grid_raw / 'reference-sources.json', reference)
        grid_provenance = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
            'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal', 'sourceSha256', 'applicationSourceChanges']}
        grid_provenance.update({'rawArtifacts': raw_path + '/' + grid_name + '-probe',
            'artifactSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(grid_raw.iterdir()) if p.is_file()},
            'limitation': 'GridBuilder with explicit host font measurements; staff-line removal, score update and later recognition remain unverified'})
        for target in [grid_results, grid_raw]:
            write(target / 'reference-sources.json', reference)
            write(target / 'provenance.json', grid_provenance)
        if args.clean_grid:
            shutil.copytree(grid_build / 'no-staff', grid_raw / 'no-staff', dirs_exist_ok=True)
            raster_hashes = {p.name: {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'bytes': p.stat().st_size}
                             for p in sorted((grid_raw / 'no-staff').glob('*.binary'))}
            for target in [grid_results, grid_raw]:
                write(target / 'no-staff-reference.json', raster_hashes)
                grid_provenance['limitation'] = 'GridStep recognition calls with explicit font measurements; later recognition remains unverified'
                grid_provenance['noStaffReference'] = raster_hashes
                write(target / 'provenance.json', grid_provenance)
        provenance['cleanGridVerification' if args.clean_grid else 'gridVerification'] = json.loads((grid_build / 'verification.json').read_text())
    if args.classifier:
        classifier_build = build / 'wasm-compat/classifier-probe'
        classifier_raw = artifacts / 'classifier-probe'
        classifier_results = root / 'wasm-compat/classifier-results'
        classifier_raw.mkdir(parents=True, exist_ok=True); classifier_results.mkdir(parents=True, exist_ok=True)
        for name in ['classifier.wasm', 'classifier.wasm-runtime.js', 'jvm-reference.json', 'jvm-portable.json',
                     'wasm-node-results.json', 'wasm-worker-results.json', 'verification.json', 'diagnostics.tsv',
                     'bad-stds.zip', 'bad-dimensions.zip']:
            shutil.copyfile(classifier_build / name, classifier_raw / name)
        for name in ['verification.json', 'diagnostics.tsv']:
            shutil.copyfile(classifier_build / name, classifier_results / name)
        shutil.copyfile(root / 'app/res/basic-classifier.zip', classifier_raw / 'model.zip')
        shutil.copytree(build / 'wasm-compat/clean-grid-probe/no-staff', artifacts / 'clean-grid-probe/no-staff', dirs_exist_ok=True)
        original = build / 'wasm-compat/upstream-neural'
        shutil.copytree(original, artifacts / 'upstream-neural', dirs_exist_ok=True)
        neural_source = json.loads((original / 'source.json').read_text())
        neural_source['classSha256'] = {str(p.relative_to(original / 'classes')): hashlib.sha256(p.read_bytes()).hexdigest()
                                       for p in sorted((original / 'classes').rglob('*.class'))}
        neural_path = 'app/src/main/java/org/audiveris/omr/math/NeuralNetwork.java'
        original_neural = subprocess.check_output(['git', '-c', f'safe.directory={root}', '-C', str(root),
            'show', f"{provenance['applicationBaseline']}:{neural_path}"]).decode().replace('\r\n', '\n')
        current_neural = (root / neural_path).read_text(encoding='utf-8')
        unchanged_methods = {}
        for signature in ['private void forward (', 'public double[] run (', 'private double sigmoid (']:
            bodies = []
            for source in [original_neural, current_neural]:
                start = source.index(signature); end = source.index('{', start) + 1; depth = 1
                while depth:
                    depth += (source[end] == '{') - (source[end] == '}'); end += 1
                bodies.append(source[start:end])
            if bodies[0] != bodies[1]:
                raise AssertionError('Original neural inference changed: ' + signature)
            unchanged_methods[signature] = hashlib.sha256(bodies[0].encode()).hexdigest()
        names = ['classifier/BasicClassifier.java', 'classifier/AbstractClassifier.java', 'classifier/MixGlyphDescriptor.java',
                 'math/PoorManAlgebra.java', 'glyph/Glyph.java', 'glyph/GlyphFactory.java']
        names += [str(p.relative_to(root / 'app/src/main/java/org/audiveris/omr'))
                  for p in sorted((root / 'app/src/main/java/org/audiveris/omr/moments').rglob('*.java'))]
        algorithm_hashes = {}
        for name in names:
            relative = 'app/src/main/java/org/audiveris/omr/' + name
            if relative in provenance['applicationSourceChanges']:
                raise AssertionError('Classifier algorithm changed: ' + relative)
            algorithm_hashes[relative] = hashlib.sha256((root / relative).read_bytes().replace(b'\r\n', b'\n')).hexdigest()
        reference = {'neural': neural_source, 'unchangedNeuralMethodSha256': unchanged_methods,
            'unchangedAlgorithmSourceSha256': algorithm_hashes,
            'scope': 'Pinned original NeuralNetwork and unchanged classifier/features/moments algorithms; shared current model runtime'}
        oracle = json.loads((classifier_build / 'jvm-reference.json').read_text())
        portable = json.loads((classifier_build / 'jvm-portable.json').read_text())
        if any(oracle[key] != portable[key] for key in ['metadata', 'weightSha256', 'normSha256']):
            raise AssertionError('Classifier JVM model loader mismatch')
        write(classifier_results / 'jvm-loader-comparison.json', {'metadata': oracle['metadata'],
            'weightSha256': oracle['weightSha256'], 'normSha256': oracle['normSha256'], 'exactWeightsAndNorms': True})
        inputs = [{key: value for key, value in page.items() if key != 'result'} for page in oracle['pages']]
        model_sha = hashlib.sha256((classifier_raw / 'model.zip').read_bytes()).hexdigest()
        baseline_model = subprocess.check_output(['git', '-c', f'safe.directory={root}', '-C', str(root),
            'show', f"{provenance['applicationBaseline']}:app/res/basic-classifier.zip"])
        if hashlib.sha256(baseline_model).hexdigest() != model_sha:
            raise AssertionError('Default classifier archive differs from the pinned baseline')
        reference['model'] = {'path': 'app/res/basic-classifier.zip', 'sha256': model_sha,
                              'bytes': len(baseline_model), 'matchesOriginalBaseline': True}
        classifier_provenance = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
            'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal', 'sourceSha256', 'applicationSourceChanges']}
        classifier_provenance.update({'rawArtifacts': raw_path + '/classifier-probe', 'modelSha256': model_sha,
            'artifactSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(classifier_raw.iterdir()) if p.is_file()},
            'limitation': 'Classifier component with an explicit host model asset; full HEADERS and subsequent recognition are unverified'})
        for target in [classifier_results, classifier_raw]:
            write(target / 'reference-sources.json', reference); write(target / 'inputs.json', inputs)
            write(target / 'provenance.json', classifier_provenance)
        provenance['classifierVerification'] = json.loads((classifier_build / 'verification.json').read_text())
    if args.header:
        export_header(root, build, artifacts, raw_path, provenance)
    for target in [results, artifacts]:
        write(target / 'provenance.json', provenance)
    print(json.dumps({key: comparison[key] for key in ['rootCount', 'baselineDiagnostics', 'patchedDiagnostics', 'modifiedMethods',
        'excludedClassesLinked', 'excludedUsedMethods', 'strictExclusionPass']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
