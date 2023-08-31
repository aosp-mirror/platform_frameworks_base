/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.visitors

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypePath

/**
 * A method visitor that removes everything from method body.
 *
 * To inject a method body, override [visitCode] and create the opcodes there.
 */
abstract class BodyReplacingMethodVisitor(
    access: Int,
    name: String,
    descriptor: String,
    signature: String?,
    exceptions: Array<String>?,
    next: MethodVisitor?,
) : MethodVisitor(OPCODE_VERSION, next) {
    val isVoid: Boolean
    val isStatic: Boolean

    init {
        isVoid = descriptor.endsWith(")V")
        isStatic = access and Opcodes.ACC_STATIC != 0
    }

    // Following methods are for things that we need to keep.
    // Since they're all calling the super method, we can just remove them, but we keep them
    // just to clarify what we're keeping.

    final override fun visitParameter(
            name: String?,
            access: Int
    ) {
        super.visitParameter(name, access)
    }

    final override fun visitAnnotationDefault(): AnnotationVisitor? {
        return super.visitAnnotationDefault()
    }

    final override fun visitAnnotation(
            descriptor: String?,
            visible: Boolean
    ): AnnotationVisitor? {
        return super.visitAnnotation(descriptor, visible)
    }

    final override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String?,
        visible: Boolean
    ): AnnotationVisitor? {
        return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible)
    }

    final override fun visitAnnotableParameterCount(
            parameterCount: Int,
            visible: Boolean
    ) {
        super.visitAnnotableParameterCount(parameterCount, visible)
    }

    final override fun visitParameterAnnotation(
            parameter: Int,
            descriptor: String?,
            visible: Boolean
    ): AnnotationVisitor? {
        return super.visitParameterAnnotation(parameter, descriptor, visible)
    }

    final override fun visitAttribute(attribute: Attribute?) {
        super.visitAttribute(attribute)
    }

    override fun visitEnd() {
        super.visitEnd()
    }

    /**
     * Control when to emit the code. We use this to ignore all visitXxx method calls caused by
     * the original method, so we'll remove all the original code.
     *
     * Only when visitXxx methods are called from [emitNewCode], we pass-through to the base class,
     * so the body will be generated.
     *
     * (See also https://asm.ow2.io/asm4-guide.pdf section 3.2.1 about the MethovVisitor
     * call order.)
     */
    var emitCode = false

    final override fun visitCode() {
        super.visitCode()

        try {
            emitCode = true

            emitNewCode()
        } finally {
            emitCode = false
        }
    }

    /**
     * Subclass must implement it and emit code, and call [visitMaxs] at the end.
     */
    abstract fun emitNewCode()

    final override fun visitMaxs(
            maxStack: Int,
            maxLocals: Int
    ) {
        if (emitCode) {
            super.visitMaxs(maxStack, maxLocals)
        }
    }

    // Following methods are called inside a method body, and we don't want to
    // emit any of them, so they are all no-op.

    final override fun visitFrame(
            type: Int,
            numLocal: Int,
            local: Array<out Any>?,
            numStack: Int,
            stack: Array<out Any>?
    ) {
        if (emitCode) {
            super.visitFrame(type, numLocal, local, numStack, stack)
        }
    }

    final override fun visitInsn(opcode: Int) {
        if (emitCode) {
            super.visitInsn(opcode)
        }
    }

    final override fun visitIntInsn(
            opcode: Int,
            operand: Int
    ) {
        if (emitCode) {
            super.visitIntInsn(opcode, operand)
        }
    }

    final override fun visitVarInsn(
            opcode: Int,
            varIndex: Int
    ) {
        if (emitCode) {
            super.visitVarInsn(opcode, varIndex)
        }
    }

    final override fun visitTypeInsn(
            opcode: Int,
            type: String?
    ) {
        if (emitCode) {
            super.visitTypeInsn(opcode, type)
        }
    }

    final override fun visitFieldInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?
    ) {
        if (emitCode) {
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }
    }

    final override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean
    ) {
        if (emitCode) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }
    }

    final override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?
    ) {
        if (emitCode) {
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle,
                    *bootstrapMethodArguments)
        }
    }

    final override fun visitJumpInsn(
            opcode: Int,
            label: Label?
    ) {
        if (emitCode) {
            super.visitJumpInsn(opcode, label)
        }
    }

    final override fun visitLabel(label: Label?) {
        if (emitCode) {
            super.visitLabel(label)
        }
    }

    final override fun visitLdcInsn(value: Any?) {
        if (emitCode) {
            super.visitLdcInsn(value)
        }
    }

    final override fun visitIincInsn(
            varIndex: Int,
            increment: Int
    ) {
        if (emitCode) {
            super.visitIincInsn(varIndex, increment)
        }
    }

    final override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label?,
            vararg labels: Label?
    ) {
        if (emitCode) {
            super.visitTableSwitchInsn(min, max, dflt, *labels)
        }
    }

    final override fun visitLookupSwitchInsn(
            dflt: Label?,
            keys: IntArray?,
            labels: Array<out Label>?
    ) {
        if (emitCode) {
            super.visitLookupSwitchInsn(dflt, keys, labels)
        }
    }

    final override fun visitMultiANewArrayInsn(
            descriptor: String?,
            numDimensions: Int
    ) {
        if (emitCode) {
            super.visitMultiANewArrayInsn(descriptor, numDimensions)
        }
    }

    final override fun visitInsnAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean
    ): AnnotationVisitor? {
        if (emitCode) {
            return super.visitInsnAnnotation(typeRef, typePath, descriptor, visible)
        }
        return null
    }

    final override fun visitTryCatchBlock(
            start: Label?,
            end: Label?,
            handler: Label?,
            type: String?
    ) {
        if (emitCode) {
            super.visitTryCatchBlock(start, end, handler, type)
        }
    }

    final override fun visitTryCatchAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean
    ): AnnotationVisitor? {
        if (emitCode) {
            return super.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible)
        }
        return null
    }

    final override fun visitLocalVariable(
            name: String?,
            descriptor: String?,
            signature: String?,
            start: Label?,
            end: Label?,
            index: Int
    ) {
        if (emitCode) {
            super.visitLocalVariable(name, descriptor, signature, start, end, index)
        }
    }

    final override fun visitLocalVariableAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            start: Array<out Label>?,
            end: Array<out Label>?,
            index: IntArray?,
            descriptor: String?,
            visible: Boolean
    ): AnnotationVisitor? {
        if (emitCode) {
            return super.visitLocalVariableAnnotation(
                    typeRef, typePath, start, end, index, descriptor, visible)
        }
        return null
    }

    final override fun visitLineNumber(
            line: Int,
            start: Label?
    ) {
        if (emitCode) {
            super.visitLineNumber(line, start)
        }
    }
}
