package org.audiveris.wasm.compat.test;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import org.audiveris.omr.image.TemplateFactory;
import org.audiveris.omr.ui.symbol.*;
import org.audiveris.omr.sheet.Scale;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Native template data from the pinned factory, and its current default implementation. */
public final class TemplateOracle {
    static double[] profile() throws Exception {
        var field=TemplateFactory.class.getDeclaredField("constants");field.setAccessible(true);var constants=field.get(null);
        String[] names={"binarizationThreshold","stemDx","stemDy","holeLargerRatio","defaultPointSize","minCellPerSide","maxRawDistanceFromSymbol"};
        double[] values=new double[names.length];
        for(int i=0;i<names.length;i++) {field=constants.getClass().getDeclaredField(names[i]);field.setAccessible(true);var value=field.get(constants);
            values[i]=((Number)value.getClass().getMethod("getValue").invoke(value)).doubleValue();}
        return values;
    }
    private static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static void main(String[] args) throws Exception {
        var cli=new org.audiveris.omr.CLI("Audiveris");cli.parseParameters(new String[0]);
        var field=org.audiveris.omr.Main.class.getDeclaredField("cli");field.setAccessible(true);field.set(null,cli);
        Path origin=Path.of(TemplateFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if(!origin.equals(Path.of(args[1]).toRealPath())) {throw new AssertionError("Wrong template factory origin: "+origin);}
        Path output=Path.of(args[0]);Files.createDirectories(output);
        MusicFont.populateAllSymbols();
        List<Map<String,Object>> rows=new ArrayList<>();long totalPoints=0;
        for(MusicFamily family:MusicFamily.values()) {for(int size:new int[]{48,64,90,91,92}) {
            byte[] catalog=TemplateCodec.catalog(family,size,profile()),matching=TemplateCodec.match(family,size);
            String name=family.name()+"-"+size;
            Files.write(output.resolve(name+".atpl"),catalog);Files.write(output.resolve(name+".match"),matching);
            int supported=0;long points=0;var templates=TemplateFactory.getInstance().getCatalog(family,size);
            for(var shape:org.audiveris.omr.glyph.ShapeSet.Heads) {var t=templates.getTemplate(shape);if(t!=null){supported++;points+=t.getKeyPoints().size();}}
            rows.add(Map.of("family",family.name(),"pointSize",size,"file",name,"bytes",catalog.length,"sha256",sha(catalog),
                    "matchingBytes",matching.length,"matchingSha256",sha(matching),"supported",supported,"keyPoints",points));totalPoints+=points;
        }}
        field=MusicFont.class.getDeclaredField("constants");field.setAccessible(true);var constants=field.get(null);
        field=constants.getClass().getDeclaredField("headRatio");field.setAccessible(true);
        var ratio=(org.audiveris.omr.constant.Constant.Ratio)field.get(constants);double saved=ratio.getValue();int checks=0;
        try {
            for(double r:new double[]{0.5,1.0,1.5,2.25}) {ratio.setValue(r);
                for(int interline:new int[]{6,12,21,48}) {var scale=new Scale(new Scale.InterlineScale(interline,interline,interline),new Scale.LineScale(2,2,2),new Scale.BeamScale(7,false),null,null);
                    for(int points:new int[]{-1,0,48,91,147}) {
                        scale.setMusicFontScale(points<0?null:new Scale.MusicFontScale("Bravura",points));
                        for(int n=-4;n<=192;n++) {double actualInterline=n/4.0;
                            if(MusicFont.getHeadPointSize(scale,actualInterline)!=FontFamilyDefaults.headPointSize(scale,actualInterline)) {throw new AssertionError("Head point-size calculation differs");}checks++;
                        }
                    }
                }
            }
        } finally {ratio.setValue(saved);}
        Random random=new Random(1048576);long dilationPixels=0;
        for(int n=0;n<2048;n++) {
            int w=3+random.nextInt(30),h=3+random.nextInt(30);byte[] data=new byte[w*h];
            for(int i=0;i<data.length;i++) {data[i]=(n%17==0||random.nextBoolean())?(byte)255:0;}
            var nativeImage=new ij.process.ByteProcessor(w,h,data.clone(),null);
            var rawImage=new ij.process.ByteProcessor(w,h,data.clone(),null);Object owned=rawImage.getPixels();
            for(int k=0;k<3;k++) {nativeImage.dilate();org.audiveris.wasm.compat.platform.TemplateDilation.dilate(rawImage);
                if(owned!=rawImage.getPixels()||!Arrays.equals((byte[])nativeImage.getPixels(),(byte[])rawImage.getPixels())) {throw new AssertionError("Template dilation differs");}dilationPixels+=data.length;}
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("reference.json").toFile(),Map.of("catalogs",rows,"keyPoints",totalPoints,"headPointSizeChecks",checks,"profile",profile(),"dilationCases",2048,"dilationPixels",dilationPixels));
        System.out.println("Native template catalogs="+rows.size()+" points="+totalPoints+" head-point-size checks="+checks);
    }
}
