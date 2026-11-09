"""Classify TeaVM diagnostics by application roots and target API; no application rewriting."""
import argparse
import collections
import csv
import json
from pathlib import Path


def rows(path):
    with path.open(encoding='utf-8', newline='') as stream:
        yield from csv.DictReader(stream, delimiter='\t')


def family(text):
    rules = [
        ('OCR and native bindings', ('org.bytedeco.', 'com.sun.jna.')),
        ('AWT geometry', ('java.awt.geom.', 'java.awt.Point', 'java.awt.Rectangle', 'java.awt.Polygon', 'java.awt.Dimension', 'java.awt.Insets')),
        ('AWT fonts and text', ('java.awt.font.', 'java.awt.Font', 'java.awt.GraphicsEnvironment')),
        ('AWT pixels and drawing', ('java.awt.image.', 'java.awt.Graphics', 'java.awt.Color', 'java.awt.BasicStroke', 'java.awt.RenderingHints', 'java.awt.Shape')),
        ('Desktop UI', ('javax.swing.', 'java.awt.', 'java.beans.')),
        ('XML and JAXB', ('javax.xml.', 'org.w3c.', 'org.xml.sax.', 'com.sun.xml.')),
        ('Reflection and class loading', ('java.lang.reflect.', 'java.lang.Class.', 'java.lang.ClassLoader', 'java.lang.invoke.', 'sun.reflect.')),
        ('Threads and synchronization', ('java.util.concurrent.', 'java.lang.Thread', 'java.lang.Object.wait', 'java.lang.Object.notify')),
        ('Files and streams', ('java.nio.file.', 'java.io.', 'java.util.zip.', 'java.nio.channels.')),
        ('Network', ('java.net.', 'javax.net.')),
        ('Other Java runtime', ('java.', 'javax.', 'sun.', 'jdk.')),
    ]
    for name, prefixes in rules:
        if any(prefix in text for prefix in prefixes):
            return name
    return 'Other libraries or compiler diagnostics'


def summarize(folder):
    roots = list(rows(folder / 'roots.tsv'))
    masks = collections.defaultdict(int)
    for root in roots:
        masks[root['method']] |= {'ui': 1, 'engine': 2}.get(root['category'], 0)
    edges = collections.defaultdict(set)
    for edge in rows(folder / 'calls.tsv'):
        edges[edge['caller']].add(edge['callee'])
    queue = collections.deque(masks)
    pending = set(masks)
    while queue:
        caller = queue.popleft()
        pending.discard(caller)
        for callee in edges.get(caller, ()):
            mask = masks[callee] | masks[caller]
            if masks[callee] != mask:
                masks[callee] = mask
                if callee not in pending:
                    queue.append(callee)
                    pending.add(callee)
    diagnostics = list(rows(folder / 'diagnostics.tsv'))
    root_categories = {root['method']: root['category'] for root in roots}
    if (folder / 'root-plan.tsv').exists():
        # A non-root original method remains application code when reached transitively.
        root_categories.update({row['method']: row['category'] for row in rows(folder / 'root-plan.tsv')})
    decision_file = folder / 'class-decisions.json'
    decisions = {item['class']: item['decision'] for item in json.loads(decision_file.read_text(encoding='utf-8'))['classification']} if decision_file.exists() else {}
    origin_names = {0: 'unattributed', 1: 'ui-only', 2: 'engine-side', 3: 'shared'}
    by_origin, by_family, by_severity = collections.Counter(), collections.Counter(), collections.Counter()
    apis = {}
    for diagnostic in diagnostics:
        diagnostic['category'] = root_categories.get(diagnostic['caller'], 'dependency' if diagnostic['caller'] else 'unattributed')
        owner = diagnostic['caller'].split('(')[0].rsplit('.', 1)[0] if diagnostic['caller'] else ''
        diagnostic['classDecision'] = decisions.get(owner, 'external' if owner else 'unattributed')
        origin = origin_names[masks[diagnostic['caller']]]
        # A location with no registered call edge still has useful direct application provenance.
        if origin == 'unattributed' and diagnostic['category'] in ('ui', 'engine'):
            origin = 'ui-only' if diagnostic['category'] == 'ui' else 'engine-side'
        diagnostic['origin'] = origin
        diagnostic['family'] = family(diagnostic['parameters'] or diagnostic['message'])
        by_origin[origin] += 1
        by_family[diagnostic['family']] += 1
        by_severity[diagnostic['severity']] += 1
        key = (diagnostic['family'], diagnostic['message'])
        api = apis.setdefault(key, {'family': key[0], 'message': key[1], 'count': 0, 'origins': set(), 'examples': []})
        api['count'] += 1
        api['origins'].add(origin)
        example = (diagnostic['caller'] + ' @ ' + diagnostic['location']).strip() if diagnostic['caller'] else '(no call location)'
        if example not in api['examples'] and len(api['examples']) < 5:
            api['examples'].append(example)
    keys = list(diagnostics[0]) if diagnostics else ['origin', 'family']
    with (folder / 'diagnostics-classified.tsv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=keys, delimiter='\t')
        writer.writeheader()
        writer.writerows(diagnostics)
    unique = sorted(apis.values(), key=lambda api: (-api['count'], api['message']))
    for api in unique:
        api['origins'] = sorted(api['origins'])
    summary = {
        'roots': {'classes': len({r['class'] for r in roots}), 'methods': len(roots),
                  'methodsWithBody': sum(r['hasBody'] == 'true' for r in roots),
                  'nativeMethods': sum(r['native'] == 'true' for r in roots),
                  'usedMethods': sum(r['used'] == 'true' for r in roots),
                  'analyzedBodies': sum(r['hasBody'] == 'true' and r.get('analyzed') == 'true' for r in roots),
                  'byCategory': dict(collections.Counter(r['category'] for r in roots))},
        'diagnostics': len(diagnostics), 'uniqueMessages': len(unique),
        'bySeverity': dict(by_severity), 'byOrigin': dict(by_origin), 'byFamily': dict(by_family),
        'byDirectOwner': dict(collections.Counter(d['category'] for d in diagnostics)),
        'byClassDecision': dict(collections.Counter(d['classDecision'] for d in diagnostics)),
        'callGraph': {'callers': len(edges), 'edges': sum(map(len, edges.values()))},
        'apis': unique,
    }
    (folder / 'classified-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with (folder / 'apis.tsv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t')
        writer.writerow(['family', 'count', 'origins', 'message', 'examples'])
        for api in unique:
            writer.writerow([api['family'], api['count'], ','.join(api['origins']), api['message'], ' | '.join(api['examples'])])
    print(json.dumps({key: value for key, value in summary.items() if key != 'apis'}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    summarize(parser.parse_args().folder)
