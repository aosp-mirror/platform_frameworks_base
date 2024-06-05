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

class DefaultHookInjectingFilter(
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

    override fun getClassLoadHooks(className: String): List<String> {
        return addLists(super.getClassLoadHooks(className), defaultClassLoadHookAsList)
    }

    override fun getMethodCallHooks(
        className: String,
        methodName: String,
        descriptor: String
    ): List<String> {
        return addLists(
            super.getMethodCallHooks(className, methodName, descriptor),
            defaultMethodCallHookAsList,
            )
    }
}