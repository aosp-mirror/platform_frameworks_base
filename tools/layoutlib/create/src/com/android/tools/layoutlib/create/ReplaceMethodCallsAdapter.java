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

import com.android.tools.layoutlib.java.LinkedHashMap_Delegate;
import com.android.tools.layoutlib.java.System_Delegate;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final List<MethodReplacer> METHOD_REPLACERS = new ArrayList<MethodReplacer>(5);

    private static final String ANDROID_LOCALE_CLASS =
            "com/android/layoutlib/bridge/android/AndroidLocale";

    private static final String JAVA_LOCALE_CLASS = Type.getInternalName(java.util.Locale.class);
    private static final Type STRING = Type.getType(String.class);

    private static final String JAVA_LANG_SYSTEM = Type.getInternalName(System.class);

    // Static initialization block to initialize METHOD_REPLACERS.
    static {
        // Case 1: java.lang.System.arraycopy()
        METHOD_REPLACERS.add(new MethodReplacer() {
            @Override
            public boolean isNeeded(String owner, String name, String desc) {
                return JAVA_LANG_SYSTEM.equals(owner) && "arraycopy".equals(name) &&
                        ARRAYCOPY_DESCRIPTORS.contains(desc);
            }

            @Override
            public void replace(MethodInformation mi) {
                assert isNeeded(mi.owner, mi.name, mi.desc);
                mi.desc = "(Ljava/lang/Object;ILjava/lang/Object;II)V";
            }
        });

        // Case 2: java.util.Locale.toLanguageTag() and java.util.Locale.getScript()
        METHOD_REPLACERS.add(new MethodReplacer() {

            private final String LOCALE_TO_STRING =
                    Type.getMethodDescriptor(STRING, Type.getType(Locale.class));

            @Override
            public boolean isNeeded(String owner, String name, String desc) {
                return JAVA_LOCALE_CLASS.equals(owner) && "()Ljava/lang/String;".equals(desc) &&
                        ("toLanguageTag".equals(name) || "getScript".equals(name));
            }

            @Override
            public void replace(MethodInformation mi) {
                assert isNeeded(mi.owner, mi.name, mi.desc);
                mi.opcode = Opcodes.INVOKESTATIC;
                mi.owner = ANDROID_LOCALE_CLASS;
                mi.desc = LOCALE_TO_STRING;
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
            public void replace(MethodInformation mi) {
                assert isNeeded(mi.owner, mi.name, mi.desc);
                mi.owner = ANDROID_LOCALE_CLASS;
            }
        });

        // Case 4: java.lang.System.log?()
        METHOD_REPLACERS.add(new MethodReplacer() {
            @Override
            public boolean isNeeded(String owner, String name, String desc) {
                return JAVA_LANG_SYSTEM.equals(owner) && name.length() == 4
                        && name.startsWith("log");
            }

            @Override
            public void replace(MethodInformation mi) {
                assert isNeeded(mi.owner, mi.name, mi.desc);
                assert mi.desc.equals("(Ljava/lang/String;Ljava/lang/Throwable;)V")
                        || mi.desc.equals("(Ljava/lang/String;)V");
                mi.name = "log";
                mi.owner = Type.getInternalName(System_Delegate.class);
            }
        });

        // Case 5: java.util.LinkedHashMap.eldest()
        METHOD_REPLACERS.add(new MethodReplacer() {

            private final String VOID_TO_MAP_ENTRY =
                    Type.getMethodDescriptor(Type.getType(Map.Entry.class));
            private final String LINKED_HASH_MAP = Type.getInternalName(LinkedHashMap.class);

            @Override
            public boolean isNeeded(String owner, String name, String desc) {
                return LINKED_HASH_MAP.equals(owner) &&
                        "eldest".equals(name) &&
                        VOID_TO_MAP_ENTRY.equals(desc);
            }

            @Override
            public void replace(MethodInformation mi) {
                assert isNeeded(mi.owner, mi.name, mi.desc);
                mi.opcode = Opcodes.INVOKESTATIC;
                mi.owner = Type.getInternalName(LinkedHashMap_Delegate.class);
                mi.desc = Type.getMethodDescriptor(
                        Type.getType(Map.Entry.class), Type.getType(LinkedHashMap.class));
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
                    MethodInformation mi = new MethodInformation(opcode, owner, name, desc);
                    replacer.replace(mi);
                    opcode = mi.opcode;
                    owner = mi.owner;
                    name = mi.name;
                    desc = mi.desc;
                    break;
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc);
        }
    }

    private static class MethodInformation {
        public int opcode;
        public String owner;
        public String name;
        public String desc;

        public MethodInformation(int opcode, String owner, String name, String desc) {
            this.opcode = opcode;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }
    }

    private interface MethodReplacer {
        public boolean isNeeded(String owner, String name, String desc);

        /**
         * Updates the MethodInformation with the new values of the method attributes -
         * opcode, owner, name and desc.
         *
         */
        public void replace(MethodInformation mi);
    }
}
