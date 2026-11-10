"""Compare unchanged engine roots before/after explicit Worker platform substitutions."""
import argparse
import csv
import hashlib
import gzip
import json
import shutil
from pathlib import Path
from compare_compat import compare, rows
from export_results import export


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


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
    comparison = compare(before, after)
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
                       'applicationSourceChanges': {name: hashlib.sha256((root / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest()
                                                    for name in ['app/src/main/java/org/audiveris/omr/constant/Constant.java',
                                                                 'app/src/main/java/org/audiveris/omr/constant/ConstantSet.java',
                                                                 'app/src/main/java/org/audiveris/omr/sheet/BookManager.java']},
                       'applicationSourceChangeNotes': 'Stage 4: ConstantSet namespace hook, fail-fast initialization and initialize-before-update for book output settings; recognition roots unchanged',
                       'rawArtifacts': raw_path,
                       'probeSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                       for p in sorted((artifacts / 'probe').iterdir()) if p.is_file()},
                       'sourceSha256': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
                                        for p in sorted((root / 'wasm-compat/src').rglob('*')) if p.is_file()}})
    for target in [results, artifacts]:
        write(target / 'provenance.json', provenance)
    print(json.dumps({key: comparison[key] for key in ['rootCount', 'baselineDiagnostics', 'patchedDiagnostics', 'modifiedMethods',
        'excludedClassesLinked', 'excludedUsedMethods', 'strictExclusionPass']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
