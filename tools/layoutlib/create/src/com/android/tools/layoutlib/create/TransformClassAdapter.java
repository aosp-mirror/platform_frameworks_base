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

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Set;

/**
 * Class adapter that can stub some or all of the methods of the class.
 */
class TransformClassAdapter extends ClassVisitor {

    /** True if all methods should be stubbed, false if only native ones must be stubbed. */
    private final boolean mStubAll;
    /** True if the class is an interface. */
    private boolean mIsInterface;
    private final String mClassName;
    private final Log mLog;
    private final Set<String> mStubMethods;
    private Set<String> mDeleteReturns;

    /**
     * Creates a new class adapter that will stub some or all methods.
     * @param stubMethods  list of method signatures to always stub out
     * @param deleteReturns list of types that trigger the deletion of methods returning them.
     * @param className The name of the class being modified
     * @param cv The parent class writer visitor
     * @param stubNativesOnly True if only native methods should be stubbed. False if all
     *                        methods should be stubbed.
     */
    public TransformClassAdapter(Log logger, Set<String> stubMethods,
            Set<String> deleteReturns, String className, ClassVisitor cv,
            boolean stubNativesOnly) {
        super(Opcodes.ASM4, cv);
        mLog = logger;
        mStubMethods = stubMethods;
        mClassName = className;
        mStubAll = !stubNativesOnly;
        mIsInterface = false;
        mDeleteReturns = deleteReturns;
    }

    /* Visits the class header. */
    @Override
    public void visit(int version, int access, String name,
            String signature, String superName, String[] interfaces) {

        // This class might be being renamed.
        name = mClassName;

        // remove final
        access = access & ~Opcodes.ACC_FINAL;
        // note: leave abstract classes as such
        // don't try to implement stub for interfaces

        mIsInterface = ((access & Opcodes.ACC_INTERFACE) != 0);
        super.visit(version, access, name, signature, superName, interfaces);
    }

    /* Visits the header of an inner class. */
    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        // remove final
        access = access & ~Opcodes.ACC_FINAL;
        // note: leave abstract classes as such
        // don't try to implement stub for interfaces

        super.visitInnerClass(name, outerName, innerName, access);
    }

    /* Visits a method. */
    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
            String signature, String[] exceptions) {

        if (mDeleteReturns != null) {
            Type t = Type.getReturnType(desc);
            if (t.getSort() == Type.OBJECT) {
                String returnType = t.getInternalName();
                if (returnType != null) {
                    if (mDeleteReturns.contains(returnType)) {
                        return null;
                    }
                }
            }
        }

        String methodSignature = mClassName.replace('/', '.') + "#" + name;

        // remove final
        access = access & ~Opcodes.ACC_FINAL;

        // stub this method if they are all to be stubbed or if it is a native method
        // and don't try to stub interfaces nor abstract non-native methods.
        if (!mIsInterface &&
            ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != Opcodes.ACC_ABSTRACT) &&
            (mStubAll ||
             (access & Opcodes.ACC_NATIVE) != 0) ||
             mStubMethods.contains(methodSignature)) {

            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            boolean isNative = (access & Opcodes.ACC_NATIVE) != 0;

            // remove abstract, final and native
            access = access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_FINAL | Opcodes.ACC_NATIVE);

            String invokeSignature = methodSignature + desc;
            mLog.debug("  Stub: %s (%s)", invokeSignature, isNative ? "native" : "");

            MethodVisitor mw = super.visitMethod(access, name, desc, signature, exceptions);
            return new StubMethodAdapter(mw, name, returnType(desc), invokeSignature,
                    isStatic, isNative);

        } else {
            mLog.debug("  Keep: %s %s", name, desc);
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
    }

    /**
     * Extracts the return {@link Type} of this descriptor.
     */
    Type returnType(String desc) {
        if (desc != null) {
            try {
                return Type.getReturnType(desc);
            } catch (ArrayIndexOutOfBoundsException e) {
                // ignore, not a valid type.
            }
        }
        return null;
    }
}
