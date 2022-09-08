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
import androidx.navigation.NamedNavArgument
import com.android.settingslib.spa.framework.util.normalize

const val INJECT_ENTRY_NAME = "INJECT"
const val ROOT_ENTRY_NAME = "ROOT"

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
data class SettingsPage(val name: String = "", val arguments: Bundle? = null) {
    override fun toString(): String {
        val argsStr = arguments?.toString()?.removeRange(0, 6) ?: ""
        return name + argsStr
    }

    companion object {
        fun create(
            name: String,
            parameter: List<NamedNavArgument> = emptyList(),
            arguments: Bundle? = null
        ): SettingsPage {
            return SettingsPage(name, parameter.normalize(arguments))
        }
    }
}

/**
 * Defines data of a Settings entry.
 */
data class SettingsEntry(
    // The unique id of this entry.
    // By default, it is computed by name + owner + fromPage + toPage
    val id: String,

    // The display name of this entry, which is used to be shown in hierarchy.
    // By default, it is computed by name + owner
    val displayName: String,

    val name: String,
    val owner: SettingsPage,

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
) {
    override fun toString(): String {
        return displayName + "(${fromPage?.toString()}->${toPage?.toString()})"
    }
}

/**
 * The helper to build a Settings Entry instance.
 */
class SettingsEntryBuilder(private val name: String, private val owner: SettingsPage) {
    private var uniqueId: String? = null
    private var displayName: String? = null
    private var fromPage: SettingsPage? = null
    private var toPage: SettingsPage? = null
    private var isAllowSearch: Boolean? = null

    private var searchDataFn: () -> SearchData? = { null }
    private var uiLayoutFn: (@Composable () -> Unit) = {}

    fun build(): SettingsEntry {
        return SettingsEntry(
            id = computeUniqueId(),
            displayName = computeDisplayName(),
            name = name,
            owner = owner,

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

    private fun computeUniqueId(): String =
        uniqueId ?: "$owner:$name" + fromPage?.toString() + toPage?.toString()

    private fun computeDisplayName(): String = displayName ?: "$owner:$name"

    private fun computeSearchable(): Boolean = isAllowSearch ?: false

    companion object {
        fun create(entryName: String, owner: SettingsPage): SettingsEntryBuilder {
            return SettingsEntryBuilder(entryName, owner)
        }

        fun createLinkFrom(entryName: String, owner: SettingsPage): SettingsEntryBuilder {
            return create(entryName, owner).setLink(fromPage = owner)
        }

        fun createLinkTo(entryName: String, owner: SettingsPage): SettingsEntryBuilder {
            return create(entryName, owner).setLink(toPage = owner)
        }

        fun createInject(owner: SettingsPage): SettingsEntryBuilder {
            return createLinkTo(INJECT_ENTRY_NAME, owner)
        }

        fun createRoot(page: SettingsPage): SettingsEntryBuilder {
            return createLinkTo(ROOT_ENTRY_NAME, page)
        }
    }
}
