// SPDX-License-Identifier: AGPL-3.0-or-later
package org.audiveris.wasm.compat;

import org.teavm.model.*;
import org.teavm.model.instructions.*;
import org.teavm.model.optimization.ConstantConditionElimination;

/** Retain ImageJ's binary thinning code on owned, non-inverted gray buffers without its UI movie. */
final class BinarySkeletonPatches {
    static void apply(ClassHolder cls){
        String trig="org.audiveris.wasm.compat.platform.PortableTrig";
        if(cls.getName().equals(trig)){
            for(String name:new String[]{"sin","cos","atan","atan2"}){
                var signature=name.equals("atan2")?new ValueType[]{ValueType.DOUBLE,ValueType.DOUBLE,ValueType.DOUBLE}:new ValueType[]{ValueType.DOUBLE,ValueType.DOUBLE};
                String owner="java.lang.FdLibm$"+Character.toUpperCase(name.charAt(0))+name.substring(1);
                StandardApiPatches.delegate(cls,name.equals("atan2")?"atan2Core":name,"compute",owner,true,true,signature);
            }
        }
        // Rewrite calls rather than imported Math declarations: native-import annotations must
        // never bypass these pure Java bodies on one particular Wasm/JavaScript backend.
        for(MethodHolder method:cls.getMethods())if(method.getProgram()!=null){
            for(BasicBlock block:method.getProgram().getBasicBlocks())for(Instruction instruction:block){
                if(instruction instanceof InvokeInstruction call&&call.getMethod().getClassName().equals("java.lang.Math")){
                    var target=call.getMethod();
                    if(java.util.Set.of("sin","cos","atan","atan2").contains(target.getName())){
                        call.setMethod(new MethodReference(trig,target.getDescriptor()));
                    }
                }
            }
        }
        if(cls.getName().equals("java.lang.Math")){
            StandardApiPatches.delegate(cls,"hypot","hypot","org.audiveris.wasm.compat.platform.PortableHypot",true,true,ValueType.DOUBLE,ValueType.DOUBLE,ValueType.DOUBLE);
            StandardApiPatches.delegate(cls,"powerOfTwoD","powerOfTwoD","org.audiveris.wasm.compat.platform.PortableHypot",true,false,ValueType.INTEGER,ValueType.DOUBLE);
        }else if(cls.getName().equals("org.audiveris.wasm.compat.platform.PortableHypot")){
            StandardApiPatches.delegate(cls,"hypot","compute","java.lang.FdLibm$Hypot",true,true,ValueType.DOUBLE,ValueType.DOUBLE,ValueType.DOUBLE);
        }
        if(cls.getName().equals("org.audiveris.omr.sheet.curve.RehearsalsBuilder")){
            var pixels=ValueType.object("ij.process.ByteProcessor");
            StandardApiPatches.delegate(cls,"cleanScene","cleanScene","org.audiveris.omr.sheet.curve.WorkerRehearsalsBridge",false,true,pixels,ValueType.object("java.awt.Rectangle"),ValueType.object("java.util.List"),ValueType.INTEGER,pixels);
            int crops=0;
            for(MethodHolder method:cls.getMethods())if(method.getName().equals("prepare")&&method.getProgram()!=null){
                for(BasicBlock block:method.getProgram().getBasicBlocks())for(Instruction instruction:block){
                    if(instruction instanceof InvokeInstruction call&&call.getMethod().getName().equals("crop")){
                        call.setArguments(call.getInstance());call.setInstance(null);call.setType(InvocationType.SPECIAL);
                        call.setMethod(new MethodReference("org.audiveris.omr.sheet.curve.WorkerRehearsalsBridge","crop",pixels,pixels));crops++;
                    }
                }
            }
            if(crops!=1)throw new IllegalStateException("Review rehearsal crop boundary: "+crops);
        }
        if(!cls.getName().equals("ij.process.BinaryProcessor"))return;
        int colors=0,fresh=0,debug=0,inserts=0,borders=0,moves=0,lines=0;
        for(MethodHolder method:cls.getMethods()){
            if(method.getProgram()==null)continue;
            var discarded=new java.util.ArrayList<Instruction>();
            for(BasicBlock block:method.getProgram().getBasicBlocks())for(Instruction instruction:block){
                if(instruction instanceof InvokeInstruction call){
                    if(method.getName().equals("skeletonize2")&&call.getMethod().getName().equals("setColor")){
                        var receiver=call.getInstance();var color=call.getArguments().get(0);
                        call.setArguments(receiver,color);call.setInstance(null);call.setType(InvocationType.SPECIAL);
                        call.setMethod(new MethodReference("org.audiveris.omr.sheet.curve.WorkerSkeletonBridge","clearBorder",ValueType.object("ij.process.BinaryProcessor"),ValueType.INTEGER,ValueType.VOID));borders++;
                    }else if(method.getName().equals("skeletonize2")&&call.getMethod().getName().equals("moveTo")){
                        discarded.add(call);moves++;
                    }else if(method.getName().equals("skeletonize2")&&call.getMethod().getName().equals("lineTo")){
                        discarded.add(call);lines++;
                    }else if(method.getName().equals("<init>")&&call.getMethod().getName().equals("getColorModel")&&call.getArguments().isEmpty()){
                        NullConstantInstruction nil=new NullConstantInstruction();nil.setReceiver(call.getReceiver());nil.setLocation(call.getLocation());call.replace(nil);colors++;
                    }else if(method.getName().equals("expand")&&call.getMethod().getName().equals("createProcessor")){
                        if(call.getArguments().size()!=2)throw new IllegalStateException("Review ImageJ skeleton expansion");
                        call.setInstance(null);call.setType(InvocationType.SPECIAL);
                        call.setMethod(new MethodReference("org.audiveris.omr.sheet.curve.WorkerSkeletonBridge","fresh",ValueType.INTEGER,ValueType.INTEGER,ValueType.object("ij.process.ImageProcessor")));fresh++;
                    }else if(method.getName().equals("expand")&&call.getMethod().getName().equals("insert")){
                        var arguments=new java.util.ArrayList<Variable>();arguments.add(call.getInstance());arguments.addAll(call.getArguments());
                        call.setArguments(arguments.toArray(Variable[]::new));call.setInstance(null);call.setType(InvocationType.SPECIAL);
                        call.setMethod(new MethodReference("org.audiveris.omr.sheet.curve.WorkerSkeletonBridge","insert",ValueType.object("ij.process.BinaryProcessor"),ValueType.object("ij.process.ImageProcessor"),ValueType.INTEGER,ValueType.INTEGER,ValueType.VOID));inserts++;
                    }
                }else if(instruction instanceof GetFieldInstruction get&&method.getName().equals("skeletonize2")&&get.getField().equals(new FieldReference("ij.IJ","debugMode"))){
                    IntegerConstantInstruction no=new IntegerConstantInstruction();no.setConstant(0);no.setReceiver(get.getReceiver());no.setLocation(get.getLocation());get.replace(no);debug++;
                }
            }
            for(Instruction instruction:discarded)instruction.delete();
            while(new ConstantConditionElimination().optimize(method.getDescriptor(),method.getProgram())){ }
        }
        if(colors!=1||fresh!=1||debug!=1||inserts!=1||borders!=1||moves!=4||lines!=4)throw new IllegalStateException("Review pinned ImageJ skeleton boundaries: "+colors+"/"+fresh+"/"+debug+"/"+inserts+"/"+borders+"/"+moves+"/"+lines);
    }
}
