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

import com.android.hoststubgen.addLists
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.isAnnotation

class DefaultHookInjectingFilter(
    val classes: ClassNodes,
    defaultClassLoadHook: String?,
    defaultMethodCallHook: String?,
    fallback: OutputFilter
) : DelegatingFilter(fallback) {
    /**
     * Create a List containing a single element [e], if e != null. Otherwise, returns
     * an empty list.
     */
    private fun toSingleList(e: String?): List<String> {
        if (e == null) {
            return emptyList()
        }
        return listOf(e)
    }

    private val defaultClassLoadHookAsList: List<String> = toSingleList(defaultClassLoadHook)
    private val defaultMethodCallHookAsList: List<String> = toSingleList(defaultMethodCallHook)

    private fun shouldInject(className: String): Boolean {
        // Let's not inject default hooks to annotation classes or inner classes of an annotation
        // class, because these methods could be called at the class load time, which
        // is very confusing, and usually not useful.

        val cn = classes.findClass(className) ?: return false
        if (cn.isAnnotation()) {
            return false
        }
        cn.nestHostClass?.let { nestHostClass ->
            val nestHost = classes.findClass(nestHostClass) ?: return false
            if (nestHost.isAnnotation()) {
                return false
            }
        }
        return true
    }

    override fun getClassLoadHooks(className: String): List<String> {
        val s = super.getClassLoadHooks(className)
        if (!shouldInject(className)) {
            return s
        }
        return addLists(s, defaultClassLoadHookAsList)
    }

    override fun getMethodCallHooks(
        className: String,
        methodName: String,
        descriptor: String
    ): List<String> {
        val s = super.getMethodCallHooks(className, methodName, descriptor)
        if (!shouldInject(className)) {
            return s
        }
        // Don't hook Object methods.
        if (methodName == "finalize" && descriptor == "()V") {
            return s
        }
        if (methodName == "toString" && descriptor == "()Ljava/lang/String;") {
            return s
        }
        if (methodName == "equals" && descriptor == "(Ljava/lang/Object;)Z") {
            return s
        }
        if (methodName == "hashCode" && descriptor == "()I") {
            return s
        }
        return addLists(s, defaultMethodCallHookAsList)
    }
}
