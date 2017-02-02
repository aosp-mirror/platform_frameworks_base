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

import org.objectweb.asm.ClassVisitor;

import java.util.Set;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

/**
 * Promotes given classes to public visibility.
 */
public class PromoteClassClassAdapter extends ClassVisitor {

    private final Set<String> mClassNames;
    private static final int CLEAR_PRIVATE_MASK = ~(ACC_PRIVATE | ACC_PROTECTED);

    public PromoteClassClassAdapter(ClassVisitor cv, Set<String> classNames) {
        super(Main.ASM_VERSION, cv);
        mClassNames =
                classNames.stream().map(name -> name.replace(".", "/")).collect(Collectors.toSet());
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
            String[] interfaces) {
        if (mClassNames.contains(name)) {
            if ((access & ACC_PUBLIC) == 0) {
                access = (access & CLEAR_PRIVATE_MASK) | ACC_PUBLIC;
            }
        }

        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        if (mClassNames.contains(name)) {
            if ((access & ACC_PUBLIC) == 0) {
                access = (access & CLEAR_PRIVATE_MASK) | ACC_PUBLIC;
            }
        }

        super.visitInnerClass(name, outerName, innerName, access);
    }
}
