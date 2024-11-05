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

import com.android.hoststubgen.asm.ClassNodes

/**
 * Filter used by TextFileFilterPolicyParser for "method call relacement".
 */
class TextFilePolicyMethodReplaceFilter(
    val spec: List<MethodCallReplaceSpec>,
    val classes: ClassNodes,
    val fallback: OutputFilter,
) : DelegatingFilter(fallback) {

    data class MethodCallReplaceSpec(
        val fromClass: String,
        val fromMethod: String,
        val fromDescriptor: String,
        val toClass: String,
        val toMethod: String,
    )

    override fun hasAnyMethodCallReplace(): Boolean {
        return true
    }

    override fun getMethodCallReplaceTo(
        callerClassName: String,
        callerMethodName: String,
        className: String,
        methodName: String,
        descriptor: String,
    ): MethodReplaceTarget? {
        // Maybe use 'Tri' if we end up having too many replacements.
        spec.forEach {
            if (className == it.fromClass &&
                methodName == it.fromMethod &&
                descriptor == it.fromDescriptor
                ) {
                return MethodReplaceTarget(it.toClass, it.toMethod)
            }
        }
        return null
    }
}
