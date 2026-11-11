// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import java.util.ArrayList;
import java.util.Comparator;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sig.inter.*;
import org.audiveris.omr.step.OmrStep;

/** Curves, graph topology and preceding measure/chord state in one structural snapshot. */
public final class PostCurvesSnapshot {
    private PostCurvesSnapshot(){ }
    public static String summary(Sheet sheet,String stage){
        String prefix=PostTextsSnapshot.summary(sheet,stage);var out=new StringBuilder(prefix.substring(0,prefix.length()-1));
        out.append(",\"curvesDone\":").append(sheet.getStub().isDone(OmrStep.CURVES)).append(",\"curves\":[");boolean first=true;
        for(var system:sheet.getSystems()){
            var inters=new ArrayList<Inter>();for(Inter inter:system.getSig().vertexSet())if(inter instanceof SlurInter||inter instanceof WedgeInter||inter instanceof EndingInter||inter instanceof RehearsalInter)inters.add(inter);
            inters.sort(Comparator.comparing((Inter i)->i.getClass().getSimpleName()).thenComparingInt(i->i.getBounds().x).thenComparingInt(i->i.getBounds().y).thenComparingInt(i->i.getBounds().width).thenComparingInt(i->i.getBounds().height));
            for(Inter inter:inters){if(!first)out.append(',');first=false;var b=inter.getBounds();
                out.append("{\"system\":").append(system.getId()).append(",\"type\":\"").append(inter.getClass().getSimpleName()).append("\",\"bounds\":[").append(b.x).append(',').append(b.y).append(',').append(b.width).append(',').append(b.height).append("],\"grade\":").append(inter.getGrade()).append(",\"geometry\":[");
                if(inter instanceof SlurInter slur){var c=slur.getCurve();if(c!=null)out.append(c.getX1()).append(',').append(c.getY1()).append(',').append(c.getCtrlX1()).append(',').append(c.getCtrlY1()).append(',').append(c.getCtrlX2()).append(',').append(c.getCtrlY2()).append(',').append(c.getX2()).append(',').append(c.getY2());}
                else if(inter instanceof WedgeInter wedge){line(out,wedge.getLine1());out.append(',');line(out,wedge.getLine2());}
                else if(inter instanceof EndingInter ending){line(out,ending.getLine());out.append(',');line(out,ending.getLeftLeg());out.append(',');line(out,ending.getRightLeg());}
                out.append(']');if(inter instanceof RehearsalInter rehearsal){out.append(",\"text\":");quote(out,rehearsal.getValue());var e=rehearsal.getEnclosure();out.append(",\"enclosure\":[").append(e.x).append(',').append(e.y).append(',').append(e.width).append(',').append(e.height).append(']');}out.append('}');
            }
        }return out.append("]}").toString();
    }
    private static void line(StringBuilder out,java.awt.geom.Line2D line){if(line==null)out.append("null");else out.append(line.getX1()).append(',').append(line.getY1()).append(',').append(line.getX2()).append(',').append(line.getY2());}
    private static void quote(StringBuilder out,String value){out.append('"');for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32)out.append("\\u").append("0000",0,4-Integer.toHexString(c).length()).append(Integer.toHexString(c));else out.append(c);}out.append('"');}
}
