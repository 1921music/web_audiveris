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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('repository', type=Path)
    parser.add_argument('build', type=Path)
    parser.add_argument('tool_commit')
    args = parser.parse_args()
    root, build = args.repository, args.build
    before = root / 'wsl-artifacts/wasm-compat/stage5/audit'
    after = build / 'teavm-audit/fast-selected-compat-headless'
    results = root / 'wasm-compat/headless-results'
    artifacts = root / 'wsl-artifacts/wasm-headless/stage1'
    results.mkdir(parents=True, exist_ok=True)
    comparison = compare(before, after)
    export(after, artifacts / 'summary', artifacts / 'audit', args.tool_commit)
    for name in ['diagnostic-summary.json', 'apis.tsv', 'diagnostics.tsv', 'coverage.properties', 'root-selection.json', 'exclusion-summary.json', 'provenance.json']:
        shutil.copyfile(artifacts / 'summary' / name, results / name)
    shutil.copyfile(after / 'modified-methods.tsv', results / 'modified-methods.tsv')
    shutil.copyfile(after / 'modified-methods.tsv', artifacts / 'audit/modified-methods.tsv')
    shutil.copytree(build / 'wasm-compat/headless-probe', artifacts / 'probe', dirs_exist_ok=True)
    shutil.copyfile(artifacts / 'probe/verification.json', results / 'verification.json')
    shutil.copyfile(artifacts / 'probe/jvm-verification.json', results / 'jvm-verification.json')
    modified = {row['method'] for row in rows(after / 'modified-methods.tsv')}
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
    comparison['strictExclusionPass'] = new_exclusion['strictExclusionPass']
    write(results / 'comparison.json', comparison)
    provenance = json.loads((results / 'provenance.json').read_text())
    provenance.update({'headlessPlatformEnabled': True, 'comparisonBaseline': 'wsl-artifacts/wasm-compat/stage5/audit',
                       'rawArtifacts': 'wsl-artifacts/wasm-headless/stage1',
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
