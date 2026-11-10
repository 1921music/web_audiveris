package org.audiveris.wasm.compat.test;

import java.awt.Rectangle;
import java.nio.file.*;
import java.util.*;
import org.audiveris.omr.text.*;
import org.audiveris.omr.ui.symbol.*;

/** Compare the extracted measurement seam with the original expressions, then replay without AWT. */
public final class TextGeometryOracle {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); Files.createDirectories(root);
        var nativeProvider=TextFontGeometry.provider(); var recording=new RecordingTextFontGeometry(nativeProvider);
        TextFontGeometry.setProvider(recording); int checks=0;
        var cases=new ArrayList<Map<String,Object>>();
        try {
            for(int flags=0;flags<64;flags++){
                var attrs=new FontAttributes((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0,(flags&16)!=0,(flags&32)!=0);
                for(int size:new int[]{10,18,38,44})for(String text:List.of("Allegro","moderato","Music","123","AVATAR","ffi","poco a poco","Été più")){
                    var oldFont=TextFont.getBestFont(attrs,size); var target=new Rectangle(0,0,31+size,13+size/2);
                    int expected=oldFont.computeSize(text,target.getSize());
                    FontInfo actual=FontInfo.adjustedTextFont(attrs,size,text,target);
                    if(actual.pointSize!=expected||!actual.fontName.equals(oldFont.getFontName())){throw new AssertionError("Text fitting changed");}
                    cases.add(Map.of("flags",flags,"size",size,"text",text,"width",target.width,"height",target.height,"pointSize",expected,"name",actual.fontName));checks++;
                }
            }
            recording.install(); TextFontGeometry.setProvider(org.audiveris.wasm.compat.platform.HeadlessTextFontGeometry.provider());
            for(var test:cases){
                int flags=(int)test.get("flags");var attrs=new FontAttributes((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0,(flags&16)!=0,(flags&32)!=0);
                var actual=FontInfo.adjustedTextFont(attrs,(int)test.get("size"),(String)test.get("text"),new Rectangle(0,0,(int)test.get("width"),(int)test.get("height")));
                if(actual.pointSize!=(int)test.get("pointSize")||!actual.fontName.equals(test.get("name"))){throw new AssertionError("Recorded fitting changed");}checks++;
            }
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writeValue(root.resolve("font-measurements.json").toFile(),recording.records());
            mapper.writeValue(root.resolve("cases.json").toFile(),cases);
            Files.writeString(root.resolve("default-font.txt"),recording.defaultFontName());
            Files.writeString(root.resolve("jvm-reference.txt"),"Text font geometry checks: "+checks);
            System.out.println("Text font geometry checks: "+checks+"; exact original and recorded replay");
        }finally{TextFontGeometry.setProvider(null);}
    }
}
