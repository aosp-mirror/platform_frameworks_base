/*
 * Copyright (C) 2013 The Android Open Source Project
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
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

/**
 * Provides the common code for RenameClassAdapter and RefactorClassAdapter. It
 * goes through the complete class and finds references to other classes. It
 * then calls {@link #renameInternalType(String)} to convert the className to
 * the new value, if need be.
 */
public abstract class AbstractClassAdapter extends ClassVisitor {

    /**
     * Returns the new FQCN for the class, if the reference to this class needs
     * to be updated. Else, it returns the same string.
     * @param name Old FQCN
     * @return New FQCN if it needs to be renamed, else the old FQCN
     */
    abstract String renameInternalType(String name);

    public AbstractClassAdapter(ClassVisitor cv) {
        super(Main.ASM_VERSION, cv);
    }

    /**
     * Renames a type descriptor, e.g. "Lcom.package.MyClass;"
     * If the type doesn't need to be renamed, returns the input string as-is.
     */
    String renameTypeDesc(String desc) {
        if (desc == null) {
            return null;
        }

        return renameType(Type.getType(desc));
    }

    /**
     * Renames an object type, e.g. "Lcom.package.MyClass;" or an array type that has an
     * object element, e.g. "[Lcom.package.MyClass;"
     * If the type doesn't need to be renamed, returns the internal name of the input type.
     */
    String renameType(Type type) {
        if (type == null) {
            return null;
        }

        if (type.getSort() == Type.OBJECT) {
            String in = type.getInternalName();
            return "L" + renameInternalType(in) + ";";
        } else if (type.getSort() == Type.ARRAY) {
            StringBuilder sb = new StringBuilder();
            for (int n = type.getDimensions(); n > 0; n--) {
                sb.append('[');
            }
            sb.append(renameType(type.getElementType()));
            return sb.toString();
        }
        return type.getDescriptor();
    }

    /**
     * Renames an object type, e.g. "Lcom.package.MyClass;" or an array type that has an
     * object element, e.g. "[Lcom.package.MyClass;".
     * This is like renameType() except that it returns a Type object.
     * If the type doesn't need to be renamed, returns the input type object.
     */
    Type renameTypeAsType(Type type) {
        if (type == null) {
            return null;
        }

        if (type.getSort() == Type.OBJECT) {
            String in = type.getInternalName();
            String newIn = renameInternalType(in);
            if (!newIn.equals(in)) {
                return Type.getType("L" + newIn + ";");
            }
        } else if (type.getSort() == Type.ARRAY) {
            StringBuilder sb = new StringBuilder();
            for (int n = type.getDimensions(); n > 0; n--) {
                sb.append('[');
            }
            sb.append(renameType(type.getElementType()));
            return Type.getType(sb.toString());
        }
        return type;
    }

    /**
     * Renames a method descriptor, i.e. applies renameType to all arguments and to the
     * return value.
     */
    String renameMethodDesc(String desc) {
        if (desc == null) {
            return null;
        }

        Type[] args = Type.getArgumentTypes(desc);

        StringBuilder sb = new StringBuilder("(");
        for (Type arg : args) {
            String name = renameType(arg);
            sb.append(name);
        }
        sb.append(')');

        Type ret = Type.getReturnType(desc);
        String name = renameType(ret);
        sb.append(name);

        return sb.toString();
    }


    /**
     * Renames the ClassSignature handled by ClassVisitor.visit
     * or the MethodTypeSignature handled by ClassVisitor.visitMethod.
     */
    String renameTypeSignature(String sig) {
        if (sig == null) {
            return null;
        }
        SignatureReader reader = new SignatureReader(sig);
        SignatureWriter writer = new SignatureWriter();
        reader.accept(new RenameSignatureAdapter(writer));
        sig = writer.toString();
        return sig;
    }


    /**
     * Renames the FieldTypeSignature handled by ClassVisitor.visitField
     * or MethodVisitor.visitLocalVariable.
     */
    String renameFieldSignature(String sig) {
        return renameTypeSignature(sig);
    }


    //----------------------------------
    // Methods from the ClassAdapter

    @Override
    public void visit(int version, int access, String name, String signature,
            String superName, String[] interfaces) {
        name = renameInternalType(name);
        superName = renameInternalType(superName);
        signature = renameTypeSignature(signature);
        if (interfaces != null) {
            for (int i = 0; i < interfaces.length; ++i) {
                interfaces[i] = renameInternalType(interfaces[i]);
            }
        }

        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        name = renameInternalType(name);
        outerName = renameInternalType(outerName);
        super.visitInnerClass(name, outerName, innerName, access);
    }

    @Override
    public void visitOuterClass(String owner, String name, String desc) {
        super.visitOuterClass(renameInternalType(owner), name, renameTypeDesc(desc));
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
            String signature, String[] exceptions) {
        desc = renameMethodDesc(desc);
        signature = renameTypeSignature(signature);
        MethodVisitor mw = super.visitMethod(access, name, desc, signature, exceptions);
        return new RenameMethodAdapter(mw);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        desc = renameTypeDesc(desc);
        return super.visitAnnotation(desc, visible);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc,
            String signature, Object value) {
        desc = renameTypeDesc(desc);
        return super.visitField(access, name, desc, signature, value);
    }


    //----------------------------------

    /**
     * A method visitor that renames all references from an old class name to a new class name.
     */
    public class RenameMethodAdapter extends MethodVisitor {

        /**
         * Creates a method visitor that renames all references from a given old name to a given new
         * name. The method visitor will also rename all inner classes.
         * The names must be full qualified internal ASM names (e.g. com/blah/MyClass$InnerClass).
         */
        public RenameMethodAdapter(MethodVisitor mv) {
            super(Main.ASM_VERSION, mv);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            desc = renameTypeDesc(desc);

            return super.visitAnnotation(desc, visible);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
            desc = renameTypeDesc(desc);

            return super.visitParameterAnnotation(parameter, desc, visible);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // The type sometimes turns out to be a type descriptor. We try to detect it and fix.
            if (type.indexOf(';') > 0) {
                type = renameTypeDesc(type);
            } else {
                type = renameInternalType(type);
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            owner = renameInternalType(owner);
            desc = renameTypeDesc(desc);

            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                boolean itf) {
            // The owner sometimes turns out to be a type descriptor. We try to detect it and fix.
            if (owner.indexOf(';') > 0) {
                owner = renameTypeDesc(owner);
            } else {
                owner = renameInternalType(owner);
            }
            desc = renameMethodDesc(desc);

            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        public void visitLdcInsn(Object cst) {
            // If cst is a Type, this means the code is trying to pull the .class constant
            // for this class, so it needs to be renamed too.
            if (cst instanceof Type) {
                cst = renameTypeAsType((Type) cst);
            }
            super.visitLdcInsn(cst);
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            desc = renameTypeDesc(desc);

            super.visitMultiANewArrayInsn(desc, dims);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            type = renameInternalType(type);

            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        public void visitLocalVariable(String name, String desc, String signature,
                Label start, Label end, int index) {
            desc = renameTypeDesc(desc);
            signature = renameFieldSignature(signature);

            super.visitLocalVariable(name, desc, signature, start, end, index);
        }

    }

    //----------------------------------

    public class RenameSignatureAdapter extends SignatureVisitor {

        private final SignatureVisitor mSv;

        public RenameSignatureAdapter(SignatureVisitor sv) {
            super(Main.ASM_VERSION);
            mSv = sv;
        }

        @Override
        public void visitClassType(String name) {
            name = renameInternalType(name);
            mSv.visitClassType(name);
        }

        @Override
        public void visitInnerClassType(String name) {
            name = renameInternalType(name);
            mSv.visitInnerClassType(name);
        }

        @Override
        public SignatureVisitor visitArrayType() {
            SignatureVisitor sv = mSv.visitArrayType();
            return new RenameSignatureAdapter(sv);
        }

        @Override
        public void visitBaseType(char descriptor) {
            mSv.visitBaseType(descriptor);
        }

        @Override
        public SignatureVisitor visitClassBound() {
            SignatureVisitor sv = mSv.visitClassBound();
            return new RenameSignatureAdapter(sv);
        }

        @Override
        public void visitEnd() {
            mSv.visitEnd();
        }

        @Override
        public SignatureVisitor visitExceptionType() {
            SignatureVisitor sv = mSv.visitExceptionType();
            return new RenameSignatureAdapter(sv);
        }

        @Override
        public void visitFormalTypeParameter(String name) {
            mSv.visitFormalTypeParameter(name);
        }

        @Override
        public SignatureVisitor visitInterface() {
            SignatureVisitor sv = mSv.visitInterface();
            return new RenameSignatureAdapter(sv);
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            SignatureVisitor sv = mSv.visitInterfaceBound();
            return new RenameSignatureAdapter(sv);
        }

        @Override
        public SignatureVisitor visitParameterType() {
            SignatureVisitor sv = mSv.visitParameterType();
            return new RenameSignatureAdapter(sv);
        }

        @Override
        public SignatureVisitor visitReturnType() {
            SignatureVisitor sv = mSv.visitReturnType();
            return new RenameSignatureAdapter(sv);
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            SignatureVisitor sv = mSv.visitSuperclass();
            return new RenameSignatureAdapter(sv);
        }

        @Override
        public void visitTypeArgument() {
            mSv.visitTypeArgument();
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            SignatureVisitor sv = mSv.visitTypeArgument(wildcard);
            return new RenameSignatureAdapter(sv);
        }

        @Override
        public void visitTypeVariable(String name) {
            mSv.visitTypeVariable(name);
        }

    }
}
