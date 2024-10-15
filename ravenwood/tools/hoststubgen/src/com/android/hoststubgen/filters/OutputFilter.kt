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
 * Base class for "filters", which decides what APIs should go to the stub / impl jars.
 */
abstract class OutputFilter {
    /**
     * Filters are stacked over one another. This fields contains the "outermost" filter in a
     * filter stack chain.
     *
     * Subclasses must use this filter to get a policy, when they need to infer a policy
     * from the policy of another API.
     *
     * For example, [ClassWidePolicyPropagatingFilter] needs to check the policy of the enclosing
     * class to propagate "class-wide" policies, but when it does so, it can't just use
     * `this.getPolicyForClass()` because that wouldn't return policies decided by "outer"
     * filters. Instead, it uses [outermostFilter.getPolicyForClass()].
     *
     * Note, [outermostFilter] can be itself, so make sure not to cause infinity recursions when
     * using it.
     */
    open var outermostFilter: OutputFilter = this

    abstract fun getPolicyForClass(className: String): FilterPolicyWithReason

    abstract fun getPolicyForField(className: String, fieldName: String): FilterPolicyWithReason

    abstract fun getPolicyForMethod(
            className: String,
            methodName: String,
            descriptor: String,
            ): FilterPolicyWithReason

    /**
     * If a given method is a substitute-from method, return the substitute-to method name.
     *
     * The substitute-to and from methods must have the same signature, in the same class.
     */
    open fun getRenameTo(className: String, methodName: String, descriptor: String): String? {
        return null
    }

    /**
     * Return a "redirection class" name for a given class.
     *
     * The result will be in a JVM internal form. (e.g. uses '/'s instead of '.'s)
     *
     * (which corresponds to @HostSideTestRedirectClass of the standard annotations.)
     */
    open fun getRedirectionClass(className: String): String? {
        return null
    }

    /**
     * Return a "class load hook" class name for a given class.
     *
     * (which corresponds to @HostSideTestClassLoadHook of the standard annotations.)
     */
    open fun getClassLoadHooks(className: String): List<String> {
        return emptyList()
    }

    /**
     * Return the "method call hook" class name.
     *
     * The class has to have a function with the following signature:
     * `public static void onMethodCalled(Class<?> clazz, String name, String descriptor)`.
     */
    open fun getMethodCallHooks(className: String, methodName: String, descriptor: String):
            List<String> {
        return emptyList()
    }

    /**
     * Take a class (internal) name. If the class needs to be renamed, return the new name.
     * This is used by [FilterRemapper].
     */
    open fun remapType(className: String): String? {
        return null
    }

    data class MethodReplaceTarget(val className: String, val methodName: String)

    /**
     * Return if this filter may return non-null from [getMethodCallReplaceTo].
     * (Used for a small optimization)
     */
    open fun hasAnyMethodCallReplace(): Boolean {
        return false
    }

    /**
     * If a method call should be forwarded to another method, return the target's class / method.
     */
    open fun getMethodCallReplaceTo(
        callerClassName: String,
        callerMethodName: String,
        className: String,
        methodName: String,
        descriptor: String,
    ): MethodReplaceTarget? {
        return null
    }
}
