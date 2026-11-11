const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const assert = require('node:assert/strict'), http = require('node:http'), {pathToFileURL} = require('node:url');
const zlib = require('node:zlib'), {chromium} = require('playwright');
const root = path.resolve(process.argv[2]), sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
(async () => {
    const native = JSON.parse(fs.readFileSync(path.join(root, 'native-verification.json')));
    const programsBytes = fs.readFileSync(path.join(root, 'glyph-programs.json'));
    const programs = JSON.parse(programsBytes), expected = fs.readFileSync(path.join(root, 'jvm-pixels.binary'));
    assert.equal(native.cases, 2280); assert.equal(programs.length, native.cases);
    assert.equal(native.candidates[3].differentPixels, 0); assert.equal(native.candidates[3].differentCases, 0);
    assert.equal(sha(programsBytes), native.glyphProgramsSha256); assert.equal(sha(expected), native.nativePixelsSha256);
    assert.equal(expected.length, native.cases * native.width * native.height);
    const wasm = fs.readFileSync(path.join(root, 'music-masks.wasm')), fonts = {};
    assert.equal(Object.keys(native.fontSha256).length, 6);
    for (const [name, digest] of Object.entries(native.fontSha256)) {
        fonts[name] = fs.readFileSync(path.join(root, 'fonts', name)); assert.equal(sha(fonts[name]), digest);
    }
    const {default: factory} = await import(pathToFileURL(path.join(root, 'music-masks.mjs')));
    const runnerPath = path.join(__dirname, 'music-mask-runner.mjs');
    const {renderMusicMasks} = await import(pathToFileURL(runnerPath));
    const nodeRows = [];
    for (let instance = 0; instance < 2; instance++) {
        const result = await renderMusicMasks(factory, wasm, fonts, programs, native.width, native.height);
        assert.deepEqual(Buffer.from(result.pixels), expected); assert.equal(result.guards, 4);
        assert.equal(result.cachedMasks, native.cachedMasks / 2);
        nodeRows.push({guards: result.guards, cachedMasks: result.cachedMasks, linearMemoryBytes: result.linearMemoryBytes});
    }
    const worker = `import factory from '/music-masks.mjs';import {renderMusicMasks} from '/runner.mjs';
        onmessage=async()=>{try{
            const [wasm,native,programs]=await Promise.all([fetch('/music-masks.wasm').then(r=>r.arrayBuffer()),fetch('/native-verification.json').then(r=>r.json()),fetch('/glyph-programs.json').then(r=>r.json())]);
            const fonts={};for(const name of Object.keys(native.fontSha256)){fonts[name]=new Uint8Array(await fetch('/fonts/'+name).then(r=>r.arrayBuffer()));}
            const result=await renderMusicMasks(factory,new Uint8Array(wasm),fonts,programs,native.width,native.height);
            const digest=await crypto.subtle.digest('SHA-256',result.pixels);
            postMessage({bytes:result.pixels.length,guards:result.guards,cachedMasks:result.cachedMasks,linearMemoryBytes:result.linearMemoryBytes,
                pixelSha256:Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join(''),dom:typeof document});
        }catch(error){postMessage({error:String(error.stack||error)});}};`;
    const allowed = new Map(['music-masks.mjs','music-masks.wasm','native-verification.json','glyph-programs.json',
        ...Object.keys(fonts).map(name => 'fonts/' + name)].map(name => ['/' + name, path.join(root, name)]));
    const server = http.createServer((request, response) => {
        if (request.url === '/') return response.end('<!doctype html><title>Music mask comparison</title>');
        if (request.url === '/worker.mjs' || request.url === '/runner.mjs') {
            response.setHeader('Content-Type', 'text/javascript');
            return response.end(request.url === '/worker.mjs' ? worker : fs.readFileSync(runnerPath));
        }
        const file = allowed.get(request.url); if (!file) {response.statusCode = 404; return response.end();}
        response.setHeader('Content-Type', file.endsWith('.wasm') ? 'application/wasm' : file.endsWith('.mjs') ? 'text/javascript' : 'application/octet-stream');
        fs.createReadStream(file).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    const workerRows = [];
    try {
        browser = await chromium.launch({headless: true, timeout: 600000}); const page = await browser.newPage();
        await page.goto('http://127.0.0.1:' + server.address().port + '/');
        for (let instance = 0; instance < 2; instance++) {
            const result = await page.evaluate(() => new Promise((resolve, reject) => {
                const worker = new Worker('/worker.mjs', {type: 'module'});
                const timer = setTimeout(() => {worker.terminate(); reject(new Error('Music mask timeout'));}, 600000);
                worker.onmessage = ({data}) => {clearTimeout(timer); worker.terminate(); data.error ? reject(new Error(data.error)) : resolve(data);};
                worker.onerror = event => {clearTimeout(timer); worker.terminate(); reject(new Error(event.message));}; worker.postMessage(null);
            }));
            assert.equal(result.bytes, expected.length); assert.equal(result.pixelSha256, native.nativePixelsSha256);
            assert.equal(result.guards, 4); assert.equal(result.cachedMasks, nodeRows[0].cachedMasks); assert.equal(result.dom, 'undefined');
            workerRows.push(result);
        }
        const glue = fs.readFileSync(path.join(root, 'music-masks.mjs'));
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            cases: native.cases, nativeComparedPixels: expected.length, nodePixelsExact: true, workerPixelSha256Exact: true,
            inputGuardsPerInstance: 4, pixelSha256: native.nativePixelsSha256, wasmBytes: wasm.length, wasmGzipBytes: zlib.gzipSync(wasm).length,
            glueBytes: glue.length, glueGzipBytes: zlib.gzipSync(glue).length, wasmSha256: sha(wasm), glueSha256: sha(glue),
            glyphProgramsSha256: native.glyphProgramsSha256, fontSha256: native.fontSha256, runnerSha256: sha(fs.readFileSync(runnerPath)), nodeRows, workerRows,
            limitation: 'Standalone FreeType mask component with native AWT glyph IDs and positions; no TeaVM engine integration, portable symbol layout or recognition-speed improvement is established.'};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
    } finally {if (browser) await browser.close(); await new Promise(resolve => server.close(resolve));}
})().catch(error => {console.error(error); process.exitCode = 1;});
