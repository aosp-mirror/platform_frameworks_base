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
import com.android.hoststubgen.asm.isVisibilityPrivateOrPackagePrivate
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

    override fun shouldEmit(policy: FilterPolicy): Boolean {
        return policy.needsInImpl
    }

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
            mv.visitLdcInsn(Type.getType("L" + currentClassName + ";"))

            // Second argument: method name
            mv.visitLdcInsn(classLoadHook)

            // Call HostTestUtils.onClassLoaded().
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
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
    ): Int {
        if ((access and Opcodes.ACC_NATIVE) != 0 && nativeSubstitutionClass != null) {
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
        // Inject method log, if needed.
        var innerVisitor = superVisitor

        //  If method logging is enabled, inject call to the logging method.
        val methodCallHooks = filter.getMethodCallHooks(currentClassName, name, descriptor)
        if (methodCallHooks.isNotEmpty()) {
            innerVisitor = MethodCallHookInjectingAdapter(
                access,
                name,
                descriptor,
                signature,
                exceptions,
                innerVisitor,
                methodCallHooks,
                )
        }

        // If this class already has a class initializer and a class load hook is needed, then
        // we inject code.
        if (classLoadHooks.isNotEmpty() &&
            name == CLASS_INITIALIZER_NAME &&
            descriptor == CLASS_INITIALIZER_DESC) {
            innerVisitor = ClassLoadHookInjectingMethodAdapter(
                access,
                name,
                descriptor,
                signature,
                exceptions,
                innerVisitor,
            )
        }

        // If non-stub method call detection is enabled, then inject a call to the checker.
        if (options.enableNonStubMethodCallDetection && doesMethodNeedNonStubCallCheck(
                access, name, descriptor, policy) ) {
            innerVisitor = NonStubMethodCallDetectingAdapter(
                    access,
                    name,
                    descriptor,
                    signature,
                    exceptions,
                    innerVisitor,
            )
        }

        fun MethodVisitor.withAnnotation(descriptor: String): MethodVisitor {
            this.visitAnnotation(descriptor, true)
            return this
        }

        log.withIndent {
            var willThrow = false
            if (policy.policy == FilterPolicy.Throw) {
                log.v("Making method throw...")
                willThrow = true
                innerVisitor = ThrowingMethodAdapter(
                    access, name, descriptor, signature, exceptions, innerVisitor)
                    .withAnnotation(HostStubGenProcessedAsThrow.CLASS_DESCRIPTOR)
            }
            if ((access and Opcodes.ACC_NATIVE) != 0 && nativeSubstitutionClass != null) {
                log.v("Rewriting native method...")
                return NativeSubstitutingMethodAdapter(
                        access, name, descriptor, signature, exceptions, innerVisitor)
                    .withAnnotation(HostStubGenProcessedAsSubstitute.CLASS_DESCRIPTOR)
            }
            if (willThrow) {
                return innerVisitor
            }

            if (policy.policy == FilterPolicy.Ignore) {
                when (Type.getReturnType(descriptor)) {
                    Type.VOID_TYPE -> {
                        log.v("Making method ignored...")
                        return IgnoreMethodAdapter(
                                access, name, descriptor, signature, exceptions, innerVisitor)
                            .withAnnotation(HostStubGenProcessedAsIgnore.CLASS_DESCRIPTOR)
                    }
                    else -> {
                        throw RuntimeException("Ignored policy only allowed for void methods")
                    }
                }
            }
        }
        if (substituted) {
            innerVisitor?.withAnnotation(HostStubGenProcessedAsSubstitute.CLASS_DESCRIPTOR)
        }

        return innerVisitor
    }

    fun doesMethodNeedNonStubCallCheck(
            access: Int,
            name: String,
            descriptor: String,
            policy: FilterPolicyWithReason,
    ): Boolean {
        // If a method is in the stub, then no need to check.
        if (policy.policy.needsInStub) {
            return false
        }
        // If a method is private or package-private, no need to check.
        // Technically test code can use framework package name, so it's a bit too lenient.
        if (isVisibilityPrivateOrPackagePrivate(access)) {
            return false
        }
        // TODO: If the method overrides a method that's accessible by tests, then we shouldn't
        // do the check. (e.g. overrides a stub method or java standard method.)

        return true
    }

    /**
     * A method adapter that replaces the method body with a HostTestUtils.onThrowMethodCalled()
     * call.
     */
    private inner class ThrowingMethodAdapter(
            access: Int,
            val name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<String>?,
            next: MethodVisitor?
    ) : BodyReplacingMethodVisitor(access, name, descriptor, signature, exceptions, next) {
        override fun emitNewCode() {
            visitMethodInsn(Opcodes.INVOKESTATIC,
                    HostTestUtils.CLASS_INTERNAL_NAME,
                    "onThrowMethodCalled",
                    "()V",
                    false)

            // We still need a RETURN opcode for the return type.
            // For now, let's just inject a `throw`.
            visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("Unreachable")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException",
                    "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ATHROW)

            // visitMaxs(3, if (isStatic) 0 else 1)
            visitMaxs(0, 0) // We let ASM figure them out.
        }
    }

    /**
     * A method adapter that replaces the method body with a no-op return.
     */
    private inner class IgnoreMethodAdapter(
            access: Int,
            val name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<String>?,
            next: MethodVisitor?
    ) : BodyReplacingMethodVisitor(access, name, descriptor, signature, exceptions, next) {
        override fun emitNewCode() {
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0) // We let ASM figure them out.
        }
    }

    /**
     * A method adapter that replaces a native method call with a call to the "native substitution"
     * class.
     */
    private inner class NativeSubstitutingMethodAdapter(
            val access: Int,
            private val name: String,
            private val descriptor: String,
            signature: String?,
            exceptions: Array<String>?,
            next: MethodVisitor?
    ) : MethodVisitor(OPCODE_VERSION, next) {
        override fun visitCode() {
            throw RuntimeException("NativeSubstitutingMethodVisitor should be called on " +
                    " native method, where visitCode() shouldn't be called.")
        }

        override fun visitEnd() {
            super.visitCode()

            var targetDescriptor = descriptor
            var argOffset = 0

            // For non-static native method, we need to tweak it a bit.
            if ((access and Opcodes.ACC_STATIC) == 0) {
                // Push `this` as the first argument.
                this.visitVarInsn(Opcodes.ALOAD, 0)

                // Update the descriptor -- add this class's type as the first argument
                // to the method descriptor.
                val thisType = Type.getType("L" + currentClassName + ";")

                targetDescriptor = prependArgTypeToMethodDescriptor(
                        descriptor,
                        thisType,
                )

                // Shift the original arguments by one.
                argOffset = 1
            }

            writeByteCodeToPushArguments(descriptor, this, argOffset)

            visitMethodInsn(Opcodes.INVOKESTATIC,
                    nativeSubstitutionClass,
                    name,
                    targetDescriptor,
                    false)

            writeByteCodeToReturn(descriptor, this)

            visitMaxs(99, 0) // We let ASM figure them out.
            super.visitEnd()
        }
    }

    /**
     * Inject calls to the method call hooks.
     *
     * Note, when the target method is a constructor, it may contain calls to `super(...)` or
     * `this(...)`. The logging code will be injected *before* such calls.
     */
    private inner class MethodCallHookInjectingAdapter(
            access: Int,
            val name: String,
            val descriptor: String,
            signature: String?,
            exceptions: Array<String>?,
            next: MethodVisitor?,
            val hooks: List<String>,
    ) : MethodVisitor(OPCODE_VERSION, next) {
        override fun visitCode() {
            super.visitCode()

            hooks.forEach { hook ->
                mv.visitLdcInsn(Type.getType("L" + currentClassName + ";"))
                visitLdcInsn(name)
                visitLdcInsn(descriptor)
                visitLdcInsn(hook)

                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
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
        access: Int,
        val name: String,
        val descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
        next: MethodVisitor?
    ) : MethodVisitor(OPCODE_VERSION, next) {
        override fun visitCode() {
            super.visitCode()

            writeClassLoadHookCalls(this)
        }
    }

    /**
     * A method adapter that detects calls to non-stub methods.
     */
    private inner class NonStubMethodCallDetectingAdapter(
            access: Int,
            val name: String,
            val descriptor: String,
            signature: String?,
            exceptions: Array<String>?,
            next: MethodVisitor?
    ) : MethodVisitor(OPCODE_VERSION, next) {
        override fun visitCode() {
            super.visitCode()

            // First three arguments to HostTestUtils.onNonStubMethodCalled().
            visitLdcInsn(currentClassName)
            visitLdcInsn(name)
            visitLdcInsn(descriptor)

            // Call: HostTestUtils.getStackWalker().getCallerClass().
            // This push the caller Class in the stack.
            visitMethodInsn(Opcodes.INVOKESTATIC,
                    HostTestUtils.CLASS_INTERNAL_NAME,
                    "getStackWalker",
                    "()Ljava/lang/StackWalker;",
                    false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StackWalker",
                    "getCallerClass",
                    "()Ljava/lang/Class;",
                    false)

            // Then call onNonStubMethodCalled().
            visitMethodInsn(Opcodes.INVOKESTATIC,
                    HostTestUtils.CLASS_INTERNAL_NAME,
                    "onNonStubMethodCalled",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)V",
                    false)
        }
    }
}
