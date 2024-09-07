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
import com.android.hoststubgen.asm.isNative

/**
 *  For native methods that weren't handled by outer filters, we keep it so that
 *  native method registration will not crash at runtime. Ideally we shouldn't need
 *  this, but in practice unsupported native method registrations do occur.
 */
class KeepNativeFilter(
    private val classes: ClassNodes,
    fallback: OutputFilter
) : DelegatingFilter(fallback) {
    override fun getPolicyForMethod(
        className: String,
        methodName: String,
        descriptor: String,
    ): FilterPolicyWithReason {
        return classes.findMethod(className, methodName, descriptor)?.let { mn ->
            if (mn.isNative()) {
                FilterPolicy.Keep.withReason("native-preserve")
            } else {
                null
            }
        } ?: super.getPolicyForMethod(className, methodName, descriptor)
    }
}