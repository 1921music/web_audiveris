"""Archive the physical-font diagnostic; do not claim a portable font backend."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    include_probe = '--probe' in sys.argv[4:]
    source = mirror / 'build/wasm-compat/text-font-metrics'
    probe = mirror / 'build/wasm-compat/fontbox-metrics-probe'
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report = json.loads((source / 'fontbox-experiment.json').read_text())
    fixture = json.loads((source / 'jvm-reference.json').read_text())
    assert len(report) == 6 and len(fixture['faces']) == 12
    assert all(row['cases'] == 384 and row['sweptTargets'] == 196608 for row in report)
    sources = ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/FontBoxMetricsExperiment.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/TextFontMetricsOracle.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/OcrTransportOracle.java',
        'app/src/main/java/org/audiveris/omr/ui/symbol/OmrFont.java', 'app/build.gradle', 'dev/teavm-audit/audit.init.gradle']
    if include_probe:
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/FontBoxMetricsProbe.java',
            'wasm-compat/tools/verify-fontbox-metrics.cjs', 'dev/teavm-audit/src/audit/CompileCompatibilityProbe.java',
            'wasm-compat/src/main/java/org/audiveris/wasm/compat/CompilerPatches.java',
            'wasm-compat/src/main/java/org/audiveris/wasm/compat/FontMetricsPatches.java',
            'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/FontMetricsRuntime.java',
            'wasm-compat/src/main/java/org/audiveris/wasm/compat/HeadlessPatches.java',
            'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/HeadlessLogging.java']
    hashes = {}
    for name in sources:
        data = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
        committed = subprocess.check_output(['git', '-c', f'safe.directory={root}', '-C', str(root), 'show', revision + ':' + name]).replace(b'\r\n', b'\n')
        assert data == committed, name
        hashes[name] = hashlib.sha256(data).hexdigest()
    limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    cpu, memory, swap = [(limits / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert (cpu, memory, swap) == ('800000 100000', '8589934592', '0')
    relative = 'wsl-artifacts/wasm-components/fontbox-metrics'
    raw = root / relative; tracked = root / 'wasm-compat/fontbox-metrics-results'
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    for name in ['fontbox-experiment.json', 'jvm-reference.json']:
        shutil.copyfile(source / name, raw / name)
    if include_probe:
        verification = json.loads((probe / 'verification.json').read_text())
        assert verification['nodeInstances'] == verification['workers'] == 2
        assert verification['cases'] == 384 and verification['checks'] == 1536 and verification['exactPhysicalMetrics']
        assert verification['referenceSha256'] == sha(source / 'jvm-reference.json')
        assert verification['wasmSha256'] == sha(probe / 'fontbox-metrics.wasm')
        assert (probe / 'diagnostics.tsv').read_text().splitlines() == ['severity\tmethod\tmessage']
        for name in ['verification.json', 'diagnostics.tsv', 'fontbox-metrics.wasm', 'fontbox-metrics.wasm-runtime.js']:
            shutil.copyfile(probe / name, raw / name)
        for name in ['verification.json', 'diagnostics.tsv']:
            shutil.copyfile(probe / name, tracked / name)
    (raw / 'fonts').mkdir(exist_ok=True)
    for face in fixture['faces']:
        font = source / 'fonts' / face['file']; assert sha(font) == face['sha256']
        shutil.copyfile(font, raw / 'fonts' / face['file'])
    shutil.copyfile('/usr/share/doc/fonts-liberation/copyright', raw / 'fonts/copyright')
    provenance = {'sourceCommit': revision, 'sourceSha256': hashes, 'rawArtifacts': relative,
        'cpuMax': cpu, 'memoryMaxBytes': int(memory), 'memorySwapMaxBytes': int(swap),
        'artifactSha256': {str(path.relative_to(raw)): sha(path) for path in sorted(raw.rglob('*')) if path.is_file() and path.name != 'provenance.json'},
        'limitation': verification['limitation'] if include_probe else 'JVM-only FontBox diagnostic; no Wasm size/speed or general font measurement equivalence is established'}
    shutil.copyfile(source / 'fontbox-experiment.json', tracked / 'comparison.json')
    for destination in [raw, tracked]:
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps([{key: row[key] for key in ['method', 'boundsDifferent', 'sweptSizesDifferent']} for row in report]))


if __name__ == '__main__':
    main()
