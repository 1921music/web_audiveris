// SPDX-License-Identifier: AGPL-3.0-or-later
const fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url'),assert=require('node:assert/strict');
(async()=>{
    const root=path.resolve(process.argv[2]),fontRoot=path.join(root,'build/wasm-engine/fonts'),data=path.join(root,'build/wasm-engine/font-data');
    const factory=(await import(pathToFileURL(path.join(fontRoot,'music-symbols.mjs')))).default;
    const {createFreeTypeMusicSymbols}=await import(pathToFileURL(path.join(root,'wasm-compat/host/freetype-music-symbols.mjs')));
    const {createSymbolGeometry}=await import(pathToFileURL(path.join(root,'wasm-compat/host/font-symbol-geometry.mjs')));
    const read=file=>JSON.parse(fs.readFileSync(file)),fonts=Object.fromEntries(fs.readdirSync(path.join(fontRoot,'fonts')).map(name=>[name,new Uint8Array(fs.readFileSync(path.join(fontRoot,'fonts',name)))]));
    const service=await createFreeTypeMusicSymbols(factory,new Uint8Array(fs.readFileSync(path.join(fontRoot,'music-symbols.wasm'))),fonts,read(path.join(fontRoot,'recipes.json')),{textFamilies:read(path.join(fontRoot,'font-resources.json')).textFamilies});
    try{
        const geometry=createSymbolGeometry(service,read(path.join(data,'intrinsics.json'))),failures=[];let cases=0,maxDifference=0;
        for(const row of read(path.join(data,'geometry-reference.json'))){
            try{const value=geometry(row.kind,row.family,row.shape,row.scale,row.num,row.den),actual=[value.a,value.b,value.c,value.d];
                row.values.forEach((v,i)=>{const difference=Math.abs(actual[i]-v);maxDifference=Math.max(maxDifference,difference);assert.ok(Number.isFinite(actual[i])&&difference<=1e-8,'value '+i+': '+actual[i]+' != '+v);});
            }catch(error){if(failures.length<12)failures.push({row,error:String(error)});}cases++;
        }
        if(failures.length)throw new Error(JSON.stringify({cases,maxDifference,failures}));
        console.log(JSON.stringify({cases,maxDifference,pageSpecificMeasurements:0}));
    }finally{service.dispose();}
})().catch(error=>{console.error(error);process.exitCode=1;});
