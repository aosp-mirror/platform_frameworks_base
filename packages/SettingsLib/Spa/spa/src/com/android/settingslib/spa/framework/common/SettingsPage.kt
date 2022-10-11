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
import com.android.settingslib.spa.framework.util.isRuntimeParam
import com.android.settingslib.spa.framework.util.navLink
import com.android.settingslib.spa.framework.util.normalize

/**
 * Defines data to identify a Settings page.
 */
data class SettingsPage(
    // The unique id of this page, which is computed by name + normalized(arguments)
    val id: String,

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
            displayName: String? = null,
            parameter: List<NamedNavArgument> = emptyList(),
            arguments: Bundle? = null
        ): SettingsPage {
            return SettingsPage(
                id = id(name, parameter, arguments),
                name = name,
                displayName = displayName ?: name,
                parameter = parameter,
                arguments = arguments
            )
        }

        // The unique id of this page, which is computed by name + normalized(arguments)
        private fun id(
            name: String,
            parameter: List<NamedNavArgument> = emptyList(),
            arguments: Bundle? = null
        ): String {
            val normArguments = parameter.normalize(arguments)
            return "$name:${normArguments?.toString()}".toHashId()
        }
    }

    // Returns if this Settings Page is created by the given Spp.
    fun isCreateBy(SppName: String): Boolean {
        return name == SppName
    }

    fun formatArguments(): String {
        val normArguments = parameter.normalize(arguments)
        if (normArguments == null || normArguments.isEmpty) return "[No arguments]"
        return normArguments.toString().removeRange(0, 6)
    }

    fun formatDisplayTitle(): String {
        return "$displayName ${formatArguments()}"
    }

    fun buildRoute(): String {
        return name + parameter.navLink(arguments)
    }

    fun hasRuntimeParam(): Boolean {
        for (navArg in parameter) {
            if (navArg.isRuntimeParam()) return true
        }
        return false
    }
}

fun String.toHashId(): String {
    return this.hashCode().toUInt().toString(36)
}
