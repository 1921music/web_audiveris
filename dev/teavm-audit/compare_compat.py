"""Compare identical original-method roots, without treating root pruning as an API fix."""
import argparse
import csv
import json
from pathlib import Path


def rows(path):
    with path.open(encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def compare(baseline, patched):
    before_roots = {row['method']: (row['class'], row['hasBody'], row['native'], row['category'])
                    for row in rows(baseline / 'roots.tsv')}
    after_roots = {row['method']: (row['class'], row['hasBody'], row['native'], row['category'])
                   for row in rows(patched / 'roots.tsv')}
    if before_roots != after_roots:
        raise AssertionError('Original method roots changed; diagnostic differences are not comparable')
    before = json.loads((baseline / 'classified-summary.json').read_text(encoding='utf-8'))
    after = json.loads((patched / 'classified-summary.json').read_text(encoding='utf-8'))
    before_apis = {api['message']: api for api in before['apis']}
    after_apis = {api['message']: api for api in after['apis']}
    removed = sorted(before_apis.keys() - after_apis.keys())
    added = sorted(after_apis.keys() - before_apis.keys())
    prefixes = ['Field java.awt.Color.', 'Method java.awt.Color.', 'Method java.awt.Point.', 'Method java.awt.Dimension.',
                'Method java.util.Collections.unmodifiableSortedSet(', 'Method java.util.Collections.emptySortedSet(',
                'Method java.io.File.toPath(', 'Method java.lang.String.lines(', 'Method java.lang.String.getBytes(II[BI)',
                'Method java.lang.String.<init>([BI)', 'Method java.lang.String.<init>([BIII)',
                'Method java.util.Arrays.compare([B[B)', 'Method java.lang.Integer.parseUnsignedInt(Ljava/lang/String;)',
                'Method java.nio.charset.Charset.isSupported(', 'Method java.lang.Class.getPackageName(',
                'Method java.lang.Class.descriptorString(', 'Method java.lang.Class.componentType(',
                'Method java.lang.Class.arrayType(', 'Method java.lang.Class.forPrimitiveName(',
                'Method java.util.Enumeration.asIterator(', 'Method java.util.ServiceLoader.findFirst(',
                'Method java.util.Spliterators.emptySpliterator(', 'Method java.util.Collections.singletonSpliterator(',
                'Method java.util.Date.from(', 'Method java.net.URLConnection.getContentLengthLong(',
                'Method java.util.concurrent.atomic.AtomicLong.compareAndExchange(',
                'Method java.lang.Thread.threadId(', 'Method java.lang.Thread.isVirtual(', 'Method java.lang.Thread.onSpinWait(',
                'Method java.lang.Thread.dumpStack(', 'Method java.lang.ref.Reference.reachabilityFence(',
                'Method java.util.UUID.<init>(JJ)', 'Method java.util.UUID.getMostSignificantBits(',
                'Method java.util.UUID.getLeastSignificantBits(', 'Method java.util.Collections.emptySortedMap(',
                'Method java.util.Collections.unmodifiableSortedMap(', 'Method java.lang.reflect.Method.isDefault(',
                'Method java.util.Properties.loadFromXML(', 'Method java.util.Properties.store(Ljava/io/Writer;',
                'Method java.lang.ClassLoader.loadClass(Ljava/lang/String;)',
                'Method java.lang.ClassLoader.getResource', 'Method java.lang.ClassLoader.getSystemResource',
                'Method java.lang.Class.getResource', 'Method java.lang.Class.getModule(',
                'Method java.lang.Class.forName(Ljava/lang/Module;',
                'Method java.lang.ClassLoader.getUnnamedModule(',
                'Method java.lang.ClassLoader.getPlatformClassLoader(',
                'Method java.lang.ClassLoader.registerAsParallelCapable(',
                'Method java.lang.ClassLoader.isRegisteredAsParallelCapable(',
                'Method java.lang.ClassLoader.getName(', 'Method java.lang.ClassLoader.<init>(Ljava/lang/String;',
                'Method java.lang.Thread.setContextClassLoader(',
                'Method java.lang.Package.getImplementationVersion(', 'Method java.lang.Package.getImplementationVendor(',
                'Method java.lang.Package.getSpecificationTitle(',
                'Method java.util.ResourceBundle.getBundle(Ljava/lang/String;Ljava/util/Locale;Ljava/lang/Module;',
                'Method java.util.Arrays.compare([Ljava/lang/Comparable;',
                'Method java.util.stream.Stream.ofNullable(',
                'Method java.lang.System$Logger.log(Ljava/lang/System$Logger$Level;Ljava/lang/String;[Ljava/lang/Object;']
    remaining_targets = [message for message in after_apis if any(message.startswith(prefix) for prefix in prefixes)]
    result = {'identicalOriginalRoots': True, 'rootCount': len(before_roots),
              'baselineDiagnostics': before['diagnostics'], 'patchedDiagnostics': after['diagnostics'],
              'diagnosticReduction': before['diagnostics'] - after['diagnostics'],
              'baselineUniqueMessages': before['uniqueMessages'], 'patchedUniqueMessages': after['uniqueMessages'],
              'removedMessages': removed, 'newMessages': added,
              'newDiagnosticCount': sum(after_apis[message]['count'] for message in added),
              'remainingTargetedApiMessages': remaining_targets,
              'baselineByFamily': before['byFamily'], 'patchedByFamily': after['byFamily'],
              'baselineByDirectOwner': before['byDirectOwner'], 'patchedByDirectOwner': after['byDirectOwner']}
    (patched / 'compatibility-comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if remaining_targets:
        raise AssertionError('Targeted API gaps remain: ' + repr(remaining_targets))
    print(json.dumps({key: value for key, value in result.items() if key not in ('removedMessages', 'newMessages')}, ensure_ascii=False))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('baseline', type=Path)
    parser.add_argument('patched', type=Path)
    args = parser.parse_args()
    compare(args.baseline, args.patched)
