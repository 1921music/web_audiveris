"""Export compatibility evidence, retaining the first compatibility stage for comparison."""
import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path
from zipfile import ZipFile

from compare_compat import compare
from export_results import export


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('repository', type=Path, help='Authoritative Windows checkout mounted in WSL')
    parser.add_argument('build', type=Path, help='Linux mirror build directory')
    parser.add_argument('tool_commit')
    parser.add_argument('--stage', type=int, default=3)
    args = parser.parse_args()
    root, build = args.repository, args.build
    source = build / 'teavm-audit/fast-selected-compat'
    results = root / 'wasm-compat/results'
    if args.stage < 2:
        raise ValueError('Compatibility stage must be at least 2')
    artifacts = root / f'wsl-artifacts/wasm-compat/stage{args.stage}'
    previous = root / ('wsl-artifacts/wasm-compat/audit' if args.stage == 2
                       else f'wsl-artifacts/wasm-compat/stage{args.stage - 1}/audit')
    baseline = compare(build / 'teavm-audit/fast-selected', source)
    results.mkdir(parents=True, exist_ok=True)
    if not (results / 'stage1-comparison.json').exists() and (results / 'compatibility-comparison.json').exists():
        shutil.copyfile(results / 'compatibility-comparison.json', results / 'stage1-comparison.json')
    write(results / 'compatibility-comparison.json', baseline)
    if (previous / 'classified-summary.json').exists():
        stage = compare(previous, source)
        write(results / f'stage{args.stage}-comparison.json', stage)
        with (source / 'diagnostics-classified.tsv').open(encoding='utf-8', newline='') as stream:
            reader = csv.DictReader(stream, delimiter='\t')
            fields = reader.fieldnames
            new_rows = [row for row in reader if row['message'] in stage['newMessages']]
        with (results / 'new-diagnostics.tsv').open('w', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=fields, delimiter='\t', lineterminator='\n')
            writer.writeheader()
            writer.writerows(new_rows)
        write(source / 'compatibility-comparison.json', baseline)
    export(source, artifacts / 'summary', artifacts / 'audit', args.tool_commit)
    shutil.copytree(build / 'wasm-compat/probe', artifacts / 'probe', dirs_exist_ok=True)
    for name in ['diagnostic-summary.json', 'diagnostics.tsv', 'apis.tsv', 'coverage.properties', 'root-selection.json', 'provenance.json']:
        shutil.copyfile(artifacts / 'summary' / name, results / name)
    for name in ['jvm-verification.json', 'verification.json', 'browser-verification.json']:
        shutil.copyfile(artifacts / 'probe' / name, results / name)
    for directory, name, target in [('bundled-resources', 'resources.json', 'bundled-resources.json'),
                                     ('kxml2-patched', 'kxml-patch.json', 'kxml-patch.json')]:
        candidate = build / 'wasm-compat' / directory / name
        if candidate.exists():
            shutil.copyfile(candidate, results / target)
            shutil.copytree(candidate.parent, artifacts / directory, dirs_exist_ok=True)
    benchmark = build / 'wasm-compat/xml-benchmark.json'
    if benchmark.exists():
        shutil.copyfile(benchmark, results / 'xml-benchmark.json')
        for backend in ['jdk', 'kxml2']:
            directory = build / f'wasm-compat/xml-bench-{backend}'
            shutil.copytree(directory, artifacts / directory.name, dirs_exist_ok=True)
    provenance = json.loads((results / 'provenance.json').read_text())
    provenance['compatibilityEnabled'] = True
    provenance['probeSha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                 for p in sorted((artifacts / 'probe').iterdir()) if p.is_file()}
    provenance['compatibilitySourceSha256'] = {
        str(p.relative_to(root)): hashlib.sha256(p.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
        for p in sorted((root / 'wasm-compat/src').rglob('*')) if p.is_file()}
    jar = next((Path.home() / '.gradle/caches/modules-2/files-2.1/com.fasterxml.jackson.core/jackson-core/2.20.0').rglob('*.jar'))
    selected = build / 'wasm-compat/upstream-base'
    with ZipFile(jar) as archive:
        classes = {}
        for path in sorted(selected.rglob('*.class')):
            name = str(path.relative_to(selected))
            data = path.read_bytes()
            if data != archive.read(name):
                raise AssertionError('Selected class differs from the unchanged base JAR entry: ' + name)
            classes[name] = hashlib.sha256(data).hexdigest()
    if len(classes) != 3:
        raise AssertionError('Expected exactly three base implementation classes')
    provenance['libraryImplementationSelection'] = {'artifact': 'com.fasterxml.jackson.core:jackson-core:2.20.0',
        'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'variant': 'base Java 8', 'classesSha256': classes}
    provenance['rawArtifacts'] = f'wsl-artifacts/wasm-compat/stage{args.stage}'
    if (results / 'kxml-patch.json').exists():
        provenance['kxmlPatch'] = json.loads((results / 'kxml-patch.json').read_text())
    if (results / 'bundled-resources.json').exists():
        provenance['bundledResources'] = json.loads((results / 'bundled-resources.json').read_text())
    for directory in [results, artifacts]:
        write(directory / 'provenance.json', provenance)


if __name__ == '__main__':
    main()
