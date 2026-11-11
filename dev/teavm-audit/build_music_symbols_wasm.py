"""Build combined mask/metric APIs using the existing verified FreeType archive and build."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root=Path(sys.argv[1]);base=root/'build/wasm-compat/freetype-native';output=Path(sys.argv[2]) if len(sys.argv)>2 else root/'build/wasm-compat/music-symbols-wasm'
    output.mkdir(parents=True,exist_ok=True)
    assert hashlib.sha256((base/'freetype-2.14.3.tar.xz').read_bytes()).hexdigest()=='36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f'
    exports=['_music_mask_memory_open','_music_mask_close','_music_mask_version','_music_mask_render',
             '_music_metrics_family','_music_metrics_full_name','_music_metrics_glyph','_music_metrics_measure','_music_symbol_glyph','_music_symbol_metrics','_music_symbol_outline','_malloc','_free']
    subprocess.run(['emcc',str(root/'dev/teavm-audit/music-symbols-wasm.c'),str(base/'wasm-build/libfreetype.a'),
        '-I'+str(base/'freetype-2.14.3/include'),'-Oz','-flto','-sMODULARIZE=1','-sEXPORT_ES6=1','-sENVIRONMENT=web,worker,node',
        '-sFILESYSTEM=0','-sALLOW_MEMORY_GROWTH=1','-sINITIAL_MEMORY=16777216','-sMAXIMUM_MEMORY=134217728','-sSTACK_SIZE=1048576',
        '-sMALLOC=emmalloc','-sEXPORTED_FUNCTIONS='+json.dumps(exports),'-sEXPORTED_RUNTIME_METHODS='+json.dumps(['HEAPU8','HEAP32','HEAPF64','UTF8ToString']),
        '-o',str(output/'music-symbols.mjs')],check=True)
    reference=root/'build/wasm-compat/music-symbols-native'
    for name in ['recipes.json','jvm-reference.json','inventory.json','font-resources.json']:shutil.copyfile(reference/name,output/name)
    (output/'fonts').mkdir(exist_ok=True)
    for path in (root/'build/wasm-compat/music-metrics-wasm/fonts').iterdir():shutil.copyfile(path,output/'fonts'/path.name)
    resources=json.loads((reference/'font-resources.json').read_text())
    for name,record in resources['fonts'].items():
        path=reference/'fonts'/name;assert hashlib.sha256(path.read_bytes()).hexdigest()==record['sha256']
        assert Path(record['sourceFile']).parent==Path('/usr/share/fonts/truetype/dejavu'), 'Review text font license'
        shutil.copyfile(path,output/'fonts'/name)
    shutil.copyfile('/usr/share/doc/fonts-dejavu-core/copyright',output/'text-font-COPYRIGHT')
    for name in ['FTL.TXT','GPLv2.TXT','LICENSE.TXT']:shutil.copyfile(root/'build/wasm-compat/music-metrics-wasm'/name,output/name)
    names=['music-symbols-wasm.c','music-mask-native.c','music-mask-wasm.c','music-metrics-wasm.c','music-metrics-functions.h','build_music_symbols_wasm.py']
    result={'sourceSha256':{name:hashlib.sha256((root/'dev/teavm-audit'/name).read_bytes()).hexdigest() for name in names},
        'wasmBytes':(output/'music-symbols.wasm').stat().st_size,'wasmSha256':hashlib.sha256((output/'music-symbols.wasm').read_bytes()).hexdigest(),
        'maximumLinearMemoryBytes':134217728,'recipesSha256':hashlib.sha256((output/'recipes.json').read_bytes()).hexdigest(),
        'nativeReferenceSha256':hashlib.sha256((output/'jvm-reference.json').read_bytes()).hexdigest()}
    (output/'build.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))

if __name__=='__main__':main()
