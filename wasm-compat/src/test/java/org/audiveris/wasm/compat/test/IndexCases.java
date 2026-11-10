package org.audiveris.wasm.compat.test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.audiveris.omr.util.BasicIndex;
import org.audiveris.omr.util.Entity;

/** Exercise the original entity index with its declared standard concurrent-map field. */
public final class IndexCases {
    public static String exercise() {
        BasicIndex<Item> index = new BasicIndex<>(new AtomicInteger());
        Collection<Item> live = index.getEntities();
        Item first = new Item(), second = new Item(), third = new Item();
        if (index.register(first) != 1 || index.register(second) != 2 || index.register(third) != 3
                || index.register(first) != 1 || live.size() != 3) { throw new AssertionError("Index registration"); }
        if (index.remove(second) != second || index.getIdAfter(1) != 3 || index.getIdBefore(3) != 1
                || index.getEntity(2) != null) { throw new AssertionError("Index removal/navigation"); }
        try { live.clear(); throw new AssertionError("Mutable public entity collection"); }
        catch (UnsupportedOperationException expected) { }
        Iterator<Item> iterator = index.iterator();
        if (iterator.next() != first) { throw new AssertionError("Index ordering"); }
        Item fourth = new Item(); index.register(fourth); iterator.remove();
        while (iterator.hasNext()) { iterator.next(); }
        if (live.size() != 2 || index.getEntity(1) != null || index.getEntity(4) != fourth) {
            throw new AssertionError("Index mutation during traversal");
        }
        String result = index.getIdAfter(0) + ":" + index.getIdBefore(4) + ":" + index.getEntities();
        index.reset(); if (!live.isEmpty() || index.register(new Item()) != 1) { throw new AssertionError("Index reset"); }
        return result + ":reset-ok";
    }
    private static final class Item implements Entity {
        private int id; private boolean vip;
        @Override public boolean contains(Point point) { return false; }
        @Override public Rectangle getBounds() { return new Rectangle(id, 0, 1, 1); }
        @Override public String dumpOf() { return toString(); }
        @Override public String getFullId() { return "" + id; }
        @Override public int getId() { return id; }
        @Override public void setId(int id) { this.id = id; }
        @Override public boolean isVip() { return vip; }
        @Override public void setVip(boolean vip) { this.vip = vip; }
        @Override public String toString() { return "item-" + id; }
    }
}
