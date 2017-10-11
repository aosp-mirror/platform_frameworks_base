/*
 * Copyright (C) 2017 The Android Open Source Project
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

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.StringJoiner;

import static org.junit.Assert.assertTrue;

/**
 * {@link ClassVisitor} that logs all the calls to the different visit methods so they can be later
 * inspected.
 */
class LoggingClassVisitor extends ClassVisitor {
    List<String> mLog = new LinkedList<String>();

    public LoggingClassVisitor() {
        super(Main.ASM_VERSION);
    }

    public LoggingClassVisitor(ClassVisitor cv) {
        super(Main.ASM_VERSION, cv);
    }

    private static String formatAccess(int access) {
        StringJoiner modifiers = new StringJoiner(",");

        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            modifiers.add("public");
        }
        if ((access & Opcodes.ACC_PRIVATE) != 0) {
            modifiers.add("private");
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            modifiers.add("protected");
        }
        if ((access & Opcodes.ACC_STATIC) != 0) {
            modifiers.add("static");
        }
        if ((access & Opcodes.ACC_FINAL) != 0) {
            modifiers.add("static");
        }

        return "[" + modifiers.toString() + "]";
    }

    private void log(String method, String format, Object...args) {
        mLog.add(
                String.format("[%s] - %s", method, String.format(format, (Object[]) args))
        );
    }

    @Override
    public void visitOuterClass(String owner, String name, String desc) {
        log(
                "visitOuterClass",
                "owner=%s, name=%s, desc=%s",
                owner, name, desc
        );

        super.visitOuterClass(owner, name, desc);
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        log(
                "visitInnerClass",
                "name=%s, outerName=%s, innerName=%s, access=%s",
                name, outerName, innerName, formatAccess(access)
        );

        super.visitInnerClass(name, outerName, innerName, access);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
            String[] interfaces) {
        log(
                "visit",
                "version=%d, access=%s, name=%s, signature=%s, superName=%s, interfaces=%s",
                version, formatAccess(access), name, signature, superName, Arrays.toString(interfaces)
        );

        super.visit(version, access, name, signature, superName, interfaces);
    }
}

class PackageProtectedClass {}

public class PromoteClassClassAdapterTest {
    private static class PrivateClass {}
    private static class ClassWithPrivateInnerClass {
        private class InnerPrivateClass {}
    }

    @Test
    public void testInnerClassPromotion() throws IOException {
        ClassReader reader = new ClassReader(PrivateClass.class.getName());
        LoggingClassVisitor log = new LoggingClassVisitor();

        PromoteClassClassAdapter adapter = new PromoteClassClassAdapter(log, new HashSet<String>() {
            {
                add("com.android.tools.layoutlib.create.PromoteClassClassAdapterTest$PrivateClass");
                add("com.android.tools.layoutlib.create" +
                        ".PromoteClassClassAdapterTest$ClassWithPrivateInnerClass$InnerPrivateClass");
            }
        });
        reader.accept(adapter, 0);
        assertTrue(log.mLog.contains(
                "[visitInnerClass] - " +
                        "name=com/android/tools/layoutlib/create" +
                        "/PromoteClassClassAdapterTest$PrivateClass, " +
                        "outerName=com/android/tools/layoutlib/create" +
                        "/PromoteClassClassAdapterTest, innerName=PrivateClass, access=[public,static]"));

        // Test inner of inner class
        log.mLog.clear();
        reader = new ClassReader(ClassWithPrivateInnerClass.class.getName());
        reader.accept(adapter, 0);

        assertTrue(log.mLog.contains("[visitInnerClass] - " +
                "name=com/android/tools/layoutlib/create" +
                "/PromoteClassClassAdapterTest$ClassWithPrivateInnerClass$InnerPrivateClass, " +
                "outerName=com/android/tools/layoutlib/create" +
                "/PromoteClassClassAdapterTest$ClassWithPrivateInnerClass, " +
                "innerName=InnerPrivateClass, access=[public]"));

    }

    @Test
    public void testProtectedClassPromotion() throws IOException {
        ClassReader reader = new ClassReader(PackageProtectedClass.class.getName());
        LoggingClassVisitor log = new LoggingClassVisitor();

        PromoteClassClassAdapter adapter = new PromoteClassClassAdapter(log, new HashSet<String>() {
            {
                add("com.android.tools.layoutlib.create.PackageProtectedClass");
            }
        });

        reader.accept(adapter, 0);
        assertTrue(log.mLog.contains("[visit] - version=52, access=[public], " +
                "name=com/android/tools/layoutlib/create/PackageProtectedClass, signature=null, " +
                "superName=java/lang/Object, interfaces=[]"));

    }
}