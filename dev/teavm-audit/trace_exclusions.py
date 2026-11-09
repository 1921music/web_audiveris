"""Trace selected roots to unwanted features without hiding or replacing their implementations."""
import argparse
import collections
import csv
import json
from pathlib import Path
from select_roots import read_tsv


def shortest_paths(roots, edges):
    parents = {root: None for root in sorted(roots)}
    queue = collections.deque(parents)
    while queue:
        caller = queue.popleft()
        for callee in sorted(edges.get(caller, ())):
            if callee not in parents:
                parents[callee] = caller
                queue.append(callee)
    return parents


def witness(method, parents):
    if method not in parents:
        return []
    path = [method]
    while parents[path[-1]] is not None:
        path.append(parents[path[-1]])
    return list(reversed(path))


def table(path, fields, rows):
    with path.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)


def check_exclusion_guard(summary, fail_on_excluded):
    if summary['excludedClassesForcedAsRoots'] or summary['excludedMethodsForcedAsRoots']:
        raise AssertionError('Forbidden roots were selected')
    if fail_on_excluded and not summary['strictExclusionPass']:
        raise SystemExit(2)


def trace(folder, inventory, baseline, fail_on_excluded=False):
    plan = read_tsv(folder / 'root-plan.tsv')
    roots = {row['method'] for row in plan if row['action'] == 'root'}
    forbidden = {row['method']: row for row in plan if row['action'] == 'excluded'}
    classes = {item['class']: item for item in json.loads((folder / 'class-decisions.json').read_text(encoding='utf-8'))['classification']}
    excluded_classes = {name for name, item in classes.items() if item['decision'] == 'exclude'}
    methods = read_tsv(folder / 'reached-methods.tsv')
    used = {row['method'] for row in methods if row['used'] == 'true'}
    linked_classes = set((folder / 'reached-classes.txt').read_text(encoding='utf-8').splitlines())
    by_class = collections.defaultdict(list)
    for row in methods:
        if row['used'] == 'true':
            by_class[row['class']].append(row['method'])
    edges = collections.defaultdict(set)
    for row in read_tsv(folder / 'calls.tsv'):
        if row['caller'] in used and row['callee'] in used:
            edges[row['caller']].add(row['callee'])
    parents = shortest_paths(roots, edges)
    references = read_tsv(inventory / 'source-references.tsv')
    direct = collections.defaultdict(list)
    source_edges = collections.defaultdict(set)
    source_members = {row['method'] for row in plan}
    crossings = []
    for row in references:
        if row['kind'].startswith('invoke-') or row['kind'] == 'initialize':
            direct[row['caller'], row['targetMember']].append(row)
            if row['targetMember'] in source_members:
                source_edges[row['caller']].add(row['targetMember'])
        if row['caller'] not in used:
            continue
        if row['targetClass'] not in excluded_classes and row['targetMember'] not in forbidden:
            continue
        path = witness(row['caller'], parents)
        owner = row['caller'].split('(', 1)[0].rsplit('.', 1)[0]
        crossings.append({**row, 'callerDecision': classes.get(owner, {}).get('decision', 'external'),
                          'callerIsRoot': str(row['caller'] in roots).lower(),
                          'pathToCaller': ' -> '.join(path),
                          'evidence': 'original-body-reference; call path is TeaVM fast approximation' if path else 'original-body-reference; caller used but no recorded root call path'})
    boundary_fields = ['caller', 'targetClass', 'targetMember', 'kind', 'location', 'callerDecision',
                       'callerIsRoot', 'pathToCaller', 'evidence']
    table(folder / 'exclusion-boundaries.tsv', boundary_fields, crossings)
    entry_boundaries = [row for row in crossings if row['callerDecision'] in ('keep', 'mixed', 'boundary')]
    table(folder / 'entry-boundaries.tsv', boundary_fields, entry_boundaries)
    source_parents = shortest_paths(roots, source_edges)
    original_paths = []
    for row in entry_boundaries:
        path = witness(row['caller'], source_parents)
        original_paths.append({**{key: row[key] for key in ['caller', 'targetClass', 'targetMember', 'kind', 'location']},
                               'root': path[0] if path else '', 'pathToCaller': ' -> '.join(path),
                               'evidence': 'original-application-declared-reference-path; branches not evaluated' if path
                                    else 'original-reference-in-used-method; no all-original-call-path from roots'})
    table(folder / 'original-reference-paths.tsv', ['caller', 'targetClass', 'targetMember', 'kind', 'location',
                                                 'root', 'pathToCaller', 'evidence'], original_paths)

    def path_row(target, target_type, method):
        path = witness(method, parents)
        locations, inferred = [], 0
        for caller, callee in zip(path, path[1:]):
            records = direct.get((caller, callee), ())
            if records:
                locations.append('; '.join(sorted({r['location'] or '(unknown)' for r in records})))
            else:
                locations.append('(TeaVM inferred/dependency edge; no matching original application callsite)')
                inferred += 1
        return {'target': target, 'targetType': target_type, 'representativeMethod': method,
                'root': path[0] if path else '', 'pathLength': len(path) - 1 if path else '',
                'path': ' -> '.join(path), 'locations': ' -> '.join(locations), 'inferredEdges': inferred,
                'evidence': 'TeaVM-fast-possible-call-path' if path else 'used-but-no-root-call-path-recorded'}

    path_fields = ['target', 'targetType', 'representativeMethod', 'root', 'pathLength', 'path', 'locations', 'inferredEdges', 'evidence']
    method_paths = [path_row(method, forbidden[method]['rule'], method) for method in sorted(used & forbidden.keys())]
    table(folder / 'excluded-method-paths.tsv', path_fields, method_paths)
    class_paths = []
    for name in sorted(excluded_classes & linked_classes):
        candidates = sorted(by_class[name], key=lambda m: (len(witness(m, parents)) if m in parents else 10**9, m))
        if candidates:
            row = path_row(name, 'used-method', candidates[0])
        else:
            row = path_row(name, 'type-only', '')
            row['evidence'] = 'linked-type-only; no method marked used; not an execution path'
        row['usedMethodCount'] = len(candidates)
        class_paths.append(row)
    table(folder / 'excluded-class-paths.tsv', path_fields + ['usedMethodCount'], class_paths)

    by_rule = collections.Counter(forbidden[method]['rule'] for method in used & forbidden.keys())
    by_target = collections.Counter(row['targetClass'] for row in entry_boundaries)
    old = json.loads(baseline.read_text(encoding='utf-8'))
    now = json.loads((folder / 'classified-summary.json').read_text(encoding='utf-8'))
    old_messages = {api['message'] for api in old['apis']}
    now_messages = {api['message'] for api in now['apis']}
    summary = {'profile': json.loads((folder / 'root-selection.json').read_text())['profile'],
               'totalApplicationMethods': len(plan), 'selectedRoots': len(roots), 'selectedRootClasses': len({r['class'] for r in plan if r['action'] == 'root'}),
               'excludedClassesForcedAsRoots': len({row['class'] for row in plan if row['action'] == 'root'} & excluded_classes),
               'excludedMethodsForcedAsRoots': len(roots & forbidden.keys()),
               'reachedApplicationClasses': len(linked_classes & classes.keys()),
               'excludedLinkedClasses': len(class_paths),
               'excludedClassesWithUsedMethods': sum(row['usedMethodCount'] > 0 for row in class_paths),
               'excludedTypeOnlyClasses': sum(row['usedMethodCount'] == 0 for row in class_paths),
               'excludedUsedMethods': len(method_paths),
               'excludedUsedMethodsByRule': dict(by_rule),
               'usedExcludedMethodsWithoutRecordedPath': sum(not row['path'] for row in method_paths),
               'originalReferenceCrossings': len(crossings),
               'entryBoundaryReferences': len(entry_boundaries),
               'entryBoundarySourceLines': len({row['location'] for row in entry_boundaries if row['location'] and not row['location'].endswith(':-1')}),
               'topBoundaryTargets': dict(by_target.most_common(30)),
               'comparison': {'allMethodsRoots': old['roots']['methods'], 'allMethodsDiagnostics': old['diagnostics'],
                              'selectedDiagnostics': now['diagnostics'], 'allUniqueMessages': old['uniqueMessages'],
                              'selectedUniqueMessages': now['uniqueMessages'],
                              'noLongerReportedMessages': sorted(old_messages - now_messages),
                              'newlyReportedMessages': sorted(now_messages - old_messages)},
               'strictExclusionPass': not class_paths and not method_paths,
               'limitations': ['Roots are conservative engine candidates, not a final engine API.',
                               'TeaVM fast virtual dispatch is an over-approximation; a witness is not a runtime trace.',
                               'Original references prove a source dependency, not that its branch executes.',
                               'Type-only, implicit initializer and reflection dependencies need separate interpretation.']}
    (folder / 'exclusion-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in summary.items() if k not in ('comparison', 'limitations', 'topBoundaryTargets')}, ensure_ascii=False))
    check_exclusion_guard(summary, fail_on_excluded)
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('inventory', type=Path)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('--fail-on-excluded', action='store_true')
    args = parser.parse_args()
    trace(args.folder, args.inventory, args.baseline, args.fail_on_excluded)
