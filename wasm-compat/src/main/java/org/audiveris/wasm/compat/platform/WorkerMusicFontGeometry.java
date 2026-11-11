// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.awt.geom.Rectangle2D;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.audiveris.omr.text.*;
import org.teavm.jso.*;

/** Sealed, module-local synchronous music measurements; initialize the external backend first. */
public final class WorkerMusicFontGeometry implements TextFontGeometry.Provider {
    @JSFunctor public interface Backend extends JSObject {
        Result measure(int kind,String family,int pointSize,String value);
    }
    public interface Result extends JSObject {
        @JSProperty String getFontName();
        @JSProperty double getX();
        @JSProperty double getY();
        @JSProperty double getWidth();
        @JSProperty double getHeight();
        @JSProperty double getAdvance();
    }
    private record Key(int kind,String family,int size,String value) { }
    private record Entry(String name,double x,double y,double width,double height,double advance) { }
    private static Backend configured;
    private static int configuredCache;
    private static WorkerMusicFontGeometry active;
    private final TextFontGeometry.Provider text;
    private final Backend backend;
    private final int cacheLimit;
    private final LinkedHashMap<Key,Entry> cache=new LinkedHashMap<>();
    private int requests;
    private WorkerMusicFontGeometry(TextFontGeometry.Provider text,Backend backend,int cacheLimit){this.text=text;this.backend=backend;this.cacheLimit=cacheLimit;}
    public static void install(Backend backend,int cacheLimit){
        Objects.requireNonNull(backend);
        if(cacheLimit<1 || cacheLimit>65536)throw new IllegalArgumentException("Invalid music geometry cache limit");
        if(configured!=null || active!=null)throw new IllegalStateException("Music font backend is already configured");
        configured=backend;configuredCache=cacheLimit;
    }
    public static void activate(){
        if(configured==null)throw new IllegalStateException("Configure the music font backend before recognition");
        if(active==null)active=new WorkerMusicFontGeometry(TextFontGeometry.provider(),configured,configuredCache);
        TextFontGeometry.setProvider(active);
    }
    public static int measurements(){return active==null?0:active.requests;}
    private Entry measure(int kind,FontInfo font,String value){
        Objects.requireNonNull(font);Objects.requireNonNull(value);
        if(font.pointSize<1 || font.pointSize>16384 || value.isEmpty())throw new IllegalArgumentException("Invalid music font request");
        Key key=new Key(kind,font.fontName,font.pointSize,value);Entry old=cache.get(key);if(old!=null)return old;
        requests++;
        Result result=backend.measure(kind,font.fontName,font.pointSize,value);
        if(result==null)throw new IllegalArgumentException("Missing music font measurement");
        String name=result.getFontName();double x=result.getX(),y=result.getY(),width=result.getWidth(),height=result.getHeight(),advance=result.getAdvance();
        if(name==null || name.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) ||
            !Double.isFinite(height) || !Double.isFinite(advance) || width<0 || height<0)throw new IllegalArgumentException("Invalid music font measurement");
        Entry entry=new Entry(name,x,y,width,height,advance);
        if(cache.size()>=cacheLimit)cache.remove(cache.keySet().iterator().next());
        cache.put(key,entry);return entry;
    }
    @Override public TextFontGeometry.Measurement visualBounds(FontAttributes attributes,int pointSize,String value){return text.visualBounds(attributes,pointSize,value);}
    @Override public Rectangle2D layoutBounds(FontInfo font,String value){return text.layoutBounds(font,value);}
    @Override public double advance(FontInfo font,String value){return text.advance(font,value);}
    @Override public String defaultFontName(){return text.defaultFontName();}
    @Override public TextFontGeometry.Measurement musicVisualBounds(FontInfo font,String value){
        Entry entry=measure(3,font,value);return new TextFontGeometry.Measurement(entry.name,entry.x,entry.y,entry.width,entry.height);
    }
    @Override public Rectangle2D musicLayoutBounds(FontInfo font,String value){
        Entry entry=measure(2,font,value);return new Rectangle2D.Double(entry.x,entry.y,entry.width,entry.height);
    }
    @Override public double musicAdvance(FontInfo font,String value){return measure(2,font,value).advance;}
}
