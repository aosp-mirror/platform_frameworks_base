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

import java.util.Set;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

/**
 * Promotes given fields to public visibility.
 */
public class PromoteFieldClassAdapter extends ClassVisitor {

    private final Set<String> mFieldNames;
    private static final int CLEAR_PRIVATE_MASK = ~(ACC_PRIVATE | ACC_PROTECTED);

    public PromoteFieldClassAdapter(ClassVisitor cv, Set<String> fieldNames) {
        super(Main.ASM_VERSION, cv);
        mFieldNames = fieldNames;
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature,
            Object value) {
        if (mFieldNames.contains(name)) {
            if ((access & ACC_PUBLIC) == 0) {
                access = (access & CLEAR_PRIVATE_MASK) | ACC_PUBLIC;
            }
        }
        return super.visitField(access, name, desc, signature, value);
    }
}
