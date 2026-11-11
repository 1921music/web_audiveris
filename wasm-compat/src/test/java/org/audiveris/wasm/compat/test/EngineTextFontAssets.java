// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Font;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.audiveris.omr.text.FontAttributes;
import org.audiveris.omr.ui.symbol.TextFont;

/** Build-time font resolution for all twelve canonical styles, independent of score inputs. */
public final class EngineTextFontAssets {
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args[0]);Files.createDirectories(output.resolve("fonts"));
        Class<?> composite=Class.forName("sun.font.CompositeFont"),fileFont=Class.forName("sun.font.FileFont");
        var resolve=Class.forName("sun.font.FontUtilities").getMethod("getFont2D",Font.class);
        var slot=composite.getMethod("getSlotFont",int.class);
        var filename=fileFont.getDeclaredMethod("getPublicFileName");filename.setAccessible(true);
        var physicalStyle=Class.forName("sun.font.Font2D").getMethod("getStyle");
        var faces=new LinkedHashMap<Path,Map<String,Object>>();var styles=new ArrayList<Map<String,Object>>();
        for(int family:new int[]{0,8,16})for(int style=0;style<4;style++){
            int flags=family|style;
            var attrs=new FontAttributes((flags&1)!=0,(flags&2)!=0,false,(flags&8)!=0,(flags&16)!=0,false);
            TextFont font=TextFont.getBestFont(attrs,12);
            Object physical=resolve.invoke(null,font);
            if(composite.isInstance(physical))physical=slot.invoke(physical,0);
            if(!fileFont.isInstance(physical))throw new IllegalStateException("No physical font: "+font);
            int actualStyle=(int)physicalStyle.invoke(physical);
            if(font.isBold()&&(actualStyle&Font.BOLD)==0)throw new IllegalStateException("Unsupported synthetic bold: "+font);
            boolean italic=font.isItalic()&&(actualStyle&Font.ITALIC)==0;
            Path source=Path.of((String)filename.invoke(physical)).toRealPath();
            var face=faces.get(source);
            if(face==null){
                byte[] bytes=Files.readAllBytes(source);
                String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                String file=sha.substring(0,12)+"-"+source.getFileName();
                Files.write(output.resolve("fonts").resolve(file),bytes);
                face=Map.of("id","Text"+faces.size(),"file",file,"sourceName",source.getFileName().toString(),"sha256",sha);
                faces.put(source,face);
            }
            styles.add(Map.of("flags",flags,"face",face.get("id"),"name",font.getFontName(),"syntheticItalic",italic));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("font-resources.json").toFile(),
                Map.of("defaultFont",TextFont.TEXT_FONT_NAME,"faces",faces.values(),"styles",styles));
        System.out.println("Page-independent text fonts: "+styles.size()+" styles, "+faces.size()+" faces");
    }
}
