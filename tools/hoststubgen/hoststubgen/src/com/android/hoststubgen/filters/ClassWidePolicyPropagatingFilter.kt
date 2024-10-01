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

import com.android.hoststubgen.asm.ClassNodes

/**
 * This is used as the second last fallback filter. This filter propagates the class-wide policy
 * (obtained from [outermostFilter]) to the fields and methods.
 */
class ClassWidePolicyPropagatingFilter(
    private val classes: ClassNodes,
    fallback: OutputFilter
) : DelegatingFilter(fallback) {

    /**
     * We don't use ClassNode.outerClass, because it gives as the top-level
     * outer class (A$B$C -> A), not the direct outer class (A$B$C -> A$B).
     *
     * Sometimes a class name includes `$`, but is not as a nested class name separator
     * (e.g. a class name like `MyClass$$`). In this case, `MyClass$` is not actually a class.
     *
     * So before getting the class policy on a nonexistent class, which may cause an
     * incorrect result, we make sure the class actually exists.
     */
    private fun getDirectOuterClass(className: String): String? {
        var currentClass = className
        while (true) {
            val pos = currentClass.lastIndexOf('$')
            if (pos < 0) {
                return null
            }
            currentClass = currentClass.substring(0, pos)
            if (classes.hasClass(currentClass)) {
                return currentClass
            }
        }
    }

    private fun getClassWidePolicy(className: String, resolve: Boolean): FilterPolicyWithReason? {
        outermostFilter.getPolicyForClass(className).let { policy ->
            if (policy.policy == FilterPolicy.KeepClass) {
                val p = if (resolve) {
                    policy.policy.resolveClassWidePolicy()
                } else {
                    policy.policy
                }

                return p.withReason(policy.reason)
                    .wrapReason("class-wide in $className")
            }
            // If the class's policy is remove, then remove it.
            if (policy.policy == FilterPolicy.Remove) {
                return FilterPolicy.Remove.withReason("class-wide in $className")
            }
        }
        return null
    }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        // If the class name is `a.b.c.A$B$C`, then we try to get the class wide policy
        // from a.b.c.A$B$C, then a.b.c.A$B, and then a.b.c.A, recursively
        return getDirectOuterClass(className)?.let { getClassWidePolicy(it, resolve = false) }
            ?: super.getPolicyForClass(className)
    }

    override fun getPolicyForField(className: String, fieldName: String): FilterPolicyWithReason {
        return getClassWidePolicy(className, resolve = true)
                ?: super.getPolicyForField(className, fieldName)
    }

    override fun getPolicyForMethod(
        className: String,
        methodName: String,
        descriptor: String
    ): FilterPolicyWithReason {
        return getClassWidePolicy(className, resolve = true)
            ?: super.getPolicyForMethod(className, methodName, descriptor)
    }
}
