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

/**
 * Captures a [FilterPolicy] with a human-readable reason.
 */
data class FilterPolicyWithReason (
        val policy: FilterPolicy,
        val reason: String = "",
) {
    /**
     * Return a new [FilterPolicy] with an updated reason, while keeping the original reason
     * as an "inner-reason".
     */
    fun wrapReason(reason: String): FilterPolicyWithReason {
        return FilterPolicyWithReason(policy, "$reason [inner-reason: ${this.reason}]")
    }

    /**
     * If the visibility is lower than "Keep" (meaning if it's "remove"),
     * then return a new [FilterPolicy] with "Keep".
     * Otherwise, return itself
     */
    fun promoteToKeep(promotionReason: String): FilterPolicyWithReason {
        if (policy.needsInImpl) {
            return this
        }
        val newPolicy = if (policy.isClassWidePolicy) FilterPolicy.KeepClass else FilterPolicy.Keep

        return FilterPolicyWithReason(newPolicy,
                "$promotionReason [original remove reason: ${this.reason}]")
    }

    /**
     * If the visibility is above "Keep" (meaning if it's "stub"),
     * then return a new [FilterPolicy] with "Keep".
     * Otherwise, return itself
     */
    fun demoteToKeep(promotionReason: String): FilterPolicyWithReason {
        if (!policy.needsInStub) {
            return this
        }
        val newPolicy = if (policy.isClassWidePolicy) FilterPolicy.KeepClass else FilterPolicy.Keep

        return FilterPolicyWithReason(newPolicy,
                "$promotionReason [original stub reason: ${this.reason}]")
    }

    override fun toString(): String {
        return "[$policy - reason: $reason]"
    }

    /** Returns whether this policy should be ignored for stats. */
    val isIgnoredForStats: Boolean
        get() {
            return reason.contains("anonymous-inner-class")
                    || reason.contains("is-annotation")
                    || reason.contains("is-enum")
                    || reason.contains("is-synthetic-method")
                    || reason.contains("special-class")
                    || reason.contains("substitute-to")
        }
}
