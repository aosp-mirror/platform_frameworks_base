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

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.navigation.NamedNavArgument
import com.android.settingslib.spa.framework.BrowseActivity
import com.android.settingslib.spa.framework.util.isRuntimeParam
import com.android.settingslib.spa.framework.util.navLink
import com.android.settingslib.spa.framework.util.normalize

private const val NULL_PAGE_NAME = "NULL"

/**
 * Defines data to identify a Settings page.
 */
data class SettingsPage(
    // The unique id of this page, which is computed by sppName + normalized(arguments)
    val id: String,

    // The name of the page provider, who creates this page. It is used to compute the unique id.
    val sppName: String,

    // The display name of the page, for better readability.
    val displayName: String,

    // The parameters defined in its page provider.
    val parameter: List<NamedNavArgument> = emptyList(),

    // The arguments of this page.
    val arguments: Bundle? = null,
) {
    companion object {
        fun createNull(): SettingsPage {
            return create(NULL_PAGE_NAME)
        }

        fun create(
            name: String,
            displayName: String? = null,
            parameter: List<NamedNavArgument> = emptyList(),
            arguments: Bundle? = null
        ): SettingsPage {
            return SettingsPage(
                id = id(name, parameter, arguments),
                sppName = name,
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
        return sppName == SppName
    }

    fun buildRoute(): String {
        return sppName + parameter.navLink(arguments)
    }

    fun hasRuntimeParam(): Boolean {
        for (navArg in parameter) {
            if (navArg.isRuntimeParam()) return true
        }
        return false
    }

    fun createBrowseIntent(entryId: String? = null): Intent? {
        val context = SpaEnvironmentFactory.instance.appContext
        val browseActivityClass = SpaEnvironmentFactory.instance.browseActivityClass
        return createBrowseIntent(context, browseActivityClass, entryId)
    }

    fun createBrowseIntent(
        context: Context?,
        browseActivityClass: Class<out Activity>?,
        entryId: String? = null
    ): Intent? {
        if (!isBrowsable(context, browseActivityClass)) return null
        return Intent().setComponent(ComponentName(context!!, browseActivityClass!!))
            .apply {
                putExtra(BrowseActivity.KEY_DESTINATION, buildRoute())
                if (entryId != null) {
                    putExtra(BrowseActivity.KEY_HIGHLIGHT_ENTRY, entryId)
                }
            }
    }

    fun createBrowseAdbCommand(
        context: Context?,
        browseActivityClass: Class<out Activity>?,
        entryId: String? = null
    ): String? {
        if (!isBrowsable(context, browseActivityClass)) return null
        val packageName = context!!.packageName
        val activityName = browseActivityClass!!.name.replace(packageName, "")
        val destinationParam = " -e ${BrowseActivity.KEY_DESTINATION} ${buildRoute()}"
        val highlightParam =
            if (entryId != null) " -e ${BrowseActivity.KEY_HIGHLIGHT_ENTRY} $entryId" else ""
        return "adb shell am start -n $packageName/$activityName$destinationParam$highlightParam"
    }

    fun isBrowsable(context: Context?, browseActivityClass: Class<out Activity>?): Boolean {
        return context != null &&
            browseActivityClass != null &&
            !isCreateBy(NULL_PAGE_NAME) &&
            !hasRuntimeParam()
    }
}

fun SettingsPageProvider.createSettingsPage(arguments: Bundle? = null): SettingsPage {
    return SettingsPage.create(
        name = name,
        displayName = displayName,
        parameter = parameter,
        arguments = arguments
    )
}

fun String.toHashId(): String {
    return this.hashCode().toUInt().toString(36)
}
