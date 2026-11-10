package org.audiveris.wasm.compat.test;

import java.awt.Rectangle;
import org.audiveris.omr.text.*;
import org.audiveris.wasm.compat.platform.HeadlessTextFontGeometry;
import org.teavm.jso.JSExport;

public final class TextGeometryProbe {
    public static void main(String[] args) { }
    @JSExport public static void register(int kind,int flags,int size,String value,String family,String name,double x,double y,double width,double height,double advance) {
        HeadlessTextFontGeometry.register(kind,flags,size,value,family,name,x,y,width,height,advance);
    }
    @JSExport public static void defaultFont(String name) { HeadlessTextFontGeometry.defaultName(name); }
    @JSExport public static String fit(int flags,int size,String text,int width,int height) {
        var attrs=new FontAttributes((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0,(flags&16)!=0,(flags&32)!=0);
        var font=FontInfo.adjustedTextFont(attrs,size,text,new Rectangle(0,0,width,height));
        return font.pointSize+":"+font.fontName;
    }
    @JSExport public static boolean missingRejected() {
        try{fit(0,13,"not installed",10,10);return false;}catch(UnsupportedOperationException expected){return true;}
    }
}
