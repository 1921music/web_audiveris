"""Compare raw JVM OCR metadata with the actual Node/browser adapter fixture.

Different native engine releases may disagree; record every difference instead of
silently relaxing geometry/confidence checks or claiming full TEXTS equivalence.
"""
import hashlib
import json
from pathlib import Path
import sys


def compare(left, right, path, differences):
    if isinstance(left, dict) and isinstance(right, dict):
        for key in sorted(left.keys() | right.keys()):
            if key not in left or key not in right:
                differences.append({'path': path + '.' + key, 'native': left.get(key), 'wasm': right.get(key)})
            else:
                compare(left[key], right[key], path + '.' + key, differences)
    elif isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            differences.append({'path': path + '.length', 'native': len(left), 'wasm': len(right)})
        for index, (a, b) in enumerate(zip(left, right)):
            compare(a, b, f'{path}[{index}]', differences)
    elif left != right:
        differences.append({'path': path, 'native': left, 'wasm': right})


def main():
    root = Path(sys.argv[1])
    read = lambda path: json.loads(path.read_text())
    adapter = root / 'legacy-word-metadata'
    results = read(adapter / 'results.json')
    verification = read(adapter / 'verification.json')
    for name in ['input.gray', 'native.png', 'fixture.json']:
        assert (root / 'fixture' / name).read_bytes() == (adapter / name).read_bytes(), name
    modes = []
    for psm, name in [(6, 'first'), (3, 'second')]:
        native = read(root / 'native-legacy' / f'psm-{psm}.json')
        assert native['oem'] == 0 and native['sourceDpi'] == 70
        a, b = native['result'], results[name]['result']
        differences = []
        compare(a, b, '$', differences)
        words = lambda result: [word for line in result['lines'] for word in line['words']]
        assert [word['text'] for word in words(a)] == ['Allegro', 'moderato', 'Music', '123']
        assert sum(len(word['characters']) for word in words(a)) == 23
        modes.append({'psm': psm, 'nativeEngineVersion': native['engineVersion'],
                      'wordCount': len(words(a)), 'characterCount': 23,
                      'exact': not differences, 'differences': differences})
    # Both paths consume the same fixture bytes and the same decompressed English model.
    raw = root / 'native-legacy' / 'tessdata' / 'eng.traineddata'
    import gzip
    model = Path(sys.argv[2])
    assert hashlib.sha256(model.read_bytes()).hexdigest() == verification['assets']['/models/eng.traineddata.gz']['sha256']
    assert raw.read_bytes() == gzip.decompress(model.read_bytes())
    report = {'wasmEngineVersions': verification['engineVersions'], 'oem': 0, 'sourceDpi': 70,
              'adapterResultsSha256': hashlib.sha256((adapter / 'results.json').read_bytes()).hexdigest(),
              'sameModelBytes': True, 'traineddataSha256': hashlib.sha256(raw.read_bytes()).hexdigest(),
              'inputGraySha256': hashlib.sha256((adapter / 'input.gray').read_bytes()).hexdigest(),
              'modes': modes, 'nodeAndTwoBrowserWorkersExact': verification['workerResultsExact'],
              'limitation': 'Raw OCR fixture only; not original TextWord font fitting, page cleanup or full TEXTS recognition'}
    (root / 'native-legacy' / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'modes': [{'psm': mode['psm'], 'exact': mode['exact'], 'differences': len(mode['differences'])} for mode in modes]}))
    assert all(mode['exact'] for mode in modes), 'Raw OCR fixture changed; inspect recorded differences'


if __name__ == '__main__':
    main()
