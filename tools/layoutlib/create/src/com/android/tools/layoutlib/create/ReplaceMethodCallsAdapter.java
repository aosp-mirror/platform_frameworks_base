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
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    private static final String ANDROID_LOCALE_CLASS =
            "com/android/layoutlib/bridge/android/AndroidLocale";

    private static final String JAVA_LOCALE_CLASS = "java/util/Locale";
    private static final Type STRING = Type.getType(String.class);

    // Static initialization block to initialize METHOD_REPLACERS.
    static {
        // Case 1: java.lang.System.arraycopy()
        METHOD_REPLACERS.add(new MethodReplacer() {
            @Override
            public boolean isNeeded(String owner, String name, String desc) {
                return "java/lang/System".equals(owner) && "arraycopy".equals(name) &&
                        ARRAYCOPY_DESCRIPTORS.contains(desc);
            }

            @Override
            public void replace(int[] opcode, String[] methodInformation) {
                assert methodInformation.length == 3 && isNeeded(methodInformation[0], methodInformation[1], methodInformation[2])
                        && opcode.length == 1;
                methodInformation[2] = "(Ljava/lang/Object;ILjava/lang/Object;II)V";
            }
        });

        // Case 2: java.util.Locale.toLanguageTag() and java.util.Locale.getScript()
        METHOD_REPLACERS.add(new MethodReplacer() {

            String LOCALE_TO_STRING = Type.getMethodDescriptor(STRING, Type.getType(Locale.class));

            @Override
            public boolean isNeeded(String owner, String name, String desc) {
                return JAVA_LOCALE_CLASS.equals(owner) && "()Ljava/lang/String;".equals(desc) &&
                        ("toLanguageTag".equals(name) || "getScript".equals(name));
            }

            @Override
            public void replace(int[] opcode, String[] methodInformation) {
                assert methodInformation.length == 3 && isNeeded(methodInformation[0], methodInformation[1], methodInformation[2])
                        && opcode.length == 1;
                opcode[0] = Opcodes.INVOKESTATIC;
                methodInformation[0] = ANDROID_LOCALE_CLASS;
                methodInformation[2] = LOCALE_TO_STRING;
            }
        });

        // Case 3: java.util.Locale.adjustLanguageCode() or java.util.Locale.forLanguageTag()
        METHOD_REPLACERS.add(new MethodReplacer() {

            private final String STRING_TO_STRING = Type.getMethodDescriptor(STRING, STRING);
            private final String STRING_TO_LOCALE = Type.getMethodDescriptor(
                    Type.getType(Locale.class), STRING);

            @Override
            public boolean isNeeded(String owner, String name, String desc) {
                return JAVA_LOCALE_CLASS.equals(owner) &&
                        ("adjustLanguageCode".equals(name) && desc.equals(STRING_TO_STRING) ||
                        "forLanguageTag".equals(name) && desc.equals(STRING_TO_LOCALE));
            }

            @Override
            public void replace(int[] opcode, String[] methodInformation) {
                assert methodInformation.length == 3 && isNeeded(methodInformation[0], methodInformation[1], methodInformation[2])
                        && opcode.length == 1;
                methodInformation[0] = ANDROID_LOCALE_CLASS;
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
            for (MethodReplacer replacer : METHOD_REPLACERS) {
                if (replacer.isNeeded(owner, name, desc)) {
                    String[] methodInformation = {owner, name, desc};
                    int[] opcodeOut = {opcode};
                    replacer.replace(opcodeOut, methodInformation);
                    opcode = opcodeOut[0];
                    owner = methodInformation[0];
                    name = methodInformation[1];
                    desc = methodInformation[2];
                    break;
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc);
        }
    }

    private interface MethodReplacer {
        public boolean isNeeded(String owner, String name, String desc);

        /**
         * This method must update the arrays with the new values of the method attributes -
         * opcode, owner, name and desc.
         * @param opcode This array should contain the original value of the opcode. The value is
         *               modified by the method if needed. The size of the array must be 1.
         *
         * @param methodInformation This array should contain the original values of the method
         *                          attributes - owner, name and desc in that order. The values
         *                          may be modified as needed. The size of the array must be 3.
         */
        public void replace(int[] opcode, String[] methodInformation);
    }
}
