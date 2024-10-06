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
 * Base class for an [OutputFilter] that uses another filter as a fallback.
 */
abstract class DelegatingFilter(
        // fallback shouldn't be used by subclasses directly, so make it private.
        // They should instead be calling into `super` or `outermostFilter`.
        private val fallback: OutputFilter
) : OutputFilter() {
    init {
        fallback.outermostFilter = this
    }

    /**
     * Returns the outermost filter in a filter chain.
     *
     * When methods in a subclass needs to refer to a policy on an item (class, fields, methods)
     * that are not the "subject" item -- e.g.
     * in [ClassWidePolicyPropagatingFilter.getPolicyForField], when it checks the
     * class policy -- [outermostFilter] must be used, rather than the super's method.
     * The former will always return the correct policy, but the later won't consult outer
     * filters than the current filter.
     */
    override var outermostFilter: OutputFilter = this
        get() = field
        set(value) {
            field = value
            // Propagate to the inner filters.
            fallback.outermostFilter = value
        }

    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        return fallback.getPolicyForClass(className)
    }

    override fun getPolicyForField(
            className: String,
            fieldName: String
    ): FilterPolicyWithReason {
        return fallback.getPolicyForField(className, fieldName)
    }

    override fun getPolicyForMethod(
            className: String,
            methodName: String,
            descriptor: String
    ): FilterPolicyWithReason {
        return fallback.getPolicyForMethod(className, methodName, descriptor)
    }

    override fun getRenameTo(
            className: String,
            methodName: String,
            descriptor: String
    ): String? {
        return fallback.getRenameTo(className, methodName, descriptor)
    }

    override fun getRedirectionClass(className: String): String? {
        return fallback.getRedirectionClass(className)
    }

    override fun getClassLoadHooks(className: String): List<String> {
        return fallback.getClassLoadHooks(className)
    }

    override fun getMethodCallHooks(
        className: String,
        methodName: String,
        descriptor: String
    ): List<String> {
        return fallback.getMethodCallHooks(className, methodName, descriptor)
    }

    override fun remapType(className: String): String? {
        return fallback.remapType(className)
    }

    override fun hasAnyMethodCallReplace(): Boolean {
        return fallback.hasAnyMethodCallReplace()
    }

    override fun getMethodCallReplaceTo(
        callerClassName: String,
        callerMethodName: String,
        className: String,
        methodName: String,
        descriptor: String,
    ): MethodReplaceTarget? {
        return fallback.getMethodCallReplaceTo(
            callerClassName, callerMethodName, className, methodName, descriptor)
    }
}
