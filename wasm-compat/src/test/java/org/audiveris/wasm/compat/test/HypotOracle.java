// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.security.MessageDigest;

/** Independent JDK results, including mixed infinity/NaN and exponent extremes. */
public final class HypotOracle {
    private HypotOracle(){ }
    public static void write(Path output)throws Exception{
        var rows=new ArrayList<List<String>>();
        long[] special={0L,Long.MIN_VALUE,1L,Long.MIN_VALUE|1L,0x0010000000000000L,
            0x8010000000000000L,0x3ff0000000000000L,0xbff0000000000000L,
            0x7fefffffffffffffL,0xffefffffffffffffL,0x7ff0000000000000L,
            0xfff0000000000000L,0x7ff8000000000000L,0x7ff0000000000001L};
        for(long x:special)for(long y:special)add(rows,x,y);
        var random=new Random(0x4859504f54L);
        for(int i=0;i<4096;i++)add(rows,random.nextLong(),random.nextLong());
        String classSha;
        try(var in=Object.class.getResourceAsStream("/java/lang/FdLibm$Hypot.class")){
            if(in==null)throw new AssertionError("Pinned JDK hypot implementation unavailable");
            classSha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
        var trigHashes=new TreeMap<String,String>();
        for(String name:List.of("Sin","Cos","Atan","Atan2","RemPio2","KernelRemPio2")){
            try(var in=Object.class.getResourceAsStream("/java/lang/FdLibm$"+name+".class")){
                if(in==null)throw new AssertionError("Pinned JDK trig implementation unavailable: "+name);
                trigHashes.put(name,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            }
        }
        new ObjectMapper().writeValue(output.toFile(),Map.of("runtime",System.getProperty("java.runtime.version"),
            "jdkHypotClassSha256",classSha,"jdkTrigClassSha256",trigHashes,"cases",rows));
    }
    private static void add(List<List<String>> rows,long xb,long yb){
        double x=Double.longBitsToDouble(xb),y=Double.longBitsToDouble(yb);
        long expected=Double.doubleToLongBits(Math.hypot(x,y));
        var hex=HexFormat.of();rows.add(List.of(hex.toHexDigits(xb),hex.toHexDigits(yb),hex.toHexDigits(expected),
            hex.toHexDigits(Double.doubleToLongBits(StrictMath.sin(x))),hex.toHexDigits(Double.doubleToLongBits(StrictMath.cos(x))),
            hex.toHexDigits(Double.doubleToLongBits(StrictMath.atan(x))),hex.toHexDigits(Double.doubleToLongBits(StrictMath.atan2(x,y)))));
    }
}
