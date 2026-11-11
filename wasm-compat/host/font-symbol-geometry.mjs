// SPDX-License-Identifier: AGPL-3.0-or-later
/** Synchronous font-derived geometry; intrinsic data are font assets, independent of score pages. */
export function createSymbolGeometry(music,intrinsics) {
    const fixed=structuredClone(intrinsics),rint=x=>{const floor=Math.floor(x),fraction=x-floor;return fraction===.5?floor+(floor%2):Math.floor(x+.5);};
    return (kind,family,shape,scale,numerator,denominator)=>{
        if(!Number.isInteger(scale)||scale<0||scale>4096)throw new RangeError('Invalid symbol scale');
        if(kind===1||kind===3){
            const key=kind+':'+family+':'+shape,value=fixed[key];
            if(!value||value.length!==4||!value.every(Number.isFinite))throw new Error('Missing font intrinsic: '+key);
            return {a:value[0],b:value[1],c:value[2],d:value[3]};
        }
        if(kind===2){
            const size=4*scale,num=music.measureNumber(family,numerator,size),den=music.measureNumber(family,denominator,size);
            return {a:rint(Math.max(num.width,den.width)),b:rint(2*rint((size+2)/4)+Math.max(num.height,den.height)),c:0,d:0};
        }
        const box=music.measureSymbol(family,shape,kind===4?scale:4*scale);
        if(kind===4)return {a:box.width,b:0,c:0,d:0};
        if(kind!==0)throw new RangeError('Unknown symbol geometry kind');
        return {a:box.x,b:box.y,c:box.width,d:box.height};
    };
}
