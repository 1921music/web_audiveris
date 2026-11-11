// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.*;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.Point2D;
import java.awt.image.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.ui.symbol.*;

/** JVM-only FreeType candidate; native glyph IDs and layout are still supplied by AWT. */
public final class MusicMaskTrace {
    private static final int SIDE=160;
    private record Key(String family,int glyph,double size,int hinting) { }
    private record Mask(int x,int y,int width,int height,byte[] alpha) { }
    public static void main(String[] args) throws Exception {
        if (!Path.of(org.audiveris.omr.WellKnowns.RES_URI).equals(Path.of("res").toAbsolutePath())) throw new AssertionError("Unexpected font resources");
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        MusicFont.populateAllSymbols();
        var nativeImage=new BufferedImage(SIDE,SIDE,BufferedImage.TYPE_BYTE_GRAY);
        byte[] reference=((DataBufferByte)nativeImage.getRaster().getDataBuffer()).getData();
        var original=nativeImage.createGraphics();
        var library=NativeLibrary.getInstance(Path.of(args[0]).toAbsolutePath().toString());
        int[] version=new int[3];
        if(library.getFunction("music_mask_version").invokeInt(new Object[]{version})!=0 || !Arrays.equals(version,new int[]{2,14,3}))
            throw new AssertionError("Unexpected linked FreeType version");
        var faces=new HashMap<String,Pointer>(); var files=new HashMap<String,Path>();
        for (MusicFamily family : MusicFamily.values()) files.put(family.getFontName().replace(" ","").toLowerCase(Locale.ROOT),Path.of("res",family.getFileName()).toAbsolutePath());
        var fontHashes=new TreeMap<String,String>();
        for(Path file:files.values()) fontHashes.put(file.getFileName().toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        var cache=new HashMap<Key,Mask>(); var candidates=new Candidate[4];
        Path output=Path.of(args[1]); Files.createDirectories(output);
        var programs=new ArrayList<Object>();
        MessageDigest pixelDigest=MessageDigest.getInstance("SHA-256");
        for(int mode=0;mode<4;mode++) {
            var image=new BufferedImage(SIDE,SIDE,BufferedImage.TYPE_BYTE_GRAY);
            byte[] pixels=((DataBufferByte)image.getRaster().getDataBuffer()).getData();
            var wrapper=new MaskGraphics(image.createGraphics(),pixels,mode,library,files,faces,cache);
            candidates[mode]=new Candidate(wrapper,pixels);
        }
        original.setColor(Color.WHITE); original.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
        Shape[] shapes={Shape.NOTEHEAD_BLACK,Shape.NOTEHEAD_VOID,Shape.NOTEHEAD_BLACK_SMALL,Shape.WHOLE_REST,Shape.HALF_REST,
            Shape.QUARTER_REST,Shape.EIGHTH_REST,Shape.ONE_16TH_REST,Shape.FERMATA,Shape.FERMATA_BELOW,Shape.DYNAMICS_P,
            Shape.DYNAMICS_PP,Shape.DYNAMICS_MP,Shape.DYNAMICS_F,Shape.DYNAMICS_FF,Shape.DYNAMICS_MF,Shape.DYNAMICS_SFZ,Shape.PEDAL_MARK,Shape.PEDAL_UP_MARK};
        int cases=0; long intermediatePixels=0;
        try(var nativeOutput=new java.security.DigestOutputStream(Files.newOutputStream(output.resolve("jvm-pixels.binary")),pixelDigest)) {
            for(MusicFamily family:MusicFamily.values()) for(Shape shape:shapes) for(int size:new int[]{12,19,24,37,48}) {
                FontSymbol fs=shape.getFontSymbolBySize(family,size);
                if(fs.symbol==null) throw new AssertionError("Missing selected symbol");
                for(double fraction:new double[]{0,.25,.5,.75}) {
                    var center=new Point2D.Double(SIDE/2.0+fraction,SIDE/2.0+fraction);
                    Arrays.fill(reference,(byte)0); fs.symbol.paintSymbol(original,fs.font,center,Alignment.AREA_CENTER);
                    nativeOutput.write(reference);
                    for(byte pixel:reference) if(pixel!=0 && pixel!=(byte)255) intermediatePixels++;
                    for(int mode=0;mode<candidates.length;mode++) {
                        Candidate candidate=candidates[mode]; Arrays.fill(candidate.pixels,(byte)0);
                        candidate.graphics.operations.clear();
                        fs.symbol.paintSymbol(candidate.graphics,fs.font,center,Alignment.AREA_CENTER);
                        long differences=0;
                        for(int i=0;i<reference.length;i++) if(reference[i]!=candidate.pixels[i]) differences++;
                        candidate.pixelsDifferent+=differences;
                        if(differences!=0) {
                            candidate.casesDifferent++;
                            if(candidate.examples.size()<8) candidate.examples.add(Map.of("family",family.name(),"shape",shape.name(),"size",size,"fraction",fraction,"pixels",differences));
                        }
                    }
                    if(candidates[3].graphics.operations.isEmpty())throw new AssertionError("Missing glyph program");
                    programs.add(Map.of("family",family.name(),"shape",shape.name(),"size",size,"fraction",fraction,
                        "glyphs",new ArrayList<>(candidates[3].graphics.operations)));
                    cases++;
                }
            }
        } finally {
            original.dispose(); for(Candidate c:candidates) c.graphics.dispose();
            for(Pointer face:faces.values()) library.getFunction("music_mask_close").invokeVoid(new Object[]{face});
            library.close();
        }
        if(cases!=2280) throw new AssertionError("Incomplete fixture grid");
        if(candidates[3].casesDifferent!=0 || intermediatePixels==0) throw new AssertionError("Unhinted masks with rounded origins no longer reproduce native painting");
        var rows=new ArrayList<Object>();
        for(int mode=0;mode<candidates.length;mode++) {
            Candidate c=candidates[mode]; rows.add(Map.of("noHinting",mode/2==1,"roundOrigin",mode%2==1,
                "differentCases",c.casesDifferent,"differentPixels",c.pixelsDifferent,"examples",c.examples));
        }
        var json=new ObjectMapper(); Path programFile=output.resolve("glyph-programs.json");
        json.writeValue(programFile.toFile(),programs);
        var result=new LinkedHashMap<String,Object>();
        result.put("cases",cases);result.put("candidates",rows);result.put("cachedMasks",cache.size());
        result.put("nativeIntermediatePixels",intermediatePixels);result.put("fontSha256",fontHashes);
        result.put("linkedFreeTypeVersion",version);result.put("javaRuntime",System.getProperty("java.runtime.version"));
        result.put("width",SIDE);result.put("height",SIDE);result.put("nativePixelsSha256",HexFormat.of().formatHex(pixelDigest.digest()));
        result.put("glyphProgramsSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(programFile))));
        result.put("limitation","Native-only candidate: actual AWT glyph IDs, positions and font selection are still required. No portable font backend or Wasm result is established.");
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("verification.json").toFile(),result);
        System.out.println("Music FreeType mask candidates: "+new ObjectMapper().writeValueAsString(rows));
    }
    private static final class Candidate {
        final MaskGraphics graphics; final byte[] pixels; int casesDifferent; long pixelsDifferent;
        final ArrayList<Object> examples=new ArrayList<>();
        Candidate(MaskGraphics graphics,byte[] pixels){this.graphics=graphics;this.pixels=pixels;}
    }
    private static final class MaskGraphics extends GraphicsDelegate {
        final byte[] pixels; final int mode; final NativeLibrary library; final Map<String,Path> files;
        final Map<String,Pointer> faces; final Map<Key,Mask> cache;
        final ArrayList<Object> operations=new ArrayList<>();
        MaskGraphics(Graphics2D delegate,byte[] pixels,int mode,NativeLibrary library,Map<String,Path> files,Map<String,Pointer> faces,Map<Key,Mask> cache){
            super(delegate);this.pixels=pixels;this.mode=mode;this.library=library;this.files=files;this.faces=faces;this.cache=cache;
            delegate.setColor(Color.WHITE);delegate.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
        }
        @Override public Graphics create(){throw new AssertionError("Unexpected child graphics");}
        @Override public void fill(java.awt.Shape shape){throw new AssertionError("Unexpected outline operation");}
        @Override public void draw(java.awt.Shape shape){throw new AssertionError("Unexpected outline operation");}
        @Override public void fillRect(int x,int y,int width,int height){throw new AssertionError("Unexpected rectangle operation");}
        @Override public void drawGlyphVector(GlyphVector glyphs,float x,float y){
            if(!Color.WHITE.equals(delegate.getColor()) || !AlphaComposite.SrcOver.equals(delegate.getComposite())) throw new AssertionError("Unsupported glyph paint/composite");
            if(!delegate.getTransform().isIdentity() || delegate.getClip()!=null) throw new AssertionError("Unsupported device transform/clip");
            Font font=glyphs.getFont(); var transform=font.getTransform();
            if(transform.getShearX()!=0 || transform.getShearY()!=0 || transform.getTranslateX()!=0 || transform.getTranslateY()!=0 ||
                transform.getScaleX()!=transform.getScaleY() || transform.getScaleX()<=0 || font.getStyle()!=Font.PLAIN)
                throw new AssertionError("Unsupported font transform/style: "+font+" / "+transform);
            String family=font.getFamily(Locale.ROOT).replace(" ","").toLowerCase(Locale.ROOT);
            Path file=files.get(family);if(file==null)throw new AssertionError("Unknown physical music family: "+family);
            Pointer face=faces.computeIfAbsent(family,key->{
                Pointer p=library.getFunction("music_mask_open").invokePointer(new Object[]{file.toString()});
                if(p==null)throw new AssertionError("FreeType could not open "+file);return p;
            });
            for(int i=0;i<glyphs.getNumGlyphs();i++) {
                if(glyphs.getGlyphTransform(i)!=null)throw new AssertionError("Per-glyph transform");
                var key=new Key(family,glyphs.getGlyphCode(i),font.getSize2D()*transform.getScaleX(),mode/2);
                Mask mask=cache.computeIfAbsent(key,k->{
                    int[] info=new int[4]; byte[] alpha=new byte[512*512];
                    int error=library.getFunction("music_mask_render").invokeInt(new Object[]{face,k.glyph,k.size,k.hinting,info,alpha,alpha.length});
                    if(error!=0)throw new AssertionError("FreeType glyph error "+error+" / "+key);
                    return new Mask(info[0],info[1],info[2],info[3],Arrays.copyOf(alpha,info[2]*info[3]));
                });
                Point2D position=glyphs.getGlyphPosition(i);double bias=mode%2==1?.5:0;
                int originX=(int)Math.floor(x+position.getX()+bias),originY=(int)Math.floor(y+position.getY()+bias);
                if(mode==3)operations.add(Map.of("font",file.getFileName().toString(),"glyph",key.glyph,"size",key.size,"x",originX,"y",originY));
                int dx=originX+mask.x,dy=originY+mask.y;
                for(int row=0;row<mask.height;row++)for(int col=0;col<mask.width;col++){
                    int px=dx+col,py=dy+row;if(px<0 || py<0 || px>=SIDE || py>=SIDE)continue;
                    int alpha=mask.alpha[row*mask.width+col]&255,index=py*SIDE+px,old=pixels[index]&255;
                    pixels[index]=(byte)(alpha+(old*(255-alpha)+127)/255);
                }
            }
        }
        @Override public void drawString(String text,float x,float y){throw new AssertionError("Unexpected drawString");}
        @Override public void drawString(String text,int x,int y){throw new AssertionError("Unexpected drawString");}
    }
}
