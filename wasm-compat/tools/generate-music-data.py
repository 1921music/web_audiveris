"""Extract the metronome character set from unchanged upstream tables, without loading Swing."""
import hashlib
import json
import re
import sys
from pathlib import Path


def extract(root, output):
    names = ['glyph/ShapeSet.java', 'ui/symbol/BravuraSymbols.java', 'ui/field/MusicPane.java', 'ui/symbol/Symbols.java', 'sheet/ui/TemplateView.java', 'ui/util/UIUtil.java']
    sources = {name: (root / 'app/src/main/java/org/audiveris/omr' / name).read_text(encoding='utf-8') for name in names}
    pane = sources[names[2]]
    # Fail if the source algorithm changes: the generator only implements this reviewed pure-data body.
    expected = '''final Set<Integer> set = new HashSet<>();
        final Symbols symbols = MusicFamily.Bravura.getSymbols();
        ShapeSet.BeatUnits.forEach(bu -> { for (int c : symbols.getCode(bu)) {
            if (c >= HIGH_CODE) { set.add(c); }
        } }); return set;'''
    start = re.search(r'private static Set<Integer> buildMusicCodes\s*\(\s*\)\s*\{', pane)
    if not start:
        raise ValueError('Review MusicPane.buildMusicCodes signature')
    index, depth = start.end(), 1
    while depth:
        depth += (pane[index] == '{') - (pane[index] == '}')
        index += 1
    normalize = lambda value: re.sub(r'\s+', '', value)
    if not re.search(r'public static int\[\] ints\s*\(int\.\.\. codes\)\s*\{\s*return codes;\s*\}', sources[names[3]]):
        raise ValueError('Review Symbols.ints semantics')
    if normalize(pane[start.end():index - 1]) != normalize(expected):
        raise ValueError('Review MusicPane music-code algorithm before generating data')
    threshold = int(re.search(r'HIGH_CODE\s*=\s*(0x[0-9a-fA-F]+)\s*;', pane)[1], 16)
    units = re.search(r'\bBeatUnits\s*=\s*EnumSet.of\(([^)]+)\)', sources[names[0]])[1]
    shapes = [part.strip() for part in units.split(',')]
    if not all(re.fullmatch(r'[A-Z_]+', shape) for shape in shapes):
        raise ValueError('Review BeatUnits syntax')
    cases = {}
    for labels, arguments in re.findall(r'case\s+([A-Z_ ,\r\n]+?)\s*->\s*ints\(([^)]+)\)\s*;', sources[names[1]]):
        for label in labels.split(','):
            label = label.strip()
            if label not in shapes:
                continue
            values = [part.strip() for part in arguments.split(',')]
            if label in cases or not all(re.fullmatch(r'0x[0-9a-fA-F]+', value) for value in values):
                raise ValueError('Review Bravura code table: ' + label)
            cases[label] = [int(value, 16) for value in values]
    if set(cases) != set(shapes):
        raise ValueError('Missing BeatUnits code table entries: ' + repr(set(shapes) - set(cases)))
    codes = sorted({code for values in cases.values() for code in values if code >= threshold})
    if any(code > 0xFFFF for code in codes):
        raise ValueError('Review supplementary metronome codes before generating BMP regexp fragments')
    composite = re.search(r'public static final AlphaComposite templateComposite\s*=\s*AlphaComposite\.getInstance\(\s*AlphaComposite\.([A-Z_]+),\s*([0-9.]+f)\s*\);', sources[names[4]])
    if not composite or composite[1] != 'SRC_OVER':
        raise ValueError('Review template composite before detaching the view class')
    target = output / 'src/org/audiveris/wasm/compat/platform/HeadlessMusicData.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    additions = '\n'.join(f'        codes.add(0x{code:X});' for code in codes)
    target.write_text('''// Generated from upstream BeatUnits/Bravura/MusicPane; do not edit.
package org.audiveris.wasm.compat.platform;
public final class HeadlessMusicData {
    private static final java.util.Set<Integer> CODES = build();
    private static java.util.Set<Integer> build() {
        java.util.Set<Integer> codes = new java.util.HashSet<>();
''' + additions + '''
        return codes;
    }
    public static java.util.Set<Integer> musicCodes() { return CODES; }
    public static String beatUnitRegexp() {
        StringBuilder result = new StringBuilder();
        for (int code : CODES) {
            String hex = Integer.toHexString(code);
            if (code > 0xFFFF) { throw new IllegalStateException("Review supplementary metronome codes"); }
            result.append('\\\\').append('u');
            for (int i = hex.length(); i < 4; i++) { result.append('0'); }
            result.append(hex);
        }
        return result.toString();
    }
}
''', encoding='utf-8')
    render = output / 'src/org/audiveris/wasm/compat/platform/HeadlessRenderData.java'
    render.write_text('package org.audiveris.wasm.compat.platform;\npublic final class HeadlessRenderData {\n'
                      '  private static final java.awt.AlphaComposite COMPOSITE = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.'
                      + composite[1] + ', ' + composite[2] + ');\n'
                      '  public static java.awt.AlphaComposite templateComposite() { return COMPOSITE; }\n}\n', encoding='utf-8')
    methods = []
    for name in ['setAbsoluteStroke', 'setAbsoluteDashedStroke']:
        matches = list(re.finditer(r'public static Stroke ' + name + r'\s*\(Graphics g,\s*float width\)\s*\{', sources[names[5]]))
        if len(matches) != 1:
            raise ValueError('Review drawing method signature: ' + name)
        start = matches[0]; index, depth = start.end(), 1
        while depth and index < len(sources[names[5]]):
            depth += (sources[names[5]][index] == '{') - (sources[names[5]][index] == '}')
            index += 1
        if depth:
            raise ValueError('Review drawing method body: ' + name)
        method = sources[names[5]][start.start():index]
        if any(word in method for word in ['org.', 'logger', 'constants', 'closeWindow']):
            raise ValueError('Drawing helper acquired a UI/context dependency: ' + name)
        methods.append(method)
    render_source = ('// Generated by extracting the unchanged UIUtil source methods.\n'
                     'package org.audiveris.wasm.compat.platform;\nimport java.awt.*;\nimport java.awt.geom.AffineTransform;\n'
                     'public final class HeadlessRenderOps {\n' + '\n'.join(methods) + '\n}\n')
    (render.parent / 'HeadlessRenderOps.java').write_text(render_source, encoding='utf-8')
    (output / 'render-ops.json').write_text(json.dumps({'source': names[5], 'sourceSha256': hashlib.sha256(sources[names[5]].encode()).hexdigest(),
        'methodSourceSha256': {name: hashlib.sha256(method.encode()).hexdigest() for name, method in zip(['setAbsoluteStroke', 'setAbsoluteDashedStroke'], methods)},
        'generatedSourceSha256': hashlib.sha256(render_source.encode()).hexdigest(), 'policy': 'Verbatim source method extraction; no fields, class initializer or window listener'}, indent=2) + '\n', encoding='utf-8')
    metadata = {'shapes': cases, 'threshold': threshold, 'codes': codes, 'templateComposite': {'rule': composite[1], 'alpha': composite[2]},
                'sourcesSha256': {name: hashlib.sha256(text.encode()).hexdigest() for name, text in sources.items()}}
    (output / 'music-data.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    extract(Path(sys.argv[1]), Path(sys.argv[2]))
