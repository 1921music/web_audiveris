"""Archive logical music-symbol rendering against independent native pixel hashes."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
from zipfile import ZipFile


def main():
    root, mirror = map(Path, sys.argv[1:3]); revision = sys.argv[3]
    source = mirror / 'build/wasm-compat/music-symbols-wasm'
    engine = mirror / 'build/wasm-compat/music-symbol-probe'
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    read = lambda p: json.loads(p.read_text())
    report, build, inventory = [read(source / n) for n in ['verification.json', 'build.json', 'inventory.json']]
    assert report['nodeInstances'] == report['workers'] == 2 and report['nativePixelHashesExact']
    assert report['originalPackedRasterInWasm'] and report['suppliedGlyphIds'] == report['suppliedGlyphPositions'] == 0
    assert report['recipes'] == inventory['recipes'] == len(read(source / 'recipes.json'))
    assert report['cases'] == inventory['cases'] == report['recipes'] * len(inventory['pointSizes']) * 4
    assert report['pixelsPerInstance'] == report['cases'] * inventory['width'] * inventory['height']
    assert report['unsupportedDefinitions'] == len(inventory['unsupported']) and report['evictionChecks'] == report['sharedFaceMetricRecoveryChecks'] == 20
    assert report['pointSizes'] == [1, 12, 19, 24, 37, 48, 88, 100, 101, 128]
    from collections import Counter
    routes = {
        'BeamSymbol': 'AbstractBeamInter: area fill', 'BeamHookSymbol': 'AbstractBeamInter: area fill',
        'SmallBeamSymbol': 'AbstractBeamInter: area fill', 'BraceSymbol': 'BraceInter: existing glyph',
        'EndingSymbol': 'EndingInter: line and legs', 'SlurSymbol': 'SlurInter: area fill',
        'LedgerSymbol': 'LedgerInter: line drawing', 'FlagsSymbol': 'AbstractFlagInter: existing glyph',
        'FlagsDownSymbol': 'AbstractFlagInter: existing glyph', 'SlashedFlagSymbol': 'AbstractFlagInter: existing glyph',
        'KeyFlatSymbol': 'KeyInter/KeyAlterInter: existing glyphs', 'KeySharpSymbol': 'KeyInter/KeyAlterInter: existing glyphs',
        'KeyCancelSymbol': 'KeyInter/KeyAlterInter: existing glyphs', 'NumDenSymbol': 'TimeWhole/TimePair/TimeCustomInter: glyph/member visitors',
        'MetronomeSymbol': 'Composite UI icon; BeatUnitInter uses its existing glyph',
        'NumberSymbol': 'UI prototype; AbstractNumberInter uses its existing glyph',
        'NonDraggableSymbol': 'UI marker', 'No symbol': 'No definition; unsupported requests are rejected'}
    routing = {'scope': 'Only PageCleaner.symbol calls; this is not an engine-wide class exclusion list',
        'source': 'app/src/main/java/org/audiveris/omr/sheet/PageCleaner.java',
        'remaining': [{'definitionClassOrReason': reason, 'definitions': count, 'cleanupRoute': routes.get(reason, 'Needs review; do not exclude')}
                      for reason, count in sorted(Counter(inventory['unsupported'].values()).items())]}
    assert sha(source / 'music-symbols.wasm') == build['wasmSha256'] == report['fontWasmSha256']
    assert sha(source / 'jvm-reference.json') == build['nativeReferenceSha256'] == report['nativeReferenceSha256']
    assert sha(source / 'recipes.json') == build['recipesSha256'] == report['recipesSha256']
    assert sha(engine / 'music-symbol.wasm') == report['engineWasmSha256']
    assert (engine / 'diagnostics.tsv').read_text().splitlines() == ['severity\tmethod\tmessage']
    for row in report['nodeRows'] + report['workerRows']:
        assert row['calls'] == report['cases'] and row['guards'] == 8
        assert 0 < row['marlinBufferBytes'] <= 64 * 1024 * 1024
        stats = row['stats']; assert stats['cachedMasks'] <= stats['cacheLimit'] == 4096
        assert stats['cachedMaskBytes'] <= stats['maskCacheBudgetBytes'] == 16 * 1024 * 1024
    names = ['app/src/main/java/org/audiveris/omr/ui/symbol',
             'app/src/main/java/org/audiveris/omr/glyph/Shape.java',
             'app/src/main/java/org/audiveris/omr/sheet/PageCleaner.java',
             'app/src/main/java/org/audiveris/omr/image/PageRaster.java',
             'wasm-compat/src/main', 'wasm-compat/src/jdk', 'dev/teavm-audit/src',
             'wasm-compat/resources.json','wasm-compat/tools/generate-resources.py','wasm-compat/tools/generate-music-data.py',
             'dev/teavm-audit/audit.init.gradle', 'dev/teavm-audit/music-paint.init.gradle',
             'dev/teavm-audit/export_music_symbols.py', 'wasm-compat/tools/verify-music-symbols.cjs',
             'wasm-compat/host/freetype-music-symbols.mjs', 'wasm-compat/host/freetype-music-metrics.mjs',
             'wasm-compat/third-party/openjdk', 'wasm-compat/third-party/freetype',
             'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/MusicSymbolOracle.java',
             'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/MusicSymbolProbe.java']
    names += ['dev/teavm-audit/' + n for n in build['sourceSha256']]
    names += ['app/res/' + n for n in inventory['musicFontSha256']]
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    archive = subprocess.check_output(git + ['archive', revision] + names); hashes = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as files:
        for entry in files:
            if not entry.isfile(): continue
            committed = files.extractfile(entry).read()
            directory = root if entry.name == 'dev/teavm-audit/export_music_symbols.py' else mirror
            actual = (directory / entry.name).read_bytes()
            if not entry.name.endswith(('.ttf', '.otf', '.png')):
                actual = actual.replace(b'\r\n', b'\n'); committed = committed.replace(b'\r\n', b'\n')
            assert actual == committed, entry.name
            hashes[entry.name] = hashlib.sha256(committed).hexdigest()
    for name, digest in build['sourceSha256'].items(): assert hashes['dev/teavm-audit/' + name] == digest
    for name, key in [('freetype-music-symbols.mjs', 'hostSha256'), ('freetype-music-metrics.mjs', 'metricsHostSha256')]:
        assert hashes['wasm-compat/host/' + name] == report[key]
    assert hashes['wasm-compat/tools/verify-music-symbols.cjs'] == report['verifierSha256']
    resources=read(source / 'font-resources.json')
    assert sha(source / 'font-resources.json') == report['fontResourcesSha256'] and report['missingTextRoleRejected']
    for name, digest in inventory['fontSha256'].items():
        assert sha(source / 'fonts' / name) == report['fontSha256'][name] == digest
        if name in inventory['musicFontSha256']: assert hashes['app/res/' + name] == digest
        else: assert resources['fonts'][name]['sha256'] == digest
    cg = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    limits = [(cg / n).read_text().strip() for n in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert limits == ['800000 100000', '8589934592', '0']
    relative = 'wsl-artifacts/wasm-components/music-symbols'; raw = root / relative
    tracked = root / 'wasm-compat/music-symbol-results'; tracked.mkdir(parents=True, exist_ok=True)
    def copy(origin, name):
        target = raw / name; target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(origin, target)
    for folder, prefix in [(source, 'music-symbols-wasm'), (engine, 'music-symbol-probe')]:
        for path in folder.rglob('*'):
            if path.is_file(): copy(path, str(Path(prefix) / path.relative_to(folder)))
    for name in ['freetype-music-symbols.mjs', 'freetype-music-metrics.mjs']:
        copy(mirror / 'wasm-compat/host' / name, 'host/' + name)
    copy(mirror / 'wasm-compat/tools/verify-music-symbols.cjs', 'tools/verify-music-symbols.cjs')
    copy(mirror / 'build/wasm-compat/freetype-native/freetype-2.14.3.tar.xz', 'licenses/freetype-2.14.3.tar.xz')
    for name in ['openjdk', 'freetype']:
        for path in (mirror / 'wasm-compat/third-party' / name).iterdir():
            if path.is_file(): copy(path, 'licenses/' + name + '/' + path.name)
    sdk = Path('/home/dev/tools/emsdk/upstream/emscripten')
    sdk_revision = subprocess.check_output(['git','-C',str(sdk.parent.parent),'rev-parse','HEAD'],text=True).strip()
    assert sdk_revision == 'e4fe26ef59168ff44f4c23c466e497bf60b3411e'
    copy(sdk / 'LICENSE', 'licenses/emscripten-LICENSE'); copy(sdk / 'system/lib/libc/musl/COPYRIGHT', 'licenses/musl-COPYRIGHT')
    jdk_sources = {}
    with ZipFile('/opt/wasm-audiveris/jdk-25/lib/src.zip') as files:
        for name in files.namelist():
            if name.endswith('/'): continue
            if name.startswith('java.desktop/sun/java2d/marlin/') or name in [
                    'java.desktop/sun/java2d/pipe/Region.java', 'java.desktop/sun/java2d/pipe/AATileGenerator.java']:
                data = files.read(name); target = raw / 'jdk-sources' / name; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
                jdk_sources[name] = hashlib.sha256(data).hexdigest()
    (raw / 'routing.json').write_text(json.dumps(routing, indent=2) + '\n')
    artifacts = {str(p.relative_to(raw)): sha(p) for p in sorted(raw.rglob('*')) if p.is_file() and p.name != 'provenance.json'}
    provenance = {'sourceCommit': revision, 'sourceSha256': hashes, 'jdkSourceSha256': jdk_sources,
        'javaRuntime': inventory['javaRuntime'], 'emsdkRevision':sdk_revision, 'textFontResources':resources,'rawArtifacts': relative, 'artifactSha256': artifacts,
        'cpuMax': limits[0], 'memoryMaxBytes': int(limits[1]), 'memorySwapMaxBytes': int(limits[2]),
        'memoryEventsLocal': (cg / 'memory.events.local').read_text().strip(), 'limitation': report['limitation']}
    for name in ['verification.json', 'build.json', 'inventory.json']: shutil.copyfile(source / name, tracked / name)
    (tracked / 'routing.json').write_text(json.dumps(routing, indent=2) + '\n')
    shutil.copyfile(engine / 'diagnostics.tsv', tracked / 'diagnostics.tsv')
    for directory in [raw, tracked]: (directory / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({k: report[k] for k in ['recipes', 'cases', 'pixelsPerInstance', 'engineWasmBytes', 'fontWasmBytes']}))


if __name__ == '__main__': main()
