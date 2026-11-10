package org.audiveris.wasm.compat.test;

import java.util.ArrayList;
import org.audiveris.omr.sheet.*;
import org.audiveris.omr.sheet.note.*;
import org.audiveris.wasm.compat.platform.HeadlessSheetContext;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Dependency probe for the unchanged HEADS algorithms; template/font backend is still pending. */
public final class HeadsProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array model) { DistanceProbe.configure(model); }
    @JSExport public static void configureSerifs(String f,int i,double a,double b,double c,double d) { DistanceProbe.configureSerifs(f,i,a,b,c,d); }
    @JSExport public static void configureGeometry(int k,String f,String s,int i,int n,int d,double a,double b,double c,double e) { DistanceProbe.configureGeometry(k,f,s,i,n,d,a,b,c,e); }
    @JSExport public static void configurePointWidth(String f,String s,int p,double w) { DistanceProbe.configurePointWidth(f,s,p,w); }
    @JSExport public static String fixture() throws Exception {
        return analyze(PixelSheetProbe.create(1024,300,GridProbe.staffPixels(),"heads-staff"));
    }
    public static String analyze(Sheet sheet) throws Exception {
        String prefix=DistanceProbe.analyze(sheet);
        if(prefix.contains("\"rejected\":true")) {return prefix;}
        Sheet previous=HeadlessSheetContext.current(); HeadlessSheetContext.bind(sheet);
        try {
            var table=new DistancesBuilder(sheet).buildDistances();
            var spots=new HeadSpotsBuilder(sheet).getSpots();
            var tallies=new ArrayList<HeadSeedTally>();
            var drumDumped=new java.util.TreeMap<Integer,java.util.Map<String,Boolean>>();
            for(SystemInfo system:sheet.getSystems()) {
                var tally=new HeadSeedTally();tallies.add(tally);
                new NoteHeadsBuilder(system,table,spots.get(system),tally,false,drumDumped).buildHeads();
            }
            sheet.getPicture().discardImage(Picture.ImageKey.HEAD_SPOTS);
            HeadSeedTally.analyze(sheet,tallies); BeamProbe.checkErrors();
            int count=0;
            for(SystemInfo system:sheet.getSystems()) {
                count+=system.getSig().inters(org.audiveris.omr.sig.inter.HeadInter.class).size();
            }
            return prefix.substring(0,prefix.length()-1)+",\"headsCount\":"+count+"}";
        } finally {HeadlessSheetContext.bind(previous);}
    }
}
