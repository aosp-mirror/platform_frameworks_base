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
import com.android.settingslib.spa.framework.BrowseActivity
import com.android.settingslib.spa.framework.util.navLink

/**
 * Defines data to identify a Settings page.
 */
data class SettingsPage(
    // The name of the page, which is used to compute the unique id, and need to be stable.
    val name: String,

    // The display name of the page, for better readability.
    val displayName: String,

    // Defined parameters of this page.
    val parameter: List<NamedNavArgument> = emptyList(),

    // The arguments of this page.
    val arguments: Bundle? = null,
) {
    companion object {
        fun create(
            name: String,
            parameter: List<NamedNavArgument> = emptyList(),
            arguments: Bundle? = null
        ): SettingsPage {
            return SettingsPage(name, name, parameter, arguments)
        }
    }

    // The unique id of this page, which is computed by name + normalized(arguments)
    fun id(): String {
        val normArguments = parameter.normalize(arguments)
        return "$name:${normArguments?.toString()}".toHashId()
    }

    // Returns if this Settings Page is created by the given Spp.
    fun isCreateBy(SppName: String): Boolean {
        return name == SppName
    }

    fun formatArguments(): String {
        val normalizedArguments = parameter.normalize(arguments)
        if (normalizedArguments == null || normalizedArguments.isEmpty) return "[No arguments]"
        return normalizedArguments.toString().removeRange(0, 6)
    }

    fun formatDisplayTitle(): String {
        return "$displayName ${formatArguments()}"
    }

    fun buildRoute(highlightEntryId: String? = null): String {
        val highlightParam =
            if (highlightEntryId == null)
                ""
            else
                "?${BrowseActivity.HIGHLIGHT_ENTRY_PARAM_NAME}=$highlightEntryId"
        return name + parameter.navLink(arguments) + highlightParam
    }

    fun hasRuntimeParam(): Boolean {
        return parameter.hasRuntimeParam(arguments)
    }
}

private fun List<NamedNavArgument>.normalize(arguments: Bundle? = null): Bundle? {
    if (this.isEmpty()) return null
    val normArgs = Bundle()
    for (navArg in this) {
        when (navArg.argument.type) {
            NavType.StringType -> {
                val value = arguments?.getString(navArg.name)
                if (value != null)
                    normArgs.putString(navArg.name, value)
                else
                    normArgs.putString("unset_" + navArg.name, null)
            }
            NavType.IntType -> {
                if (arguments != null && arguments.containsKey(navArg.name))
                    normArgs.putInt(navArg.name, arguments.getInt(navArg.name))
                else
                    normArgs.putString("unset_" + navArg.name, null)
            }
        }
    }
    return normArgs
}

private fun List<NamedNavArgument>.hasRuntimeParam(arguments: Bundle? = null): Boolean {
    for (navArg in this) {
        if (arguments == null || !arguments.containsKey(navArg.name)) return true
    }
    return false
}

fun String.toHashId(): String {
    return this.hashCode().toString(36)
}
