// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;
import java.util.regex.Pattern;
import org.teavm.jso.JSExport;
public final class RegexCaptureProbe {
    public static void main(String[] args){System.out.println(run());}
    @JSExport public static String run(){
        StringBuilder out=new StringBuilder();
        for(String pattern:new String[]{"(?<outer>(?<inner>m)?(?<degree>7)?)", "(?<outer>(?<inner>m)|(?<major>M))?", "(?<root>[A-G])(?<kind>(?<minor>m)?(?<degree>7)?)"}){
            for(String input:new String[]{"","m","7","m7","C7","Am"}){
                var matcher=Pattern.compile(pattern).matcher(input);boolean matches=matcher.matches();out.append(pattern).append('|').append(input).append('|').append(matches);
                if(matches){for(int i=0;i<=matcher.groupCount();i++)out.append('|').append(i).append(':').append(matcher.group(i)).append(':').append(matcher.start(i)).append(':').append(matcher.end(i));
                    for(String name:new String[]{"outer","inner","degree","root","kind","minor"})out.append('|').append(name).append(':').append(org.audiveris.omr.util.RegexUtil.getGroup(matcher,name)); }
                out.append('\n');
            }
        }
        return out.toString();
    }
}
