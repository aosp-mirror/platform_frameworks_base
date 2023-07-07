/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.util

import android.os.Bundle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType

const val RUNTIME_PARAM_PREFIX = "rt_"
const val UNSET_PARAM_PREFIX = "unset_"
const val UNSET_PARAM_VALUE = "[unset]"

fun List<NamedNavArgument>.navRoute(): String {
    return this.joinToString("") { argument -> "/{${argument.name}}" }
}

fun List<NamedNavArgument>.navLink(arguments: Bundle? = null): String {
    return normalizeArgList(arguments).joinToString("") { arg -> "/$arg" }
}

fun List<NamedNavArgument>.normalizeArgList(
    arguments: Bundle? = null,
    eraseRuntimeValues: Boolean = false
): List<String> {
    val argsArray = mutableListOf<String>()
    for (navArg in this) {
        if (eraseRuntimeValues && navArg.isRuntimeParam()) continue
        if (arguments == null || !arguments.containsKey(navArg.name)) {
            argsArray.add(UNSET_PARAM_VALUE)
            continue
        }
        when (navArg.argument.type) {
            NavType.StringType -> {
                argsArray.add(arguments.getString(navArg.name, ""))
            }
            NavType.IntType -> {
                argsArray.add(arguments.getInt(navArg.name).toString())
            }
        }
    }
    return argsArray
}

fun List<NamedNavArgument>.normalize(
    arguments: Bundle? = null,
    eraseRuntimeValues: Boolean = false
): Bundle? {
    if (this.isEmpty()) return null
    val normArgs = Bundle()
    for (navArg in this) {
        // Erase value of runtime parameters.
        if (eraseRuntimeValues && navArg.isRuntimeParam()) {
            normArgs.putString(navArg.name, null)
            continue
        }

        when (navArg.argument.type) {
            NavType.StringType -> {
                val value = arguments?.getString(navArg.name)
                if (value != null)
                    normArgs.putString(navArg.name, value)
                else
                    normArgs.putString(UNSET_PARAM_PREFIX + navArg.name, null)
            }
            NavType.IntType -> {
                if (arguments != null && arguments.containsKey(navArg.name))
                    normArgs.putInt(navArg.name, arguments.getInt(navArg.name))
                else
                    normArgs.putString(UNSET_PARAM_PREFIX + navArg.name, null)
            }
        }
    }
    return normArgs
}

fun List<NamedNavArgument>.getStringArg(name: String, arguments: Bundle? = null): String? {
    if (this.containsStringArg(name) && arguments != null) {
        return arguments.getString(name)
    }
    return null
}

fun List<NamedNavArgument>.getIntArg(name: String, arguments: Bundle? = null): Int? {
    if (this.containsIntArg(name) && arguments != null && arguments.containsKey(name)) {
        return arguments.getInt(name)
    }
    return null
}

fun List<NamedNavArgument>.containsStringArg(name: String): Boolean {
    for (navArg in this) {
        if (navArg.argument.type == NavType.StringType && navArg.name == name) return true
    }
    return false
}

fun List<NamedNavArgument>.containsIntArg(name: String): Boolean {
    for (navArg in this) {
        if (navArg.argument.type == NavType.IntType && navArg.name == name) return true
    }
    return false
}

fun NamedNavArgument.isRuntimeParam(): Boolean {
    return this.name.startsWith(RUNTIME_PARAM_PREFIX)
}
