package org.audiveris.wasm.compat.test;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.bind.JAXBContext;
import org.audiveris.omr.score.DrumSet;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Pinned JAXB decoding and map-merging reference; reflection is used only by this JVM oracle. */
public final class DrumXmlOracle {
    private static Object field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static String describe(Object root) throws Exception {
        var out=new StringBuilder();
        for(Object staff:(List<?>)field(root,"staffList")) {
            out.append('[').append(field(staff,"lineCount")).append(':');
            for(Object e:(List<?>)field(staff,"list")) {for(String name:new String[]{"pitchPosition","headMotif","sign","sound"}) {
                out.append(field(e,name)).append(name.equals("sound")?';':'/');
            }}out.append(']');
        }return out.toString();
    }
    public static void main(String[] args) throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(DrumSet.class)).setLevel(ch.qos.logback.classic.Level.ERROR);
        var cli=new org.audiveris.omr.CLI("Audiveris");cli.parseParameters(new String[0]);
        var cliField=org.audiveris.omr.Main.class.getDeclaredField("cli");cliField.setAccessible(true);cliField.set(null,cli);
        boolean portable=args[3].equals("portable");
        Path origin=Path.of(DrumSet.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if(!origin.equals(Path.of(args[2]).toRealPath())) {throw new AssertionError("Wrong DrumSet origin: "+origin);}
        var constantsField=DrumSet.class.getDeclaredField("constants");constantsField.setAccessible(true);
        var constants=constantsField.get(null);var dump=constants.getClass().getDeclaredField("dumpDrumSet");dump.setAccessible(true);
        ((org.audiveris.omr.constant.Constant.Boolean)dump.get(constants)).setValue(false);
        var context=JAXBContext.newInstance(Class.forName("org.audiveris.omr.score.DrumSet$DrumSetEntries"));
        List<String> fixtures=new ArrayList<>();fixtures.add(Files.readString(Path.of(args[0])));
        fixtures.add("<drum-set/>");
        fixtures.add("<?xml version='1.0'?><!--x--><drum-set>\n<staff line-count='7'><entry pitch-position='-2' motif='oval' sign='PLAYING_OPEN' sound='Cowbell'/></staff>\n</drum-set>\n");
        fixtures.add("<drum-set><other><staff line-count='99'/></other><staff line-count='5'><other><entry motif='oval'/></other><entry/></staff></drum-set>");
        fixtures.add("<drum-set xmlns='urn:unsupported'><staff line-count='5'/></drum-set>");
        fixtures.add("<drum-set xmlns:x='urn:other'><x:staff line-count='5'/><staff x:line-count='5'/></drum-set>");
        fixtures.add("<drum-set xmlns:x='urn:other'><staff line-count='5'><x:entry motif='oval'/><entry x:motif='oval'/></staff></drum-set>");
        fixtures.add("<drum-set><!--c--><?instruction ignored?><staff line-count='5'/></drum-set>\n<!--end-->\n");
        for(String motif:new String[]{"oval","small","cross","diamond","triangle","circle","null","bad",""}) {
            for(String pitch:new String[]{"-3","+2","null","NULL","bad"," 2 "}) {
                fixtures.add("<drum-set><staff line-count='5'><entry extra='a&amp;b' pitch-position='"+pitch+"' motif='"+motif+"' sign='PLAYING_OPEN' sound='Cowbell'/></staff></drum-set>");
            }
        }
        for(String sound:new String[]{"null","bad","","Hi_Bongo","Cowbell"}) {
            for(String sign:new String[]{"PLAYING_OPEN","PLAYING_CLOSED","bad","null",""}) {
                fixtures.add("<drum-set><staff line-count='5'><entry pitch-position='0' motif='oval' sign='"+sign+"' sound='"+sound+"'/></staff></drum-set>");
            }
        }
        fixtures.add("<drum-set><staff line-count='bad'><entry/></staff><staff/><staff line-count='5'/><staff line-count='5'><entry pitch-position='0' motif='oval'/></staff></drum-set>");
        for(String line:new String[]{"","+","-","1 2","--3","1+2","2147483648","-2147483649"," 5 ","5.0","1e2","null","1&#9;2","&#160;5"}) {
            fixtures.add("<drum-set><staff line-count='"+line+"'/></drum-set>");
        }
        Random random=new Random(20261111);
        for(int n=0;n<128;n++) {
            var value=new StringBuilder();
            for(int i=0,count=random.nextInt(25);i<count;i++) {value.append("0123456789 +-".charAt(random.nextInt(13)));}
            fixtures.add("<drum-set><staff line-count='"+value+"'/></drum-set>");
        }
        List<Map<String,String>> rows=new ArrayList<>();
        for(String xml:fixtures) {
            String expected;
            try {expected=describe(context.createUnmarshaller().unmarshal(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));}
            catch(Exception error) {expected="ERROR:"+error.getClass().getSimpleName();}
            if(portable) {
                String actual;
                try {actual=DrumProbe.entries(org.audiveris.wasm.compat.platform.WorkerDrumXml.readEntries(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));}
                catch(Exception error) {actual="ERROR:"+error.getClass().getSimpleName();}
                if(!actual.equals(expected)) {throw new AssertionError("Drum XML differs\n"+xml+"\n"+expected+"\n"+actual);}
            }
            rows.add(Map.of("xml",xml,"expected",expected));
        }
        var constructor=DrumSet.class.getDeclaredConstructor();constructor.setAccessible(true);
        DrumSet set=constructor.newInstance();String defaults=DrumProbe.mapping(set);
        if(defaults.isEmpty()) {throw new AssertionError("Mandatory default drum-set did not load");}
        String overlay="<drum-set><staff line-count='1'><entry pitch-position='0' motif='oval' sound='Cowbell'/><entry pitch-position='-1' motif='oval'/></staff><staff line-count='7'><entry pitch-position='-2' motif='diamond' sign='PLAYING_OPEN' sound='Hi_Bongo'/><entry pitch-position='-2' motif='diamond' sign='PLAYING_OPEN' sound='Maracas'/></staff></drum-set>";
        var load=DrumSet.class.getDeclaredMethod("loadConfiguration",InputStream.class);load.setAccessible(true);
        load.invoke(set,new ByteArrayInputStream(overlay.getBytes(StandardCharsets.UTF_8)));
        Path output=Path.of(args[1]);Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),Map.of("cases",rows,"defaultMapping",defaults,"overlay",overlay,"overlayMapping",DrumProbe.mapping(set)));
        System.out.println("Drum XML cases="+rows.size()+" portable="+portable);
    }
}
