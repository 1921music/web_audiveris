"""Archive a small, independently verified Wasm component and its compiled-source revision."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('repository', type=Path)
    parser.add_argument('mirror', type=Path)
    parser.add_argument('name')
    parser.add_argument('revision')
    parser.add_argument('--source', action='append', required=True)
    args = parser.parse_args()
    root, mirror, name = args.repository, args.mirror, args.name
    if not name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in name):
        raise ValueError('Invalid component name')
    source = mirror / 'build/wasm-compat' / (name + '-probe')
    raw_relative = 'wsl-artifacts/wasm-components/' + name
    raw = root / raw_relative
    tracked = root / 'wasm-compat' / (name + '-results')
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    report = json.loads((source / 'verification.json').read_text())
    if report['wasmSha256'] != sha(source / (name + '.wasm')):
        raise AssertionError('Verified binary changed')
    if (source / 'diagnostics.tsv').read_text().splitlines() != ['severity\tmethod\tmessage']:
        raise AssertionError('Compilation diagnostics remain')
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    sources = {}
    for relative in args.source:
        path = Path(relative)
        if path.is_absolute() or '..' in path.parts:
            raise ValueError('Invalid relative source path')
        compiled = (mirror / path).read_bytes().replace(b'\r\n', b'\n')
        committed = subprocess.check_output(git + ['show', args.revision + ':' + relative]).replace(b'\r\n', b'\n')
        if compiled != committed:
            raise AssertionError('Compiled source does not match revision: ' + relative)
        sources[relative] = hashlib.sha256(compiled).hexdigest()
    limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    cpu, memory, swap = [(limits / key).read_text().strip() for key in ['cpu.max', 'memory.max', 'memory.swap.max']]
    if cpu != '800000 100000' or memory != '8589934592' or swap != '0':
        raise AssertionError('Unexpected resource limits')
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    files = [name + '.wasm', name + '.wasm-runtime.js', 'verification.json', 'diagnostics.tsv', 'jvm-reference.txt']
    for file in files:
        shutil.copyfile(source / file, raw / file)
    provenance = {'sourceCommit': args.revision, 'sourceSha256': sources, 'rawArtifacts': raw_relative,
        'cpuMax': cpu, 'allowedCpus': (limits / 'cpuset.cpus.effective').read_text().strip(),
        'memoryMaxBytes': int(memory), 'memorySwapMaxBytes': int(swap),
        'memoryEventsLocal': (limits / 'memory.events.local').read_text().strip(),
        'artifactSha256': {file: sha(raw / file) for file in files}, 'limitation': report['limitation']}
    for destination in [tracked, raw]:
        for file in ['verification.json', 'diagnostics.tsv', 'jvm-reference.txt']:
            if destination != raw:
                shutil.copyfile(source / file, destination / file)
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
