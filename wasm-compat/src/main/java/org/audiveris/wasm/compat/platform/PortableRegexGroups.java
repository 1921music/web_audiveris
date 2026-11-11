// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.util.*;
import java.util.regex.*;

/** TeaVM 0.16.0 loses outer names when its lexer looks ahead to a nested named group.
 * Numeric captures remain correct. Resolve names from the already validated Java pattern;
 * leave matching, group bounds and backtracking with the original regex engine.
 */
public final class PortableRegexGroups {
    private record Key(String expression,int flags){ }
    private static final Map<Key,Map<String,Integer>> cache=new LinkedHashMap<>();
    private PortableRegexGroups(){ }
    public static String getGroup(Matcher matcher,String name){
        try{
            var pattern=matcher.pattern();var key=new Key(pattern.pattern(),pattern.flags());
            var groups=cache.get(key);
            if(groups==null){groups=indices(key.expression(),key.flags());if(cache.size()>=256)cache.remove(cache.keySet().iterator().next());cache.put(key,groups);}
            Integer index=groups.get(name);if(index==null)return "";
            String value=matcher.group(index);return value==null?"":value;
        }catch(Exception ignored){return "";} // RegexUtil's original absent/unmatched-group contract.
    }
    public static Map<String,Integer> indices(String pattern,int flags){
        if((flags&Pattern.LITERAL)!=0)return Map.of();
        var groups=new LinkedHashMap<String,Integer>();var scopes=new ArrayList<Boolean>();
        boolean comments=(flags&Pattern.COMMENTS)!=0,quoted=false;int count=0,classes=0;boolean classFirst=false;
        for(int i=0;i<pattern.length();i++){
            char ch=pattern.charAt(i);
            if(quoted){if(ch=='\\'&&i+1<pattern.length()&&pattern.charAt(i+1)=='E'){quoted=false;i++;}continue;}
            if(ch=='\\'){if(i+1<pattern.length()&&pattern.charAt(i+1)=='Q')quoted=true;i++;classFirst=false;continue;}
            if(comments&&ch=='#'){while(i+1<pattern.length()&&pattern.charAt(i+1)!='\n'&&pattern.charAt(i+1)!='\r')i++;continue;}
            if(comments&&Character.isWhitespace(ch))continue;
            if(classes>0){
                if(ch=='['){classes++;classFirst=true;}
                else if(ch==']'&&!classFirst){classes--;classFirst=false;}
                else if(ch!='^'||!classFirst)classFirst=false;
                continue;
            }
            if(ch=='['){classes=1;classFirst=true;continue;}
            if(ch==')'){if(!scopes.isEmpty())comments=scopes.remove(scopes.size()-1);continue;}
            if(ch!='(')continue;
            boolean previous=comments;
            if(i+1>=pattern.length()||pattern.charAt(i+1)!='?'){count++;scopes.add(previous);continue;}
            int start=i+2;
            if(start<pattern.length()&&pattern.charAt(start)=='<'&&start+1<pattern.length()
                    &&pattern.charAt(start+1)!='='&&pattern.charAt(start+1)!='!'){
                int end=pattern.indexOf('>',start+1);
                groups.put(pattern.substring(start+1,end),++count);i=end;scopes.add(previous);continue;
            }
            boolean disable=false;int next=start;
            while(next<pattern.length()){
                char flag=pattern.charAt(next);
                if(flag=='-'){disable=true;next++;continue;}
                if("idmsuxU".indexOf(flag)<0)break;
                if(flag=='x')comments=!disable;
                next++;
            }
            if(next>start&&next<pattern.length()&&pattern.charAt(next)==')'){i=next;continue;}
            scopes.add(previous);
            if(next>start&&next<pattern.length()&&pattern.charAt(next)==':')i=next;
        }
        return Collections.unmodifiableMap(groups);
    }
}
