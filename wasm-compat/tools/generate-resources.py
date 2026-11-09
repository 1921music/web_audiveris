"""Bundle an explicit resource allowlist, without scanning dependencies or including UI assets."""
import base64
import hashlib
import json
import sys
from pathlib import Path

root, output = Path(sys.argv[1]).resolve(), Path(sys.argv[2])
manifest = json.loads((root / 'wasm-compat/resources.json').read_text(encoding='utf-8'))
lines = ['package org.audiveris.wasm.compat.resources;',
         'public final class BuiltinResources {',
         '  public static void load(java.util.Map<String, byte[]> target) {']
metadata = {}
for name, relative in sorted(manifest.items()):
    source = (root / relative).resolve()
    if root not in source.parents or name.startswith('/') or '..' in name.split('/'):
        raise ValueError('Resource must stay inside the source checkout: ' + name)
    data = source.read_bytes()
    encoded = base64.b64encode(data).decode('ascii')
    chunks = [json.dumps(encoded[i:i + 16000]) for i in range(0, len(encoded), 16000)]
    expression = 'String.join("", new String[]{' + ','.join(chunks) + '})'
    lines.append('    target.put(' + json.dumps(name) + ', java.util.Base64.getDecoder().decode(' + expression + '));')
    destination = output / 'classpath' / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    metadata[name] = {'source': relative, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
lines += ['  }', '}']
source = output / 'src/org/audiveris/wasm/compat/resources/BuiltinResources.java'
source.parent.mkdir(parents=True, exist_ok=True)
source.write_text('\n'.join(lines) + '\n', encoding='utf-8')
bundles = [name[:-len('.properties')].replace('/', '.') for name in sorted(manifest) if name.endswith('.properties')]
if bundles:
    providers = output / 'classpath/META-INF/services/java.util.ResourceBundle'
    providers.parent.mkdir(parents=True, exist_ok=True)
    providers.write_text('\n'.join(bundles) + '\n', encoding='utf-8')
(output / 'resources.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
