"""Archive the JVM/Worker physical-font diagnostic without claiming engine equivalence."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    source = mirror / 'build/wasm-compat/text-font-metrics'
    read = lambda path: json.loads(path.read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report = read(source / 'comparison.json')
    assert report['workers'] == 2 and report['workerResultsExact'] and report['cases'] == 384
    assert sha(source / 'jvm-reference.json') == report['referenceSha256']
    sources = list(report['sourceSha256']) + [
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/OcrTransportOracle.java',
        'dev/teavm-audit/audit.init.gradle']
    source_hashes = {}
    for name in sources:
        data = (mirror / name).read_bytes().replace(b'\r\n', b'\n')
        committed = subprocess.check_output(['git', '-c', f'safe.directory={root}', '-C', str(root), 'show', revision + ':' + name]).replace(b'\r\n', b'\n')
        assert data == committed, name
        source_hashes[name] = hashlib.sha256(data).hexdigest()
        if name in report['sourceSha256']:
            assert source_hashes[name] == report['sourceSha256'][name], name
    limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    cpu, memory, swap = [(limits / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert (cpu, memory, swap) == ('800000 100000', '8589934592', '0')
    relative = 'wsl-artifacts/wasm-components/text-font-metrics'
    raw = root / relative; tracked = root / 'wasm-compat/text-font-metrics-results'
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    for name in ['comparison.json', 'jvm-reference.json', 'worker-results.json']:
        shutil.copyfile(source / name, raw / name)
    (raw / 'fonts').mkdir(exist_ok=True)
    for face in report['faces']:
        font = source / 'fonts' / face['file']; assert sha(font) == face['sha256']
        shutil.copyfile(font, raw / 'fonts' / face['file'])
    license_path = Path('/usr/share/doc/fonts-liberation/copyright')
    shutil.copyfile(license_path, raw / 'fonts' / 'copyright')
    provenance = {'sourceCommit': revision, 'sourceSha256': source_hashes, 'rawArtifacts': relative,
                  'cpuMax': cpu, 'memoryMaxBytes': int(memory), 'memorySwapMaxBytes': int(swap),
                  'artifactSha256': {str(path.relative_to(raw)): sha(path) for path in sorted(raw.rglob('*')) if path.is_file() and path.name != 'provenance.json'},
                  'limitation': report['limitation']}
    shutil.copyfile(source / 'comparison.json', tracked / 'comparison.json')
    for destination in [raw, tracked]:
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['cases', 'differentBounds', 'differentSizes', 'maxSizeError']}))


if __name__ == '__main__':
    main()
