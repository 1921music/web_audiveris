"""Archive font-derived catalogs and exact native/Worker template matching evidence."""
import csv
import hashlib
import json
import shutil
import subprocess


def export_template(root, build, artifacts, raw_path, provenance):
    source = build / 'wasm-compat/template-probe'
    original = build / 'wasm-compat/template-original-probe'
    portable = build / 'wasm-compat/template-portable-probe'
    raw = artifacts / 'template-probe'
    results = root / 'wasm-compat/template-results'
    raw.mkdir(parents=True, exist_ok=True); results.mkdir(parents=True, exist_ok=True)
    read = lambda p: json.loads(p.read_text(encoding='utf-8'))
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    def write(path, data):
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    reference = read(original / 'reference.json')
    if reference != read(portable / 'reference.json'):
        raise AssertionError('Pinned/current native templates differ')
    verification = read(source / 'verification.json')
    for key in ['templateDataExact', 'matchingAndForegroundPixelsExact', 'immutableRecords',
                'atomicInstallation', 'parameterMismatchRejected', 'explicitSourceInjection']:
        if not verification[key]:
            raise AssertionError('Incomplete template verification: ' + key)
    for name in ['templates.wasm', 'templates.wasm-runtime.js', 'verification.json', 'diagnostics.tsv']:
        shutil.copyfile(source / name, raw / name)
    for name in ['verification.json', 'diagnostics.tsv']:
        shutil.copyfile(source / name, results / name)
    asset_root = raw / 'catalogs'; asset_root.mkdir(exist_ok=True)
    for row in reference['catalogs']:
        for extension in ['atpl', 'match']:
            name = row['file'] + '.' + extension
            if (original / name).read_bytes() != (portable / name).read_bytes():
                raise AssertionError('Native catalog/matching differs: ' + name)
            shutil.copyfile(original / name, asset_root / name)
    for target in [results, raw]:
        write(target / 'catalogs.json', reference)
    pinned = build / 'wasm-compat/upstream-template'
    shutil.copytree(pinned, artifacts / 'upstream-template', dirs_exist_ok=True)
    evidence = read(pinned / 'source.json')
    evidence['classSha256'] = {str(p.relative_to(pinned / 'classes')): sha(p)
                              for p in sorted((pinned / 'classes').rglob('*.class'))}
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    prefix = 'app/src/main/java/org/audiveris/omr/'
    def baseline(name):
        return subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + prefix + name]).decode().replace('\r\n', '\n')
    def current(name):
        return (root / (prefix + name)).read_text(encoding='utf-8')
    def body(text, marker):
        start = text.index(marker); end = text.index('{', start) + 1; depth = 1
        while depth:
            depth += (text[end] == '{') - (text[end] == '}'); end += 1
        return text[start:end]
    first, second = baseline('image/TemplateFactory.java'), current('image/TemplateFactory.java')
    start = second.index('    /** Font-derived template data,')
    end = second.index('    /** All shapes with hole(s). */', start)
    stripped = (second[:start] + second[end:]).replace(
        'return source().keyPoints(shape, family, pointSize);',
        'return new Builder(shape, family, pointSize).processSymbol(1);').replace(
        'templates.put(shape, source().build(shape, family, pointSize));',
        'templates.put(shape, new Builder(shape, family, pointSize).buildTemplate());')
    if stripped != first:
        raise AssertionError('TemplateFactory changed beyond the added source boundary and two delegates')
    evidence['unchangedFactoryBodies'] = {}
    for marker in ['private static class Builder', 'private static void addAnchors (',
                   'public static double maxDistanceFromSymbol (', 'private static int maxRawDistanceFromSymbol (']:
        before, after = body(first, marker), body(second, marker)
        if before != after:
            raise AssertionError('Native template generation changed: ' + marker)
        evidence['unchangedFactoryBodies'][marker] = hashlib.sha256(before.encode()).hexdigest()
    before = body(first, 'public class Catalog').replace('new Builder(shape, family, pointSize).buildTemplate()', 'source().build(shape, family, pointSize)')
    if before != body(second, 'public class Catalog'):
        raise AssertionError('Catalog changed beyond template acquisition')
    before = body(first, 'public static List<PixelDistance> retrieveKeyPoints (').replace('new Builder(shape, family, pointSize).processSymbol(1)', 'source().keyPoints(shape, family, pointSize)')
    if before != body(second, 'public static List<PixelDistance> retrieveKeyPoints ('):
        raise AssertionError('Lazy key points changed beyond source delegation')
    if baseline('image/Template.java') != current('image/Template.java'):
        raise AssertionError('Original template matching source changed')
    evidence['unchangedTemplateInputs'] = {}
    for name in ['glyph/ShapeSet.java', 'ui/symbol/TemplateSymbol.java', 'image/PixelDistance.java', 'image/Anchored.java']:
        if baseline(name) != current(name):
            raise AssertionError('Template input definitions changed: ' + name)
        evidence['unchangedTemplateInputs'][name] = hashlib.sha256(baseline(name).encode()).hexdigest()
    old = 'MusicFont.getHeadPointSize(system.getSheet().getScale(), specificInterline)'
    new = 'org.audiveris.omr.ui.symbol.FontFamilyDefaults.headPointSize(system.getSheet().getScale(), specificInterline)'
    if baseline('sheet/Staff.java').replace(old, new) != current('sheet/Staff.java'):
        raise AssertionError('Staff changed beyond head point-size acquisition')
    name = 'ui/symbol/MusicFont.java'
    if body(baseline(name), 'public static int getHeadPointSize (') != body(current(name), 'public static int getHeadPointSize ('):
        raise AssertionError('Native head point-size reference changed')
    evidence['sourceSeams'] = 'Template data source and scalar font-size acquisition only; Template matching and native Builder source unchanged. One fresh-binary dilation call is scoped in the compiler.'
    evidence['fontResources'] = {}
    for path in sorted((root / 'app/res').iterdir()):
        if path.suffix not in ['.otf', '.ttf']:
            continue
        relative = str(path.relative_to(root)).replace('\\', '/')
        original_bytes = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + relative])
        if path.read_bytes() != original_bytes:
            raise AssertionError('Font resource changed: ' + relative)
        evidence['fontResources'][relative] = sha(path)
    metadata = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
        'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal',
        'sourceSha256', 'applicationSourceChanges']}
    metadata.update({'rawArtifacts': raw_path + '/template-probe',
        'artifactSha256': {p.name: sha(p) for p in raw.iterdir() if p.is_file()},
        'limitation': verification['limitation']})
    heads = build / 'wasm-compat/heads-baseline-probe'
    with (heads / 'diagnostics.tsv').open(encoding='utf-8', newline='') as stream:
        diagnostics = list(csv.DictReader(stream, delimiter='\t'))
    if diagnostics or not (heads / 'heads.wasm').exists():
        raise AssertionError('HEADS strict compile has not passed')
    compiled = raw / 'heads-compiled'; compiled.mkdir(exist_ok=True)
    for name in ['heads.wasm', 'heads.wasm-runtime.js', 'diagnostics.tsv']:
        shutil.copyfile(heads / name, compiled / name)
    for target in [results, raw]:
        write(target / 'reference-sources.json', evidence)
        write(target / 'provenance.json', metadata)
        write(target / 'heads-compile.json', {'diagnostics': 0, 'strictCompileSucceeded': True,
            'wasmBytes': (compiled / 'heads.wasm').stat().st_size, 'wasmSha256': sha(compiled / 'heads.wasm'),
            'runtimeRecognitionVerified': False, 'rawArtifacts': raw_path + '/template-probe/heads-compiled'})
    provenance['templateVerification'] = verification
