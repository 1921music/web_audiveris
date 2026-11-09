"""Export reviewable audit tables; keep bulky raw data out of Git."""
import argparse
import csv
import gzip
import hashlib
import json
import shutil
from pathlib import Path


def export(source, results, artifacts, tool_commit):
    results.mkdir(parents=True, exist_ok=True)
    artifacts.mkdir(parents=True, exist_ok=True)
    for name in ['roots.tsv', 'diagnostics.tsv', 'diagnostics-classified.tsv', 'summary.properties',
                 'class-decisions.tsv', 'class-decisions.json', 'classified-summary.json', 'apis.tsv', 'kept-to-excluded.tsv']:
        shutil.copyfile(source / name, artifacts / name)
    with (source / 'calls.tsv').open('rb') as input_stream, gzip.open(artifacts / 'calls.tsv.gz', 'wb', compresslevel=6) as output:
        shutil.copyfileobj(input_stream, output)
    decisions = json.loads((source / 'class-decisions.json').read_text(encoding='utf-8'))
    with (results / 'classes.tsv').open('w', encoding='utf-8', newline='') as output:
        writer = csv.writer(output, delimiter='\t', lineterminator='\n')
        writer.writerow(['decision', 'class', 'source', 'reason'])
        for item in sorted(decisions['classification'], key=lambda item: item['class']):
            writer.writerow([item['decision'], item['class'], item['source'], item['reason']])
    for original, target in [('classified-summary.json', 'diagnostic-summary.json'),
                             ('diagnostics-classified.tsv', 'diagnostics.tsv'), ('apis.tsv', 'apis.tsv'),
                             ('kept-to-excluded.tsv', 'kept-to-excluded.tsv'), ('summary.properties', 'coverage.properties')]:
        shutil.copyfile(source / original, results / target)
    if (source / 'root-plan.tsv').exists():
        inventory = source.parent / 'inventory'
        for original, target in [('source-references.tsv', 'source-references.tsv.gz')]:
            with (inventory / original).open('rb') as input_stream, gzip.open(artifacts / target, 'wb', compresslevel=6) as output:
                shutil.copyfileobj(input_stream, output)
        shutil.copyfile(inventory / 'roots.tsv', artifacts / 'inventory-roots.tsv')
        for name in ['root-plan.tsv', 'selected-roots.tsv', 'root-selection.json', 'root-policy.json',
                     'exclusion-summary.json', 'exclusion-boundaries.tsv', 'entry-boundaries.tsv',
                     'original-reference-paths.tsv', 'excluded-class-paths.tsv', 'excluded-method-paths.tsv',
                     'reached-methods.tsv', 'reached-classes.txt']:
            shutil.copyfile(source / name, artifacts / name)
        for name in ['root-selection.json', 'root-policy.json', 'exclusion-summary.json',
                     'entry-boundaries.tsv', 'original-reference-paths.tsv', 'excluded-class-paths.tsv']:
            shutil.copyfile(source / name, results / name)
        with (source / 'root-plan.tsv').open(encoding='utf-8', newline='') as stream:
            plan = list(csv.DictReader(stream, delimiter='\t'))
        with (results / 'method-exclusions.tsv').open('w', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=list(plan[0]), delimiter='\t', lineterminator='\n')
            writer.writeheader()
            writer.writerows(row for row in plan if row['action'] == 'excluded' and row['classDecision'] != 'exclude')
    classification_summary = {key: value for key, value in decisions.items() if key != 'classification'}
    (results / 'class-summary.json').write_text(json.dumps(classification_summary, indent=2) + '\n', encoding='utf-8')
    group = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    provenance = {'applicationBaseline': '04697c74fae431ef8436a9429386957a5cb25993',
                  'toolCommit': tool_commit, 'teaVM': '0.16.0', 'target': 'wasm-gc', 'mode': 'fast',
                  'cpuMax': (group / 'cpu.max').read_text().strip(),
                  'allowedCpus': (group / 'cpuset.cpus.effective').read_text().strip(),
                  'memoryMaxBytes': int((group / 'memory.max').read_text()),
                  'memoryHighBytes': int((group / 'memory.high').read_text()),
                  'memorySwapMaxBytes': int((group / 'memory.swap.max').read_text()),
                  'memoryEventsLocal': dict(line.split() for line in (group / 'memory.events.local').read_text().splitlines()),
                  'artifactSha256': {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                     for path in sorted(artifacts.iterdir()) if path.is_file() and path.name != 'provenance.json'}}
    for directory in [results, artifacts]:
        (directory / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'classes': decisions['classes'], 'results': str(results), 'artifacts': str(artifacts)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('results', type=Path)
    parser.add_argument('artifacts', type=Path)
    parser.add_argument('tool_commit')
    args = parser.parse_args()
    export(args.source, args.results, args.artifacts, args.tool_commit)
