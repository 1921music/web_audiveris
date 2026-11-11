// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.audiveris.omr.image.TemplateFactory;
import org.audiveris.omr.ui.symbol.*;

/** Compare the raster boundary against archived original templates, then emit font-only cases. */
public final class TemplateRasterBoundaryOracle {
    public static void main(String[] args)throws Exception {
        var resources=org.audiveris.omr.WellKnowns.RES_URI;MusicFont.populateAllSymbols();
        var cli=new org.audiveris.omr.CLI("Audiveris");cli.parseParameters(new String[0]);var f=org.audiveris.omr.Main.class.getDeclaredField("cli");f.setAccessible(true);f.set(null,cli);
        Path original=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);var json=new ObjectMapper();
        int regression=0;for(var row:json.readTree(original.resolve("reference.json").toFile()).get("catalogs")){
            var family=MusicFamily.valueOf(row.get("family").asText());int size=row.get("pointSize").asInt();
            byte[] actual=TemplateCodec.catalog(family,size,TemplateFactory.resourceProfile());
            byte[] expected=Files.readAllBytes(original.resolve(row.get("file").asText()+".atpl"));
            if(!Arrays.equals(actual,expected))throw new AssertionError("Original template data changed: "+family+"/"+size);
            regression++;
        }
        var rows=new ArrayList<Object>();for(var family:MusicFamily.values())for(int size:new int[]{4,20,48,64,84,90,91,92,128,129,192,256}){
            String name=family.name()+"-"+size;byte[] catalog=TemplateCodec.catalog(family,size,TemplateFactory.resourceProfile());
            Files.write(output.resolve(name+".atpl"),catalog);Files.write(output.resolve(name+".match"),TemplateCodec.match(family,size));
            rows.add(Map.of("family",family.name(),"pointSize",size,"file",name));
        }
        json.writeValue(output.resolve("reference.json").toFile(),Map.of("originalCatalogChecks",regression,"catalogs",rows));
        System.out.println("Original template regression="+regression+"; font-generated template cases="+rows.size());
    }
}
