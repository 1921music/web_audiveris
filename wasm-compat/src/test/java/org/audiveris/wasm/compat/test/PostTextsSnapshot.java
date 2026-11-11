// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.step.OmrStep;

/** Structural keys ignore allocation IDs, preserving multiplicity, membership and graph edges. */
public final class PostTextsSnapshot {
    private PostTextsSnapshot(){ }
    public static String summary(Sheet sheet,String stage){
        var out=new StringBuilder("{\"stage\":\"").append(stage).append("\",\"measuresDone\":").append(sheet.getStub().isDone(OmrStep.MEASURES))
            .append(",\"chordsDone\":").append(sheet.getStub().isDone(OmrStep.CHORDS)).append(",\"stacks\":[");boolean first=true;
        for(var system:sheet.getSystems())for(var stack:system.getStacks()){
            if(!first)out.append(',');first=false;
            out.append("{\"system\":").append(system.getId()).append(",\"id\":").append(stack.getIdValue()).append(",\"left\":").append(stack.getLeft()).append(",\"right\":").append(stack.getRight()).append(",\"parts\":[");
            boolean partFirst=true;for(var measure:stack.getMeasures()){
                if(!partFirst)out.append(',');partFirst=false;
                out.append("{\"part\":").append(measure.getPart().getId()).append(",\"headChords\":");ids(out,new ArrayList<Inter>(measure.getHeadChords()));
                out.append(",\"restChords\":");ids(out,new ArrayList<Inter>(measure.getRestChords()));out.append('}');
            }out.append("]}");
        }
        out.append("],\"chords\":[");first=true;
        for(var system:sheet.getSystems()){
            var chords=new ArrayList<>(system.getSig().inters(AbstractChordInter.class));chords.sort(Comparator.comparing(PostTextsSnapshot::key));
            for(var inter:chords){var chord=(AbstractChordInter)inter;var box=chord.getBounds();if(!first)out.append(',');first=false;
                out.append("{\"system\":").append(system.getId()).append(",\"key\":\"").append(key(chord)).append("\",\"grade\":").append(chord.getGrade()).append(",\"bounds\":[")
                    .append(box.x).append(',').append(box.y).append(',').append(box.width).append(',').append(box.height).append("],\"stem\":\"").append(chord.getStem()==null?"":key(chord.getStem())).append('"')
                    .append(",\"stemDir\":").append(chord instanceof org.audiveris.omr.sig.inter.GraceChordInter&&members(chord).isEmpty()?"null":Integer.toString(chord.getStemDir())).append(",\"notes\":");ids(out,new ArrayList<Inter>(members(chord)));out.append('}');
            }
        }
        out.append("],\"edges\":[");appendEdges(out,sheet);return out.append("]}").toString();
    }
    private static String key(Inter inter){
        var box=inter.getBounds();String base=inter.getClass().getSimpleName()+":"+inter.getShape()+":"+box.x+":"+box.y+":"+box.width+":"+box.height+":staff"+(inter.getStaff()==null?0:inter.getStaff().getId());
        if(inter instanceof AbstractChordInter chord){
            var notes=new ArrayList<String>();for(var note:members(chord))notes.add(key(note));notes.sort(String::compareTo);
            base+="["+String.join(";",notes)+"]stem="+(chord.getStem()==null?"":key(chord.getStem()));
        }return base;
    }
    private static void ids(StringBuilder out,List<Inter> items){
        var keys=new ArrayList<String>();for(Inter inter:items)keys.add(key(inter));keys.sort(String::compareTo);
        out.append('[');for(int i=0;i<keys.size();i++){if(i>0)out.append(',');out.append('"').append(keys.get(i)).append('"');}out.append(']');
    }
    /** Read graph membership without GraceChordInter's lazy creation of hidden notes/stems. */
    public static List<Inter> members(AbstractChordInter chord){
        return org.audiveris.omr.sig.inter.EnsembleHelper.getMembers(chord,org.audiveris.omr.sig.inter.Inters.byReverseCenterOrdinate);
    }
    private static void appendEdges(StringBuilder out,Sheet sheet){
        boolean first=true;for(var system:sheet.getSystems()){
            var graph=system.getSig();var edges=new ArrayList<>(graph.edgeSet());
            edges.sort(Comparator.comparing((org.audiveris.omr.sig.relation.Relation e)->e.getClass().getSimpleName()).thenComparing(e->key(graph.getEdgeSource(e))).thenComparing(e->key(graph.getEdgeTarget(e))));
            for(var edge:edges){if(!first)out.append(',');first=false;
                out.append("{\"system\":").append(system.getId()).append(",\"type\":\"").append(edge.getClass().getSimpleName()).append("\",\"source\":\"").append(key(graph.getEdgeSource(edge))).append("\",\"target\":\"").append(key(graph.getEdgeTarget(edge))).append('"');
                if(edge instanceof org.audiveris.omr.sig.relation.Support support)out.append(",\"grade\":").append(support.getGrade()).append(",\"sourceRatio\":").append(support.getSourceRatio()).append(",\"targetRatio\":").append(support.getTargetRatio());
                out.append('}');
            }
        }
    }
}
