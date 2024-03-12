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
import com.android.hoststubgen.asm.toJvmClassName

/**
 * Filter to apply a policy to classes extending or implementing a class,
 * either directly or indirectly.
 *
 * The policy won't apply to the super class itself.
 */
class SubclassFilter(
        private val classes: ClassNodes,
        fallback: OutputFilter
) : DelegatingFilter(fallback) {
    private val mPolicies: MutableMap<String, FilterPolicyWithReason> = mutableMapOf()

    /**
     * Add a policy to all classes extending or implementing a class, either directly or indirectly.
     */
    fun addPolicy(superClassName: String, policy: FilterPolicyWithReason) {
        mPolicies[superClassName.toJvmClassName()] = policy
    }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        return findPolicyForClass(className) ?: super.getPolicyForClass(className)
    }

    /**
     * Find a policy for a class.
     */
    private fun findPolicyForClass(className: String): FilterPolicyWithReason? {
        val cn = classes.findClass(className) ?: return null

        if (cn.superName == null) {
            return null
        }
        // First, check the direct super class / interfaces.
        mPolicies[cn.superName]?.let { policy ->
            return policy
        }
        cn.interfaces?.forEach { iface ->
            mPolicies[iface]?.let { policy ->
                return policy
            }
        }

        // Then recurse.
        cn.superName?.let { superName ->
            findPolicyForClass(superName)?.let { policy ->
                return policy
            }
        }
        cn.interfaces?.forEach { iface ->
            findPolicyForClass(iface)?.let { policy ->
                return policy
            }
        }
        return null
    }
}