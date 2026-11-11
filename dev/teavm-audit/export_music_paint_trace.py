"""Archive the native music-symbol outline experiment without claiming a portable renderer."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    source = mirror / 'build/wasm-compat/music-paint-trace/verification.json'
    report = json.loads(source.read_text())
    assert report['cases'] == 2280 and report['shapes'] == 19
    assert len(report['families']) == 6 and all(row['cases'] == 380 for row in report['families'].values())
    assert 0 <= report['differentCases'] <= report['cases']
    assert sum(row['differentCases'] for row in report['families'].values()) == report['differentCases']
    assert report['nativeGlyphVectorCalls'] >= report['cases']
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    names = ['app/src/main/java/org/audiveris/omr/ui/symbol',
             'app/src/main/java/org/audiveris/omr/glyph/Shape.java',
             'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/MusicPaintTrace.java',
             'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/GraphicsDelegate.java',
             'dev/teavm-audit/music-paint.init.gradle', 'dev/teavm-audit/export_music_paint_trace.py']
    names += ['app/res/' + name for name in report['fontSha256']]
    hashes = {}
    archive = subprocess.check_output(git + ['archive', revision] + names)
    with tarfile.open(fileobj=io.BytesIO(archive)) as files:
        for entry in files:
            if not entry.isfile():
                continue
            data = files.extractfile(entry).read()
            path = root / entry.name if entry.name == 'dev/teavm-audit/export_music_paint_trace.py' else mirror / entry.name
            actual = path.read_bytes()
            if entry.name.endswith(('.java', '.gradle', '.py', '.properties', '.uxf')):
                data = data.replace(b'\r\n', b'\n'); actual = actual.replace(b'\r\n', b'\n')
            assert actual == data, entry.name
            hashes[entry.name] = hashlib.sha256(data).hexdigest()
    for name, digest in report['fontSha256'].items():
        assert hashes['app/res/' + name] == digest
    group = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    limits = [(group / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert limits == ['800000 100000', '8589934592', '0']
    relative = 'wsl-artifacts/wasm-components/music-paint-trace'
    raw = root / relative; tracked = root / 'wasm-compat/music-paint-results'
    provenance = {'sourceCommit': revision, 'sourceSha256': hashes, 'rawArtifacts': relative,
                  'reportSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                  'cpuMax': limits[0], 'memoryMaxBytes': int(limits[1]), 'memorySwapMaxBytes': int(limits[2]),
                  'memoryEventsLocal': (group / 'memory.events.local').read_text().strip(),
                  'limitation': report['limitation']}
    for destination in [raw, tracked]:
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination / 'verification.json')
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['cases', 'differentCases', 'differentPixels']}))


if __name__ == '__main__':
    main()
