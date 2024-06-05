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

import com.android.hoststubgen.HostStubGenErrors
import com.android.hoststubgen.asm.ClassNodes

private const val REASON = "demoted, not in intersect jars"

/**
 * An [OutputFilter] that will restrict what to put in stub to only what shows up in "intersecting
 * jar" files.
 *
 * For example, if the Android public API stub jar is provided, then the HostStubGen's output
 * stub will be restricted to public APIs.
 */
class StubIntersectingFilter(
        private val errors: HostStubGenErrors,
        /**
         * If a class / field / method is not in any of these jars, then we will not put it in
         * stub.
         */
        private val intersectingJars: Map<String, ClassNodes>,
        fallback: OutputFilter,
) : DelegatingFilter(fallback) {
    private inline fun exists(predicate: (ClassNodes) -> Boolean): Boolean {
        intersectingJars.forEach { entry ->
            if (predicate(entry.value)) {
                return true
            }
        }
        return false
    }

    /**
     * If [origPolicy] is less than "Stub", then return it as-is.
     *
     * Otherwise, call [inStubChecker] to see if the API is in any of [intersectingJars].
     * If yes, then return [origPolicy] as-is. Otherwise, demote to "Keep".
     */
    private fun intersectWithStub(
            origPolicy: FilterPolicyWithReason,
            inStubChecker: () -> Boolean,
    ): FilterPolicyWithReason {
        if (origPolicy.policy.needsInStub) {
            // Only check the stub jars, when the class is supposed to be in stub otherwise.
            if (!inStubChecker()) {
                return origPolicy.demoteToKeep(REASON)
            }
        }
        return origPolicy
    }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        return intersectWithStub(super.getPolicyForClass(className)) {
            exists { classes -> classes.findClass(className) != null }
        }
    }

    override fun getPolicyForField(
            className: String,
            fieldName: String
    ): FilterPolicyWithReason {
        return intersectWithStub(super.getPolicyForField(className, fieldName)) {
            exists { classes -> classes.findField(className, fieldName) != null }
        }
    }

    override fun getPolicyForMethod(
            className: String,
            methodName: String,
            descriptor: String
    ): FilterPolicyWithReason {
        return intersectWithStub(super.getPolicyForMethod(className, methodName, descriptor)) {
            exists { classes -> classes.findMethod(className, methodName, descriptor) != null }
        }
    }
}