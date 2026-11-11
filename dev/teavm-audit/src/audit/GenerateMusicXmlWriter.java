// SPDX-License-Identifier: AGPL-3.0-or-later
package audit;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Read the pinned JAXB model at build time; emit direct field access without runtime reflection. */
public final class GenerateMusicXmlWriter {
    private static final String PACKAGE="org.audiveris.proxymusic";
    private static final StringBuilder code=new StringBuilder();
    private static Annotation annotation(AnnotatedElement owner,String name){
        for(var value:owner.getAnnotations())if(value.annotationType().getSimpleName().equals(name))return value;
        return null;
    }
    private static Object property(Annotation value,String name)throws Exception{return value.annotationType().getMethod(name).invoke(value);}
    private static String name(Annotation value,String fallback)throws Exception{
        if(value==null)return fallback;
        String name=(String)property(value,"name");return name.equals("##default")?fallback:name;
    }
    private static String qualified(Annotation value,String fallback)throws Exception{
        String local=name(value,fallback);
        if(value==null)return local;
        String ns=(String)property(value,"namespace");
        return switch(ns){case "", "##default"->local;case "http://www.w3.org/XML/1998/namespace"->"xml:"+local;
            case "http://www.w3.org/1999/xlink"->"xlink:"+local;default->throw new IllegalArgumentException("Unknown XML namespace "+ns);};
    }
    private static String type(Class<?> cls){return cls.getCanonicalName();}
    private static void line(String text){code.append(text).append('\n');}
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static List<Field> fields(Class<?> cls){
        return Arrays.stream(cls.getDeclaredFields()).filter(f->!Modifier.isStatic(f.getModifiers())&&!Modifier.isTransient(f.getModifiers())
                &&annotation(f,"XmlTransient")==null).toList();
    }
    private static int depth(Class<?> cls){int count=0;while((cls=cls.getSuperclass())!=null)count++;return count;}
    public static void main(String[] args)throws Exception{
        Class<?> factory=Class.forName(PACKAGE+".ObjectFactory");
        Path jar=Path.of(factory.getProtectionDomain().getCodeSource().getLocation().toURI());
        var models=new ArrayList<Class<?>>();var enums=new ArrayList<Class<?>>();
        try(var archive=new JarFile(jar.toFile())){
            for(var entry:Collections.list(archive.entries())){
                String path=entry.getName();
                if(!path.startsWith(PACKAGE.replace('.','/')+"/")||!path.endsWith(".class"))continue;
                String shortName=path.substring(PACKAGE.length()+1);
                if(shortName.contains("/")||shortName.equals("ObjectFactory.class")||shortName.equals("package-info.class"))continue;
                Class<?> cls=Class.forName(path.substring(0,path.length()-6).replace('/','.'));
                if(cls.isEnum())enums.add(cls);else if(annotation(cls,"XmlType")!=null)models.add(cls);
            }
        }
        models.sort(Comparator.<Class<?>>comparingInt(GenerateMusicXmlWriter::depth).reversed().thenComparing(Class::getName));
        line("// Generated from the pinned proxymusic JAXB annotations. Do not edit.");
        line("package "+PACKAGE+";");
        line("import java.lang.String;");
        line("public final class PortableMusicXmlWriter {");
        line("private final StringBuilder out=new StringBuilder();");
        line("public static String write(ScorePartwise score){var w=new PortableMusicXmlWriter();w.out.append(\"<?xml version=\\\"1.0\\\" encoding=\\\"UTF-8\\\"?>\");w.element(\"score-partwise\",score);return w.out.toString();}");
        line("private void text(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '&'->out.append(\"&amp;\");case '<'->out.append(\"&lt;\");case '>'->out.append(\"&gt;\");case '\"'->out.append(\"&quot;\");case '\\r'->out.append(\"&#13;\");case '\\n'->out.append(\"&#10;\");case '\\t'->out.append(\"&#9;\");default->{if(c<32||c==0xfffe||c==0xffff)throw new IllegalArgumentException(\"Invalid XML character\");out.append(c);}}}}");
        line("private String lexical(Object v){");
        for(Class<?> cls:enums)line("if(v instanceof "+type(cls)+" x)return lexical(x.value());");
        line("if(v instanceof java.math.BigDecimal x)return x.toPlainString();");
        line("if(v instanceof String||v instanceof Number||v instanceof Boolean)return v.toString();");
        line("throw new IllegalArgumentException(\"Unsupported XML scalar\");}");
        line("private void attribute(String name,Object v){if(v!=null){out.append(' ').append(name).append(\"=\\\"\");text(lexical(v));out.append('\"');}}");
        line("private Object id(Object value){");
        for(Class<?> cls:models)for(Field f:fields(cls))if(annotation(f,"XmlID")!=null)
            line("if(value instanceof "+type(cls)+" x)return x."+f.getName()+";");
        line("if(value instanceof String)return value;throw new IllegalArgumentException(\"Missing XML ID\");}");
        line("private void element(String name,Object value){if(value==null)return; if(value instanceof jakarta.xml.bind.JAXBElement<?> x){element(x.getName().getLocalPart(),x.getValue());return;}");
        for(Class<?> cls:models)line("if(value instanceof "+type(cls)+" x){write(name,x);return;}");
        line("out.append('<').append(name).append('>');text(lexical(value));out.append(\"</\").append(name).append('>');}");
        line("private void reference(Object value){if(value==null)return;if(value instanceof jakarta.xml.bind.JAXBElement<?>){element(\"\",value);return;}");
        for(Class<?> cls:models){Annotation root=annotation(cls,"XmlRootElement");if(root!=null)line("if(value instanceof "+type(cls)+" x){element("+quote(qualified(root,cls.getSimpleName()))+",x);return;}");}
        line("throw new IllegalArgumentException(\"Unknown XML element reference\");}");
        for(Class<?> cls:models){
            List<Field> own=fields(cls);Class<?> parent=cls.getSuperclass();boolean inherited=models.contains(parent);
            line("private void write(String name,"+type(cls)+" x){out.append('<').append(name);if(name.equals(\"score-partwise\"))out.append(\" xmlns:xlink=\\\"http://www.w3.org/1999/xlink\\\"\");attributes(x);out.append('>');content(x);out.append(\"</\").append(name).append('>');}");
            line("private void attributes("+type(cls)+" x){");if(inherited)line("attributes(("+type(parent)+")x);");
            for(Field f:own){Annotation attr=annotation(f,"XmlAttribute");if(attr==null)continue;
                String access="x."+f.getName();
                if(annotation(f,"XmlIDREF")!=null)access="("+access+"==null?null:id("+access+"))";
                if(annotation(f,"XmlList")!=null)throw new IllegalArgumentException("XML attribute list "+f);
                line("attribute("+quote(qualified(attr,f.getName()))+","+access+");");
            }
            line("}");line("private void content("+type(cls)+" x){");if(inherited)line("content(("+type(parent)+")x);");
            Annotation xmlType=annotation(cls,"XmlType");String[] order=(String[])property(xmlType,"propOrder");
            var remaining=new ArrayList<>(own.stream().filter(f->annotation(f,"XmlAttribute")==null).toList());
            var ordered=new ArrayList<Field>();
            for(String field:order)if(!field.isEmpty()){
                var match=remaining.stream().filter(f->f.getName().equals(field)).findFirst().orElseThrow();ordered.add(match);remaining.remove(match);
            }
            ordered.addAll(remaining);
            for(Field f:ordered){
                String access="x."+f.getName();boolean list=List.class.isAssignableFrom(f.getType());
                if(list){line("if("+access+"!=null)for(Object item:"+access+"){");access="item";}
                if(annotation(f,"XmlValue")!=null)line((f.getType().isPrimitive()?"":"if("+access+"!=null)")+"text(lexical("+access+"));");
                else if(annotation(f,"XmlElements")!=null){
                    Annotation[] choices=(Annotation[])property(annotation(f,"XmlElements"),"value");
                    line("if("+access+"!=null){");
                    for(int i=0;i<choices.length;i++){
                        Class<?> choice=(Class<?>)property(choices[i],"type");
                        line((i==0?"if":"else if")+"("+access+" instanceof "+type(choice)+")element("+quote(qualified(choices[i],f.getName()))+","+access+");");
                    }
                    line("else throw new IllegalArgumentException(\"Unknown XML choice\");}");
                }else if(annotation(f,"XmlElementRefs")!=null||annotation(f,"XmlElementRef")!=null){
                    if(annotation(f,"XmlMixed")!=null)line("if("+access+" instanceof String s)text(s);else reference("+access+");");
                    else line("reference("+access+");");
                }else{
                    if(annotation(f,"XmlList")!=null)throw new IllegalArgumentException("XML list "+f);
                    line("element("+quote(qualified(annotation(f,"XmlElement"),f.getName()))+","+access+");");
                }
                if(list)line("}");
            }
            line("}");
        }
        line("}");
        Path target=Path.of(args[0]).resolve(PACKAGE.replace('.','/')).resolve("PortableMusicXmlWriter.java");
        Files.createDirectories(target.getParent());Files.writeString(target,code.toString());
        System.out.println("Generated MusicXML writer: "+models.size()+" model classes, "+enums.size()+" enums");
    }
}
