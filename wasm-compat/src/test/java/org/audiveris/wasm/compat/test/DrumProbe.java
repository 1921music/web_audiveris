package org.audiveris.wasm.compat.test;

import java.io.*;
import java.util.*;
import org.audiveris.omr.score.DrumSet;
import org.audiveris.omr.score.DrumSet.*;
import org.audiveris.wasm.compat.platform.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/** Standard drum mapping APIs with the Worker XML/transport adapters. */
public final class DrumProbe {
    public static void main(String[] args) { }
    @JSExport public static void configure(Int8Array xml) {configure(xml,false);}
    @JSExport public static void configureDefaults(Int8Array xml) {configure(xml,true);}
    private static void configure(Int8Array xml,boolean defaults) {
        HeadlessEvents.setSink((kind,subject,detail)->{
            if(kind.equals("log")&&detail.startsWith("ERROR ")) {throw new IllegalStateException("Drum configuration error: "+subject+": "+detail);}
        });
        HeadlessConfiguration.configure(defaults?Map.of():Map.of("org.audiveris.omr.score.DrumSet.dumpDrumSet","false"),Map.of());
        byte[] bytes=xml.copyToJavaArray(); WorkerDrumXml.installOverlay(bytes.length==0?null:bytes);
        Arrays.fill(bytes,(byte)0); // Installed configuration must own a snapshot.
    }
    @JSExport public static String parse(Int8Array xml) throws Exception {
        return entries(WorkerDrumXml.readEntries(new ByteArrayInputStream(xml.copyToJavaArray())));
    }
    @JSExport public static String parseOutcome(Int8Array xml) {
        try {return parse(xml);}catch(Exception error) {return "ERROR:"+error.getClass().getSimpleName();}
    }
    @JSExport public static String mapping() {return mapping(DrumSet.getInstance());}
    @JSExport public static boolean rejects(Int8Array xml) {
        try {parse(xml);return false;}catch(Exception expected) {return true;}
    }
    @JSExport public static boolean frozen() {
        try {WorkerDrumXml.installOverlay(new byte[0]);return false;}catch(IllegalStateException expected) {return true;}
    }
    static String entries(DrumSetEntries root) {
        var out=new StringBuilder();
        for(var staff:root.staffList) {
            out.append('[').append(staff.lineCount).append(':');
            for(var e:staff.list) {out.append(e.pitchPosition).append('/').append(e.headMotif).append('/').append(e.sign).append('/').append(e.sound).append(';');}
            out.append(']');
        }
        return out.toString();
    }
    public static String mapping(DrumSet set) {
        var out=new StringBuilder();
        for(int lines:new int[]{1,5,7}) {
            var byPitch=set.getStaffSet(lines);if(byPitch==null) {continue;}
            for(var row:byPitch.entrySet()) {for(var item:row.getValue().entrySet()) {
                var key=item.getKey();var instrument=item.getValue();
                out.append(lines).append(':').append(row.getKey()).append(':').append(key.motif).append(':').append(key.sign)
                    .append('=').append(instrument==null?"null":instrument.sound.name()).append(';');
            }}
        }
        return out.toString();
    }
}
