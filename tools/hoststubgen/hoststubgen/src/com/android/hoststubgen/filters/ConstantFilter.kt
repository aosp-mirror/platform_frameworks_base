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

import com.android.hoststubgen.HostStubGenInternalException


/**
 * [OutputFilter] with a given policy. Used to represent the default policy.
 *
 * This is used as the last fallback filter.
 *
 * @param policy the policy. Cannot be a "substitute" policy.
 */
class ConstantFilter(
    policy: FilterPolicy,
    private val reason: String
) : OutputFilter() {

    private val classPolicy: FilterPolicy
    private val fieldPolicy: FilterPolicy
    private val methodPolicy: FilterPolicy

    init {
        if (!policy.isUsableWithDefault) {
            throw HostStubGenInternalException("ConstantFilter doesn't support $policy.")
        }
        methodPolicy = policy

        // If the default policy is "throw", we convert it to "keep" for classes and fields.
        classPolicy = when (policy) {
            FilterPolicy.Throw -> FilterPolicy.Keep
            else -> policy
        }
        fieldPolicy = classPolicy
    }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        return classPolicy.withReason(reason)
    }

    override fun getPolicyForField(className: String, fieldName: String): FilterPolicyWithReason {
        return fieldPolicy.withReason(reason)
    }

    override fun getPolicyForMethod(
        className: String,
        methodName: String,
        descriptor: String,
    ): FilterPolicyWithReason {
        return methodPolicy.withReason(reason)
    }
}
