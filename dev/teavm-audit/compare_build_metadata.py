"""Prove a Wasm rebuild differs only in its embedded Audiveris build revision."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def integer(data, at):
    value = shift = 0
    while True:
        byte = data[at]; at += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, at
        shift += 7


def sections(data):
    if data[:8] != b'\0asm\x01\0\0\0':
        raise AssertionError('Unexpected Wasm header')
    at = 8; result = []
    while at < len(data):
        kind = data[at]; size, start = integer(data, at + 1)
        result.append((kind, data[start:start + size])); at = start + size
    if at != len(data):
        raise AssertionError('Invalid Wasm section size')
    return result


def blob(payload):
    if payload[:5] != b'\x01\0A\0\x0b':
        raise AssertionError('Review data segment format')
    size, at = integer(payload, 5)
    if len(payload) - at != size:
        raise AssertionError('Invalid data payload size')
    return payload[at:]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('before', type=Path); parser.add_argument('after', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    before, after = args.before.read_bytes(), args.after.read_bytes()
    old, new = sections(before), sections(after)
    if [kind for kind, _ in old] != [kind for kind, _ in new]:
        raise AssertionError('Wasm section structure changed')
    old_blob = blob(next(value for kind, value in old if kind == 11))
    new_blob = blob(next(value for kind, value in new if kind == 11))
    revisions = [re.findall(rb'[0-9a-f]{40}(?:-dirty)?', data) for data in [old_blob, new_blob]]
    if any(len(found) != 1 for found in revisions):
        raise AssertionError('Review build revision strings')
    a, b = [found[0] for found in revisions]
    old_string, new_string = bytes([len(a)]) + a, bytes([len(b)]) + b
    if old_blob.count(old_string) != 1 or old_blob.replace(old_string, new_string) != new_blob:
        raise AssertionError('Data changes beyond the build revision')
    equal_sections = []; changed_metadata = []
    for (kind, left), (_, right) in zip(old, new):
        if left == right:
            equal_sections.append({'kind': kind, 'sha256': hashlib.sha256(left).hexdigest()}); continue
        if kind == 11:
            continue
        if kind != 0:
            raise AssertionError('Executable section changed: ' + str(kind))
        length, offset = integer(left, 0); length2, offset2 = integer(right, 0)
        name = left[offset:offset + length].decode()
        if name != 'teavm.memoryRequirements' or right[offset2:offset2 + length2].decode() != name:
            raise AssertionError('Unexpected custom metadata change: ' + name)
        x, y = json.loads(left[offset + length:]), json.loads(right[offset2 + length2:])
        if x.pop('dataSize') != len(old_blob) or y.pop('dataSize') != len(new_blob) or x != y:
            raise AssertionError('Memory requirements changed beyond string data size')
        changed_metadata.append(name)
    report = {'beforeSha256': hashlib.sha256(before).hexdigest(), 'afterSha256': hashlib.sha256(after).hexdigest(),
        'beforeBytes': len(before), 'afterBytes': len(after), 'beforeBuild': a.decode(), 'afterBuild': b.decode(),
        'onlyBuildRevisionChanged': True, 'identicalExecutableSections': equal_sections,
        'adjustedMetadata': changed_metadata,
        'limitation': 'Binary comparison only; no new end-to-end recognition run is claimed'}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['beforeBytes', 'afterBytes', 'beforeBuild', 'afterBuild', 'onlyBuildRevisionChanged']}))


if __name__ == '__main__':
    main()
