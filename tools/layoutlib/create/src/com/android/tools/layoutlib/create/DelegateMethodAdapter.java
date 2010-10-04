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

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * This method adapter rewrites a method by discarding the original code and generating
 * a call to a delegate. Original annotations are passed along unchanged.
 * <p/>
 * Calls are delegated to a class named <code>&lt;className&gt;_Delegate</code> with
 * static methods matching the methods to be overridden here. The methods have the
 * same return type. The argument type list is the same except the "this" reference is
 * passed first for non-static methods.
 * <p/>
 * A new annotation is added.
 * <p/>
 * Note that native methods have, by definition, no code so there's nothing a visitor
 * can visit. That means the caller must call {@link #generateCode()} directly for
 * a native and use the visitor pattern for non-natives.
 * <p/>
 * Instances of this class are not re-usable. You need a new instance for each method.
 */
class DelegateMethodAdapter implements MethodVisitor {

    /**
     * Suffix added to delegate classes.
     */
    public static final String DELEGATE_SUFFIX = "_Delegate";

    private static String CONSTRUCTOR = "<init>";
    private static String CLASS_INIT = "<clinit>";

    /** The parent method writer */
    private MethodVisitor mParentVisitor;
    /** Flag to output the first line number. */
    private boolean mOutputFirstLineNumber = true;
    /** The original method descriptor (return type + argument types.) */
    private String mDesc;
    /** True if the original method is static. */
    private final boolean mIsStatic;
    /** The internal class name (e.g. <code>com/android/SomeClass$InnerClass</code>.) */
    private final String mClassName;
    /** The method name. */
    private final String mMethodName;
    /** Logger object. */
    private final Log mLog;
    /** True if {@link #visitCode()} has been invoked. */
    private boolean mVisitCodeCalled;

    /**
     * Creates a new {@link DelegateMethodAdapter} that will transform this method
     * into a delegate call.
     * <p/>
     * See {@link DelegateMethodAdapter} for more details.
     *
     * @param log The logger object. Must not be null.
     * @param mv the method visitor to which this adapter must delegate calls.
     * @param className The internal class name of the class to visit,
     *          e.g. <code>com/android/SomeClass$InnerClass</code>.
     * @param methodName The simple name of the method.
     * @param desc A method descriptor (c.f. {@link Type#getReturnType(String)} +
     *          {@link Type#getArgumentTypes(String)})
     * @param isStatic True if the method is declared static.
     */
    public DelegateMethodAdapter(Log log,
            MethodVisitor mv,
            String className,
            String methodName,
            String desc,
            boolean isStatic) {
        mLog = log;
        mParentVisitor = mv;
        mClassName = className;
        mMethodName = methodName;
        mDesc = desc;
        mIsStatic = isStatic;

        if (CONSTRUCTOR.equals(methodName) || CLASS_INIT.equals(methodName)) {
            // We're going to simplify by not supporting constructors.
            // The only trick with a constructor is to find the proper super constructor
            // and call it (and deciding if we should mirror the original method call to
            // a custom constructor or call a default one.)
            throw new UnsupportedOperationException(
                    String.format("Delegate doesn't support overriding constructor %1$s:%2$s(%3$s)",
                            className, methodName, desc));
        }
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
    public void generateCode() {
        /*
         * The goal is to generate a call to a static delegate method.
         * If this method is not-static, the first parameter will be this.
         * All the parameters must be passed and then the eventual return type returned.
         *
         * Example, let's say we have a method such as
         *   public void method_1(int a, Object b, ArrayList<String> c) { ... }
         *
         * We'll want to create a body that calls a delegate method like this:
         *   TheClass_Delegate.method_1(this, a, b, c);
         *
         * The generated class name is the current class name with "_Delegate" appended to it.
         * One thing to realize is that we don't care about generics -- since generic types
         * are erased at runtime, they have no influence on the method being called.
         */

        // Add our annotation
        AnnotationVisitor aw = mParentVisitor.visitAnnotation(
                Type.getObjectType(Type.getInternalName(LayoutlibDelegate.class)).toString(),
                true); // visible at runtime
        aw.visitEnd();

        if (!mVisitCodeCalled) {
            // If this is a direct call to generateCode() as done by DelegateClassAdapter
            // for natives, visitCode() hasn't been called yet.
            mParentVisitor.visitCode();
            mVisitCodeCalled = true;
        }

        int numVars = 0;

        // Push "this" for an instance method, which is always ALOAD 0
        if (!mIsStatic) {
            mParentVisitor.visitVarInsn(Opcodes.ALOAD, numVars++);
        }

        // Push all other arguments
        Type[] argTypes = Type.getArgumentTypes(mDesc);
        for (Type t : argTypes) {
            int size = t.getSize();
            mParentVisitor.visitVarInsn(t.getOpcode(Opcodes.ILOAD), numVars);
            numVars += size;
        }

        // Construct the descriptor of the delegate. For a static method, it's the same
        // however for an instance method we need to pass the 'this' reference first
        String desc = mDesc;
        if (!mIsStatic && argTypes.length > 0) {
            Type[] argTypes2 = new Type[argTypes.length + 1];

            argTypes2[0] = Type.getObjectType(mClassName);
            System.arraycopy(argTypes, 0, argTypes2, 1, argTypes.length);

            desc = Type.getMethodDescriptor(Type.getReturnType(mDesc), argTypes2);
        }

        String delegateClassName = mClassName + DELEGATE_SUFFIX;

        // Invoke the static delegate
        mParentVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                delegateClassName,
                mMethodName,
                desc);

        Type returnType = Type.getReturnType(mDesc);
        mParentVisitor.visitInsn(returnType.getOpcode(Opcodes.IRETURN));

        mParentVisitor.visitMaxs(numVars, numVars);
        mParentVisitor.visitEnd();

        // For debugging now. Maybe we should collect these and store them in
        // a text file for helping create the delegates. We could also compare
        // the text file to a golden and break the build on unsupported changes
        // or regressions. Even better we could fancy-print something that looks
        // like the expected Java method declaration.
        mLog.debug("Delegate: %1$s # %2$s %3$s", delegateClassName, mMethodName, desc);
    }

    /* Pass down to visitor writer. In this implementation, either do nothing. */
    public void visitCode() {
        mVisitCodeCalled = true;
        mParentVisitor.visitCode();
    }

    /*
     * visitMaxs is called just before visitEnd if there was any code to rewrite.
     * Skip the original.
     */
    public void visitMaxs(int maxStack, int maxLocals) {
    }

    /**
     * End of visiting. Generate the messaging code.
     */
    public void visitEnd() {
        generateCode();
    }

    /* Writes all annotation from the original method. */
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        return mParentVisitor.visitAnnotation(desc, visible);
    }

    /* Writes all annotation default values from the original method. */
    public AnnotationVisitor visitAnnotationDefault() {
        return mParentVisitor.visitAnnotationDefault();
    }

    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc,
            boolean visible) {
        return mParentVisitor.visitParameterAnnotation(parameter, desc, visible);
    }

    /* Writes all attributes from the original method. */
    public void visitAttribute(Attribute attr) {
        mParentVisitor.visitAttribute(attr);
    }

    /*
     * Only writes the first line number present in the original code so that source
     * viewers can direct to the correct method, even if the content doesn't match.
     */
    public void visitLineNumber(int line, Label start) {
        if (mOutputFirstLineNumber) {
            mParentVisitor.visitLineNumber(line, start);
            mOutputFirstLineNumber = false;
        }
    }

    public void visitInsn(int opcode) {
        // Skip original code.
    }

    public void visitLabel(Label label) {
        // Skip original code.
    }

    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        // Skip original code.
    }

    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
        // Skip original code.
    }

    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        // Skip original code.
    }

    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
        // Skip original code.
    }

    public void visitIincInsn(int var, int increment) {
        // Skip original code.
    }

    public void visitIntInsn(int opcode, int operand) {
        // Skip original code.
    }

    public void visitJumpInsn(int opcode, Label label) {
        // Skip original code.
    }

    public void visitLdcInsn(Object cst) {
        // Skip original code.
    }

    public void visitLocalVariable(String name, String desc, String signature,
            Label start, Label end, int index) {
        // Skip original code.
    }

    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        // Skip original code.
    }

    public void visitMultiANewArrayInsn(String desc, int dims) {
        // Skip original code.
    }

    public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
        // Skip original code.
    }

    public void visitTypeInsn(int opcode, String type) {
        // Skip original code.
    }

    public void visitVarInsn(int opcode, int var) {
        // Skip original code.
    }

}
