package org.audiveris.wasm.compat.test;

import java.util.*;
import org.audiveris.wasm.compat.platform.StemItemSort;

/** Differential comparison schedules, including inconsistent mixed-kind orderings. */
public final class StemSortContract {
    public static void main(String[] args) throws Exception {
        String reference=run(false);
        if(!reference.equals(run(true))){throw new AssertionError("Stem sort wrapper differs from native list sorting");}
        var output=java.nio.file.Path.of(args[0]);java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output,reference);
        System.out.println("Pinned JVM stem sorting: 768 mixed-comparator lists and sublist boundaries");
    }
    private record Item(int id, boolean half, int reference, int start, int end) { }
    static String run(boolean portable) {
        StringBuilder output=new StringBuilder();int state=0x39b7142;
        for(int direction:new int[]{-1,1}) {
            Comparator<Item> comparator=(a,b)->a.half&&b.half?direction*Integer.compare(a.reference,b.reference)
                :direction>0?Integer.compare(a.start,b.start):Integer.compare(b.end,a.end);
            for(int test=0;test<384;test++) {
                int size=test<129?test:32+(test%97),offset=test%4;
                List<Item> backing=new ArrayList<>();
                for(int i=0;i<offset;i++){backing.add(new Item(-1,false,0,0,0));}
                for(int i=0;i<size;i++) {
                    state=state*1664525+1013904223;boolean half=(state&4)!=0;
                    state=state*1664525+1013904223;int ref=(state>>>1)%40;
                    state=state*1664525+1013904223;int start=(state>>>1)%40;
                    state=state*1664525+1013904223;int end=start+(state>>>1)%40;
                    backing.add(new Item(i,half,ref,start,end));
                }
                backing.add(new Item(-2,false,0,0,0));List<Item> values=backing.subList(offset,offset+size);
                output.append(direction).append(':').append(test).append(':');
                try {
                    if(portable){StemItemSort.sort(values,comparator);}else{Collections.sort(values,comparator);}
                    for(Item item:values){output.append(item.id).append(',');}
                }catch(IllegalArgumentException expected){output.append('!');}
                for(int i=0;i<offset;i++){if(backing.get(i).id!=-1){throw new AssertionError("Sort modified prefix");}}
                if(backing.get(backing.size()-1).id!=-2){throw new AssertionError("Sort modified suffix");}
                output.append(';');
            }
        }
        return output.toString();
    }
}
