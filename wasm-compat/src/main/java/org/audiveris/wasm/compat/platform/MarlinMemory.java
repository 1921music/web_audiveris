// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.platform;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;

/** Module-local checked byte storage for Marlin's edge and alpha buffers, not general Unsafe. */
public final class MarlinMemory {
    private record Block(WeakReference<Object> owner, byte[] bytes) { }
    private static final HashMap<Integer,Block> blocks = new HashMap<>();
    private static int nextId = 1, lastId;
    private static Block last;
    private static long used;
    private static long budget = 64L * 1024 * 1024;
    private MarlinMemory() { }
    /** Configure before first allocation; each independent Wasm module owns its budget. */
    public static void configure(long bytes) {
        if (!blocks.isEmpty()) throw new IllegalStateException("Marlin memory is already in use");
        if (bytes < 1048576 || bytes > 268435456) throw new IllegalArgumentException("Marlin memory budget");
        budget = bytes;
    }
    private static void sweep() {
        var iterator = blocks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().owner.get() == null) {
                used -= entry.getValue().bytes.length; iterator.remove();
                if (entry.getKey() == lastId) { last = null; lastId = 0; }
            }
        }
    }
    private static int size(long length, long credit) {
        if (length < 0 || length > Integer.MAX_VALUE || used - credit + length > budget)
            throw new OutOfMemoryError("Marlin buffer budget exceeded");
        return (int)length;
    }
    public static long allocate(Object owner,long length) {
        java.util.Objects.requireNonNull(owner); sweep();
        int count = size(length,0);
        if (nextId <= 0) throw new OutOfMemoryError("Marlin address space exhausted");
        Block block = new Block(new WeakReference<>(owner),new byte[count]);
        int id = nextId++; blocks.put(id,block); used += count;
        return (long)id << 32;
    }
    private static Block block(long address,int length) {
        int id = (int)(address >>> 32), offset = (int)address;
        Block block = id == lastId ? last : blocks.get(id);
        if (block == null || offset < 0 || length < 0 || offset > block.bytes.length - length)
            throw new IndexOutOfBoundsException("Marlin buffer address");
        lastId = id; last = block; return block;
    }
    public static long reallocateMemory(long address,long length) {
        Block old = block(address,0);
        if ((int)address != 0) throw new IllegalArgumentException("Marlin base address required");
        int count = size(length,old.bytes.length);
        Block replacement = new Block(old.owner,Arrays.copyOf(old.bytes,count));
        blocks.put((int)(address >>> 32),replacement); used += count - old.bytes.length;
        last = replacement; return address;
    }
    public static void freeMemory(long address) {
        if (address == 0) return;
        Block old = block(address,0);
        if ((int)address != 0) throw new IllegalArgumentException("Marlin base address required");
        blocks.remove((int)(address >>> 32)); used -= old.bytes.length; lastId = 0; last = null;
    }
    public static byte getByte(long address) { return block(address,1).bytes[(int)address]; }
    public static void putByte(long address,byte value) { block(address,1).bytes[(int)address] = value; }
    public static int getInt(long address) {
        byte[] b = block(address,4).bytes; int i = (int)address;
        return (b[i]&255) | ((b[i+1]&255)<<8) | ((b[i+2]&255)<<16) | (b[i+3]<<24);
    }
    public static void putInt(long address,int value) {
        byte[] b = block(address,4).bytes; int i = (int)address;
        b[i] = (byte)value; b[i+1] = (byte)(value>>>8); b[i+2] = (byte)(value>>>16); b[i+3] = (byte)(value>>>24);
    }
    public static void setMemory(long address,long length,byte value) {
        if (length < 0 || length > Integer.MAX_VALUE) throw new IndexOutOfBoundsException("Marlin fill length");
        byte[] b = block(address,(int)length).bytes;
        Arrays.fill(b,(int)address,(int)address+(int)length,value);
    }
    public static long usedBytes() { return used; }
}
