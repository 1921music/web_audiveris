package org.audiveris.wasm.compat.test;

import java.awt.geom.Rectangle2D;
import java.util.*;
import org.audiveris.omr.text.*;
import org.audiveris.wasm.compat.platform.HeadlessTextFontGeometry;

/** Record exact queries from the original JVM font implementation for portable replay. */
final class RecordingTextFontGeometry implements TextFontGeometry.Provider {
    record Entry(int kind,int flags,int size,String value,String family,String name,double x,double y,double width,double height,double advance) { }
    private final TextFontGeometry.Provider original;
    private final Map<String,Entry> entries = new TreeMap<>();
    RecordingTextFontGeometry(TextFontGeometry.Provider original) { this.original=original; }
    Collection<Entry> records() { return entries.values(); }
    void install() {
        HeadlessTextFontGeometry.defaultName(defaultFontName());
        for (Entry entry : entries.values()) { HeadlessTextFontGeometry.register(entry.kind(),entry.flags(),entry.size(),entry.value(),entry.family(),entry.name(),entry.x(),entry.y(),entry.width(),entry.height(),entry.advance()); }
    }
    private void record(int kind,FontAttributes attrs,int size,String value,String name,Rectangle2D bounds,double advance) {
        String family=kind>=2?((FontInfo)attrs).fontName:"";
        Entry entry=new Entry(kind,HeadlessTextFontGeometry.flags(attrs),size,value,family,name,bounds.getX(),bounds.getY(),bounds.getWidth(),bounds.getHeight(),advance);
        String key=kind+":"+entry.flags()+":"+size+":"+family+":"+value;
        Entry old=entries.putIfAbsent(key,entry);
        if(old!=null&&!old.equals(entry)){throw new AssertionError("Unstable native text metrics: "+key);}
    }
    @Override public TextFontGeometry.Measurement visualBounds(FontAttributes attrs,int size,String value) {
        var measurement=original.visualBounds(attrs,size,value);
        record(0,attrs,size,value,measurement.fontName(),new Rectangle2D.Double(measurement.x(),measurement.y(),measurement.width(),measurement.height()),0);return measurement;
    }
    @Override public Rectangle2D layoutBounds(FontInfo info,String value) {
        var bounds=original.layoutBounds(info,value);record(1,info,info.pointSize,value,"text",bounds,original.advance(info,value));return bounds;
    }
    @Override public double advance(FontInfo info,String value) { layoutBounds(info,value);return original.advance(info,value); }
    @Override public String defaultFontName() { return original.defaultFontName(); }
    @Override public TextFontGeometry.Measurement musicVisualBounds(FontInfo info,String value) {
        var measurement=original.musicVisualBounds(info,value);
        record(3,info,info.pointSize,value,measurement.fontName(),new Rectangle2D.Double(measurement.x(),measurement.y(),measurement.width(),measurement.height()),0);return measurement;
    }
    @Override public Rectangle2D musicLayoutBounds(FontInfo info,String value) {
        var bounds=original.musicLayoutBounds(info,value);record(2,info,info.pointSize,value,info.fontName,bounds,original.musicAdvance(info,value));return bounds;
    }
    @Override public double musicAdvance(FontInfo info,String value) { musicLayoutBounds(info,value);return original.musicAdvance(info,value); }
}
