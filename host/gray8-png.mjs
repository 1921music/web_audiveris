// SPDX-License-Identifier: AGPL-3.0-or-later
// Native CompressionStream keeps image encoding independent of Canvas and OCR implementations.
const crcTable=Uint32Array.from({length:256},(_,n)=>{
    let value=n;for(let i=0;i<8;i++){value=(value>>>1)^((value&1)?0xedb88320:0);}return value>>>0;
});
function chunk(type,data){
    const result=new Uint8Array(12+data.length),view=new DataView(result.buffer);
    view.setUint32(0,data.length);for(let i=0;i<4;i++){result[4+i]=type.charCodeAt(i);}result.set(data,8);
    let crc=0xffffffff;for(let i=4;i<8+data.length;i++){crc=(crc>>>8)^crcTable[(crc^result[i])&255];}
    view.setUint32(8+data.length,(crc^0xffffffff)>>>0);return result;
}
export async function encodeGray8Png({width,height,pixels}){
    if(!Number.isInteger(width)||!Number.isInteger(height)||width<=0||height<=0||width>0x7fffffff||height>0x7fffffff
            ||!(pixels instanceof Uint8Array)||width*height!==pixels.length){throw new TypeError('Invalid packed GRAY8 image');}
    const scanlines=new Uint8Array((width+1)*height);
    for(let y=0;y<height;y++){scanlines.set(pixels.subarray(y*width,(y+1)*width),y*(width+1)+1);}
    const compressed=new Uint8Array(await new Response(new Blob([scanlines]).stream().pipeThrough(new CompressionStream('deflate'))).arrayBuffer());
    const header=new Uint8Array(13),view=new DataView(header.buffer);view.setUint32(0,width);view.setUint32(4,height);header[8]=8;
    const pieces=[new Uint8Array([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',compressed),chunk('IEND',new Uint8Array())];
    const result=new Uint8Array(pieces.reduce((size,piece)=>size+piece.length,0));let offset=0;
    for(const piece of pieces){result.set(piece,offset);offset+=piece.length;}return result;
}
