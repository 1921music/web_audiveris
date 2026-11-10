const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const root = path.resolve(process.argv[2]);
(async () => {
    const expected = fs.readFileSync(path.join(root, 'jvm-reference.txt'), 'utf8');
    const overrideExpected = 'Leland:Serif|FinaleJazz:FinaleJazzText|Bravura:SansSerif';
    vm.runInThisContext(fs.readFileSync(path.join(root, 'font-defaults.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const one = (await TeaVM.wasmGC.load(path.join(root, 'font-defaults.wasm'))).exports;
    const two = (await TeaVM.wasmGC.load(path.join(root, 'font-defaults.wasm'))).exports;
    one.configure(false); two.configure(true);
    assert.equal(one.families(), 'Bravura:SansSerif'); assert.equal(two.families(), 'Leland:Serif');
    assert.equal(one.mutate(), expected); assert.equal(two.families(), 'Leland:Serif');
    assert.equal(two.mutate(), overrideExpected);
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.end('<!doctype html><title>Font configuration verification</title>'); return; }
        if (request.url === '/worker.js') {
            response.setHeader('Content-Type', 'text/javascript');
            response.end(`importScripts('/font-defaults.wasm-runtime.js'); let api;
                onmessage = async event => {
                    try { if (!api) api = (await TeaVM.wasmGC.load('/font-defaults.wasm')).exports;
                        postMessage({result: api[event.data.method](event.data.argument), dom: typeof document});
                    } catch(error) { postMessage({error: String(error.stack || error)}); }
                };`); return;
        }
        const name = request.url.slice(1);
        if (!['font-defaults.wasm', 'font-defaults.wasm-runtime.js'].includes(name)) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
        fs.createReadStream(path.join(root, name)).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); let browser;
    try {
        browser = await chromium.launch({headless: true}); const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const results = await page.evaluate(async () => {
            const workers = [new Worker('/worker.js'), new Worker('/worker.js')];
            function call(index, method, argument) {
                return new Promise((resolve, reject) => {
                    const timer = setTimeout(() => reject(new Error('Font configuration timeout')), 30000);
                    workers[index].onmessage = event => {
                        clearTimeout(timer); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data);
                    };
                    workers[index].onerror = event => { clearTimeout(timer); reject(new Error(event.message)); };
                    workers[index].postMessage({method, argument});
                });
            }
            try {
                await call(0, 'configure', false); await call(1, 'configure', true);
                return {first: await call(0, 'families'), second: await call(1, 'families'),
                    mutateFirst: await call(0, 'mutate'), isolated: await call(1, 'families'), mutateSecond: await call(1, 'mutate')};
            } finally { workers.forEach(worker => worker.terminate()); }
        });
        assert.equal(results.first.result, 'Bravura:SansSerif'); assert.equal(results.second.result, 'Leland:Serif');
        assert.equal(results.mutateFirst.result, expected); assert.equal(results.isolated.result, 'Leland:Serif');
        assert.equal(results.mutateSecond.result, overrideExpected);
        for (const result of Object.values(results)) { assert.equal(result.dom, 'undefined'); }
        const report = {node: process.version, chromium: browser.version(), nodeInstances: 2, workers: 2,
            originalConstantNamespaces: true, originalDefaults: true, bookParameterInheritance: true,
            originalFontApiAliasesJvm: true, typedMutationAndSourceReset: true, workerIsolation: true,
            wasmBytes: fs.statSync(path.join(root, 'font-defaults.wasm')).size};
        fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify(report));
    } finally { if (browser) { await browser.close(); } await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
