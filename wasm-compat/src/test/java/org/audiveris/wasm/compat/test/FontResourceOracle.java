// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.audiveris.omr.glyph.*;
import org.audiveris.omr.ui.symbol.*;

/** Font-only build assets and independent measurement fixtures; never inspects a score page. */
public final class FontResourceOracle {
    public static void main(String[] args)throws Exception {
        var resources=org.audiveris.omr.WellKnowns.RES_URI;MusicFont.populateAllSymbols();
        Path output=Path.of(args[0]);Files.createDirectories(output);var json=new ObjectMapper();
        var fixed=new TreeMap<String,double[]>();var rows=new ArrayList<Object>();
        var geometry=SymbolGeometry.provider();var shapes=EnumSet.copyOf(ShapeSet.Heads);shapes.addAll(ShapeSet.Clefs);
        shapes.addAll(EnumSet.of(Shape.BRACKET_UPPER_SERIF,Shape.BRACKET_LOWER_SERIF,Shape.FLAT,Shape.SHARP,Shape.NATURAL,Shape.TIME_ZERO,Shape.TIME_FOUR,Shape.TIME_TWELVE));
        int[] interlines={1,2,4,8,16,21,32,33,48,64,96,128,256};
        for(var family:MusicFamily.values()) {
            for(var shape:ShapeSet.Clefs){var p=geometry.centroidOffset(family,shape,16);fixed.put("1:"+family+":"+shape,new double[]{p.getX(),p.getY(),0,0});}
            for(var shape:shapes)for(int interline:interlines){
                var b=geometry.bounds(family,shape,interline);
                rows.add(Map.of("kind",0,"family",family.name(),"shape",shape.name(),"scale",interline,"num",0,"den",0,"values",new double[]{b.getX(),b.getY(),b.getWidth(),b.getHeight()}));
            }
            for(int size:new int[]{0,1,4,17,63,84,127,128,129,192,256,512,1024})rows.add(Map.of("kind",4,"family",family.name(),"shape",Shape.NOTEHEAD_BLACK.name(),"scale",size,"num",0,"den",0,"values",new double[]{geometry.widthAtPointSize(family,Shape.NOTEHEAD_BLACK,size),0,0,0}));
            for(int interline:interlines)for(int[] nd:new int[][]{{0,1},{1,4},{3,8},{4,4},{7,16},{12,8},{123,999}}){
                var d=geometry.timeDimension(family,Shape.TIME_CUSTOM,interline,nd[0],nd[1]);
                rows.add(Map.of("kind",2,"family",family.name(),"shape",Shape.TIME_CUSTOM.name(),"scale",interline,"num",nd[0],"den",nd[1],"values",new double[]{d.width,d.height,0,0}));
            }
        }
        for(var shape:new Shape[]{Shape.G_CLEF,Shape.G_CLEF_SMALL,Shape.G_CLEF_8VA,Shape.G_CLEF_8VB,Shape.F_CLEF,Shape.F_CLEF_SMALL,Shape.F_CLEF_8VA,Shape.F_CLEF_8VB,Shape.FLAT})fixed.put("3:Bravura:"+shape,new double[]{org.audiveris.omr.sig.inter.AbstractPitchedInter.getAreaPitchOffset(shape),0,0,0});
        json.writeValue(output.resolve("intrinsics.json").toFile(),fixed);json.writeValue(output.resolve("geometry-reference.json").toFile(),rows);
        System.out.println("Font-only intrinsics="+fixed.size()+"; geometry cases="+rows.size());
    }
}
