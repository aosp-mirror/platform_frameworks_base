/*
 * Copyright (C) 2010 The Android Open Source Project
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

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

/**
 * A {@link DelegateClassAdapter} can transform some methods from a class into
 * delegates that defer the call to an associated delegate class.
 * <p/>
 * This is used to override specific methods and or all native methods in classes.
 */
public class DelegateClassAdapter extends ClassAdapter {

    /** Suffix added to original methods. */
    private static final String ORIGINAL_SUFFIX = "_Original";
    private static String CONSTRUCTOR = "<init>";
    private static String CLASS_INIT = "<clinit>";

    public final static String ALL_NATIVES = "<<all_natives>>";

    private final String mClassName;
    private final Set<String> mDelegateMethods;
    private final Log mLog;

    /**
     * Creates a new {@link DelegateClassAdapter} that can transform some methods
     * from a class into delegates that defer the call to an associated delegate class.
     * <p/>
     * This is used to override specific methods and or all native methods in classes.
     *
     * @param log The logger object. Must not be null.
     * @param cv the class visitor to which this adapter must delegate calls.
     * @param className The internal class name of the class to visit,
     *          e.g. <code>com/android/SomeClass$InnerClass</code>.
     * @param delegateMethods The set of method names to modify and/or the
     *          special constant {@link #ALL_NATIVES} to convert all native methods.
     */
    public DelegateClassAdapter(Log log,
            ClassVisitor cv,
            String className,
            Set<String> delegateMethods) {
        super(cv);
        mLog = log;
        mClassName = className;
        mDelegateMethods = delegateMethods;
    }

    //----------------------------------
    // Methods from the ClassAdapter

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
            String signature, String[] exceptions) {

        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        boolean isNative = (access & Opcodes.ACC_NATIVE) != 0;

        boolean useDelegate = (isNative && mDelegateMethods.contains(ALL_NATIVES)) ||
                              mDelegateMethods.contains(name);

        if (!useDelegate) {
            // Not creating a delegate for this method, pass it as-is from the reader
            // to the writer.
            return super.visitMethod(access, name, desc, signature, exceptions);
        }

        if (useDelegate) {
            if (CONSTRUCTOR.equals(name) || CLASS_INIT.equals(name)) {
                // We don't currently support generating delegates for constructors.
                throw new UnsupportedOperationException(
                    String.format(
                        "Delegate doesn't support overriding constructor %1$s:%2$s(%3$s)",  //$NON-NLS-1$
                        mClassName, name, desc));
            }
        }

        if (isNative) {
            // Remove native flag
            access = access & ~Opcodes.ACC_NATIVE;
            MethodVisitor mwDelegate = super.visitMethod(access, name, desc, signature, exceptions);

            DelegateMethodAdapter2 a = new DelegateMethodAdapter2(
                    mLog, null /*mwOriginal*/, mwDelegate, mClassName, name, desc, isStatic);

            // A native has no code to visit, so we need to generate it directly.
            a.generateDelegateCode();

            return mwDelegate;
        }

        // Given a non-native SomeClass.MethodName(), we want to generate 2 methods:
        // - A copy of the original method named SomeClass.MethodName_Original().
        //   The content is the original method as-is from the reader.
        // - A brand new implementation of SomeClass.MethodName() which calls to a
        //   non-existing method named SomeClass_Delegate.MethodName().
        //   The implementation of this 'delegate' method is done in layoutlib_brigde.

        int accessDelegate = access;
        // change access to public for the original one
        if (Main.sOptions.generatePublicAccess) {
            access &= ~(Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
            access |= Opcodes.ACC_PUBLIC;
        }

        MethodVisitor mwOriginal = super.visitMethod(access, name + ORIGINAL_SUFFIX,
                                                     desc, signature, exceptions);
        MethodVisitor mwDelegate = super.visitMethod(accessDelegate, name,
                                                     desc, signature, exceptions);

        DelegateMethodAdapter2 a = new DelegateMethodAdapter2(
                mLog, mwOriginal, mwDelegate, mClassName, name, desc, isStatic);
        return a;
    }
}
