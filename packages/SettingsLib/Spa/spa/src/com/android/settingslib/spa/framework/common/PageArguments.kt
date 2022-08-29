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

package com.android.settingslib.spa.framework.common

import android.os.Bundle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType

class PageArguments(
    private val navArgsDef: List<NamedNavArgument>,
    private val rawArgs: Bundle?
) {
    fun normalize(): Bundle {
        if (rawArgs == null) return Bundle.EMPTY
        val normArgs = Bundle()
        for (navArg in navArgsDef) {
            when (navArg.argument.type) {
                NavType.StringType -> {
                    val value = rawArgs.getString(navArg.name)
                    if (value != null) normArgs.putString(navArg.name, value)
                }
                NavType.IntType -> {
                    if (rawArgs.containsKey(navArg.name))
                        normArgs.putInt(navArg.name, rawArgs.getInt(navArg.name))
                }
            }
        }
        return normArgs
    }

    fun navLink(): String{
        if (rawArgs == null) return ""
        val argsArray = mutableListOf<String>()
        for (navArg in navArgsDef) {
            when (navArg.argument.type) {
                NavType.StringType -> {
                    argsArray.add(rawArgs.getString(navArg.name, ""))
                }
                NavType.IntType -> {
                    argsArray.add(rawArgs.getInt(navArg.name).toString())
                }
            }
        }
        return argsArray.joinToString("") {arg -> "/$arg" }
    }

    fun getStringArg(key: String): String? {
        if (rawArgs != null && rawArgs.containsKey(key)) {
            return rawArgs.getString(key)
        }
        return null
    }

    fun getIntArg(key: String): Int? {
        if (rawArgs != null && rawArgs.containsKey(key)) {
            return rawArgs.getInt(key)
        }
        return null
    }
}
