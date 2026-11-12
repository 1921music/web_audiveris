// SPDX-License-Identifier: AGPL-3.0-or-later
// Classic Worker deliberately uses the same HTTP / single-file loader boundary as the engine.
let engine;
async function bytes(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error('OCR asset unavailable: ' + response.status);
    return response;
}
self.onmessage = async ({data}) => {
    try {
        if (data.type === 'init') {
            const {createOCREngine, supportsFastBuild} = await import(data.library);
            engine = await createOCREngine({wasmBinary: await (await bytes(supportsFastBuild() ? data.core : data.fallbackCore)).arrayBuffer()});
            const model = await bytes(data.model);
            engine.loadModel(await new Response(model.body.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer());
            for (const [name, value] of Object.entries(data.variables ?? {})) engine.setVariable(name, value);
            engine.setVariable('hocr_char_boxes', '1');
            engine.setVariable('hocr_font_info', '1');
        } else if (data.type === 'recognize') {
            const {width, height, pixels, mode} = data;
            const rgba = new Uint8ClampedArray(width * height * 4);
            for (let i = 0; i < pixels.length; i++) {
                rgba[i * 4] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = pixels[i]; rgba[i * 4 + 3] = 255;
            }
            engine.setVariable('tessedit_pageseg_mode', mode);
            engine.loadImage(new ImageData(rgba, width, height));
            try { postMessage({id: data.id, hocr: engine.getHOCR()}); }
            finally { engine.clearImage(); }
            return;
        } else throw new Error('Unknown OCR worker operation');
        postMessage({id: data.id});
    } catch (error) { postMessage({id: data.id, error: String(error?.message || error)}); }
};
