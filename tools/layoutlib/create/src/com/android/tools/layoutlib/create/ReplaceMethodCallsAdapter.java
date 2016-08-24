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
    private static Set<String> ARRAYCOPY_DESCRIPTORS = new HashSet<>(Arrays.asList(
            "([CI[CII)V", "([BI[BII)V", "([SI[SII)V", "([II[III)V",
            "([JI[JII)V", "([FI[FII)V", "([DI[DII)V", "([ZI[ZII)V"));

    private static final List<MethodReplacer> METHOD_REPLACERS = new ArrayList<>(5);

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
            public boolean isNeeded(String owner, String name, String desc, String sourceClass) {
                return JAVA_LANG_SYSTEM.equals(owner) && "arraycopy".equals(name) &&
                        ARRAYCOPY_DESCRIPTORS.contains(desc);
            }

            @Override
            public void replace(MethodInformation mi) {
                mi.desc = "(Ljava/lang/Object;ILjava/lang/Object;II)V";
            }
        });

        // Case 2: java.util.Locale.toLanguageTag() and java.util.Locale.getScript()
        METHOD_REPLACERS.add(new MethodReplacer() {

            private final String LOCALE_TO_STRING =
                    Type.getMethodDescriptor(STRING, Type.getType(Locale.class));

            @Override
            public boolean isNeeded(String owner, String name, String desc, String sourceClass) {
                return JAVA_LOCALE_CLASS.equals(owner) && "()Ljava/lang/String;".equals(desc) &&
                        ("toLanguageTag".equals(name) || "getScript".equals(name));
            }

            @Override
            public void replace(MethodInformation mi) {
                mi.opcode = Opcodes.INVOKESTATIC;
                mi.owner = ANDROID_LOCALE_CLASS;
                mi.desc = LOCALE_TO_STRING;
            }
        });

        // Case 3: java.util.Locale.adjustLanguageCode() or java.util.Locale.forLanguageTag() or
        // java.util.Locale.getDefault()
        METHOD_REPLACERS.add(new MethodReplacer() {

            private final String STRING_TO_STRING = Type.getMethodDescriptor(STRING, STRING);
            private final String STRING_TO_LOCALE = Type.getMethodDescriptor(
                    Type.getType(Locale.class), STRING);
            private final String VOID_TO_LOCALE =
                    Type.getMethodDescriptor(Type.getType(Locale.class));

            @Override
            public boolean isNeeded(String owner, String name, String desc, String sourceClass) {
                return JAVA_LOCALE_CLASS.equals(owner) &&
                        ("adjustLanguageCode".equals(name) && desc.equals(STRING_TO_STRING) ||
                        "forLanguageTag".equals(name) && desc.equals(STRING_TO_LOCALE) ||
                        "getDefault".equals(name) && desc.equals(VOID_TO_LOCALE));
            }

            @Override
            public void replace(MethodInformation mi) {
                mi.owner = ANDROID_LOCALE_CLASS;
            }
        });

        // Case 4: java.lang.System.log?()
        METHOD_REPLACERS.add(new MethodReplacer() {
            @Override
            public boolean isNeeded(String owner, String name, String desc, String sourceClass) {
                return JAVA_LANG_SYSTEM.equals(owner) && name.length() == 4
                        && name.startsWith("log");
            }

            @Override
            public void replace(MethodInformation mi) {
                assert mi.desc.equals("(Ljava/lang/String;Ljava/lang/Throwable;)V")
                        || mi.desc.equals("(Ljava/lang/String;)V");
                mi.name = "log";
                mi.owner = Type.getInternalName(System_Delegate.class);
            }
        });

        // Case 5: java.lang.System time calls
        METHOD_REPLACERS.add(new MethodReplacer() {
            @Override
            public boolean isNeeded(String owner, String name, String desc, String sourceClass) {
                return JAVA_LANG_SYSTEM.equals(owner) && name.equals("nanoTime");
            }

            @Override
            public void replace(MethodInformation mi) {
                mi.name = "nanoTime";
                mi.owner = Type.getInternalName(System_Delegate.class);
            }
        });
        METHOD_REPLACERS.add(new MethodReplacer() {
            @Override
            public boolean isNeeded(String owner, String name, String desc, String sourceClass) {
                return JAVA_LANG_SYSTEM.equals(owner) && name.equals("currentTimeMillis");
            }

            @Override
            public void replace(MethodInformation mi) {
                mi.name = "currentTimeMillis";
                mi.owner = Type.getInternalName(System_Delegate.class);
            }
        });

        // Case 6: java.util.LinkedHashMap.eldest()
        METHOD_REPLACERS.add(new MethodReplacer() {

            private final String VOID_TO_MAP_ENTRY =
                    Type.getMethodDescriptor(Type.getType(Map.Entry.class));
            private final String LINKED_HASH_MAP = Type.getInternalName(LinkedHashMap.class);

            @Override
            public boolean isNeeded(String owner, String name, String desc, String sourceClass) {
                return LINKED_HASH_MAP.equals(owner) &&
                        "eldest".equals(name) &&
                        VOID_TO_MAP_ENTRY.equals(desc);
            }

            @Override
            public void replace(MethodInformation mi) {
                mi.opcode = Opcodes.INVOKESTATIC;
                mi.owner = Type.getInternalName(LinkedHashMap_Delegate.class);
                mi.desc = Type.getMethodDescriptor(
                        Type.getType(Map.Entry.class), Type.getType(LinkedHashMap.class));
            }
        });

        // Case 7: android.content.Context.getClassLoader() in LayoutInflater
        METHOD_REPLACERS.add(new MethodReplacer() {
            // When LayoutInflater asks for a class loader, we must return the class loader that
            // cannot return app's custom views/classes. This is so that in case of any failure
            // or exception when instantiating the views, the IDE can replace it with a mock view
            // and have proper error handling. However, if a custom view asks for the class
            // loader, we must return a class loader that can find app's custom views as well.
            // Thus, we rewrite the call to get class loader in LayoutInflater to
            // getFrameworkClassLoader and inject a new method in Context. This leaves the normal
            // method: Context.getClassLoader() free to be used by the apps.
            private final String VOID_TO_CLASS_LOADER =
                    Type.getMethodDescriptor(Type.getType(ClassLoader.class));

            @Override
            public boolean isNeeded(String owner, String name, String desc, String sourceClass) {
                return owner.equals("android/content/Context") &&
                        sourceClass.equals("android/view/LayoutInflater") &&
                        name.equals("getClassLoader") &&
                        desc.equals(VOID_TO_CLASS_LOADER);
            }

            @Override
            public void replace(MethodInformation mi) {
                mi.name = "getFrameworkClassLoader";
            }
        });
    }

    /**
     * If a method some.package.Class.Method(args) is called from some.other.Class,
     * @param owner some/package/Class
     * @param name Method
     * @param desc (args)returnType
     * @param sourceClass some/other/Class
     * @return if the method invocation needs to be replaced by some other class.
     */
    public static boolean isReplacementNeeded(String owner, String name, String desc,
            String sourceClass) {
        for (MethodReplacer replacer : METHOD_REPLACERS) {
            if (replacer.isNeeded(owner, name, desc, sourceClass)) {
                return true;
            }
        }
        return false;
    }

    private final String mOriginalClassName;

    public ReplaceMethodCallsAdapter(ClassVisitor cv, String originalClassName) {
        super(Main.ASM_VERSION, cv);
        mOriginalClassName = originalClassName;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature,
            String[] exceptions) {
        return new MyMethodVisitor(super.visitMethod(access, name, desc, signature, exceptions));
    }

    private class MyMethodVisitor extends MethodVisitor {

        public MyMethodVisitor(MethodVisitor mv) {
            super(Main.ASM_VERSION, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                boolean itf) {
            for (MethodReplacer replacer : METHOD_REPLACERS) {
                if (replacer.isNeeded(owner, name, desc, mOriginalClassName)) {
                    MethodInformation mi = new MethodInformation(opcode, owner, name, desc);
                    replacer.replace(mi);
                    opcode = mi.opcode;
                    owner = mi.owner;
                    name = mi.name;
                    desc = mi.desc;
                    break;
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
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
        boolean isNeeded(String owner, String name, String desc, String sourceClass);

        /**
         * Updates the MethodInformation with the new values of the method attributes -
         * opcode, owner, name and desc.
         */
        void replace(MethodInformation mi);
    }
}
