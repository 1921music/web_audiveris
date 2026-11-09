package org.audiveris.omr.constant;

/** The unchanged reference filter only needs its configured default window radius. */
public abstract class Constant {
    public static final class Integer {
        private final int value;
        public Integer(String unit, int value, String description) { this.value = value; }
        public int getValue() { return value; }
    }
}
