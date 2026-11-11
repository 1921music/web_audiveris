"""Inventory original class files and classify their roles in the agreed headless engine scope."""
import argparse
import collections
import csv
import json
import re
import struct
from pathlib import Path

PREFIX = 'org.audiveris.omr.'


def read_class(path):
    data = path.read_bytes()
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError(path)
    count = struct.unpack_from('>H', data, 8)[0]
    pool, cursor, index = [None] * count, 10, 1
    while index < count:
        tag = data[cursor]
        cursor += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, cursor)[0]
            cursor += 2
            pool[index] = ('utf8', data[cursor:cursor + length].decode('utf-8', errors='replace'))
            cursor += length
        elif tag == 7:
            pool[index] = ('class', struct.unpack_from('>H', data, cursor)[0])
            cursor += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            cursor += 4
        elif tag in (5, 6):
            cursor += 8
            index += 1
        elif tag in (8, 16, 19, 20):
            cursor += 2
        elif tag == 15:
            cursor += 3
        else:
            raise ValueError((path, index, tag))
        index += 1
    def name(item):
        return pool[pool[item][1]][1].replace('/', '.') if item else ''
    _, this, parent, interface_count = struct.unpack_from('>HHHH', data, cursor)
    interfaces = [name(struct.unpack_from('>H', data, cursor + 8 + 2 * i)[0]) for i in range(interface_count)]
    refs = set()
    for item in pool:
        if not item:
            continue
        if item[0] == 'class':
            value = pool[item[1]][1]
            if not value.startswith('['):
                refs.add(value.replace('/', '.'))
        elif item[0] == 'utf8':
            refs.update(x.replace('/', '.') for x in re.findall(r'L([\w/$]+)[;<]', item[1]))
    refs.discard(name(this))
    return {'class': name(this), 'parent': name(parent), 'interfaces': interfaces, 'references': sorted(refs),
            'classMajorVersion': struct.unpack_from('>H', data, 6)[0]}


EXCLUDE_TOP = {
    'Audiveris', 'org.audiveris.omr.Main', 'org.audiveris.omr.CLI', 'org.audiveris.omr.Debug',
    'org.audiveris.omr.DocCheck', 'org.audiveris.omr.IconCheck', 'org.audiveris.omr.SchemaGeneration',
    'org.audiveris.omr.RunPartCheck', 'org.audiveris.omr.RunSmallStaffCheck', 'org.audiveris.omr.RunTablatureCheck',
    PREFIX + 'ui.symbol.FontPrintOut', PREFIX + 'ui.symbol.SymbolRipper',
    PREFIX + 'image.ImageLoading', PREFIX + 'image.Ghostscript',
    PREFIX + 'classifier.Annotations', PREFIX + 'classifier.AnnotationsBuilder',
    PREFIX + 'classifier.Sample', PREFIX + 'classifier.SampleRepository', PREFIX + 'classifier.SampleSheet',
    PREFIX + 'classifier.SampleSource', PREFIX + 'classifier.SheetContainer', PREFIX + 'classifier.TrainingMonitor',
    PREFIX + 'classifier.Tribe', PREFIX + 'classifier.TribeList',
    PREFIX + 'sheet.PlayList', PREFIX + 'sheet.AliasPatterns',
    PREFIX + 'sheet.SheetDiff', PREFIX + 'run.RunsViewer',
    PREFIX + 'check.CheckBoard', PREFIX + 'check.CheckPanel', PREFIX + 'glyph.SymbolSample',
    PREFIX + 'step.TestStep', PREFIX + 'step.RunClass',
    PREFIX + 'util.WindowsRegistry', PREFIX + 'util.SheetCounter', PREFIX + 'util.ChartPlotter',
    PREFIX + 'util.PathHistory', PREFIX + 'util.SheetPathHistory', PREFIX + 'util.AbstractNameHistory',
    PREFIX + 'util.SheetPath', PREFIX + 'util.WeakPropertyChangeListener',
    PREFIX + 'util.PersistentStringSet', PREFIX + 'util.BasicTask', PREFIX + 'util.VoidTask',
    PREFIX + 'util.PathTask', PREFIX + 'util.PathListTask', PREFIX + 'util.InstancesWatcher',
    PREFIX + 'util.Dumper', PREFIX + 'util.Dumping', PREFIX + 'util.Memory',
}
BOUNDARY_TOP = {
    PREFIX + 'OMR', PREFIX + 'OmrEngine', PREFIX + 'WellKnowns',
    PREFIX + 'sheet.BookManager', PREFIX + 'sheet.DataHolder', PREFIX + 'sheet.ImageHolder', PREFIX + 'sheet.RunTableHolder',
    PREFIX + 'step.LoadStep', PREFIX + 'util.OmrExecutors', PREFIX + 'util.Concurrency',
    PREFIX + 'util.Jaxb', PREFIX + 'util.CustomXMLStreamWriter', PREFIX + 'util.Zip', PREFIX + 'util.ZipFileSystem',
    PREFIX + 'util.UriUtil', PREFIX + 'util.FileUtil', PREFIX + 'util.ClassUtil',
    PREFIX + 'util.Version', PREFIX + 'util.Clock',
    PREFIX + 'score.ScoreExporter', PREFIX + 'score.OpusExporter', PREFIX + 'score.Mxl',
}
MIXED_TOP = {
    PREFIX + 'sheet.Book', PREFIX + 'sheet.Sheet', PREFIX + 'sheet.SheetStub', PREFIX + 'sheet.Picture',
    PREFIX + 'classifier.BasicClassifier', PREFIX + 'classifier.AbstractClassifier', PREFIX + 'classifier.Classifier',
    PREFIX + 'math.NeuralNetwork', PREFIX + 'sheet.grid.GridBuilder',
}
PURE_UTIL = {
    'AbstractEntity', 'Arrangements', 'BasicIndex', 'BrokenLine', 'ByteUtil', 'Entities', 'Entity', 'EntityIndex',
    'HorizontalSide', 'IdUtil', 'IntUtil', 'LabeledEnum', 'NamedDouble', 'NaturalSpec', 'Navigable', 'Pair',
    'PairPredicate', 'RegexUtil', 'StopWatch', 'StringUtil', 'Table', 'TreeNode', 'Trimmable', 'VerticalSide',
    'Vip', 'Wrapper', 'WrappedBoolean', 'WrappedDouble', 'WrappedInteger', 'XmlUtil',
}


def is_component(name, inventory, seen=None):
    if name == 'java.awt.Component' or name.startswith('javax.swing.') or ('.ui.' in name and '.ui.symbol.' not in name):
        return True
    seen = set() if seen is None else seen
    if not name or name in seen:
        return False
    seen.add(name)
    if '$' in name and is_component(name.rsplit('$', 1)[0], inventory, seen):
        return True
    item = inventory.get(name)
    if not item:
        return name in ('java.awt.Canvas', 'java.awt.Panel', 'java.awt.Frame', 'java.awt.Window', 'java.awt.Dialog')
    return is_component(item['parent'], inventory, seen)


def decide(item, inventory, method_categories):
    name = item['class']
    top = name.split('$')[0]
    local = top.removeprefix(PREFIX)
    if name == PREFIX + 'sheet.PageCleaner$AwtRaster':
        return 'exclude', 'JVM-only Java2D page raster backend; the cleaner traversal and PageRaster API remain retained'
    if name in {PREFIX + 'text.TextFontGeometry$AwtProvider', PREFIX + 'text.TextFontGeometry$DefaultHolder'}:
        return 'exclude', 'JVM-only Java2D text-font default; the engine measurement facade and provider API remain retained'
    if top == 'org.audiveris.omrdataset.api.OmrShape':
        return 'keep', 'Symbol-category enum used directly by recognition inters/factories, not just dataset export'
    if top.startswith('org.audiveris.omrdataset.'):
        return 'exclude', 'Dataset annotation/export or standalone tablature-area report; no dataset output in initial engine scope'
    if top in EXCLUDE_TOP:
        return 'exclude', 'Explicit non-engine tool, training/sample management, GUI helper, or external image/PDF decoding'
    if '.ui.symbol.' in name:
        return 'boundary', 'Font/symbol implementation may supply recognition templates or measurements; retain required APIs, separate desktop rendering'
    if '.ui.' in name or is_component(name, inventory) or '$ChartListener' in name or top.endswith('.SpotsController'):
        return 'exclude', 'Desktop UI, component, view, dialog, event presentation or chart listener'
    if local.startswith(('plugin.', 'log.')):
        return 'exclude', 'Desktop external-program integration or logging UI/configuration; engine logging can use a separate sink'
    if top in BOUNDARY_TOP or local.startswith(('constant.', 'text.tesseract.')):
        return 'boundary', 'Host environment, OCR/native binding, configuration, execution, resource or serialization boundary'
    if top in MIXED_TOP:
        return 'mixed', 'Recognition data/inference/control is needed; file/UI/training/desktop responsibilities are mixed in the same class'
    if top.endswith('.package-info') or top.endswith('.ProgramId'):
        return 'review', 'Metadata/generated build identity; decide with resource/XML and engine startup requirements'
    if local.startswith('util.param.'):
        return 'keep', 'Recognition parameter values and inheritance; host persistence is separate'
    if local.startswith('util.'):
        if local.split('.')[-1] not in PURE_UTIL:
            return 'review', 'Utility outside established pure-data list; review actual selected engine call sites'
    elif not local.startswith(('check.', 'classifier.', 'glyph.', 'image.', 'lag.', 'math.', 'moments.', 'run.',
                               'score.', 'sheet.', 'sig.', 'step.', 'text.')):
        return 'review', 'No sufficiently specific role rule yet'
    categories = method_categories.get(name, collections.Counter())
    if categories['ui']:
        return 'mixed', 'Recognition/data class also declares rendering or GUI methods; keep engine behavior, do not root UI methods'
    return 'keep', 'Recognition algorithms, music/geometry/image data, features, graph relations, or MusicXML construction'


def classify(classes, sources, audit):
    inventory = {}
    for path in sorted(classes.rglob('*.class')):
        item = read_class(path)
        inventory[item['class']] = item
    with (audit / 'roots.tsv').open(encoding='utf-8', newline='') as stream:
        roots = list(csv.DictReader(stream, delimiter='\t'))
    method_categories = collections.defaultdict(collections.Counter)
    for root in roots:
        method_categories[root['class']][root['category']] += 1
    for name, item in inventory.items():
        decision, reason = decide(item, inventory, method_categories)
        source_name = name.split('$')[0].replace('.', '/') + '.java'
        source = sources / source_name
        text = source.read_text(encoding='utf-8') if source.exists() else ''
        doc = re.search(r'/\*\*\s*\n\s*\*\s*(?:Class|Interface|Enum)\s+.*?(?:\n\s*\*\s*<p>|\*/)', text, re.S)
        description = re.sub(r'<[^>]+>|\s*\n\s*\*\s*', ' ', doc.group(0)).strip('/* \n')[:400] if doc else ''
        item.update(decision=decision, reason=reason, source=source_name if source.exists() else '',
                    sourceDescription=description, methodCategories=dict(method_categories[name]))
    inbound = collections.defaultdict(list)
    for name, item in inventory.items():
        for target in item['references']:
            if target in inventory:
                inbound[target].append(name)
    for name, item in inventory.items():
        item['incomingFromKept'] = sorted(s for s in inbound[name] if inventory[s]['decision'] in ('keep', 'mixed'))
    counts = collections.Counter(item['decision'] for item in inventory.values())
    methods = {r['class']: True for r in roots}
    assert len(inventory) >= len(methods) and set(methods) <= set(inventory)
    report = {'scope': 'GRAY8 input to recognition and MusicXML, external OCR, no desktop UI/training/.omr persistence',
              'classes': len(inventory), 'topLevelClasses': len({c.split('$')[0] for c in inventory}),
              'byDecision': dict(counts),
              'topLevelByDecision': dict(collections.Counter(item['decision'] for name, item in inventory.items() if '$' not in name)),
              'classification': list(inventory.values())}
    (audit / 'class-decisions.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with (audit / 'class-decisions.tsv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t')
        writer.writerow(['decision', 'class', 'source', 'reason', 'parent', 'methodCategories', 'incomingFromKept', 'sourceDescription'])
        for name, item in sorted(inventory.items()):
            writer.writerow([item['decision'], name, item['source'], item['reason'], item['parent'],
                             json.dumps(item['methodCategories']), ';'.join(item['incomingFromKept']), item['sourceDescription']])
    crossing = []
    with (audit / 'kept-to-excluded.tsv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t')
        writer.writerow(['caller', 'excludedReference', 'callerDecision', 'excludedReason'])
        for name, item in sorted(inventory.items()):
            if item['decision'] not in ('keep', 'mixed'):
                continue
            for target in item['references']:
                if target in inventory and inventory[target]['decision'] == 'exclude':
                    writer.writerow([name, target, item['decision'], inventory[target]['reason']])
                    crossing.append((name, target))
    print(json.dumps({'classes': report['classes'], 'topLevelClasses': report['topLevelClasses'],
                      'byDecision': report['byDecision'], 'keptToExcludedReferences': len(crossing)}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('classes', type=Path)
    parser.add_argument('sources', type=Path)
    parser.add_argument('audit', type=Path)
    args = parser.parse_args()
    classify(args.classes, args.sources, args.audit)
