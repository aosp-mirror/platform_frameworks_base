/*
 * Copyright (C) 2024 The Android Open Source Project
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
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.toHumanReadableClassName
import com.android.hoststubgen.log

/**
 * Check whether the policies in the inner layers make sense, and sanitize the results.
 */
class SanitizationFilter(
    private val errors: HostStubGenErrors,
    private val classes: ClassNodes,
    fallback: OutputFilter
) : DelegatingFilter(fallback) {
    override fun getPolicyForMethod(
        className: String,
        methodName: String,
        descriptor: String
    ): FilterPolicyWithReason {
        val policy = super.getPolicyForMethod(className, methodName, descriptor)
        if (policy.policy == FilterPolicy.Redirect) {
            // Check whether the hosting class has a redirection class
            if (getRedirectionClass(className) == null) {
                errors.onErrorFound("Method $methodName$descriptor requires a redirection " +
                        "class set on ${className.toHumanReadableClassName()}")
            }
        }
        return policy
    }

    override fun getRedirectionClass(className: String): String? {
        return super.getRedirectionClass(className)?.also { clazz ->
            if (classes.findClass(clazz) == null) {
                log.w("Redirection class $clazz not found. Class must be available at runtime.")
            } else if (outermostFilter.getPolicyForClass(clazz).policy != FilterPolicy.KeepClass) {
                // If the class exists, it must have a KeepClass policy.
                errors.onErrorFound("Redirection class $clazz must have @KeepWholeClass.")
            }
        }
    }
}
