/*
 * Copyright (C) 2014 The Android Open Source Project
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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Replaces calls to certain methods that do not exist in the Desktop VM.
 */
public class ReplaceMethodCallsAdapter extends ClassVisitor {

    /**
     * Descriptors for specialized versions {@link System#arraycopy} that are not present on the
     * Desktop VM.
     */
    private static Set<String> ARRAYCOPY_DESCRIPTORS = new HashSet<String>(Arrays.asList(
            "([CI[CII)V", "([BI[BII)V", "([SI[SII)V", "([II[III)V",
            "([JI[JII)V", "([FI[FII)V", "([DI[DII)V", "([ZI[ZII)V"));

    public ReplaceMethodCallsAdapter(ClassVisitor cv) {
        super(Opcodes.ASM4, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature,
            String[] exceptions) {
        return new MyMethodVisitor(super.visitMethod(access, name, desc, signature, exceptions));
    }

    private class MyMethodVisitor extends MethodVisitor {

        public MyMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM4, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc) {
            // Check if method is a specialized version of java.lang.System.arrayCopy
            if (owner.equals("java/lang/System") && name.equals("arraycopy")) {

                if (ARRAYCOPY_DESCRIPTORS.contains(desc)) {
                    desc = "(Ljava/lang/Object;ILjava/lang/Object;II)V";
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc);
        }
    }
}
