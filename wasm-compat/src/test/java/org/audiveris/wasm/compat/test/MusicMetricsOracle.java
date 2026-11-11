// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.*;
import java.awt.geom.Rectangle2D;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.audiveris.omr.sig.inter.BeatUnitInter;
import org.audiveris.omr.ui.symbol.*;

/** Independent actual MusicFont references, including all dotted beat-unit strings. */
public final class MusicMetricsOracle {
    private record Key(int size,int codepoint) { }
    private record Glyph(int id,double[] values) { }
    public static void main(String[] args) throws Exception {
        if(!Path.of(org.audiveris.omr.WellKnowns.RES_URI).equals(Path.of("res").toAbsolutePath()))throw new AssertionError("Font directory");
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        MusicFont.populateAllSymbols();
        var references=new ArrayList<Object>();var differences=new ArrayList<Object>();var fontHashes=new TreeMap<String,String>();
        int differentCases=0,mappingDifferences=0,portableCases=0,portableDifferences=0;double maxDifference=0;
        var familyCounts=new TreeMap<String,Object>();
        try(var api=NativeLibrary.getInstance(Path.of(args[0]).toAbsolutePath().toString())) {
            int[] version=new int[3];if(api.getFunction("music_mask_version").invokeInt(new Object[]{version})!=0 || !Arrays.equals(version,new int[]{2,14,3}))throw new AssertionError("FreeType version");
            for(MusicFamily family:MusicFamily.values()) {
                Path file=Path.of("res",family.getFileName()).toAbsolutePath();
                fontHashes.put(file.getFileName().toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                Pointer face=api.getFunction("music_mask_open").invokePointer(new Object[]{file.toString()});if(face==null)throw new AssertionError("Missing font");
                try {
                    byte[] nameBytes=new byte[1024];int nameLength=api.getFunction("music_metrics_full_name").invokeInt(new Object[]{face,nameBytes,nameBytes.length});
                    if(nameLength<=0)throw new AssertionError("Missing music font name");
                    String name=new String(nameBytes,0,nameLength,java.nio.charset.StandardCharsets.UTF_8);
                    var cache=new HashMap<Key,Glyph>();
                    int familyDifferent=0,familyPortable=0;var missing=new TreeSet<Integer>();
                    for(int index=1;index<=129;index++) {
                        int size=index==129?16384:index;var font=new MusicFont(family,size);
                        for(BeatUnitInter.Note note:BeatUnitInter.Note.values()) {
                            String value=note.getString();var vector=font.createGlyphVector(OmrFont.frc,value);
                            var visual=vector.getVisualBounds();var layout=font.layout(value);
                            double[] candidate=measure(api,face,size,value,cache);
                            int[] ids=new int[value.length()];int mappingCount=0;
                            for(int i=0;i<value.length();i++) {
                                ids[i]=cache.get(new Key(size,value.charAt(i))).id;
                                if(ids[i]!=vector.getGlyphCode(i))mappingCount++;
                            }
                            mappingDifferences+=mappingCount;
                            boolean portable=true;
                            for(int i=0;i<ids.length;i++)if(ids[i]==0){portable=false;missing.add((int)value.charAt(i));}
                            var row=new LinkedHashMap<String,Object>();row.put("family",family.name());row.put("font",family.getFileName());row.put("name",font.getFontName());
                            row.put("size",size);row.put("value",value);row.put("visual",bounds(visual));row.put("layout",bounds(layout.getBounds()));row.put("advance",(double)layout.getAdvance());
                            row.put("portable",portable);
                            references.add(row);
                            double delta=0;
                            for(Rectangle2D box:new Rectangle2D[]{visual,layout.getBounds()}) {
                                double[] expected=bounds(box);for(int i=0;i<4;i++)delta=Math.max(delta,Math.abs(candidate[i]-expected[i]));
                            }
                            delta=Math.max(delta,Math.abs(candidate[4]-layout.getAdvance()));maxDifference=Math.max(maxDifference,delta);
                            boolean different=delta!=0 || mappingCount!=0 || !name.equals(font.getFontName());
                            if(portable){portableCases++;familyPortable++;if(different)portableDifferences++;}
                            if(different) {
                                differentCases++;familyDifferent++;
                                if(differences.size()<24)differences.add(Map.of("reference",row,"candidate",candidate,"nativeIds",vector.getGlyphCodes(0,vector.getNumGlyphs(),null),"candidateIds",ids,"candidateName",name));
                            }
                        }
                    }
                    familyCounts.put(family.name(),Map.of("portableCases",familyPortable,"differentCases",familyDifferent,"missingCodepoints",missing));
                } finally {api.getFunction("music_mask_close").invokeVoid(new Object[]{face});}
            }
        }
        Path output=Path.of(args[1]);Files.createDirectories(output);var json=new ObjectMapper();
        json.writeValue(output.resolve("jvm-reference.json").toFile(),references);
        var report=new LinkedHashMap<String,Object>();report.put("cases",references.size());report.put("differentCases",differentCases);
        report.put("mappingDifferences",mappingDifferences);report.put("maxDifference",maxDifference);report.put("fontSha256",fontHashes);
        report.put("differences",differences);report.put("javaRuntime",System.getProperty("java.runtime.version"));
        report.put("portableCases",portableCases);report.put("portableDifferences",portableDifferences);report.put("families",familyCounts);
        report.put("limitation","Unshaped music metrics for characters present in the physical font. Missing glyphs need a separate fallback/shaping backend; those cases remain diagnostic only.");
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("comparison.json").toFile(),report);
        System.out.println("Native music font metrics: "+differentCases+"/"+references.size()+" cases differ; max "+maxDifference+"; cmap differences "+mappingDifferences);
        System.out.println("Supported-character music metrics: "+portableCases+" cases / "+portableDifferences+" differences; "+json.writeValueAsString(familyCounts));
        if(portableCases<2322 || portableDifferences!=0)throw new AssertionError("Supported-character music metrics differ");
    }
    private static double[] bounds(Rectangle2D box){return new double[]{box.getX(),box.getY(),box.getWidth(),box.getHeight()};}
    private static double[] measure(NativeLibrary api,Pointer face,int size,String value,Map<Key,Glyph> cache){
        float position=0,minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        for(int codepoint:value.codePoints().toArray()) {
            Glyph glyph=cache.computeIfAbsent(new Key(size,codepoint),key->{
                int id=api.getFunction("music_metrics_glyph").invokeInt(new Object[]{face,key.codepoint});
                double[] result=new double[5];int error=api.getFunction("music_metrics_measure").invokeInt(new Object[]{face,id,key.size,result});
                if(error!=0)throw new AssertionError("Metric error "+error);return new Glyph(id,result);
            });
            double[] box=glyph.values;
            if(box[2]>0 && box[3]>0) {
                float x=position+(float)box[0],y=(float)box[1];minX=Math.min(minX,x);minY=Math.min(minY,y);
                maxX=Math.max(maxX,x+(float)box[2]);maxY=Math.max(maxY,y+(float)box[3]);
            }
            position+=(float)box[4];
        }
        return Float.isInfinite(minX)?new double[]{0,0,0,0,position}:new double[]{minX,minY,maxX-minX,maxY-minY,position};
    }
}
