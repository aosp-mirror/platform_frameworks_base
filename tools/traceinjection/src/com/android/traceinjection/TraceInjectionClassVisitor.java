/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.traceinjection;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * {@link ClassVisitor} that injects tracing code to methods annotated with the configured
 * annotation.
 */
public class TraceInjectionClassVisitor extends ClassVisitor {
    private final TraceInjectionConfiguration mParams;
    public TraceInjectionClassVisitor(ClassVisitor classVisitor,
            TraceInjectionConfiguration params) {
        super(Opcodes.ASM7, classVisitor);
        mParams = params;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature,
            String[] exceptions) {
        MethodVisitor chain = super.visitMethod(access, name, desc, signature, exceptions);
        return new TraceInjectionMethodAdapter(chain, access, name, desc, mParams);
    }
}
