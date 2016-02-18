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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * This method adapter rewrites a method by discarding the original code and generating
 * a stub depending on the return type. Original annotations are passed along unchanged.
 */
class StubMethodAdapter extends MethodVisitor {

    private static final String CONSTRUCTOR = "<init>";
    private static final String CLASS_INIT = "<clinit>";

    /** The parent method writer */
    private MethodVisitor mParentVisitor;
    /** The method return type. Can be null. */
    private Type mReturnType;
    /** Message to be printed by stub methods. */
    private String mInvokeSignature;
    /** Flag to output the first line number. */
    private boolean mOutputFirstLineNumber = true;
    /** Flag that is true when implementing a constructor, to accept all original
     *  code calling the original super constructor. */
    private boolean mIsInitMethod = false;

    private boolean mMessageGenerated;
    private final boolean mIsStatic;
    private final boolean mIsNative;

    public StubMethodAdapter(MethodVisitor mv, String methodName, Type returnType,
            String invokeSignature, boolean isStatic, boolean isNative) {
        super(Main.ASM_VERSION);
        mParentVisitor = mv;
        mReturnType = returnType;
        mInvokeSignature = invokeSignature;
        mIsStatic = isStatic;
        mIsNative = isNative;

        if (CONSTRUCTOR.equals(methodName) || CLASS_INIT.equals(methodName)) {
            mIsInitMethod = true;
        }
    }

    private void generateInvoke() {
        /* Generates the code:
         *  OverrideMethod.invoke("signature", mIsNative ? true : false, null or this);
         */
        mParentVisitor.visitLdcInsn(mInvokeSignature);
        // push true or false
        mParentVisitor.visitInsn(mIsNative ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        // push null or this
        if (mIsStatic) {
            mParentVisitor.visitInsn(Opcodes.ACONST_NULL);
        } else {
            mParentVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        }

        int sort = mReturnType != null ? mReturnType.getSort() : Type.VOID;
        switch(sort) {
        case Type.VOID:
            mParentVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/android/tools/layoutlib/create/OverrideMethod",
                    "invokeV",
                    "(Ljava/lang/String;ZLjava/lang/Object;)V",
                    false);
            mParentVisitor.visitInsn(Opcodes.RETURN);
            break;
        case Type.BOOLEAN:
        case Type.CHAR:
        case Type.BYTE:
        case Type.SHORT:
        case Type.INT:
            mParentVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/android/tools/layoutlib/create/OverrideMethod",
                    "invokeI",
                    "(Ljava/lang/String;ZLjava/lang/Object;)I",
                    false);
            switch(sort) {
            case Type.BOOLEAN:
                Label l1 = new Label();
                mParentVisitor.visitJumpInsn(Opcodes.IFEQ, l1);
                mParentVisitor.visitInsn(Opcodes.ICONST_1);
                mParentVisitor.visitInsn(Opcodes.IRETURN);
                mParentVisitor.visitLabel(l1);
                mParentVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                mParentVisitor.visitInsn(Opcodes.ICONST_0);
                break;
            case Type.CHAR:
                mParentVisitor.visitInsn(Opcodes.I2C);
                break;
            case Type.BYTE:
                mParentVisitor.visitInsn(Opcodes.I2B);
                break;
            case Type.SHORT:
                mParentVisitor.visitInsn(Opcodes.I2S);
                break;
            }
            mParentVisitor.visitInsn(Opcodes.IRETURN);
            break;
        case Type.LONG:
            mParentVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/android/tools/layoutlib/create/OverrideMethod",
                    "invokeL",
                    "(Ljava/lang/String;ZLjava/lang/Object;)J",
                    false);
            mParentVisitor.visitInsn(Opcodes.LRETURN);
            break;
        case Type.FLOAT:
            mParentVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/android/tools/layoutlib/create/OverrideMethod",
                    "invokeF",
                    "(Ljava/lang/String;ZLjava/lang/Object;)F",
                    false);
            mParentVisitor.visitInsn(Opcodes.FRETURN);
            break;
        case Type.DOUBLE:
            mParentVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/android/tools/layoutlib/create/OverrideMethod",
                    "invokeD",
                    "(Ljava/lang/String;ZLjava/lang/Object;)D",
                    false);
            mParentVisitor.visitInsn(Opcodes.DRETURN);
            break;
        case Type.ARRAY:
        case Type.OBJECT:
            mParentVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/android/tools/layoutlib/create/OverrideMethod",
                    "invokeA",
                    "(Ljava/lang/String;ZLjava/lang/Object;)Ljava/lang/Object;",
                    false);
            mParentVisitor.visitTypeInsn(Opcodes.CHECKCAST, mReturnType.getInternalName());
            mParentVisitor.visitInsn(Opcodes.ARETURN);
            break;
        }

    }

    private void generatePop() {
        /* Pops the stack, depending on the return type.
         */
        switch(mReturnType != null ? mReturnType.getSort() : Type.VOID) {
        case Type.VOID:
            break;
        case Type.BOOLEAN:
        case Type.CHAR:
        case Type.BYTE:
        case Type.SHORT:
        case Type.INT:
        case Type.FLOAT:
        case Type.ARRAY:
        case Type.OBJECT:
            mParentVisitor.visitInsn(Opcodes.POP);
            break;
        case Type.LONG:
        case Type.DOUBLE:
            mParentVisitor.visitInsn(Opcodes.POP2);
            break;
        }
    }

    /* Pass down to visitor writer. In this implementation, either do nothing. */
    @Override
    public void visitCode() {
        mParentVisitor.visitCode();
    }

    /*
     * visitMaxs is called just before visitEnd if there was any code to rewrite.
     * For non-constructor, generate the messaging code and the return statement
     * if it hasn't been done before.
     */
    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        if (!mIsInitMethod && !mMessageGenerated) {
            generateInvoke();
            mMessageGenerated = true;
        }
        mParentVisitor.visitMaxs(maxStack, maxLocals);
    }

    /**
     * End of visiting.
     * For non-constructor, generate the messaging code and the return statement
     * if it hasn't been done before.
     */
    @Override
    public void visitEnd() {
        if (!mIsInitMethod && !mMessageGenerated) {
            generateInvoke();
            mMessageGenerated = true;
            mParentVisitor.visitMaxs(1, 1);
        }
        mParentVisitor.visitEnd();
    }

    /* Writes all annotation from the original method. */
    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        return mParentVisitor.visitAnnotation(desc, visible);
    }

    /* Writes all annotation default values from the original method. */
    @Override
    public AnnotationVisitor visitAnnotationDefault() {
        return mParentVisitor.visitAnnotationDefault();
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc,
            boolean visible) {
        return mParentVisitor.visitParameterAnnotation(parameter, desc, visible);
    }

    /* Writes all attributes from the original method. */
    @Override
    public void visitAttribute(Attribute attr) {
        mParentVisitor.visitAttribute(attr);
    }

    /*
     * Only writes the first line number present in the original code so that source
     * viewers can direct to the correct method, even if the content doesn't match.
     */
    @Override
    public void visitLineNumber(int line, Label start) {
        if (mIsInitMethod || mOutputFirstLineNumber) {
            mParentVisitor.visitLineNumber(line, start);
            mOutputFirstLineNumber = false;
        }
    }

    /**
     * For non-constructor, rewrite existing "return" instructions to write the message.
     */
    @Override
    public void visitInsn(int opcode) {
        if (mIsInitMethod) {
            switch (opcode) {
            case Opcodes.RETURN:
            case Opcodes.ARETURN:
            case Opcodes.DRETURN:
            case Opcodes.FRETURN:
            case Opcodes.IRETURN:
            case Opcodes.LRETURN:
                // Pop the last word from the stack since invoke will generate its own return.
                generatePop();
                generateInvoke();
                mMessageGenerated = true;
                //$FALL-THROUGH$
            default:
                mParentVisitor.visitInsn(opcode);
            }
        }
    }

    @Override
    public void visitLabel(Label label) {
        if (mIsInitMethod) {
            mParentVisitor.visitLabel(label);
        }
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        if (mIsInitMethod) {
            mParentVisitor.visitTryCatchBlock(start, end, handler, type);
        }
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        if (mIsInitMethod) {
            mParentVisitor.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        if (mIsInitMethod) {
            mParentVisitor.visitFieldInsn(opcode, owner, name, desc);
        }
    }

    @Override
    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
        if (mIsInitMethod) {
            mParentVisitor.visitFrame(type, nLocal, local, nStack, stack);
        }
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        if (mIsInitMethod) {
            mParentVisitor.visitIincInsn(var, increment);
        }
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (mIsInitMethod) {
            mParentVisitor.visitIntInsn(opcode, operand);
        }
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        if (mIsInitMethod) {
            mParentVisitor.visitJumpInsn(opcode, label);
        }
    }

    @Override
    public void visitLdcInsn(Object cst) {
        if (mIsInitMethod) {
            mParentVisitor.visitLdcInsn(cst);
        }
    }

    @Override
    public void visitLocalVariable(String name, String desc, String signature,
            Label start, Label end, int index) {
        if (mIsInitMethod) {
            mParentVisitor.visitLocalVariable(name, desc, signature, start, end, index);
        }
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        if (mIsInitMethod) {
            mParentVisitor.visitLookupSwitchInsn(dflt, keys, labels);
        }
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        if (mIsInitMethod) {
            mParentVisitor.visitMultiANewArrayInsn(desc, dims);
        }
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
        if (mIsInitMethod) {
            mParentVisitor.visitTableSwitchInsn(min, max, dflt, labels);
        }
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (mIsInitMethod) {
            mParentVisitor.visitTypeInsn(opcode, type);
        }
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        if (mIsInitMethod) {
            mParentVisitor.visitVarInsn(opcode, var);
        }
    }

}
