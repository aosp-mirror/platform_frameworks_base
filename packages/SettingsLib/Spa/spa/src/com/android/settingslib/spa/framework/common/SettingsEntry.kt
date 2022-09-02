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
import androidx.compose.runtime.Composable

/**
 * Defines data of one Settings entry for Settings search.
 */
data class SearchData(val keyword: String = "")

/**
 * Defines data of one Settings entry for UI rendering.
 */
data class UiData(val title: String = "")

/**
 * Defines data to identify a Settings page.
 */
data class SettingsPage(val name: String = "", val args: Bundle = Bundle.EMPTY)

/**
 * Defines data of a Settings entry.
 */
data class SettingsEntry(
    val name: String,
    val owner: SettingsPage,

    // Generates the unique id of this entry
    val uniqueId: String,

    // Defines linking of Settings entries
    val fromPage: SettingsPage? = null,
    val toPage: SettingsPage? = null,

    /**
     * ========================================
     * Defines entry attributes here.
     * ========================================
     */
    val isAllowSearch: Boolean,

    /**
     * ========================================
     * Defines entry APIs to get data here.
     * ========================================
     */

    /**
     * API to get Search related data for this entry.
     * Returns null if this entry is not available for the search at the moment.
     */
    val searchData: () -> SearchData? = { null },

    /**
     * API to get UI related data for this entry.
     * Returns null if the entry is not render-able.
     */
    val uiData: () -> UiData? = { null },

    /**
     * API to Render UI of this entry directly. For now, we use it in the internal injection, to
     * support the case that the injection page owner wants to maintain both data and UI of the
     * injected entry. In the long term, we may deprecate the @Composable Page() API in SPP, and
     * use each entries' UI rendering function in the page instead.
     */
    val uiLayout: (@Composable () -> Unit) = {},
)

/**
 * The helper to build a Settings Page instance.
 */
class SettingsPageBuilder(private val name: String) {
    private val arguments = Bundle()

    fun pushArgs(args: Bundle?): SettingsPageBuilder {
        if (args != null) arguments.putAll(args)
        return this
    }

    fun pushArg(argName: String, argValue: String?): SettingsPageBuilder {
        if (argValue != null) this.arguments.putString(argName, argValue)
        return this
    }

    fun pushArg(argName: String, argValue: Int?): SettingsPageBuilder {
        if (argValue != null) this.arguments.putInt(argName, argValue)
        return this
    }

    fun build(): SettingsPage {
        return SettingsPage(name, arguments)
    }

    companion object {
        fun create(name: String, args: Bundle? = null): SettingsPageBuilder {
            return SettingsPageBuilder(name).apply {
                pushArgs(args)
            }
        }
    }
}

/**
 * The helper to build a Settings Entry instance.
 */
class SettingsEntryBuilder(private val name: String, private val owner: SettingsPage) {
    private var uniqueId: String? = null
    private var fromPage: SettingsPage? = null
    private var toPage: SettingsPage? = null
    private var isAllowSearch: Boolean? = null

    private var searchDataFn: () -> SearchData? = { null }
    private var uiLayoutFn: (@Composable () -> Unit) = {}

    fun build(): SettingsEntry {
        return SettingsEntry(
            name = name,
            owner = owner,
            uniqueId = computeUniqueId(),

            // linking data
            fromPage = fromPage,
            toPage = toPage,

            // attributes
            isAllowSearch = computeSearchable(),

            // functions
            searchData = searchDataFn,
            uiLayout = uiLayoutFn,
        )
    }

    fun setLink(
        fromPage: SettingsPage? = null,
        toPage: SettingsPage? = null
    ): SettingsEntryBuilder {
        if (fromPage != null) this.fromPage = fromPage
        if (toPage != null) this.toPage = toPage
        return this
    }

    fun setIsAllowSearch(isAllowSearch: Boolean): SettingsEntryBuilder {
        this.isAllowSearch = isAllowSearch
        return this
    }

    fun setSearchDataFn(fn: () -> SearchData?): SettingsEntryBuilder {
        this.searchDataFn = fn
        return this
    }

    fun setUiLayoutFn(fn: @Composable () -> Unit): SettingsEntryBuilder {
        this.uiLayoutFn = fn
        return this
    }

    private fun computeUniqueId(): String = uniqueId ?: (name + owner.toString())

    private fun computeSearchable(): Boolean = isAllowSearch ?: false

    companion object {
        fun create(
            entryName: String,
            ownerPageName: String,
            ownerPageArgs: Bundle? = null
        ): SettingsEntryBuilder {
            val owner = SettingsPageBuilder.create(ownerPageName, ownerPageArgs)
            return SettingsEntryBuilder(entryName, owner.build())
        }

        fun create(entryName: String, owner: SettingsPage): SettingsEntryBuilder {
            return SettingsEntryBuilder(entryName, owner)
        }

        fun createLinkTo(
            entryName: String,
            ownerPageName: String,
            ownerPageArgs: Bundle? = null
        ): SettingsEntryBuilder {
            val owner = SettingsPageBuilder.create(ownerPageName, ownerPageArgs)
            return SettingsEntryBuilder(entryName, owner.build()).setLink(toPage = owner.build())
        }

        fun createLinkTo(entryName: String, owner: SettingsPage): SettingsEntryBuilder {
            return SettingsEntryBuilder(entryName, owner).setLink(toPage = owner)
        }
    }
}
