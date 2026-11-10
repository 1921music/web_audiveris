"""Archive the pinned JAXB/original-merge reference and Worker drum configuration checks."""
import csv
import hashlib
import json
import shutil
import subprocess


def export_drum(root, build, artifacts, raw_path, provenance):
    source = build / 'wasm-compat/drum-probe'
    raw = artifacts / 'drum-probe'
    results = root / 'wasm-compat/drum-results'
    raw.mkdir(parents=True, exist_ok=True); results.mkdir(parents=True, exist_ok=True)
    read = lambda path: json.loads(path.read_text(encoding='utf-8'))
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    def write(path, data):
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    if read(source / 'jvm-original.json') != read(source / 'jvm-portable.json'):
        raise AssertionError('Pinned/current JVM drum references differ')
    verification = read(source / 'verification.json')
    if not all(verification[key] for key in ['originalAndPortableJvmExact', 'defaultAndOverlayMapsExact',
                                           'copiedOverlay', 'freezeAfterFirstUse', 'workerIsolation']):
        raise AssertionError('Incomplete drum configuration verification')
    for name in ['drum.wasm', 'drum.wasm-runtime.js', 'diagnostics.tsv', 'verification.json',
                 'jvm-original.json', 'jvm-portable.json']:
        shutil.copyfile(source / name, raw / name)
    for name in ['diagnostics.tsv', 'verification.json']:
        shutil.copyfile(source / name, results / name)
    original = build / 'wasm-compat/upstream-drum'
    shutil.copytree(original, artifacts / 'upstream-drum', dirs_exist_ok=True)
    reference = read(original / 'source.json')
    reference['classSha256'] = {str(p.relative_to(original / 'classes')): sha(p)
                               for p in sorted((original / 'classes').rglob('*.class'))}
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    relative = 'app/src/main/java/org/audiveris/omr/score/DrumSet.java'
    baseline = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + relative]).decode().replace('\r\n', '\n')
    current = (root / relative).read_text(encoding='utf-8')
    def body(text, marker):
        start = text.index(marker); end = text.index('{', start) + 1; depth = 1
        while depth:
            depth += (text[end] == '{') - (text[end] == '}'); end += 1
        return text[start:end]
    old = '''        if (jaxbContext == null) {
            jaxbContext = JAXBContext.newInstance(DrumSetEntries.class);
        }

        final Unmarshaller um = jaxbContext.createUnmarshaller();
        final DrumSetEntries entries = (DrumSetEntries) um.unmarshal(in);'''
    if body(baseline, 'private void loadConfiguration (').replace(old, '        final DrumSetEntries entries = readEntries(in);') != body(current, 'private void loadConfiguration ('):
        raise AssertionError('Drum merging changed beyond XML acquisition')
    old = '''                final URL url = uri.toURL();
                try (InputStream input = url.openStream()) {'''
    if body(baseline, 'private void loadAllConfigurations (').replace(old, '                try (InputStream input = openConfiguration(uri)) {') != body(current, 'private void loadAllConfigurations ('):
        raise AssertionError('Drum loading changed beyond transport acquisition')
    unchanged = ['public Map<Integer, Map<MotifSign, DrumInstrument>> getStaffSet (',
                 'public void dumpResultingDrumSet (', 'private DrumSet (',
                 'static class MotifAdapter', 'static class PitchAdapter', 'static class SoundAdapter',
                 'public static class MotifSign', 'public static class DrumInstrument']
    reference['unchangedBodies'] = {}
    for marker in unchanged:
        first, second = body(baseline, marker), body(current, marker)
        if first != second:
            raise AssertionError('Unexpected drum algorithm change: ' + marker)
        reference['unchangedBodies'][marker] = hashlib.sha256(first.encode()).hexdigest()
    reference['sourceSeams'] = 'XML/URI acquisition extracted; XML DTO/adapters accessible to the backend. Original ordering, additions, replacement and removal logic unchanged.'
    resource = 'app/res/drum-set.xml'
    baseline_xml = subprocess.check_output(git + ['show', provenance['applicationBaseline'] + ':' + resource]).replace(b'\r\n', b'\n')
    if baseline_xml != (root / resource).read_bytes().replace(b'\r\n', b'\n'):
        raise AssertionError('Default drum definitions changed')
    reference['defaultResource'] = {'path': resource, 'normalizedSha256': hashlib.sha256(baseline_xml).hexdigest()}
    evidence = {key: provenance[key] for key in ['applicationBaseline', 'toolCommit', 'teaVM', 'target',
        'cpuMax', 'allowedCpus', 'memoryMaxBytes', 'memorySwapMaxBytes', 'memoryEventsLocal',
        'sourceSha256', 'applicationSourceChanges']}
    evidence.update({'rawArtifacts': raw_path + '/drum-probe',
                     'artifactSha256': {p.name: sha(p) for p in raw.iterdir() if p.is_file()},
                     'limitation': verification['limitation']})
    for target in [results, raw]:
        write(target / 'provenance.json', evidence)
        write(target / 'reference-sources.json', reference)
    heads = build / 'wasm-compat/heads-baseline-probe'
    if (heads / 'diagnostics.tsv').exists():
        target = raw / 'heads-after-drum'; target.mkdir(exist_ok=True)
        for name in ['diagnostics.tsv', 'dependency-paths.tsv']:
            shutil.copyfile(heads / name, target / name)
        with (heads / 'diagnostics.tsv').open(encoding='utf-8', newline='') as stream:
            diagnostics = list(csv.DictReader(stream, delimiter='\t'))
        write(results / 'heads-after-drum.json', {'diagnostics': len(diagnostics), 'strictCompileSucceeded': False,
            'rawArtifacts': raw_path + '/drum-probe/heads-after-drum',
            'scope': 'Unchanged HEADS recognition after drum decoding/transport substitution',
            'toolCommit': provenance['toolCommit'],
            'files': {p.name: sha(p) for p in target.iterdir() if p.is_file()}})
    provenance['drumVerification'] = verification
