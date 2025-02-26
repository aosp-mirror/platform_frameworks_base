/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.platform.test.ravenwood.ravenhelper.policytoannot

import com.android.hoststubgen.filters.FilterPolicy


/**
 * This class knows about the Ravenwood annotations.
 */
class Annotations {
    enum class Target {
        Class,
        Field,
        Method,
    }

    fun get(policy: FilterPolicy, target: Target): String? {
        return when (policy) {
            FilterPolicy.Keep ->
                if (target == Target.Class) {
                    "@android.ravenwood.annotation.RavenwoodKeepPartialClass"
                } else {
                    "@android.ravenwood.annotation.RavenwoodKeep"
                }
            FilterPolicy.KeepClass ->
                "@android.ravenwood.annotation.RavenwoodKeepWholeClass"
            FilterPolicy.Substitute ->
                "@android.ravenwood.annotation.RavenwoodReplace"
            FilterPolicy.Redirect ->
                "@android.ravenwood.annotation.RavenwoodRedirect"
            FilterPolicy.Throw ->
                "@android.ravenwood.annotation.RavenwoodThrow"
            FilterPolicy.Ignore ->
                "@android.ravenwood.annotation.RavenwoodIgnore"
            FilterPolicy.Remove ->
                "@android.ravenwood.annotation.RavenwoodRemove"
            FilterPolicy.AnnotationAllowed -> null // Can't convert to an annotation.
        }
    }

    private fun withArg(annot: String, arg: String): String {
        return "@$annot(\"$arg\")"
    }

    fun getClassLoadHookAnnotation(arg: String): String {
        return withArg("android.ravenwood.annotation.RavenwoodClassLoadHook", arg)
    }

    fun getRedirectionClassAnnotation(arg: String): String {
        return withArg("android.ravenwood.annotation.RavenwoodRedirectionClass", arg)
    }
}

