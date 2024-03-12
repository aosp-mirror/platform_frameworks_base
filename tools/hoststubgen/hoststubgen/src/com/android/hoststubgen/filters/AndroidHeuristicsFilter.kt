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
 * Filter that deals with Android specific heuristics.
 */
class AndroidHeuristicsFilter(
        private val classes: ClassNodes,
        val aidlPolicy: FilterPolicyWithReason?,
        val featureFlagsPolicy: FilterPolicyWithReason?,
        fallback: OutputFilter
) : DelegatingFilter(fallback) {
    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        if (aidlPolicy != null && classes.isAidlClass(className)) {
            return aidlPolicy
        }
        if (featureFlagsPolicy != null && classes.isFeatureFlagsClass(className)) {
            return featureFlagsPolicy
        }
        return super.getPolicyForClass(className)
    }
}

/**
 * @return if a given class "seems like" an AIDL (top-level) class.
 */
private fun ClassNodes.isAidlClass(className: String): Boolean {
    return hasClass(className) &&
            hasClass("$className\$Stub") &&
            hasClass("$className\$Stub\$Proxy")
}

/**
 * @return if a given class "seems like" an feature flags class.
 */
private fun ClassNodes.isFeatureFlagsClass(className: String): Boolean {
    // Matches template classes defined here:
    // https://cs.android.com/android/platform/superproject/+/master:build/make/tools/aconfig/templates/
    return className.endsWith("/Flags")
            || className.endsWith("/FeatureFlags")
            || className.endsWith("/FeatureFlagsImpl")
            || className.endsWith("/FakeFeatureFlagsImpl");
}
