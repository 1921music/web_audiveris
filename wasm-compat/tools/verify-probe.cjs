const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(process.argv[2]);
function compareText(kind, actual) {
    const expected = fs.readFileSync(path.join(root, kind + '.txt'), 'utf8');
    const a = actual.split('\n'), e = expected.split('\n'), differences = [];
    for (let i = 0; i < Math.max(a.length, e.length); i++) {
        if (a[i] !== e[i] && differences.length < 8) { differences.push({ line: i, actual: a[i], expected: e[i] }); }
    }
    assert.deepEqual(differences, [], kind);
}
(async () => {
    vm.runInThisContext(fs.readFileSync(path.join(root, 'compat.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const api = (await TeaVM.wasmGC.load(path.join(root, 'compat.wasm'))).exports;
    assert.equal(api.fenceProbe(), JSON.parse(fs.readFileSync(path.join(root, 'fence.json'), 'utf8')), 'GC fence calls');
    const moduleExports = WebAssembly.Module.exports(new WebAssembly.Module(fs.readFileSync(path.join(root, 'compat.wasm'))));
    assert.ok(moduleExports.some(item => item.name === 'audiveris_probe_fence_root' && item.kind === 'global'), 'GC root global emitted');
    assert.equal(api.audiveris_probe_fence_root, null, 'Fence does not retain references after the call');
    const gcFence = require('./verify-gc-fence.cjs')(fs.readFileSync(path.join(root, 'compat.wasm')));
    const traces = [], previousError = console.error;
    try { console.error = (...args) => traces.push(args.join(' ')); api.dumpStack(); }
    finally { console.error = previousError; }
    assert.ok(traces.join('\n').includes('java.lang.Exception: Stack trace'), 'Thread.dumpStack output');
    const standard = api.standard();
    compareText('utilities', api.utilities());
    for (const kind of ['properties', 'resources', 'modules', 'loggers']) { compareText(kind, api[kind]()); }
    assert.equal(standard, fs.readFileSync(path.join(root, 'standard.txt'), 'utf8'), 'Standard API behavior');
    const byteCases = api.bytes(), numbers = api.numbers();
    assert.equal(byteCases, fs.readFileSync(path.join(root, 'bytes.txt'), 'utf8'), 'Byte-array behavior');
    assert.equal(numbers, fs.readFileSync(path.join(root, 'numbers.txt'), 'utf8'), 'Jackson numeric parser');
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
        matchedPathValues: paths.length, matchedStandardCases: standard.trimEnd().split('\n').length,
        matchedByteCases: byteCases.trimEnd().split('\n').length, matchedNumberCases: numbers.trimEnd().split('\n').length, maxError,
        dumpStack: true, utilities: true, properties: true, resources: true, modules: true, loggers: true, gcFence,
        wasmBytes: fs.statSync(path.join(root, 'compat.wasm')).size };
    fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report));
})().catch(error => { console.error(error); process.exitCode = 1; });
