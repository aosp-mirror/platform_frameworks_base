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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces calls to certain methods that do not exist in the Desktop VM. Useful for methods in the
 * "java" package.
 */
public class ReplaceMethodCallsAdapter extends ClassVisitor {

    /**
     * Descriptors for specialized versions {@link System#arraycopy} that are not present on the
     * Desktop VM.
     */
    private static Set<String> ARRAYCOPY_DESCRIPTORS = new HashSet<String>(Arrays.asList(
            "([CI[CII)V", "([BI[BII)V", "([SI[SII)V", "([II[III)V",
            "([JI[JII)V", "([FI[FII)V", "([DI[DII)V", "([ZI[ZII)V"));

    private static final List<MethodReplacer> METHOD_REPLACERS = new ArrayList<MethodReplacer>(2);

    // Static initialization block to initialize METHOD_REPLACERS.
    static {
        // Case 1: java.lang.System.arraycopy()
        METHOD_REPLACERS.add(new MethodReplacer() {
            @Override
            public boolean isNeeded(String owner, String name, String desc) {
                return owner.equals("java/lang/System") && name.equals("arraycopy") &&
                        ARRAYCOPY_DESCRIPTORS.contains(desc);
            }

            @Override
            public void replace(int opcode, String owner, String name, String desc,
                    int[] opcodeOut, String[] output) {
                assert isNeeded(owner, name, desc) && output.length == 3
                        && opcodeOut.length == 1;
                opcodeOut[0] = opcode;
                output[0] = owner;
                output[1] = name;
                output[2] = "(Ljava/lang/Object;ILjava/lang/Object;II)V";
            }
        });

        // Case 2: java.util.Locale.toLanguageTag()
        METHOD_REPLACERS.add(new MethodReplacer() {
            @Override
            public boolean isNeeded(String owner, String name, String desc) {
                return owner.equals("java/util/Locale") && name.equals("toLanguageTag") &&
                        "()Ljava/lang/String;".equals(desc);
            }

            @Override
            public void replace(int opcode, String owner, String name, String desc,
                    int[] opcodeOut, String[] output) {
                assert isNeeded(owner, name, desc) && output.length == 3
                        && opcodeOut.length == 1;
                opcodeOut[0] = Opcodes.INVOKESTATIC;
                output[0] = "com/android/layoutlib/bridge/android/AndroidLocale";
                output[1] = name;
                output[2] = "(Ljava/util/Locale;)Ljava/lang/String;";
            }
        });
    }

    public static boolean isReplacementNeeded(String owner, String name, String desc) {
        for (MethodReplacer replacer : METHOD_REPLACERS) {
            if (replacer.isNeeded(owner, name, desc)) {
                return true;
            }
        }
        return false;
    }

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
            for (MethodReplacer replacer : METHOD_REPLACERS) {
                if (replacer.isNeeded(owner, name, desc)) {
                    String[] output = new String[3];
                    int[] opcodeOut = new int[1];
                    replacer.replace(opcode, owner, name, desc, opcodeOut, output);
                    opcode = opcodeOut[0];
                    owner = output[0];
                    name = output[1];
                    desc = output[2];
                    break;
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc);
        }
    }

    private interface MethodReplacer {
        public boolean isNeeded(String owner, String name, String desc);

        /**
         * This method must update the values of the output arrays with the new values of method
         * attributes - opcode, owner, name and desc.
         * @param opcodeOut An array that will contain the new value of the opcode. The size of
         *                  the array must be 1.
         * @param output An array that will contain the new values of the owner, name and desc in
         *               that order. The size of the array must be 3.
         */
        public void replace(int opcode, String owner, String name, String desc, int[] opcodeOut,
                String[] output);
    }
}
