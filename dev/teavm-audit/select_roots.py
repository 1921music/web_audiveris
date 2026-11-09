"""Select explicit TeaVM roots without deleting or transforming original methods."""
import argparse
import collections
import csv
import hashlib
import json
import re
from pathlib import Path


def read_tsv(path):
    with path.open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def method_name(reference):
    return reference.split('(', 1)[0].rsplit('.', 1)[1]


def choose(root, decision, policy):
    if decision == 'exclude':
        return 'excluded', 'class-excluded', 'Class is outside the initial engine scope'
    if decision in policy['dependencyOnlyClassDecisions']:
        return 'dependency-only', 'class-boundary', 'Do not force a boundary/metadata class; let selected callers determine reachability'
    name = method_name(root['method'])
    # Lambdas from a rejected method are not independent engine entry points.
    origin = re.match(r'lambda\$(.*?)\$\d+$', name)
    origin_name = origin.group(1) if origin else name
    for rule in policy['rules']:
        if re.fullmatch(rule['class'], root['class']) and re.fullmatch(rule['name'], origin_name):
            return 'excluded', rule['id'], rule['reason']
    if policy['excludeUiMethods'] and root['category'] == 'ui':
        return 'excluded', 'ui-method', 'Rendering/UI method in a retained class; do not force it as an engine entry'
    if decision not in policy['rootClassDecisions']:
        raise ValueError(('Unknown class decision', decision, root['method']))
    return 'root', 'engine-candidate', 'Non-UI recognition/data method in a retained/mixed class'


def select(inventory, policy_file, output):
    policy = json.loads(policy_file.read_text(encoding='utf-8'))
    classes = {item['class']: item for item in json.loads((inventory / 'class-decisions.json').read_text(encoding='utf-8'))['classification']}
    roots = read_tsv(inventory / 'roots.tsv')
    output.mkdir(parents=True, exist_ok=True)
    plan = []
    for root in roots:
        decision = classes[root['class']]['decision']
        action, rule, reason = choose(root, decision, policy)
        plan.append({**{key: root[key] for key in ['category', 'class', 'method', 'hasBody', 'native']},
                     'classDecision': decision, 'action': action, 'rule': rule, 'reason': reason})
    selected = [row for row in plan if row['action'] == 'root']
    if not selected or len({row['method'] for row in plan}) != len(plan):
        raise ValueError('Empty root set or duplicate methods')
    assert not any(row['classDecision'] == 'exclude' or row['category'] == 'ui' for row in selected)
    for name, data in [('root-plan.tsv', plan), ('selected-roots.tsv', selected)]:
        with (output / name).open('w', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=list(plan[0]), delimiter='\t', lineterminator='\n')
            writer.writeheader()
            writer.writerows(data)
    summary = {'profile': policy['profile'], 'totalMethods': len(plan), 'selectedMethods': len(selected),
               'selectedClasses': len({row['class'] for row in selected}),
               'selectedBodies': sum(row['hasBody'] == 'true' for row in selected),
               'byAction': dict(collections.Counter(row['action'] for row in plan)),
               'byRule': dict(collections.Counter(row['rule'] for row in plan)),
               'policySha256': hashlib.sha256(policy_file.read_bytes()).hexdigest(),
               'classDecisionsSha256': hashlib.sha256((inventory / 'class-decisions.json').read_bytes()).hexdigest(),
               'inventorySha256': hashlib.sha256((inventory / 'roots.tsv').read_bytes()).hexdigest(),
               'excludedClassesForcedAsRoots': 0, 'excludedMethodsForcedAsRoots': 0}
    (output / 'root-selection.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    (output / 'root-policy.json').write_bytes(policy_file.read_bytes())
    # Keep immutable classification input separate from the selected-run method subset.
    for name in ['class-decisions.json', 'class-decisions.tsv', 'kept-to-excluded.tsv']:
        (output / name).write_bytes((inventory / name).read_bytes())
    print(json.dumps(summary))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('inventory', type=Path)
    parser.add_argument('policy', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    select(args.inventory, args.policy, args.output)
