"""Archive staged TEXTS/PAGE recognition proofs and independent native diagnostic references."""
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
    page = '--page' in sys.argv[4:]
    curves = page or '--curves' in sys.argv[4:]
    post = curves or '--post' in sys.argv[4:]
    music = post or '--music' in sys.argv[4:]
    cleanup = music or '--cleanup' in sys.argv[4:]
    ttf = cleanup or '--ttf' in sys.argv[4:]
    live = ttf or '--live' in sys.argv[4:]
    source = mirror / 'build/wasm-compat/texts-continuation-probe'
    module_name = 'page-recognition' if page else 'post-curves' if curves else 'post-texts' if post else 'texts-music-backed' if music else 'texts-cleanup' if cleanup else 'texts-font-backed' if ttf else 'texts-continuation'
    module = mirror / 'build/wasm-compat' / (module_name + '-probe') if ttf else source
    native = source / ('live-ocr/jvm' if live else 'native')
    verification = module / 'verification.json' if ttf else source / ('live-ocr/verification.json' if live else 'verification.json')
    read = lambda path: json.loads(path.read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report = read(verification)
    checkpoint = module / 'node-verification.json'
    if 'nodeCheckpointSha256' in report:
        assert sha(checkpoint) == report['nodeCheckpointSha256']
        saved = read(checkpoint)
        assert saved['verifierHash'] == report['verifierSha256']
        assert saved['checks'] == report['pages'] * 2
    assert report.get('liveOcr', False) == live
    assert report.get('ordinaryFontsFromFiles', False) == ttf
    assert report.get('originalCleanupInWasm', False) == cleanup
    if post:
        stages=['MEASURES','CHORDS']+(['CURVES'] if curves else [])+(['SYMBOLS','LINKS','RHYTHMS','PAGE'] if page else [])
        assert report['originalPostTextsRecognition'] and report['postTextsStages'] == stages
        assert report['postTextsChecks'] == report['pages'] * 4 * len(stages) and report['postTextsGuardCounts'] == [111 if page else 66 if curves else 51]*4
        if page:
            assert report['symbolsChecks']==report['pages']*4 and report['suppliedSymbolsRasters']==0 and report['nativeSymbolsPixelsExact']
            dimensions=read(mirror/'build/wasm-compat/page-recognition-native/symbol-layout-dimensions.json')
            assert dimensions['exact'] and dimensions['cases']>=2304
        if curves:
            assert report['skeletonChecks']==report['pages']*4 and report['suppliedSkeletonRasters']==0 and report['nativeSkeletonPixelsExact']
            assert report['hypotCases']>4000 and report['hypotChecks']==report['hypotCases']*4
            assert report['trigChecks']==report['hypotChecks']*4 and len(report['jdkTrigClassSha256'])==6
    if cleanup:
        assert report['suppliedCleanupRasters'] == 0 and report['cleanupReferencePixelsExact']
        cleanup_comparison = mirror / 'build/wasm-compat/texts-cleanup-trace/verification.json'
        assert read(cleanup_comparison)['portableCleanupTraversalExact']
    if ttf:
        assert report['suppliedOrdinaryFontMeasurements'] == 0 and report['fontMeasurements'] == (0 if music else 2)
        assert report['fontResourcesSealed'] and len(report['textFontMeasurementCalls']) == 4
        assert all(value > 0 for value in report['textFontMeasurementCalls'])
    if music:
        assert report['suppliedMusicFontMeasurements'] == 0 and report['musicFontBackend'] == 'FreeType 2.14.3'
        assert len(report['musicFontMeasurementCalls']) == 4 and all(value > 0 for value in report['musicFontMeasurementCalls'])
        assert len(report['musicFontStats']) == 4
        for calls, stats in zip(report['musicFontMeasurementCalls'], report['musicFontStats']):
            assert calls == stats['requests'] and stats['cachedMeasurements'] <= stats['cacheLimit'] == 256
            assert stats['symbols']['calls'] == report['symbolSmokeChecksPerInstance'] + stats['pageSymbolCalls']
            assert stats['symbols']['cachedMaskBytes'] <= stats['symbols']['maskCacheBudgetBytes']
        assert report['sharedMusicMetricsAndRendering'] and report['symbolSmokeChecksPerInstance'] == 5
    assert report['workers'] == report['nodeInstances'] == 2
    assert report['checks'] == report['pages'] * 4 and report['originalTextWords'] > 0
    assert report['originalSystemProcessing'] and report['asynchronousReplyBoundary'] and report['duplicateAndLateRepliesRejected']
    assert report['maxDifference'] <= 1e-8 and sha(module / (module_name + '.wasm')) == report['wasmSha256']
    assert (module / 'diagnostics.tsv').read_text().splitlines() == ['severity\tmethod\tmessage']
    for name, expected in report['inputSha256'].items():
        path = (source / name).resolve(); assert path.is_relative_to(mirror.resolve())
        assert sha(path) == expected, 'Verified TEXTS input changed: ' + name
    git = ['git', '-c', f'safe.directory={root}', '-C', str(root)]
    changed = subprocess.check_output(git + ['diff', '--name-only', '-z',
        '04697c74fae431ef8436a9429386957a5cb25993', revision, '--', 'app/src/main/java']).decode().split('\0')
    sources = [name for name in changed if name.endswith('.java')] + [
        'wasm-compat/src/main/java/org/audiveris/omr/text/WorkerTextsBridge.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/HeadlessPatches.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/HeadlessTextFontGeometry.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/OcrExchange.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/OcrImageFrame.java',
        'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/OcrTransport.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/TextsContinuationProbe.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/TextsContinuationOracle.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/NativeOcrBackendOracle.java',
        'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/RecordingTextFontGeometry.java',
        'wasm-compat/tools/verify-texts-continuation.cjs', 'dev/teavm-audit/audit.init.gradle']
    if live:
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/LiveTextsOracle.java',
            'wasm-compat/src/test/java/org/audiveris/omr/text/DirectTextsSystems.java',
            'wasm-compat/tools/compare-score-ocr.cjs', 'wasm-compat/host/tesseract-js-ocr.mjs',
            'wasm-compat/package-lock.json',
            'wasm-compat/host/gray8-png.mjs', 'wasm-compat/host/tesseract-word-metadata.mjs',
            'wasm-compat/host/tesseract-word-metadata-node.cjs', 'wasm-compat/host/tesseract-core-word-metadata.js']
    if ttf:
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/' + name + '.java' for name in ['FontBackedTextsProbe', 'RecordedTextFontsOracle']]
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/' + name + '.java' for name in ['CompilerPatches', 'FontMetricsPatches']]
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/' + name + '.java' for name in ['TrueTypeFaceMetrics', 'TrueTypeGlyphBounds', 'PortableTextGeometry', 'FontMetricsRuntime', 'HeadlessLogging']]
    if cleanup:
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/CleanupTextsProbe.java']
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/' + name + '.java' for name in ['TextsCleanupTrace', 'GraphicsDelegate']]
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/' + name + '.java' for name in ['PackedPageRaster', 'PortableTextsCleanup', 'GraySpanRaster', 'StrokeRuntime']]
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/StrokePathPatches.java']
        sources += ['wasm-compat/src/jdk/java/org/audiveris/wasm/compat/platform/' + name + '.java' for name in ['GrayPathRaster', 'GrayThinRaster', 'StrokePaths']]
        sources += ['wasm-compat/third-party/openjdk/LICENSE', 'wasm-compat/third-party/openjdk/NOTICE.md']
    if music:
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/WorkerMusicFontGeometry.java',
            'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/MusicBackedTextsProbe.java',
            'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/MusicSymbolProbe.java',
            'wasm-compat/src/test/java/org/audiveris/wasm/compat/test/MusicSymbolOracle.java',
            'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/WorkerMusicSymbols.java',
            'wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/MarlinMemory.java',
            'wasm-compat/src/jdk/java/org/audiveris/wasm/compat/platform/GrayAlphaRaster.java',
            'wasm-compat/src/main/java/org/audiveris/wasm/compat/MarlinAlphaPatches.java',
            'wasm-compat/host/freetype-music-metrics.mjs', 'wasm-compat/host/freetype-music-symbols.mjs',
            'wasm-compat/third-party/freetype/NOTICE.md', 'dev/teavm-audit/music-paint.init.gradle']
        inventory = read(mirror / 'build/wasm-compat/music-symbols-wasm/inventory.json')
        sources += ['app/res/' + name for name in inventory['musicFontSha256']]
        sources += subprocess.check_output(git + ['ls-tree', '-r', '--name-only', revision, '--', 'app/src/main/java/org/audiveris/omr/ui/symbol']).decode().splitlines()
        sources += ['dev/teavm-audit/' + name for name in ['music-mask-native.c', 'music-mask-wasm.c',
            'music-metrics-functions.h', 'music-metrics-wasm.c', 'music-symbols-wasm.c', 'build_music_symbols_wasm.py']]
    if post:
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/PostTextsRecognition.java']
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/'+name+'.java' for name in ['PostTextsProbe','PostTextsSnapshot','PostTextsOracle']]
        sources += ['app/src/main/java/org/audiveris/omr/sheet/'+name+'.java' for name in ['rhythm/MeasuresStep','rhythm/MeasuresBuilder','note/ChordsStep','note/ChordsBuilder']]
        sources += ['app/src/main/java/org/audiveris/omr/score/Page.java','app/src/main/java/org/audiveris/omr/sheet/rhythm/Measure.java','app/src/main/java/org/audiveris/omr/sheet/rhythm/MeasureStack.java']
    if curves:
        sources += ['dev/teavm-audit/src/audit/CompileCompatibilityProbe.java']
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/PortableHypot.java','wasm-compat/src/test/java/org/audiveris/wasm/compat/test/HypotOracle.java']
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/PortableTrig.java']
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/PostCurvesRecognition.java','wasm-compat/src/main/java/org/audiveris/wasm/compat/BinarySkeletonPatches.java','wasm-compat/src/main/java/org/audiveris/omr/sheet/curve/WorkerSkeletonBridge.java','wasm-compat/src/main/java/org/audiveris/omr/sheet/curve/WorkerRehearsalsBridge.java']
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/'+name+'.java' for name in ['PostCurvesProbe','PostCurvesSnapshot','CurveSkeletonOracle']]
        sources += subprocess.check_output(git+['ls-tree','-r','--name-only',revision,'--','app/src/main/java/org/audiveris/omr/sheet/curve']).decode().splitlines()
    if page:
        sources += ['dev/teavm-audit/page-recognition.init.gradle']
        sources += ['wasm-compat/src/main/java/org/audiveris/wasm/compat/platform/PageRecognitionContinuation.java','wasm-compat/src/main/java/org/audiveris/wasm/compat/SymbolsRasterPatches.java','wasm-compat/src/main/java/org/audiveris/omr/sheet/symbol/WorkerSymbolsBridge.java']
        sources += ['wasm-compat/src/test/java/org/audiveris/wasm/compat/test/'+name+'.java' for name in ['PageRecognitionProbe','PageRecognitionOracle','PageRecognitionSnapshot','SymbolsRasterOracle']]
        sources += subprocess.check_output(git+['ls-tree','-r','--name-only',revision,'--','app/src/main/java/org/audiveris/omr/sheet/symbol','app/src/main/java/org/audiveris/omr/sheet/rhythm']).decode().splitlines()
        sources += ['app/src/main/java/org/audiveris/omr/step/PageStep.java']
    sources.append('dev/teavm-audit/export_texts_continuation.py')
    source_hashes = {}
    archive = subprocess.check_output(git + ['archive', revision] + sorted(set(sources)))
    with tarfile.open(fileobj=io.BytesIO(archive)) as files:
        for entry in files:
            if not entry.isfile(): continue
            name=entry.name;directory=root if name in ['wasm-compat/third-party/freetype/NOTICE.md','dev/teavm-audit/export_texts_continuation.py'] else mirror
            data=(directory/name).read_bytes();committed=files.extractfile(entry).read()
            if not name.endswith(('.otf','.ttf')):
                data=data.replace(b'\r\n',b'\n');committed=committed.replace(b'\r\n',b'\n')
            assert data==committed,name
            source_hashes[name]=hashlib.sha256(data).hexdigest()
    if music:
        backend_build = read(mirror / 'build/wasm-compat/music-symbols-wasm/build.json')
        assert backend_build['wasmSha256'] == report['musicFontAssets']['/music/module.wasm']['sha256']
        for name, digest in backend_build['sourceSha256'].items():
            assert source_hashes['dev/teavm-audit/' + name] == digest
    if page:
        page_native=mirror/'build/wasm-compat/page-recognition-native'
        dot_order=read(page_native/'dot-order-comparison.json')
        assert dot_order['pages']==report['pages'] and dot_order['prefixThroughCurvesExact']
        assert dot_order['afterReferenceSha256']==sha(page_native/'jvm-reference.json')
        assert dot_order['beforeReferenceSha256']==sha(page_native/'before-dot-order-jvm-reference.json')
        assert dot_order['beforeDotSourceSha256']==sha(page_native/'before-dot-order-DotFactory.java')
        assert dot_order['afterDotSourceSha256']==source_hashes['app/src/main/java/org/audiveris/omr/sheet/symbol/DotFactory.java']
        assert len(dot_order['orderingSources'])==3
        for ordering_source, info in dot_order['orderingSources'].items():
            assert info['afterSourceSha256']==source_hashes[ordering_source]
            assert info['beforeSourceSha256']==sha(page_native/info['beforeSourceArtifact'])
    limits = Path('/sys/fs/cgroup/wasm_audiveris_dev')
    cpu, memory, swap = [(limits / name).read_text().strip() for name in ['cpu.max', 'memory.max', 'memory.swap.max']]
    assert (cpu, memory, swap) == ('800000 100000', '8589934592', '0')
    name = 'page-recognition' if page else 'post-curves' if curves else 'post-texts' if post else 'texts-music-backed' if music else 'texts-cleanup' if cleanup else 'texts-font-backed' if ttf else 'texts-live-ocr' if live else 'texts-continuation'
    relative = 'wsl-artifacts/wasm-components/' + name
    raw = root / relative; tracked = root / ('wasm-compat/' + name + '-results')
    raw.mkdir(parents=True, exist_ok=True); tracked.mkdir(parents=True, exist_ok=True)
    def copy(origin, name):
        destination = raw / name; destination.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(origin, destination)
    if post:
        post_native=mirror/('build/wasm-compat/page-recognition-native' if page else 'build/wasm-compat/post-curves-native' if curves else 'build/wasm-compat/post-texts-native')
        for name in ['jvm-reference.json','symbol-geometry.json','point-widths.json']:
            copy(post_native/name,'post-native/'+name)
        if curves:
            copy(post_native/'hypot-reference.json','post-native/hypot-reference.json')
            for row in read(post_native/'jvm-reference.json'):
                comparison=read(post_native/row['id']/'skeleton-comparison.json')
                assert comparison['differentPixels']==0 and comparison['erasureDecisionsExact']
                assert sha(post_native/row['id']/'skeleton.gray')==comparison['nativeSha256']==comparison['packedSha256']
                for name in ['skeleton.gray','skeleton-comparison.json']:copy(post_native/row['id']/name,'post-native/'+row['id']+'/'+name)
        if page:
            copy(post_native/'symbol-layout-dimensions.json','post-native/symbol-layout-dimensions.json')
            for name in ['dot-order-comparison.json','before-dot-order-jvm-reference.json']+[info['beforeSourceArtifact'] for info in dot_order['orderingSources'].values()]:
                copy(post_native/name,'post-native/'+name)
            for row in read(post_native/'jvm-reference.json'):
                comparison=read(post_native/row['id']/'symbols-comparison.json')
                assert comparison['differentPixels']==0 and comparison['optionalGlyphSelectionExact']
                assert sha(post_native/row['id']/'symbols.gray')==comparison['nativeSha256']==comparison['packedSha256']
                for name in ['symbols.gray','symbols-comparison.json']:copy(post_native/row['id']/name,'post-native/'+row['id']+'/'+name)
    copy(verification, 'verification.json')
    if cleanup:
        copy(cleanup_comparison, 'native-cleanup-verification.json')
        for name in ['LICENSE', 'NOTICE.md']:
            copy(mirror / 'wasm-compat/third-party/openjdk' / name, 'licenses/openjdk/' + name)
    if 'nodeCheckpointSha256' in report:
        copy(checkpoint, 'node-verification.json')
    for name in ['diagnostics.tsv', module_name + '.wasm', module_name + '.wasm-runtime.js']:
        copy(module / name, name)
    native_names = ['jvm-reference.json', 'jvm-rejections.json', 'coverage.json', 'default-font.txt', 'symbol-geometry.json', 'point-widths.json']
    if not ttf:
        native_names += ['font-measurements.json']
    for name in native_names:
        copy(native / name, 'native/' + name)
    rows = read(native / 'jvm-reference.json'); assert len(rows) == report['pages']
    prior_path = mirror / 'build/wasm-compat/cue-beams-default-original-probe/jvm-reference.json'
    prior = {row['id']: row['result'] for row in read(prior_path)['pages']}
    prefix_rows = rows + read(native / 'jvm-rejections.json')
    for row in prefix_rows:
        expected = prior[row['id']]
        assert {key: row['result'][key] for key in expected} == expected, 'Earlier CUE result changed: ' + row['id']
    copy(prior_path, 'native/cue-baseline.json')
    for row in rows:
        identifier = row['id']; assert identifier.isdigit()
        folder = native / identifier; meta = read(folder / 'metadata.json')
        assert (folder / 'cleaned.gray').stat().st_size == meta['width'] * meta['height']
        if not live:
            assert read(folder / 'original-lines.json') == read(folder / 'imported-lines.json')
        assert read(folder / 'expected.json') == row['result']
        names = ['metadata.json', 'cleaned.gray', 'ocr-result.json', 'expected.json']
        if not live:
            names += ['padded.png', 'original-lines.json', 'imported-lines.json']
        for name in names:
            copy(folder / name, 'native/' + identifier + '/' + name)
        copy(mirror / 'build/wasm-compat/pixel-probe/samples' / (identifier + '.binary'), 'inputs/' + identifier + '.binary')
    copy(mirror / 'app/res/basic-classifier.zip', 'model.zip')
    copy(mirror / 'build/wasm-compat/cue-beams-default-portable-probe/font-measurements.json', 'serifs.json')
    template_root = mirror / 'build/wasm-compat/template-original-probe'
    required = {key for row in rows for key in row['result'].get('templatesUsed', [])}
    catalogs = [row for row in read(template_root / 'reference.json')['catalogs'] if row['family'] + ':' + str(row['pointSize']) in required]
    assert len(catalogs) == len(required)
    for row in catalogs:
        path = template_root / (row['file'] + '.atpl'); assert sha(path) == row['sha256']; copy(path, 'templates/' + path.name)
    copy(template_root / 'reference.json', 'templates/reference.json')
    if ttf:
        fonts = mirror / 'build/wasm-compat/recorded-text-fonts'
        resources = read(fonts / 'font-resources.json')
        for name in ['font-resources.json'] + ([] if music else ['music-measurements.json']):
            copy(fonts / name, 'text-fonts/' + name)
        for face in resources['faces']:
            path = fonts / 'fonts' / face['file']; assert sha(path) == face['sha256']
            copy(path, 'text-fonts/fonts/' + face['file'])
        copy(Path('/usr/share/doc/fonts-dejavu-core/copyright'), 'text-fonts/fonts/copyright')
    if music:
        backend = mirror / 'build/wasm-compat/music-symbols-wasm'
        for url, info in report['musicFontAssets'].items():
            assert url.startswith('/music/') and '..' not in Path(url).parts
            matches = [source / name for name, digest in report['inputSha256'].items() if digest == info['sha256']]
            assert matches and matches[0].stat().st_size == info['bytes'], url
            copy(matches[0], 'font-backend' + url)
        for name in ['FTL.TXT', 'GPLv2.TXT', 'LICENSE.TXT']:
            copy(backend / name, 'licenses/freetype/' + name)
        copy(root / 'wasm-compat/third-party/freetype/NOTICE.md', 'licenses/freetype/NOTICE.md')
        copy(mirror / 'build/wasm-compat/freetype-native/freetype-2.14.3.tar.xz', 'licenses/freetype/freetype-2.14.3.tar.xz')
        copy(backend / 'build.json', 'font-backend/build.json')
        copy(backend / 'jvm-reference.json', 'font-backend/jvm-reference.json')
        copy(backend / 'inventory.json', 'font-backend/inventory.json')
        copy(backend / 'font-resources.json', 'font-backend/font-resources.json')
        copy(backend / 'text-font-COPYRIGHT', 'licenses/text-symbol-font/COPYRIGHT')
        sdk = Path('/home/dev/tools/emsdk/upstream/emscripten')
        copy(sdk / 'LICENSE', 'licenses/emscripten/LICENSE')
        copy(sdk / 'system/lib/libc/musl/COPYRIGHT', 'licenses/emscripten/musl-COPYRIGHT')
    if live:
        diagnostic = read(source / 'live-ocr/comparison.json')
        assert diagnostic['workers'] == 2 and diagnostic['workerResultsExact'] and len(diagnostic['pages']) == len(rows)
        for name in ['comparison.json', 'node-results.json', 'worker-results.json']:
            copy(source / 'live-ocr' / name, 'live-ocr/' + name)
        for row in rows:
            copy(source / 'native' / row['id'] / 'ocr-result.json', 'native-ocr/' + row['id'] + '.json')
        for url, info in report['ocrAssets'].items():
            assert url.startswith('/') and '..' not in Path(url).parts
            matches = [source / name for name, digest in report['inputSha256'].items() if digest == info['sha256']]
            assert matches and matches[0].stat().st_size == info['bytes'], url
            copy(matches[0], 'ocr' + url)
        copy(mirror / 'wasm-compat/host/tesseract-word-metadata-node.cjs', 'ocr/host/tesseract-word-metadata-node.cjs')
        for package in ['tesseract.js', 'tesseract.js-core', '@tesseract.js-data/eng']:
            for path in (mirror / 'wasm-compat/node_modules' / package).iterdir():
                if path.is_file() and (path.name == 'package.json' or 'license' in path.name.lower() or 'notice' in path.name.lower()):
                    copy(path, 'ocr/packages/' + package + '/' + path.name)
    artifact_hashes = {str(path.relative_to(raw)): sha(path) for path in sorted(raw.rglob('*')) if path.is_file() and path.name != 'provenance.json'}
    input_artifacts = {name: [file for file, digest in artifact_hashes.items() if digest == expected]
                       for name, expected in report['inputSha256'].items()}
    assert all(input_artifacts.values()), 'A verified input was not included in the archive'
    provenance = {'sourceCommit': revision, 'sourceSha256': source_hashes, 'rawArtifacts': relative,
        'priorCuePrefixRegression': {'pages': len(prefix_rows), 'exact': True, 'referenceSha256': sha(prior_path)},
        'cpuMax': cpu, 'memoryMaxBytes': int(memory), 'memorySwapMaxBytes': int(swap),
        'memoryEventsLocal': (limits / 'memory.events.local').read_text().strip(),
        'artifactSha256': artifact_hashes, 'verifiedInputArtifacts': input_artifacts,
        'limitation': report['limitation']}
    shutil.copyfile(verification, tracked / 'verification.json')
    shutil.copyfile(module / 'diagnostics.tsv', tracked / 'diagnostics.tsv')
    if cleanup:
        shutil.copyfile(cleanup_comparison, tracked / 'cleanup-comparison.json')
    if live:
        shutil.copyfile(source / 'live-ocr/comparison.json', tracked / 'ocr-comparison.json')
    for destination in [raw, tracked]:
        (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['pages', 'checks', 'originalTextWords', 'maxDifference', 'wasmBytes']}))


if __name__ == '__main__':
    main()
