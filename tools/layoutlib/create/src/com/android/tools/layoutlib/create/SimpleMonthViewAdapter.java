/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.layoutlib.create;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.text.SimpleDateFormat;

import static com.android.tools.layoutlib.create.AsmGenerator.binaryToInternalClassName;

/**
 * A very ugly hack to transform all references to {@link java.text.SimpleDateFormat} in {@code
 * android.widget.SimpleMonthView} to {@code com.ibm.icu.text.SimpleDateFormat}.
 */
public class SimpleMonthViewAdapter extends ClassVisitor {

    private static final String JAVA_SDF_DESC = Type.getDescriptor(SimpleDateFormat.class);
    private static final String JAVA_SDF_INTERNAL_NAME =
            binaryToInternalClassName(SimpleDateFormat.class.getName());
    private static final String ICU_SDF_INTERNAL_NAME = "com/ibm/icu/text/SimpleDateFormat";

    public SimpleMonthViewAdapter(ClassVisitor cv) {
        super(Opcodes.ASM4, cv);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature,
            Object value) {
        if (JAVA_SDF_DESC.equals(desc)) {
            desc = "L" + ICU_SDF_INTERNAL_NAME + ";";
        }
        return super.visitField(access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature,
            String[] exceptions) {
        return new MyMethodVisitor(super.visitMethod(access, name, desc, signature, exceptions));
    }

    private static class MyMethodVisitor extends MethodVisitor {
        public MyMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM4, mv);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (JAVA_SDF_INTERNAL_NAME.equals(type) && opcode == Opcodes.NEW) {
                type = ICU_SDF_INTERNAL_NAME;
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc) {
            if (JAVA_SDF_INTERNAL_NAME.equals(owner)) {
                owner = ICU_SDF_INTERNAL_NAME;
            }
            super.visitMethodInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (JAVA_SDF_DESC.equals(desc) &&
                    (opcode == Opcodes.PUTFIELD || opcode == Opcodes.GETFIELD) &&
                    name.equals("mDayFormatter")) {
                desc = "L" + ICU_SDF_INTERNAL_NAME + ";";
            }
            super.visitFieldInsn(opcode, owner, name, desc);
        }
    }
}
