const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const baseline = path.resolve(process.argv[2]), current = path.resolve(process.argv[3]);
(async () => {
    vm.runInThisContext(fs.readFileSync(path.join(current, 'rounding.wasm-runtime.js'), 'utf8'), {
        importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER
    });
    const old = (await TeaVM.wasmGC.load(path.join(baseline, 'rounding.wasm'))).exports;
    const fixed = (await TeaVM.wasmGC.load(path.join(current, 'rounding.wasm'))).exports;
    const expected = fs.readFileSync(path.join(current, 'jvm-reference.txt'), 'utf8');
    assert.equal(fixed.values(), expected);
    const oldValues = old.values().split(';'), jvmValues = expected.split(';');
    const differences = oldValues.flatMap((value, index) => value === jvmValues[index] ? [] : [{index, jvm: jvmValues[index], stockTeaVM: value}]);
    const report = {node: process.version, stockCompatibilityEnabled: false, currentCompatibilityEnabled: true,
        stockMismatchedEdgeCases: differences.length, currentMatchesJvm: true, differences,
        stockChecksum: old.checksum(), jvmChecksum: fs.readFileSync(path.join(current, 'jvm-checksum.txt'), 'utf8'),
        currentChecksum: fixed.checksum(), stockWasmBytes: fs.statSync(path.join(baseline, 'rounding.wasm')).size,
        currentWasmBytes: fs.statSync(path.join(current, 'rounding.wasm')).size};
    fs.writeFileSync(path.join(current, 'baseline-comparison.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({...report, differences: undefined}));
})().catch(error => { console.error(error); process.exitCode = 1; });
