// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import java.util.ArrayList;
import java.util.Comparator;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sig.inter.*;
import org.audiveris.omr.step.OmrStep;

/** Structural graph, musical timing and part identity through the final page step. */
public final class PageRecognitionSnapshot {
    private PageRecognitionSnapshot(){ }
    public static String summary(Sheet sheet,String stage){
        int vertices=0,edges=0;for(var system:sheet.getSystems()){vertices+=system.getSig().vertexSet().size();edges+=system.getSig().edgeSet().size();}
        String prefix=PostCurvesSnapshot.summary(sheet,stage);
        var out=new StringBuilder(prefix.substring(0,prefix.length()-1));
        for(var step:new OmrStep[]{OmrStep.SYMBOLS,OmrStep.LINKS,OmrStep.RHYTHMS,OmrStep.PAGE}){
            out.append(',');quote(out,step.name().toLowerCase(java.util.Locale.ROOT)+"Done");out.append(':').append(sheet.getStub().isDone(step));
        }
        boolean rhythmic=sheet.getStub().isDone(OmrStep.RHYTHMS),pageDone=sheet.getStub().isDone(OmrStep.PAGE),first=true;
        out.append(",\"inters\":[");
        for(var system:sheet.getSystems()){
            var inters=new ArrayList<>(system.getSig().vertexSet());
            // Different classifier candidates can have identical type/shape/bounds. Keep
            // their multiplicity and grades, but do not compare their allocation order.
            inters.sort(Comparator.comparing(PageRecognitionSnapshot::key).thenComparingDouble(i->i.getGrade()).thenComparingDouble(i->i.getBestGrade()));
            for(Inter inter:inters){
                if(!first)out.append(',');first=false;
                out.append("{\"system\":").append(system.getId()).append(",\"key\":");quote(out,key(inter));
                out.append(",\"grade\":").append(inter.getGrade()).append(",\"bestGrade\":").append(inter.getBestGrade())
                    .append(",\"implicit\":").append(inter.isImplicit()).append(",\"manual\":").append(inter.isManual());
                if(inter instanceof AbstractPitchedInter pitched)out.append(",\"pitch\":").append(pitched.getPitch());
                if(inter instanceof AbstractNumberInter number)out.append(",\"number\":").append(number.getValue());
                if(inter instanceof KeyInter key)out.append(",\"fifths\":").append(key.getFifths());
                if(inter instanceof WordInter word){out.append(",\"text\":");quote(out,word.getValue());}
                if(inter instanceof SlurInter slur)out.append(",\"tie\":").append(slur.isTie());
                if(inter instanceof AbstractChordInter chord&&rhythmic){
                    out.append(",\"offset\":");rational(out,chord.getTimeOffset());out.append(",\"duration\":");rational(out,chord instanceof GraceChordInter&&PostTextsSnapshot.members(chord).isEmpty()?null:chord.getDuration());
                    out.append(",\"voice\":").append(chord.getVoice()==null?"null":Integer.toString(chord.getVoice().getId()));
                }
                out.append('}');
            }
        }
        out.append("],\"timing\":[");first=true;
        if(rhythmic)for(var system:sheet.getSystems())for(var stack:system.getStacks()){
            if(!first)out.append(',');first=false;
            out.append("{\"system\":").append(system.getId()).append(",\"stack\":").append(stack.getIdValue())
                .append(",\"expected\":");rational(out,stack.getExpectedDuration());out.append(",\"actual\":");rational(out,stack.getActualDuration());
            out.append(",\"slots\":[");boolean slotFirst=true;
            for(var slot:stack.getSlots()){if(!slotFirst)out.append(',');slotFirst=false;rational(out,slot.getTimeOffset());}
            out.append("],\"voices\":[");boolean voiceFirst=true;
            var voices=new ArrayList<>(stack.getVoices());voices.sort(Comparator.comparingInt((org.audiveris.omr.sheet.rhythm.Voice v)->v.getMeasure().getPart().getId()).thenComparingInt(v->v.getId()));
            for(var voice:voices){
                if(!voiceFirst)out.append(',');voiceFirst=false;
                out.append("{\"part\":").append(voice.getMeasure().getPart().getId()).append(",\"id\":").append(voice.getId()).append(",\"kind\":");quote(out,voice.getKind().name());
                out.append(",\"duration\":");rational(out,voice.getDuration());out.append(",\"termination\":");rational(out,voice.getTermination());
                out.append(",\"chords\":[");boolean chordFirst=true;for(var chord:voice.getChords()){if(!chordFirst)out.append(',');chordFirst=false;quote(out,key(chord));}out.append("]}");
            }
            out.append("]}");
        }
        out.append("],\"partIdentities\":[");first=true;
        if(pageDone)for(var system:sheet.getSystems())for(var part:system.getParts()){
            if(!first)out.append(',');first=false;var logical=part.getLogicalPart();
            out.append("{\"system\":").append(system.getId()).append(",\"part\":").append(part.getId()).append(",\"staves\":").append(part.getStaffCount()).append(",\"name\":");quote(out,part.getName());
            out.append(",\"logicalId\":").append(logical==null?"null":Integer.toString(logical.getId())).append(",\"logicalName\":");quote(out,logical==null?null:logical.getName());out.append('}');
        }
        int observedVertices=0,observedEdges=0;for(var system:sheet.getSystems()){observedVertices+=system.getSig().vertexSet().size();observedEdges+=system.getSig().edgeSet().size();}
        if(vertices!=observedVertices||edges!=observedEdges)throw new IllegalStateException("Snapshot changed recognition graph at "+stage);
        return out.append("]}").toString();
    }
    private static String key(Inter inter){
        var b=inter.getBounds();String base=inter.getClass().getSimpleName()+":"+inter.getShape()+":"+(b==null?"null":b.x+":"+b.y+":"+b.width+":"+b.height)+":staff"+(inter.getStaff()==null?0:inter.getStaff().getId());
        if(inter instanceof AbstractChordInter chord){var notes=new ArrayList<String>();for(var note:PostTextsSnapshot.members(chord))notes.add(key(note));notes.sort(String::compareTo);base+="["+String.join(";",notes)+"]stem="+(chord.getStem()==null?"":key(chord.getStem()));}
        return base;
    }
    private static void rational(StringBuilder out,org.audiveris.omr.math.Rational value){quote(out,value==null?null:value.toString());}
    private static void quote(StringBuilder out,String value){
        if(value==null){out.append("null");return;}out.append('"');
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32){String h=Integer.toHexString(c);out.append("\\u").append("0000",0,4-h.length()).append(h);}else out.append(c);}out.append('"');
    }
}
