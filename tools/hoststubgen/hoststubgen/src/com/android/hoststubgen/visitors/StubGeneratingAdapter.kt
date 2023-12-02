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

import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.filters.FilterPolicy
import com.android.hoststubgen.filters.FilterPolicyWithReason
import com.android.hoststubgen.filters.OutputFilter
import com.android.hoststubgen.log
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * An adapter that generates the "impl" class file from an input class file.
 */
class StubGeneratingAdapter(
        classes: ClassNodes,
        nextVisitor: ClassVisitor,
        filter: OutputFilter,
        options: Options,
) : BaseAdapter(classes, nextVisitor, filter, options) {

    override fun shouldEmit(policy: FilterPolicy): Boolean {
        return policy.needsInStub
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
        return StubMethodVisitor(access, name, descriptor, signature, exceptions, superVisitor)
    }

    private inner class StubMethodVisitor(
            access: Int,
            val name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<String>?,
            next: MethodVisitor?
    ) : BodyReplacingMethodVisitor(access, name, descriptor, signature, exceptions, next) {
        override fun emitNewCode() {
            log.d("  Generating stub method for $currentClassName.$name")

            // Inject the following code:
            //   throw new RuntimeException("Stub!");

            /*
                NEW java/lang/RuntimeException
                DUP
                LDC "not supported on host side"
                INVOKESPECIAL java/lang/RuntimeException.<init> (Ljava/lang/String;)V
                ATHROW
                MAXSTACK = 3
                MAXLOCALS = 2 <- 1 for this, 1 for return value.
             */
            visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("Stub!")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException",
                    "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ATHROW)
            visitMaxs(0, 0) // We let ASM figure them out.
        }
    }
}
