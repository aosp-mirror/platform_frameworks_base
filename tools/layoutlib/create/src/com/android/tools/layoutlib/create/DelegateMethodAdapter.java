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

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;

/**
 * This method adapter generates delegate methods.
 * <p/>
 * Given a method {@code SomeClass.MethodName()}, this generates 1 or 2 methods:
 * <ul>
 * <li> A copy of the original method named {@code SomeClass.MethodName_Original()}.
 *   The content is the original method as-is from the reader.
 *   This step is omitted if the method is native, since it has no Java implementation.
 * <li> A brand new implementation of {@code SomeClass.MethodName()} which calls to a
 *   non-existing method named {@code SomeClass_Delegate.MethodName()}.
 *   The implementation of this 'delegate' method is done in layoutlib_brigde.
 * </ul>
 * A method visitor is generally constructed to generate a single method; however
 * here we might want to generate one or two depending on the context. To achieve
 * that, the visitor here generates the 'original' method and acts as a no-op if
 * no such method exists (e.g. when the original is a native method).
 * The delegate method is generated after the {@code visitEnd} of the original method
 * or by having the class adapter <em>directly</em> call {@link #generateDelegateCode()}
 * for native methods.
 * <p/>
 * When generating the 'delegate', the implementation generates a call to a class
 * class named <code>&lt;className&gt;_Delegate</code> with static methods matching
 * the methods to be overridden here. The methods have the same return type.
 * The argument type list is the same except the "this" reference is passed first
 * for non-static methods.
 * <p/>
 * A new annotation is added to these 'delegate' methods so that we can easily find them
 * for automated testing.
 * <p/>
 * This class isn't intended to be generic or reusable.
 * It is called by {@link DelegateClassAdapter}, which takes care of properly initializing
 * the two method writers for the original and the delegate class, as needed, with their
 * expected names.
 * <p/>
 * The class adapter also takes care of calling {@link #generateDelegateCode()} directly for
 * a native and use the visitor pattern for non-natives.
 * Note that native methods have, by definition, no code so there's nothing a visitor
 * can visit.
 * <p/>
 * Instances of this class are not re-usable.
 * The class adapter creates a new instance for each method.
 */
class DelegateMethodAdapter extends MethodVisitor {

    /** Suffix added to delegate classes. */
    public static final String DELEGATE_SUFFIX = "_Delegate";

    /** The parent method writer to copy of the original method.
     *  Null when dealing with a native original method. */
    private MethodVisitor mOrgWriter;
    /** The parent method writer to generate the delegating method. Never null. */
    private MethodVisitor mDelWriter;
    /** The original method descriptor (return type + argument types.) */
    private String mDesc;
    /** True if the original method is static. */
    private final boolean mIsStatic;
    /** True if the method is contained in a static inner class */
    private final boolean mIsStaticInnerClass;
    /** The internal class name (e.g. <code>com/android/SomeClass$InnerClass</code>.) */
    private final String mClassName;
    /** The method name. */
    private final String mMethodName;
    /** Logger object. */
    private final Log mLog;

    /** Array used to capture the first line number information from the original method
     *  and duplicate it in the delegate. */
    private Object[] mDelegateLineNumber;

    /**
     * Creates a new {@link DelegateMethodAdapter} that will transform this method
     * into a delegate call.
     * <p/>
     * See {@link DelegateMethodAdapter} for more details.
     *
     * @param log The logger object. Must not be null.
     * @param mvOriginal The parent method writer to copy of the original method.
     *          Must be {@code null} when dealing with a native original method.
     * @param mvDelegate The parent method writer to generate the delegating method.
     *          Must never be null.
     * @param className The internal class name of the class to visit,
     *          e.g. <code>com/android/SomeClass$InnerClass</code>.
     * @param methodName The simple name of the method.
     * @param desc A method descriptor (c.f. {@link Type#getReturnType(String)} +
     *          {@link Type#getArgumentTypes(String)})
     * @param isStatic True if the method is declared static.
     */
    public DelegateMethodAdapter(Log log,
            MethodVisitor mvOriginal,
            MethodVisitor mvDelegate,
            String className,
            String methodName,
            String desc,
            boolean isStatic,
            boolean isStaticClass) {
        super(Opcodes.ASM4);
        mLog = log;
        mOrgWriter = mvOriginal;
        mDelWriter = mvDelegate;
        mClassName = className;
        mMethodName = methodName;
        mDesc = desc;
        mIsStatic = isStatic;
        mIsStaticInnerClass = isStaticClass;
    }

    /**
     * Generates the new code for the method.
     * <p/>
     * For native methods, this must be invoked directly by {@link DelegateClassAdapter}
     * (since they have no code to visit).
     * <p/>
     * Otherwise for non-native methods the {@link DelegateClassAdapter} simply needs to
     * return this instance of {@link DelegateMethodAdapter} and let the normal visitor pattern
     * invoke it as part of the {@link ClassReader#accept(ClassVisitor, int)} workflow and then
     * this method will be invoked from {@link MethodVisitor#visitEnd()}.
     */
    public void generateDelegateCode() {
        /*
         * The goal is to generate a call to a static delegate method.
         * If this method is non-static, the first parameter will be 'this'.
         * All the parameters must be passed and then the eventual return type returned.
         *
         * Example, let's say we have a method such as
         *   public void myMethod(int a, Object b, ArrayList<String> c) { ... }
         *
         * We'll want to create a body that calls a delegate method like this:
         *   TheClass_Delegate.myMethod(this, a, b, c);
         *
         * If the method is non-static and the class name is an inner class (e.g. has $ in its
         * last segment), we want to push the 'this' of the outer class first:
         *   OuterClass_InnerClass_Delegate.myMethod(
         *     OuterClass.this,
         *     OuterClass$InnerClass.this,
         *     a, b, c);
         *
         * Only one level of inner class is supported right now, for simplicity and because
         * we don't need more.
         *
         * The generated class name is the current class name with "_Delegate" appended to it.
         * One thing to realize is that we don't care about generics -- since generic types
         * are erased at build time, they have no influence on the method name being called.
         */

        // Add our annotation
        AnnotationVisitor aw = mDelWriter.visitAnnotation(
                Type.getObjectType(Type.getInternalName(LayoutlibDelegate.class)).toString(),
                true); // visible at runtime
        if (aw != null) {
            aw.visitEnd();
        }

        mDelWriter.visitCode();

        if (mDelegateLineNumber != null) {
            Object[] p = mDelegateLineNumber;
            mDelWriter.visitLineNumber((Integer) p[0], (Label) p[1]);
        }

        ArrayList<Type> paramTypes = new ArrayList<Type>();
        String delegateClassName = mClassName + DELEGATE_SUFFIX;
        boolean pushedArg0 = false;
        int maxStack = 0;

        // Check if the last segment of the class name has inner an class.
        // Right now we only support one level of inner classes.
        Type outerType = null;
        int slash = mClassName.lastIndexOf('/');
        int dol = mClassName.lastIndexOf('$');
        if (dol != -1 && dol > slash && dol == mClassName.indexOf('$')) {
            String outerClass = mClassName.substring(0, dol);
            outerType = Type.getObjectType(outerClass);

            // Change a delegate class name to "com/foo/Outer_Inner_Delegate"
            delegateClassName = delegateClassName.replace('$', '_');
        }

        // For an instance method (e.g. non-static), push the 'this' preceded
        // by the 'this' of any outer class, if any.
        if (!mIsStatic) {

            if (outerType != null && !mIsStaticInnerClass) {
                // The first-level inner class has a package-protected member called 'this$0'
                // that points to the outer class.

                // Push this.getField("this$0") on the call stack.
                mDelWriter.visitVarInsn(Opcodes.ALOAD, 0); // var 0 = this
                mDelWriter.visitFieldInsn(Opcodes.GETFIELD,
                        mClassName,                 // class where the field is defined
                        "this$0",                   // field name
                        outerType.getDescriptor()); // type of the field
                maxStack++;
                paramTypes.add(outerType);

            }

            // Push "this" for the instance method, which is always ALOAD 0
            mDelWriter.visitVarInsn(Opcodes.ALOAD, 0);
            maxStack++;
            pushedArg0 = true;
            paramTypes.add(Type.getObjectType(mClassName));
        }

        // Push all other arguments. Start at arg 1 if we already pushed 'this' above.
        Type[] argTypes = Type.getArgumentTypes(mDesc);
        int maxLocals = pushedArg0 ? 1 : 0;
        for (Type t : argTypes) {
            int size = t.getSize();
            mDelWriter.visitVarInsn(t.getOpcode(Opcodes.ILOAD), maxLocals);
            maxLocals += size;
            maxStack += size;
            paramTypes.add(t);
        }

        // Construct the descriptor of the delegate based on the parameters
        // we pushed on the call stack. The return type remains unchanged.
        String desc = Type.getMethodDescriptor(
                Type.getReturnType(mDesc),
                paramTypes.toArray(new Type[paramTypes.size()]));

        // Invoke the static delegate
        mDelWriter.visitMethodInsn(Opcodes.INVOKESTATIC,
                delegateClassName,
                mMethodName,
                desc);

        Type returnType = Type.getReturnType(mDesc);
        mDelWriter.visitInsn(returnType.getOpcode(Opcodes.IRETURN));

        mDelWriter.visitMaxs(maxStack, maxLocals);
        mDelWriter.visitEnd();

        // For debugging now. Maybe we should collect these and store them in
        // a text file for helping create the delegates. We could also compare
        // the text file to a golden and break the build on unsupported changes
        // or regressions. Even better we could fancy-print something that looks
        // like the expected Java method declaration.
        mLog.debug("Delegate: %1$s # %2$s %3$s", delegateClassName, mMethodName, desc);
    }

    /* Pass down to visitor writer. In this implementation, either do nothing. */
    @Override
    public void visitCode() {
        if (mOrgWriter != null) {
            mOrgWriter.visitCode();
        }
    }

    /*
     * visitMaxs is called just before visitEnd if there was any code to rewrite.
     */
    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        if (mOrgWriter != null) {
            mOrgWriter.visitMaxs(maxStack, maxLocals);
        }
    }

    /** End of visiting. Generate the delegating code. */
    @Override
    public void visitEnd() {
        if (mOrgWriter != null) {
            mOrgWriter.visitEnd();
        }
        generateDelegateCode();
    }

    /* Writes all annotation from the original method. */
    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (mOrgWriter != null) {
            return mOrgWriter.visitAnnotation(desc, visible);
        } else {
            return null;
        }
    }

    /* Writes all annotation default values from the original method. */
    @Override
    public AnnotationVisitor visitAnnotationDefault() {
        if (mOrgWriter != null) {
            return mOrgWriter.visitAnnotationDefault();
        } else {
            return null;
        }
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc,
            boolean visible) {
        if (mOrgWriter != null) {
            return mOrgWriter.visitParameterAnnotation(parameter, desc, visible);
        } else {
            return null;
        }
    }

    /* Writes all attributes from the original method. */
    @Override
    public void visitAttribute(Attribute attr) {
        if (mOrgWriter != null) {
            mOrgWriter.visitAttribute(attr);
        }
    }

    /*
     * Only writes the first line number present in the original code so that source
     * viewers can direct to the correct method, even if the content doesn't match.
     */
    @Override
    public void visitLineNumber(int line, Label start) {
        // Capture the first line values for the new delegate method
        if (mDelegateLineNumber == null) {
            mDelegateLineNumber = new Object[] { line, start };
        }
        if (mOrgWriter != null) {
            mOrgWriter.visitLineNumber(line, start);
        }
    }

    @Override
    public void visitInsn(int opcode) {
        if (mOrgWriter != null) {
            mOrgWriter.visitInsn(opcode);
        }
    }

    @Override
    public void visitLabel(Label label) {
        if (mOrgWriter != null) {
            mOrgWriter.visitLabel(label);
        }
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        if (mOrgWriter != null) {
            mOrgWriter.visitTryCatchBlock(start, end, handler, type);
        }
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
        if (mOrgWriter != null) {
            mOrgWriter.visitMethodInsn(opcode, owner, name, desc);
        }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        if (mOrgWriter != null) {
            mOrgWriter.visitFieldInsn(opcode, owner, name, desc);
        }
    }

    @Override
    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
        if (mOrgWriter != null) {
            mOrgWriter.visitFrame(type, nLocal, local, nStack, stack);
        }
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        if (mOrgWriter != null) {
            mOrgWriter.visitIincInsn(var, increment);
        }
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (mOrgWriter != null) {
            mOrgWriter.visitIntInsn(opcode, operand);
        }
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        if (mOrgWriter != null) {
            mOrgWriter.visitJumpInsn(opcode, label);
        }
    }

    @Override
    public void visitLdcInsn(Object cst) {
        if (mOrgWriter != null) {
            mOrgWriter.visitLdcInsn(cst);
        }
    }

    @Override
    public void visitLocalVariable(String name, String desc, String signature,
            Label start, Label end, int index) {
        if (mOrgWriter != null) {
            mOrgWriter.visitLocalVariable(name, desc, signature, start, end, index);
        }
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        if (mOrgWriter != null) {
            mOrgWriter.visitLookupSwitchInsn(dflt, keys, labels);
        }
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        if (mOrgWriter != null) {
            mOrgWriter.visitMultiANewArrayInsn(desc, dims);
        }
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
        if (mOrgWriter != null) {
            mOrgWriter.visitTableSwitchInsn(min, max, dflt, labels);
        }
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (mOrgWriter != null) {
            mOrgWriter.visitTypeInsn(opcode, type);
        }
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        if (mOrgWriter != null) {
            mOrgWriter.visitVarInsn(opcode, var);
        }
    }

}
