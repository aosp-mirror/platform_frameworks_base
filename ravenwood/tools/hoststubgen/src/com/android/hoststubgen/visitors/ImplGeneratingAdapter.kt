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

import com.android.hoststubgen.asm.CLASS_INITIALIZER_DESC
import com.android.hoststubgen.asm.CLASS_INITIALIZER_NAME
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.prependArgTypeToMethodDescriptor
import com.android.hoststubgen.asm.writeByteCodeToPushArguments
import com.android.hoststubgen.asm.writeByteCodeToReturn
import com.android.hoststubgen.filters.FilterPolicy
import com.android.hoststubgen.filters.FilterPolicyWithReason
import com.android.hoststubgen.filters.OutputFilter
import com.android.hoststubgen.hosthelper.HostStubGenProcessedAsIgnore
import com.android.hoststubgen.hosthelper.HostStubGenProcessedAsSubstitute
import com.android.hoststubgen.hosthelper.HostStubGenProcessedAsThrow
import com.android.hoststubgen.hosthelper.HostTestUtils
import com.android.hoststubgen.log
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.INVOKEINTERFACE
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Type

/**
 * An adapter that generates the "impl" class file from an input class file.
 */
class ImplGeneratingAdapter(
    classes: ClassNodes,
    nextVisitor: ClassVisitor,
    filter: OutputFilter,
    options: Options,
) : BaseAdapter(classes, nextVisitor, filter, options) {

    private var classLoadHooks: List<String> = emptyList()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<String>
    ) {
        super.visit(version, access, name, signature, superName, interfaces)

        classLoadHooks = filter.getClassLoadHooks(currentClassName)

        // classLoadHookMethod is non-null, then we need to inject code to call it
        // in the class initializer.
        // If the target class already has a class initializer, then we need to inject code to it.
        // Otherwise, we need to create one.

        if (classLoadHooks.isNotEmpty()) {
            log.d("  ClassLoadHooks: $classLoadHooks")
            if (!classes.hasClassInitializer(currentClassName)) {
                injectClassLoadHook()
            }
        }
    }

    private fun injectClassLoadHook() {
        writeRawMembers {
            // Create a class initializer to call onClassLoaded().
            // Each class can only have at most one class initializer, but the base class
            // StaticInitMerger will merge it with the existing one, if any.
            visitMethod(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
                CLASS_INITIALIZER_NAME,
                "()V",
                null,
                null
            )!!.let { mv ->
                // Method prologue
                mv.visitCode()

                writeClassLoadHookCalls(mv)
                mv.visitInsn(Opcodes.RETURN)

                // Method epilogue
                mv.visitMaxs(0, 0)
                mv.visitEnd()
            }
        }
    }

    private fun writeClassLoadHookCalls(mv: MethodVisitor) {
        classLoadHooks.forEach { classLoadHook ->
            // First argument: the class type.
            mv.visitLdcInsn(Type.getType("L$currentClassName;"))

            // Second argument: method name
            mv.visitLdcInsn(classLoadHook)

            // Call HostTestUtils.onClassLoaded().
            mv.visitMethodInsn(
                INVOKESTATIC,
                HostTestUtils.CLASS_INTERNAL_NAME,
                "onClassLoaded",
                "(Ljava/lang/Class;Ljava/lang/String;)V",
                false
            )
        }
    }

    override fun updateAccessFlags(
        access: Int,
        name: String,
        descriptor: String,
        policy: FilterPolicy,
    ): Int {
        if (policy.isMethodRewriteBody) {
            // If we are rewriting the entire method body, we need
            // to convert native methods to non-native
            return access and Opcodes.ACC_NATIVE.inv()
        }
        return access
    }

    override fun visitMethodInner(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
        policy: FilterPolicyWithReason,
        substituted: Boolean,
        superVisitor: MethodVisitor?,
    ): MethodVisitor? {
        var innerVisitor = superVisitor

        //  If method logging is enabled, inject call to the logging method.
        val methodCallHooks = filter.getMethodCallHooks(currentClassName, name, descriptor)
        if (methodCallHooks.isNotEmpty()) {
            innerVisitor = MethodCallHookInjectingAdapter(
                name,
                descriptor,
                methodCallHooks,
                innerVisitor,
            )
        }

        // If this class already has a class initializer and a class load hook is needed, then
        // we inject code.
        if (classLoadHooks.isNotEmpty() &&
            name == CLASS_INITIALIZER_NAME &&
            descriptor == CLASS_INITIALIZER_DESC
        ) {
            innerVisitor = ClassLoadHookInjectingMethodAdapter(innerVisitor)
        }

        fun MethodVisitor.withAnnotation(descriptor: String): MethodVisitor {
            this.visitAnnotation(descriptor, true)
            return this
        }

        log.withIndent {
            // When we encounter native methods, we want to forcefully
            // inject a method body. Also see [updateAccessFlags].
            val forceCreateBody = (access and Opcodes.ACC_NATIVE) != 0
            when (policy.policy) {
                FilterPolicy.Throw -> {
                    log.v("Making method throw...")
                    return ThrowingMethodAdapter(forceCreateBody, innerVisitor)
                        .withAnnotation(HostStubGenProcessedAsThrow.CLASS_DESCRIPTOR)
                }
                FilterPolicy.Ignore -> {
                    log.v("Making method ignored...")
                    return IgnoreMethodAdapter(descriptor, forceCreateBody, innerVisitor)
                        .withAnnotation(HostStubGenProcessedAsIgnore.CLASS_DESCRIPTOR)
                }
                FilterPolicy.Redirect -> {
                    log.v("Redirecting method...")
                    return RedirectMethodAdapter(
                        access, name, descriptor,
                        forceCreateBody, innerVisitor
                    )
                        .withAnnotation(HostStubGenProcessedAsSubstitute.CLASS_DESCRIPTOR)
                }
                else -> {}
            }
        }

        if (filter.hasAnyMethodCallReplace()) {
            innerVisitor = MethodCallReplacingAdapter(name, innerVisitor)
        }
        if (substituted) {
            innerVisitor?.withAnnotation(HostStubGenProcessedAsSubstitute.CLASS_DESCRIPTOR)
        }

        return innerVisitor
    }

    /**
     * A method adapter that replaces the method body with a HostTestUtils.onThrowMethodCalled()
     * call.
     */
    private inner class ThrowingMethodAdapter(
        createBody: Boolean,
        next: MethodVisitor?
    ) : BodyReplacingMethodVisitor(createBody, next) {
        override fun emitNewCode() {
            visitMethodInsn(
                INVOKESTATIC,
                HostTestUtils.CLASS_INTERNAL_NAME,
                "onThrowMethodCalled",
                "()V",
                false
            )

            // We still need a RETURN opcode for the return type.
            // For now, let's just inject a `throw`.
            visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("Unreachable")
            visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/RuntimeException",
                "<init>", "(Ljava/lang/String;)V", false
            )
            visitInsn(Opcodes.ATHROW)

            // visitMaxs(3, if (isStatic) 0 else 1)
            visitMaxs(0, 0) // We let ASM figure them out.
        }
    }

    /**
     * A method adapter that replaces the method body with a no-op return.
     */
    private inner class IgnoreMethodAdapter(
        val descriptor: String,
        createBody: Boolean,
        next: MethodVisitor?
    ) : BodyReplacingMethodVisitor(createBody, next) {
        override fun emitNewCode() {
            when (Type.getReturnType(descriptor)) {
                Type.VOID_TYPE -> visitInsn(Opcodes.RETURN)
                Type.BOOLEAN_TYPE, Type.BYTE_TYPE, Type.CHAR_TYPE, Type.SHORT_TYPE,
                Type.INT_TYPE -> {
                    visitInsn(Opcodes.ICONST_0)
                    visitInsn(Opcodes.IRETURN)
                }
                Type.LONG_TYPE -> {
                    visitInsn(Opcodes.LCONST_0)
                    visitInsn(Opcodes.LRETURN)
                }
                Type.FLOAT_TYPE -> {
                    visitInsn(Opcodes.FCONST_0)
                    visitInsn(Opcodes.FRETURN)
                }
                Type.DOUBLE_TYPE -> {
                    visitInsn(Opcodes.DCONST_0)
                    visitInsn(Opcodes.DRETURN)
                }
                else -> {
                    visitInsn(Opcodes.ACONST_NULL)
                    visitInsn(Opcodes.ARETURN)
                }
            }
            visitMaxs(0, 0) // We let ASM figure them out.
        }
    }

    /**
     * A method adapter that rewrite a method body with a
     * call to a method in the redirection class.
     */
    private inner class RedirectMethodAdapter(
        access: Int,
        private val name: String,
        private val descriptor: String,
        createBody: Boolean,
        next: MethodVisitor?
    ) : BodyReplacingMethodVisitor(createBody, next) {

        private val isStatic = (access and Opcodes.ACC_STATIC) != 0

        override fun emitNewCode() {
            var targetDescriptor = descriptor
            var argOffset = 0

            // For non-static method, we need to tweak it a bit.
            if (!isStatic) {
                // Push `this` as the first argument.
                this.visitVarInsn(Opcodes.ALOAD, 0)

                // Update the descriptor -- add this class's type as the first argument
                // to the method descriptor.
                targetDescriptor = prependArgTypeToMethodDescriptor(
                    descriptor,
                    currentClassName,
                )

                // Shift the original arguments by one.
                argOffset = 1
            }

            writeByteCodeToPushArguments(descriptor, this, argOffset)

            visitMethodInsn(
                INVOKESTATIC,
                redirectionClass,
                name,
                targetDescriptor,
                false
            )

            writeByteCodeToReturn(descriptor, this)

            visitMaxs(99, 0) // We let ASM figure them out.
        }
    }

    /**
     * Inject calls to the method call hooks.
     *
     * Note, when the target method is a constructor, it may contain calls to `super(...)` or
     * `this(...)`. The logging code will be injected *before* such calls.
     */
    private inner class MethodCallHookInjectingAdapter(
        val name: String,
        val descriptor: String,
        val hooks: List<String>,
        next: MethodVisitor?,
    ) : MethodVisitor(OPCODE_VERSION, next) {
        override fun visitCode() {
            super.visitCode()

            hooks.forEach { hook ->
                mv.visitLdcInsn(Type.getType("L$currentClassName;"))
                visitLdcInsn(name)
                visitLdcInsn(descriptor)
                visitLdcInsn(hook)

                visitMethodInsn(
                    INVOKESTATIC,
                    HostTestUtils.CLASS_INTERNAL_NAME,
                    "callMethodCallHook",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    false
                )
            }
        }
    }

    /**
     * Inject a class load hook call.
     */
    private inner class ClassLoadHookInjectingMethodAdapter(
        next: MethodVisitor?
    ) : MethodVisitor(OPCODE_VERSION, next) {
        override fun visitCode() {
            super.visitCode()

            writeClassLoadHookCalls(this)
        }
    }

    private inner class MethodCallReplacingAdapter(
        val callerMethodName: String,
        next: MethodVisitor?,
    ) : MethodVisitor(OPCODE_VERSION, next) {
        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean,
        ) {
            when (opcode) {
                INVOKESTATIC, INVOKEVIRTUAL, INVOKEINTERFACE -> {}
                else -> {
                    // Don't touch other opcodes.
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    return
                }
            }
            val to = filter.getMethodCallReplaceTo(
                currentClassName, callerMethodName, owner!!, name!!, descriptor!!
            )

            if (to == null
                // Don't replace if the target is the callsite.
                || (to.className == currentClassName && to.methodName == callerMethodName)
            ) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }

            // Replace the method call with a (static) call to the target method.
            // If it's a non-static call, the target method's first argument will receive "this".
            // (Because of that, we don't need to manipulate the stack. Just replace the
            // method call.)

            val toDesc = if (opcode == INVOKESTATIC) {
                // Static call to static call, no need to change the desc.
                descriptor
            } else {
                // Need to prepend the "this" type to the descriptor.
                prependArgTypeToMethodDescriptor(descriptor, owner)
            }

            mv.visitMethodInsn(
                INVOKESTATIC,
                to.className,
                to.methodName,
                toDesc,
                false
            )
        }
    }
}
