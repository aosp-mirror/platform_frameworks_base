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

import com.android.hoststubgen.asm.toHumanReadableClassName
import com.android.hoststubgen.utils.Trie

/**
 * Filter to apply a policy to classes inside a package, either directly or indirectly.
 */
class PackageFilter(
    fallback: OutputFilter
) : DelegatingFilter(fallback) {

    private val mPackagePolicies = PackagePolicyTrie()

    // We want to pick the most specific filter for a package name.
    // Since any package with a matching prefix is a valid match, we can use a prefix tree
    // to help us find the nearest matching filter.
    private class PackagePolicyTrie : Trie<String, String, FilterPolicyWithReason>() {
        // Split package name into individual component
        override fun splitToComponents(key: String): Iterator<String> {
            return key.split('.').iterator()
        }
    }

    private fun getPackageKey(packageName: String): String {
        return packageName.toHumanReadableClassName()
    }

    private fun getPackageKeyFromClass(className: String): String {
        val clazz = className.toHumanReadableClassName()
        val idx = clazz.lastIndexOf('.')
        return if (idx >= 0) clazz.substring(0, idx) else ""
    }

    /**
     * Add a policy to all classes inside a package, either directly or indirectly.
     */
    fun addPolicy(packageName: String, policy: FilterPolicyWithReason) {
        mPackagePolicies[getPackageKey(packageName)] = policy
    }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        return mPackagePolicies[getPackageKeyFromClass(className)]
            ?: super.getPolicyForClass(className)
    }
}
