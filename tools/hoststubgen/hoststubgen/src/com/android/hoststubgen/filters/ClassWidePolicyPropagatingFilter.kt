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
import com.android.hoststubgen.asm.getDirectOuterClassName

/**
 * This is used as the second last fallback filter. This filter propagates the class-wide policy
 * (obtained from [outermostFilter]) to the fields and methods.
 */
class ClassWidePolicyPropagatingFilter(
    private val classes: ClassNodes,
    fallback: OutputFilter,
    ) : DelegatingFilter(fallback) {

    private fun getClassWidePolicy(className: String, resolve: Boolean): FilterPolicyWithReason? {
        var currentClass = className


        // If the class name is `a.b.c.A$B$C`, then we try to get the class wide policy
        // from a.b.c.A$B$C, then a.b.c.A$B, and then a.b.c.A.
        while (true) {
            // Sometimes a class name has a `$` in it but not as a nest class name separator --
            // e.g. class name like "MyClass$$". In this case, `MyClass$` may not actually be
            // a class name.
            // So before getting the class policy on a nonexistent class, which may cause an
            // incorrect result, we make sure if className actually exists.
            if (classes.hasClass(className)) {
                outermostFilter.getPolicyForClass(className).let { policy ->
                    if (policy.policy.isClassWidePolicy) {
                        val p = if (resolve) {
                            policy.policy.resolveClassWidePolicy()
                        } else {
                            policy.policy
                        }

                        return p.withReason(policy.reason)
                            .wrapReason("class-wide in $currentClass")
                    }
                    // If the class's policy is remove, then remove it.
                    if (policy.policy == FilterPolicy.Remove) {
                        return FilterPolicy.Remove.withReason("class-wide in $currentClass")
                    }
                }
            }

            // Next, look at the outer class...
            val outer = getDirectOuterClassName(currentClass)
            if (outer == null) {
                return null
            }
            currentClass = outer
        }
    }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        // If it's a nested class, use the outer class's policy.
        getDirectOuterClassName(className)?.let { outerName ->
            getClassWidePolicy(outerName, resolve = false)?.let { policy ->
                return policy
            }
        }

        return super.getPolicyForClass(className)
    }

    override fun getPolicyForField(
            className: String,
            fieldName: String
    ): FilterPolicyWithReason {
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