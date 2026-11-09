const assert = require('node:assert/strict');

// Check emitted code rather than accepting an empty Java/native stub as a fence.
module.exports = function verifyFence(bytes) {
    function u32(cursor) {
        let result = 0, shift = 0, value;
        do { value = bytes[cursor.offset++]; result += (value & 127) * 2 ** shift; shift += 7; } while (value & 128);
        return result;
    }
    function encode(value) {
        const result = [];
        do { const part = value & 127; value >>>= 7; result.push(part | (value ? 128 : 0)); } while (value);
        return result;
    }
    let global, code;
    const cursor = { offset: 8 };
    while (cursor.offset < bytes.length) {
        const section = bytes[cursor.offset++], length = u32(cursor), end = cursor.offset + length;
        if (section === 7) {
            const count = u32(cursor);
            for (let i = 0; i < count; i++) {
                const size = u32(cursor), name = bytes.subarray(cursor.offset, cursor.offset + size).toString('utf8');
                cursor.offset += size;
                const kind = bytes[cursor.offset++], index = u32(cursor);
                if (name === 'audiveris_probe_fence_root') { assert.equal(kind, 3); global = index; }
            }
        } else if (section === 10) { code = bytes.subarray(cursor.offset, end); }
        cursor.offset = end;
    }
    assert.notEqual(global, undefined, 'GC fence root export');
    const sequence = Buffer.from([0x20, 0, 0x24, ...encode(global), 0xd0, 0x6e, 0x24, ...encode(global)]);
    assert.ok(code && code.includes(sequence), 'Fence must emit local.get; global.set; ref.null; global.set');
    return { rootGlobalEmitted: true, storeAndClearEmitted: true };
};
