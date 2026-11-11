/** Standalone candidate only: glyph IDs and rounded origins come from the native fixture. */
export async function renderMusicMasks(factory, wasm, fonts, programs, width, height) {
    if (!Number.isInteger(width) || !Number.isInteger(height) || width <= 0 || height <= 0 || width > 512 || height > 512) {
        throw new RangeError('Fixture dimensions');
    }
    const api = await factory({wasmBinary: wasm});
    const allocate = size => {const pointer = api._malloc(size); if (!pointer) throw new Error('Mask allocation failed'); return pointer;};
    const info = allocate(16), output = allocate(512 * 512), faces = new Map(), cache = new Map();
    const pixels = new Uint8Array(programs.length * width * height);
    let guards = 0;
    try {
        if (api._music_mask_version(info) !== 0 || [0,1,2].some(i => api.HEAP32[(info >> 2) + i] !== [2,14,3][i])) {
            throw new Error('Unexpected FreeType version');
        }
        for (const [name, bytes] of Object.entries(fonts)) {
            const pointer = allocate(bytes.length);
            api.HEAPU8.set(bytes, pointer);
            const face = api._music_mask_memory_open(pointer, bytes.length);
            if (!face) {api._free(pointer); throw new Error('Invalid font: ' + name);}
            faces.set(name, {face, pointer});
        }
        if (!api._music_mask_memory_open(0, 0)) guards++;
        const face = faces.values().next().value.face;
        if (api._music_mask_render(face, -1, 12, 1, info, output, 512 * 512) !== 0) guards++;
        if (api._music_mask_render(face, 1, 0, 1, info, output, 512 * 512) !== 0) guards++;
        if (api._music_mask_render(face, 1, NaN, 1, info, output, 512 * 512) !== 0) guards++;
        if (guards !== 4) throw new Error('Missing C input guards');
        for (let index = 0; index < programs.length; index++) {
            const pageOffset = index * width * height;
            for (const glyph of programs[index].glyphs) {
                if (!faces.has(glyph.font) || !Number.isInteger(glyph.glyph) || glyph.glyph < 0 ||
                    !Number.isFinite(glyph.size) || glyph.size <= 0 || glyph.size > 128 ||
                    !Number.isInteger(glyph.x) || !Number.isInteger(glyph.y) || Math.abs(glyph.x) > 1024 || Math.abs(glyph.y) > 1024) {
                    throw new RangeError('Invalid glyph fixture');
                }
                const key = JSON.stringify([glyph.font, glyph.glyph, glyph.size]);
                let mask = cache.get(key);
                if (!mask) {
                    const error = api._music_mask_render(faces.get(glyph.font).face, glyph.glyph, glyph.size, 1, info, output, 512 * 512);
                    if (error) throw new Error('FreeType render error: ' + error);
                    const [left, top, w, h] = api.HEAP32.slice(info >> 2, (info >> 2) + 4);
                    if (w < 0 || h < 0 || w > 512 || h > 512) throw new Error('Invalid mask dimensions');
                    mask = {left, top, w, h, alpha: api.HEAPU8.slice(output, output + w * h)};
                    cache.set(key, mask);
                }
                for (let y = 0; y < mask.h; y++) for (let x = 0; x < mask.w; x++) {
                    const px = glyph.x + mask.left + x, py = glyph.y + mask.top + y;
                    if (px < 0 || py < 0 || px >= width || py >= height) continue;
                    const destination = pageOffset + py * width + px, alpha = mask.alpha[y * mask.w + x];
                    pixels[destination] = alpha + Math.floor((pixels[destination] * (255 - alpha) + 127) / 255);
                }
            }
        }
        return {pixels, guards, cachedMasks: cache.size, linearMemoryBytes: api.HEAPU8.byteLength};
    } finally {
        for (const {face, pointer} of faces.values()) {api._music_mask_close(face); api._free(pointer);}
        api._free(info); api._free(output);
    }
}
