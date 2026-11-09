const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(process.argv[2]);
(async () => {
    const files = new Map(['compat.wasm', 'compat.wasm-runtime.js'].map(name => ['/' + name, path.join(root, name)]));
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Compatibility tests</title>'); return; }
        const file = files.get(request.url);
        if (!file) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', file.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(file).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    let browser;
    try {
        browser = await chromium.launch({ headless: true });
        const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        await page.addScriptTag({ url: '/compat.wasm-runtime.js' });
        const result = await page.evaluate(async () => {
            const api = (await TeaVM.wasmGC.load('/compat.wasm')).exports;
            return { values: Array.from(api.values()), collections: Array.from(api.collections()),
                strings: Array.from(api.strings()), pathDiagnostic: api.pathDiagnostic(), paths: Array.from(api.paths()) };
        });
        const expected = JSON.parse(fs.readFileSync(path.join(root, 'oracle.json'), 'utf8'));
        assert.equal(result.values.length, expected.length);
        result.values.forEach((value, i) => assert.equal(value, expected[i], `Browser oracle value ${i}`));
        for (const kind of ['collections', 'strings', 'paths']) {
            assert.deepEqual(result[kind], JSON.parse(fs.readFileSync(path.join(root, kind + '.json'), 'utf8')));
        }
        assert.equal(result.pathDiagnostic, 'ok');
        const report = { browser: browser.version(), matchedValues: result.values.length,
            matchedStringValues: result.strings.length, matchedPathValues: result.paths.length, collections: true };
        fs.writeFileSync(path.join(root, 'browser-verification.json'), JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify(report));
    } finally {
        if (browser) { await browser.close(); }
        await new Promise(resolve => server.close(resolve));
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
