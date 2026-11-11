// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.ui.symbol.*;

/** Extracts logical definitions only; actual native symbol painting is the independent oracle. */
public final class MusicSymbolOracle {
    private static final Set<String> CENTERED=Set.of("ShapeSymbol","CodedSymbol","RestSymbol","ArticulationSymbol",
        "FlatSymbol","AugmentationSymbol","RepeatDotSymbol","StemSymbol","TremoloSymbol","CompoundNoteSymbol","OctaveShiftSymbol");
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Recipe(String family,String text,List<Double> sizeOps,String textFamily,Double textScale,List<Recipe> layers) {
        Recipe(String family,String text,List<Double> sizeOps){this(family,text,sizeOps,null,null,null);}
    }
    private static Object field(Object object,String name)throws Exception {
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static Recipe resolve(ShapeSymbol symbol,MusicFamily family,List<Double> ops,int depth)throws Exception {
        if(depth>12)throw new UnsupportedOperationException("Recursive symbol definition");
        if(symbol instanceof RepeatBarSymbol){
            return new Recipe("","",List.of(),null,null,List.of(code(symbol.getShape(),family,ops),code(Shape.THIN_BARLINE,family,ops)));
        }
        if(symbol instanceof TextSymbol){
            TextFamily textFamily=(TextFamily)field(symbol,"textFamily");if(textFamily==null)textFamily=TextFamily.SansSerif;
            String text=(String)field(symbol,"str");if(text.chars().anyMatch(c->c<32||c>126))throw new UnsupportedOperationException("Text symbol requires shaping/fallback");
            return new Recipe("",text,ops,textFamily.name(),OmrFont.RATIO_TINY,null);
        }
        if(symbol instanceof TransformedSymbol){
            Shape root=(Shape)field(symbol,"root");var transform=(AffineTransform)field(symbol,"at");
            FontSymbol fs=root.getFontSymbol(new MusicFont(family,24));
            if(fs==null)throw new UnsupportedOperationException("Missing transformed root");
            var next=new ArrayList<>(ops);if(fs.font.getMusicFamily()!=family)next.add(0.0); // backup uses rounded Font.size
            if(transform!=null){
                if(transform.getShearX()!=0||transform.getShearY()!=0||transform.getScaleX()!=transform.getScaleY())throw new UnsupportedOperationException("Nonuniform symbol transform");
                next.add((double)(float)transform.getScaleX());
            }
            return resolve(fs.symbol,fs.font.getMusicFamily(),next,depth+1);
        }
        if(!CENTERED.contains(symbol.getClass().getSimpleName()))throw new UnsupportedOperationException(symbol.getClass().getSimpleName());
        if(symbol instanceof CodedSymbol coded){
            var next=new ArrayList<>(ops);if(symbol.getMusicFamily()!=family)next.add(0.0);
            return new Recipe(symbol.getMusicFamily().name(),coded.getString(),next);
        }
        return code(symbol.getShape(),family,ops);
    }
    private static Recipe code(Shape shape,MusicFamily family,List<Double> ops){
        MusicFamily selected=family;int[] codes=selected.getSymbols().getCode(shape);
        while(codes==null&&selected.getBackup()!=null){selected=selected.getBackup();codes=selected.getSymbols().getCode(shape);}
        if(codes==null)throw new UnsupportedOperationException("Missing code");
        var next=new ArrayList<>(ops);if(selected!=family)next.add(0.0);
        return new Recipe(selected.name(),new String(codes,0,codes.length),next);
    }
    private static void textRoles(Recipe recipe,Set<String> roles){
        if(recipe.textFamily!=null)roles.add(recipe.textFamily);if(recipe.layers!=null)for(Recipe layer:recipe.layers)textRoles(layer,roles);
    }
    /** Build-time JVM discovery only: portable code receives an explicit role-to-physical-font binding. */
    private static Map<String,Object> textResources(Map<String,Recipe> recipes,Path output)throws Exception {
        var roles=new TreeSet<String>();recipes.values().forEach(r->textRoles(r,roles));
        Class<?> utilities=Class.forName("sun.font.FontUtilities"),font2d=Class.forName("sun.font.Font2D"),composite=Class.forName("sun.font.CompositeFont"),fileFont=Class.forName("sun.font.FileFont");
        var getFile=fileFont.getDeclaredMethod("getPublicFileName");getFile.setAccessible(true);
        var bindings=new TreeMap<String,String>();var files=new TreeMap<String,Object>();
        Files.createDirectories(output.resolve("fonts"));var digest=MessageDigest.getInstance("SHA-256");
        for(String role:roles){
            Font font=new Font(TextFamily.valueOf(role).getFontName(),Font.PLAIN,24);Object physical=utilities.getMethod("getFont2D",Font.class).invoke(null,font);
            if(composite.isInstance(physical))physical=composite.getMethod("getSlotFont",int.class).invoke(physical,0);
            if(!fileFont.isInstance(physical)||(int)font2d.getMethod("getStyle").invoke(physical)!=Font.PLAIN)throw new IllegalStateException("Unsupported text symbol physical font: "+role);
            for(Recipe recipe:recipes.values())if(role.equals(recipe.textFamily)){
                var gv=font.createGlyphVector(OmrFont.frc,recipe.text);for(int code:gv.getGlyphCodes(0,gv.getNumGlyphs(),null))if((code>>>24)!=0||code==0)throw new IllegalStateException("Text symbol requires physical fallback: "+recipe.text);
            }
            Path source=Path.of((String)getFile.invoke(physical)).toRealPath();byte[] bytes=Files.readAllBytes(source);String hash=HexFormat.of().formatHex(digest.digest(bytes)),filename=hash.substring(0,12)+"-"+source.getFileName();
            String family=(String)font2d.getMethod("getFamilyName",Locale.class).invoke(physical,Locale.ROOT);bindings.put(role,family);
            Files.write(output.resolve("fonts").resolve(filename),bytes);files.put(filename,Map.of("family",family,"sha256",hash,"sourceFile",source.toString()));
        }
        return Map.of("textFamilies",bindings,"fonts",files);
    }
    public static byte[] background(int test,int width,int height){
        byte[] data=new byte[width*height];for(int i=0;i<data.length;i++)data[i]=(byte)(i*37+test*53);return data;
    }
    public static void main(String[] args)throws Exception {
        var ignored=org.audiveris.omr.WellKnowns.RES_URI;
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        MusicFont.populateAllSymbols();var recipes=new TreeMap<String,Recipe>();var unsupported=new TreeMap<String,String>();
        var classes=new TreeMap<String,Integer>();
        for(MusicFamily family:MusicFamily.values())for(Shape shape:Shape.values()){
            String key=family.name()+":"+shape.name();
            FontSymbol fs=shape.getFontSymbolBySize(family,24);
            if(fs==null||fs.symbol==null){unsupported.put(key,"No symbol");continue;}
            classes.merge(fs.symbol.getClass().getSimpleName(),1,Integer::sum);
            try{recipes.put(key,resolve(fs.symbol,fs.font.getMusicFamily(),List.of(),0));}
            catch(UnsupportedOperationException error){unsupported.put(key,error.getMessage());}
        }
        final int width=160,height=160;var image=new BufferedImage(width,height,BufferedImage.TYPE_BYTE_GRAY);
        byte[] pixels=((DataBufferByte)image.getRaster().getDataBuffer()).getData();var graphics=image.createGraphics();
        graphics.setColor(Color.WHITE);graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
        var rows=new ArrayList<Object>();var digest=MessageDigest.getInstance("SHA-256");
        try{
            for(MusicFamily family:MusicFamily.values())for(int size:new int[]{1,12,19,24,37,48,88,100,101,128}){
                var font=new MusicFont(family,size);
                for(Shape shape:Shape.values()){
                    String key=family.name()+":"+shape.name();if(!recipes.containsKey(key))continue;
                    FontSymbol fs=shape.getFontSymbol(font);
                    for(int variant=0;variant<4;variant++){
                        int index=rows.size();System.arraycopy(background(index,width,height),0,pixels,0,pixels.length);
                        double x=variant==3?-1.25:80+variant*.25,y=variant==3?159.5:80+variant*.25;
                        fs.symbol.paintSymbol(graphics,fs.font,new Point2D.Double(x,y),Alignment.AREA_CENTER);
                        rows.add(Map.of("key",key,"size",size,"x",x,"y",y,"background",index,"sha256",HexFormat.of().formatHex(digest.digest(pixels))));
                    }
                }
            }
        }finally{graphics.dispose();}
        Path output=Path.of(args[0]);Files.createDirectories(output);var json=new ObjectMapper();
        json.writeValue(output.resolve("recipes.json").toFile(),recipes);json.writeValue(output.resolve("jvm-reference.json").toFile(),rows);
        Map<String,Object> resources=textResources(recipes,output);json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("font-resources.json").toFile(),resources);
        var inventory=new TreeMap<String,Object>();inventory.put("recipes",recipes.size());inventory.put("cases",rows.size());inventory.put("width",width);inventory.put("height",height);inventory.put("classes",classes);inventory.put("unsupported",unsupported);
        inventory.put("javaRuntime",System.getProperty("java.runtime.version"));inventory.put("pointSizes",List.of(1,12,19,24,37,48,88,100,101,128));
        var fontHashes=new TreeMap<String,String>();for(MusicFamily family:MusicFamily.values())fontHashes.put(family.getFileName(),HexFormat.of().formatHex(digest.digest(Files.readAllBytes(Path.of("res",family.getFileName())))));
        inventory.put("musicFontSha256",new TreeMap<>(fontHashes));
        try(var paths=Files.list(output.resolve("fonts"))){
            for(Path path:paths.toList()){
                String hash=HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
                fontHashes.put(path.getFileName().toString(),hash);
            }
        }
        inventory.put("fontSha256",fontHashes);json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("inventory.json").toFile(),inventory);
        System.out.println("Native symbol recipes: "+recipes.size()+" / cases "+rows.size()+" / explicit unsupported "+unsupported.size());
    }
}
