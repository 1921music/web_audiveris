// Research only: compare actual upstream Java2D stem-erasure commands with native Canvas.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const root = path.resolve(process.argv[2]);
const commands = JSON.parse(fs.readFileSync(path.join(root, 'raster-commands.json')));
const reference = JSON.parse(fs.readFileSync(path.join(root, 'jvm-reference.json')));
const original = JSON.parse(fs.readFileSync(path.resolve(root, '../stem-original-probe/jvm-reference.json')));
assert.deepEqual(reference, original);
const sources = new Map();
for (const page of commands) {
    assert.match(page.id, /^(staff|\d{2})$/);
    sources.set(`/input/${page.id}`, path.resolve(root, '../header-probe/no-staff', page.id + '.binary'));
    sources.set(`/expected/${page.id}`, path.join(root, 'stem-buffers', page.id + '.binary'));
}
(async () => {
    const server = http.createServer((request, response) => {
        if (request.url === '/') { response.setHeader('Content-Type', 'text/html'); return response.end('<!doctype html><title>Raster comparison</title>'); }
        if (request.url === '/commands') { response.setHeader('Content-Type', 'application/json'); return response.end(JSON.stringify(commands)); }
        const file = sources.get(request.url);
        if (!file) { response.statusCode = 404; return response.end(); }
        fs.createReadStream(file).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    let browser;
    try {
        browser = await chromium.launch({headless: true, args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage']});
        const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        const results = await page.evaluate(async () => {
            const pages = await (await fetch('/commands')).json(), results = [];
            function makePath(source) {
                const result = new Path2D();
                for (const [kind, ...c] of source.segments) {
                    if (kind === 0) { result.moveTo(...c); }
                    else if (kind === 1) { result.lineTo(...c); }
                    else if (kind === 2) { result.quadraticCurveTo(...c); }
                    else if (kind === 3) { result.bezierCurveTo(...c); }
                    else if (kind === 4) { result.closePath(); }
                    else { throw new Error('Unknown path command'); }
                }
                return result;
            }
            function histogram(pixels, width, height) {
                const bins = Array(65).fill(0);
                for (let y = 0; y < height; y++) {
                    let run = 0;
                    for (let x = 0; x <= width; x++) {
                        if (x < width && pixels[y * width + x] === 0) { run++; }
                        else if (run) { if (run < bins.length) { bins[run]++; } run = 0; }
                    }
                }
                return bins;
            }
            for (const record of pages) {
                const {id, width, height, commands} = record;
                const input = new Uint8Array(await (await fetch('/input/' + id)).arrayBuffer());
                const expected = new Uint8Array(await (await fetch('/expected/' + id)).arrayBuffer());
                const expectedHistogram = histogram(expected, width, height);
                for (const mode of ['nativeStroke', 'jvmStrokeOutline']) {
                    const canvas = new OffscreenCanvas(width, height), g = canvas.getContext('2d', {willReadFrequently: true});
                    g.fillStyle = 'white'; g.strokeStyle = 'white';
                    for (const command of commands) {
                        let source = command.path;
                        if (mode === 'jvmStrokeOutline' && command.stroke) { source = command.outlinedPath; }
                        const shape = makePath(source);
                        if (mode === 'nativeStroke' && command.stroke) {
                            const stroke = command.stroke;
                            g.lineWidth = stroke.width; g.lineCap = ['butt', 'round', 'square'][stroke.cap];
                            g.lineJoin = ['miter', 'round', 'bevel'][stroke.join]; g.miterLimit = stroke.miter;
                            g.stroke(shape);
                        } else { g.fill(shape, source.winding === 0 ? 'evenodd' : 'nonzero'); }
                    }
                    const rgba = g.getImageData(0, 0, width, height).data, actual = input.slice();
                    let different = 0, missedErasure = 0, excessErasure = 0;
                    for (let i = 0; i < actual.length; i++) {
                        if (rgba[4 * i + 3] >= 128) { actual[i] = 255; }
                        if (actual[i] !== expected[i]) { different++; if (actual[i] === 0) { missedErasure++; } else { excessErasure++; } }
                    }
                    const actualHistogram = histogram(actual, width, height);
                    results.push({id, mode, commands: commands.length, pixels: width * height, different, missedErasure, excessErasure,
                        horizontalRunHistogramDifference: actualHistogram.map((n, i) => n - expectedHistogram[i])});
                    canvas.width = 0; canvas.height = 0;
                }
            }
            return results;
        });
        const report = {chromium: browser.version(), source: 'Observed original StemScaler drawing; recorder results and pixels match the unobserved JVM',
            threshold: 128, results, appliesPreviouslyDetachedDrawing: true,
            limitation: 'Differences compare applied drawing with an unmodified recognition buffer; not Canvas precision measurements. No engine backend changed'};
        fs.writeFileSync(path.join(root, 'canvas-comparison.json'), JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify({...report, results: results.map(({horizontalRunHistogramDifference, ...row}) => ({...row,
            histogramBinsChanged: horizontalRunHistogramDifference.filter(value => value !== 0).length}))}));
    } finally {
        if (browser) { await browser.close(); }
        await new Promise(resolve => server.close(resolve));
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
