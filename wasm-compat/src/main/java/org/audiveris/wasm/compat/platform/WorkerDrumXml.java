// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.io.*;
import java.net.URI;
import javax.xml.bind.JAXBException;
import org.audiveris.omr.WellKnowns;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.score.DrumSet.*;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

/** Per-worker XML decoding and optional user overlay; the original DrumSet performs all merges. */
public final class WorkerDrumXml {
    private static byte[] overlay;
    private static boolean started;
    private WorkerDrumXml() { }
    public static void installOverlay(byte[] bytes) {
        if (started) { throw new IllegalStateException("Drum configuration is already in use"); }
        overlay=bytes==null?null:bytes.clone();
    }
    public static InputStream openConfiguration(URI uri) throws IOException {
        started=true;
        if (uri.equals(WellKnowns.CONFIG_FOLDER.resolve("drum-set.xml").toUri().normalize())) {
            if (overlay==null) { throw new FileNotFoundException("No host drum-set overlay"); }
            return new ByteArrayInputStream(overlay);
        }
        if (uri.equals(org.audiveris.omr.util.UriUtil.toURI(WellKnowns.RES_URI,"drum-set.xml"))) {
            return uri.toURL().openStream();
        }
        throw new IOException("Unexpected drum configuration URI: "+uri);
    }
    public static DrumSetEntries readEntries(InputStream input) throws JAXBException {
        try {
            KXmlParser parser=new KXmlParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);parser.setInput(input,null);
            parser.nextTag();
            if (!tag(parser,"drum-set")) { throw new javax.xml.bind.UnmarshalException("Expected unqualified drum-set root"); }
            var result=new DrumSetEntries();
            while(parser.nextTag()==XmlPullParser.START_TAG) {
                if (!tag(parser,"staff")) {skip(parser);continue;}
                var staff=new StaffEntries();staff.lineCount=integer(attribute(parser,"line-count"));
                result.staffList.add(staff);
                while(parser.nextTag()==XmlPullParser.START_TAG) {
                    if (!tag(parser,"entry")) {skip(parser);continue;}
                    var entry=new DrumSetEntry();
                    entry.pitchPosition=pitch(attribute(parser,"pitch-position"));
                    entry.headMotif=new MotifAdapter().unmarshal(attribute(parser,"motif"));
                    entry.sound=new SoundAdapter().unmarshal(attribute(parser,"sound"));
                    String sign=attribute(parser,"sign");
                    if(sign!=null) {try {entry.sign=Shape.valueOf(sign);}catch(IllegalArgumentException ignored) { }}
                    staff.list.add(entry); skip(parser);
                }
            }
            if(parser.next()!=XmlPullParser.END_DOCUMENT) {throw new IOException("Content after drum-set root");}
            return result;
        } catch(JAXBException error) {throw error;}
        catch(RuntimeException error) {throw error;}
        catch(Exception error) {throw new JAXBException("Cannot decode drum-set XML",error);}
    }
    private static String attribute(KXmlParser p,String name) {return p.getAttributeValue("",name);}
    private static boolean tag(KXmlParser p,String name) {return p.getNamespace().isEmpty()&&p.getName().equals(name);}
    private static Integer integer(String value) {
        if(value==null) {return null;}
        // JAXB 2.3.1's XML integer conversion accepts embedded whitespace/signs and wraps int overflow.
        int result=0,sign=1;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c==' '||c=='\t'||c=='\r'||c=='\n'||c=='+') {continue;}
            if(c=='-') {sign=-1;}
            else if(c>='0'&&c<='9') {result=result*10+c-'0';}
            else {throw new NumberFormatException("Not a number: "+value);}
        }
        return result*sign;
    }
    private static Integer pitch(String value) {
        try {return new PitchAdapter().unmarshal(value);}catch(Exception ignored) {return null;}
    }
    private static void skip(KXmlParser parser) throws Exception {
        int depth=1;
        while(depth!=0) {
            int event=parser.next();
            if(event==XmlPullParser.START_TAG) {depth++;}
            else if(event==XmlPullParser.END_TAG) {depth--;}
            else if(event==XmlPullParser.END_DOCUMENT) {throw new IOException("Unclosed drum-set element");}
        }
    }
}
