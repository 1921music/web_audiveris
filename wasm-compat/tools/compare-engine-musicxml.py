#!/usr/bin/env python3
"""Compare native JAXB, generated native writer, and actual standalone engine MusicXML trees."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
actual = Path(sys.argv[2]) if len(sys.argv) > 2 else root / 'build/wasm-engine/engine'
native = root / 'build/wasm-engine/native-export'
references = json.loads((native / 'reference.json').read_text())
if len(sys.argv) > 3:
    references = [row for row in references if row['id'] in sys.argv[3:]]
    if {row['id'] for row in references} != set(sys.argv[3:]):
        raise ValueError('Unknown requested reference page')


def compare(left, right, path=''):
    path += '/' + left.tag
    differences = []
    if left.tag != right.tag or left.attrib != right.attrib or (left.text or '') != (right.text or '') or len(left) != len(right):
        differences.append({'path': path, 'expected': [left.tag, left.attrib, left.text, len(left)],
                            'actual': [right.tag, right.attrib, right.text, len(right)]})
    for index, (a, b) in enumerate(zip(left, right)):
        differences.extend(compare(a, b, path + '[' + str(index) + ']'))
    return differences


results = []
for row in references:
    key = row['file']
    expected = ET.parse(native / (key + '.jaxb.musicxml')).getroot()
    portable = ET.parse(native / (key + '.portable.musicxml')).getroot()
    result = ET.parse(actual / (key + '.musicxml')).getroot()
    differences = compare(expected, portable) + compare(expected, result)
    results.append({'file': key, 'notes': len(result.findall('.//note')), 'elements': sum(1 for _ in result.iter()),
                    'differences': differences})
report = {'exact': all(not row['differences'] for row in results), 'results': results}
(actual / 'xml-comparison.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report))
if not report['exact']:
    raise SystemExit(1)
