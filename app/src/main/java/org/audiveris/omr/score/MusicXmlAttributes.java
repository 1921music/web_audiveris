// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.omr.score;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.audiveris.proxymusic.*;

/** Typed access to the MusicXML model's shared attributes (the generated model has no interfaces). */
final class MusicXmlAttributes {
    private MusicXmlAttributes(){ }
    static void staff(Object value,BigInteger staff){
        if(value instanceof Direction v)v.setStaff(staff);
        else if(value instanceof Forward v)v.setStaff(staff);
        else if(value instanceof Harmony v)v.setStaff(staff);
        else throw new IllegalArgumentException("Unsupported staff attribute");
    }
    static void placement(Object value,AboveBelow placement){
        if(value instanceof EmptyPlacement v)v.setPlacement(placement);
        else if(value instanceof EmptyTrillSound v)v.setPlacement(placement);
        else if(value instanceof HorizontalTurn v)v.setPlacement(placement);
        else if(value instanceof BreathMark v)v.setPlacement(placement);
        else if(value instanceof Caesura v)v.setPlacement(placement);
        else throw new IllegalArgumentException("Unsupported placement attribute");
    }
    static void defaultY(Object value,BigDecimal y){
        if(value instanceof EmptyPlacement v)v.setDefaultY(y);
        else if(value instanceof BreathMark v)v.setDefaultY(y);
        else if(value instanceof Caesura v)v.setDefaultY(y);
        else throw new IllegalArgumentException("Unsupported default-y attribute");
    }
    static void defaultX(Object value,BigDecimal x){
        if(value instanceof BreathMark v)v.setDefaultX(x);
        else if(value instanceof Caesura v)v.setDefaultX(x);
        else throw new IllegalArgumentException("Unsupported default-x attribute");
    }
}
