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
package com.android.hoststubgen.filters

import com.android.hoststubgen.HostStubGenErrors
import com.android.hoststubgen.HostStubGenInternalException
import com.android.hoststubgen.asm.CLASS_INITIALIZER_DESC
import com.android.hoststubgen.asm.CLASS_INITIALIZER_NAME
import com.android.hoststubgen.asm.isAnonymousInnerClass
import com.android.hoststubgen.log
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.isVisibilityPrivateOrPackagePrivate
import org.objectweb.asm.tree.ClassNode

/**
 * Filter implementing "implicit" rules, such as:
 * - "keep all anonymous inner classes if the outer class is keep".
 *   (But anonymous inner classes should never be in "stub")
 * - For classes in stub, make sure private parameterless constructors are also in stub, if any.
 */
class ImplicitOutputFilter(
        private val errors: HostStubGenErrors,
        private val classes: ClassNodes,
        fallback: OutputFilter
) : DelegatingFilter(fallback) {
    private fun getClassImplicitPolicy(className: String, cn: ClassNode): FilterPolicyWithReason? {
        if (isAnonymousInnerClass(cn)) {
            log.forDebug {
//                log.d("  anon-inner class: ${className} outer: ${cn.outerClass}  ")
            }
            if (cn.outerClass == null) {
                throw HostStubGenInternalException(
                        "outerClass is null for anonymous inner class")
            }
            // If the outer class needs to be in impl, it should be in impl too.
            val outerPolicy = outermostFilter.getPolicyForClass(cn.outerClass)
            if (outerPolicy.policy.needsInImpl) {
                return FilterPolicy.KeepClass.withReason("anonymous-inner-class")
            }
        }
        return null
    }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        val fallback = super.getPolicyForClass(className)

        // TODO: This check should be cached.
        val cn = classes.getClass(className)

        if (cn.superName == "java/lang/Enum" &&
                fallback.policy == FilterPolicy.Keep) {
            return FilterPolicy.KeepClass.withReason("enum")
        }
        if (cn.interfaces.contains("java/lang/annotation/Annotation") &&
                fallback.policy == FilterPolicy.Keep) {
            return FilterPolicy.KeepClass.withReason("annotation")
        }

        // Use the implicit policy, if any.
        getClassImplicitPolicy(className, cn)?.let { return it }

        return fallback
    }

    override fun getPolicyForMethod(
            className: String,
            methodName: String,
            descriptor: String
    ): FilterPolicyWithReason {
        val fallback = super.getPolicyForMethod(className, methodName, descriptor)

        // If the class is in the stub, then we need to put the private constructor in the stub too,
        // to prevent the class from getting instantiated.
        if (outermostFilter.getPolicyForClass(className).policy.needsInStub &&
                !fallback.policy.needsInStub &&
                (methodName == "<init>") && // Constructor?
                (descriptor == "()V")) { // Has zero parameters?
            classes.findMethod(className, methodName, descriptor)?.let { mn ->
                if (isVisibilityPrivateOrPackagePrivate(mn.access)) {
                    return FilterPolicy.Stub.withReason("private constructor in stub class")
                }
            }
        }

        // If we throw from the static initializer, the class would be useless, so we convert it
        // "keep" instead.
        if (methodName == CLASS_INITIALIZER_NAME && descriptor == CLASS_INITIALIZER_DESC &&
            fallback.policy == FilterPolicy.Throw) {
            // TODO Maybe show a warning?? But that'd be too noisy with --default-throw.
            return FilterPolicy.Keep.withReason(
                "'throw' on static initializer is handled as 'keep'" +
                        " [original throw reason: ${fallback.reason}]")
        }

        return fallback
    }
}