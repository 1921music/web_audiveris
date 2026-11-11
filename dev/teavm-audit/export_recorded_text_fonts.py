"""Archive actual TEXTS text-font queries reproduced from physical font files."""
import collections
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    source = mirror / 'build/wasm-compat/recorded-text-fonts'
    probe = mirror / 'build/wasm-compat/fontbox-metrics-probe'
    geometry = mirror / 'build/wasm-compat/texts-continuation-probe/live-ocr/jvm/font-measurements.json'
    read = lambda path: json.loads(path.read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report, comparison, fixture, bindings = [read(path) for path in [probe / 'recorded-verification.json', source / 'comparison.json', source / 'jvm-reference.json', source / 'bindings.json']]
    assert report['nodeInstances'] == report['workers'] == 2 and report['cases'] == 517 and report['checks'] == 2068
    assert report['syntheticItalicCases'] == comparison['syntheticItalicQueries'] == 88
    assert comparison['eligibleQueries'] == comparison['checkedQueries'] == 517
    assert comparison['differentQueries'] == comparison['maxDifference'] == 0 and not comparison['unsupported']
    assert report['referenceSha256'] == sha(source / 'jvm-reference.json')
    assert report['wasmSha256'] == sha(probe / 'fontbox-metrics.wasm')
    assert (probe / 'diagnostics.tsv').read_text().splitlines() == ['severity\tmethod\tmessage']
    inputs = {(row['kind'], row['flags'], row['size'], row['value']): row for row in read(geometry) if row['kind'] <= 1}
    assert len(inputs) == len(bindings) == 517
    key = lambda row: (row['face'], row['size'], row.get('text', row.get('value')), row['syntheticItalic'])
    assert collections.Counter(map(key, bindings)) == collections.Counter(map(key, fixture['measurements']))
    cases = collections.defaultdict(list)
    for row in fixture['measurements']:
        cases[key(row)].append(row)
    for binding in bindings:
        entry = inputs[(binding['kind'], binding['flags'], binding['size'], binding['value'])]
        assert any(all(case['bounds'][field] == entry[field] for field in ['x', 'y', 'width', 'height'])
                   and (binding['kind'] == 0 or case['advance'] == entry['advance']) for case in cases[key(binding)])
    sources = ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/' + name + '.java' for name in [
        'RecordedTextFontsOracle', 'QuadraticGlyphBounds', 'FontBoxMetricsExperiment', 'FontBoxMetricsProbe', 'TextFontMetricsOracle']]
    sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/' + name + '.java' for name in ['CompilerPatches', 'FontMetricsPatches', 'HeadlessPatches']]
    sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/' + name + '.java' for name in ['FontMetricsRuntime', 'HeadlessLogging']]
    sources += ['app/src/main/java/org/audiveris/omr/' + name + '.java' for name in ['text/FontAttributes', 'text/FontInfo', 'ui/symbol/OmrFont', 'ui/symbol/TextFont']]
    sources += ['app/build.gradle', 'wasm-compat/tools/verify-fontbox-metrics.cjs', 'dev/teavm-audit/audit.init.gradle']
    hashes = {}
    for name in sources:
        data = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
        committed = subprocess.check_output(['git', '-c', f'safe.directory={root}', '-C', str(root), 'show', revision + ':' + name]).replace(b'\r\n', b'\n')
        assert data == committed, name
        hashes[name] = hashlib.sha256(data).hexdigest()
    limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    cpu, memory, swap = [(limits / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert (cpu, memory, swap) == ('800000 100000', '8589934592', '0')
    relative = 'wsl-artifacts/wasm-components/recorded-text-fonts'
    raw = root / relative; tracked = root / 'wasm-compat/recorded-text-font-results'
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    for name in ['comparison.json', 'jvm-reference.json', 'bindings.json']:
        shutil.copyfile(source / name, raw / name)
    shutil.copyfile(geometry, raw / 'recorded-geometry.json')
    for name in ['recorded-verification.json', 'diagnostics.tsv', 'fontbox-metrics.wasm', 'fontbox-metrics.wasm-runtime.js']:
        shutil.copyfile(probe / name, raw / name)
    (raw / 'fonts').mkdir(exist_ok=True)
    for face in fixture['faces']:
        path = source / 'fonts' / face['file']; assert sha(path) == face['sha256']
        shutil.copyfile(path, raw / 'fonts' / face['file'])
    shutil.copyfile('/usr/share/doc/fonts-dejavu-core/copyright', raw / 'fonts/copyright')
    provenance = {'sourceCommit': revision, 'sourceSha256': hashes, 'rawArtifacts': relative,
        'cpuMax': cpu, 'memoryMaxBytes': int(memory), 'memorySwapMaxBytes': int(swap),
        'memoryEventsLocal': (limits / 'memory.events.local').read_text().strip(),
        'artifactSha256': {str(path.relative_to(raw)): sha(path) for path in sorted(raw.rglob('*')) if path.is_file() and path.name != 'provenance.json'},
        'limitation': 'Exact recorded normal-text queries from four physical TTF files, including synthetic italic; no full TEXTS integration, music-font measurement or raster cleanup is established by this component'}
    for origin, name in [(source / 'comparison.json', 'comparison.json'), (probe / 'recorded-verification.json', 'verification.json'), (probe / 'diagnostics.tsv', 'diagnostics.tsv')]:
        shutil.copyfile(origin, tracked / name)
    for destination in [raw, tracked]:
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['cases', 'checks', 'syntheticItalicCases', 'wasmBytes']}))


if __name__ == '__main__':
    main()
