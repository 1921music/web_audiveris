const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(process.argv[2]);
(async () => {
    vm.runInThisContext(fs.readFileSync(path.join(root, 'compat.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const api = (await TeaVM.wasmGC.load(path.join(root, 'compat.wasm'))).exports;
    const actual = Array.from(api.values());
    const expected = JSON.parse(fs.readFileSync(path.join(root, 'oracle.json'), 'utf8'));
    assert.equal(actual.length, expected.length);
    const collections = Array.from(api.collections());
    assert.deepEqual(collections, JSON.parse(fs.readFileSync(path.join(root, 'collections.json'), 'utf8')));
    const strings = Array.from(api.strings());
    assert.deepEqual(strings, JSON.parse(fs.readFileSync(path.join(root, 'strings.json'), 'utf8')));
    assert.equal(api.pathDiagnostic(), 'ok', 'Wasm path API failure');
    const paths = Array.from(api.paths());
    assert.deepEqual(paths, JSON.parse(fs.readFileSync(path.join(root, 'paths.json'), 'utf8')));
    let maxError = 0;
    for (let i = 0; i < actual.length; i++) {
        const error = Math.abs(actual[i] - expected[i]);
        assert.ok(Number.isFinite(actual[i]) && error <= 1e-7, `Wasm value ${i}: ${actual[i]} != ${expected[i]}`);
        maxError = Math.max(maxError, error);
    }
    const report = { node: process.version, matchedValues: actual.length, collections, matchedStringValues: strings.length,
        matchedPathValues: paths.length, maxError,
        wasmBytes: fs.statSync(path.join(root, 'compat.wasm')).size };
    fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report));
})().catch(error => { console.error(error); process.exitCode = 1; });
