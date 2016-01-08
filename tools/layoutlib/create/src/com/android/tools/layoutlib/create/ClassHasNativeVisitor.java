/*
 * Copyright (C) 2008 The Android Open Source Project
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

import com.android.tools.layoutlib.annotations.VisibleForTesting;
import com.android.tools.layoutlib.annotations.VisibleForTesting.Visibility;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Indicates if a class contains any native methods.
 */
public class ClassHasNativeVisitor extends ClassVisitor {
    public ClassHasNativeVisitor() {
        super(Main.ASM_VERSION);
    }

    private boolean mHasNativeMethods = false;

    public boolean hasNativeMethods() {
        return mHasNativeMethods;
    }

    @VisibleForTesting(visibility=Visibility.PRIVATE)
    protected void setHasNativeMethods(boolean hasNativeMethods, String methodName) {
        mHasNativeMethods = hasNativeMethods;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
            String superName, String[] interfaces) {
        // pass
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        // pass
        return null;
    }

    @Override
    public void visitAttribute(Attribute attr) {
        // pass
    }

    @Override
    public void visitEnd() {
        // pass
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc,
            String signature, Object value) {
        // pass
        return null;
    }

    @Override
    public void visitInnerClass(String name, String outerName,
            String innerName, int access) {
        // pass
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
            String signature, String[] exceptions) {
        if ((access & Opcodes.ACC_NATIVE) != 0) {
            setHasNativeMethods(true, name);
        }
        return null;
    }

    @Override
    public void visitOuterClass(String owner, String name, String desc) {
        // pass
    }

    @Override
    public void visitSource(String source, String debug) {
        // pass
    }

}
