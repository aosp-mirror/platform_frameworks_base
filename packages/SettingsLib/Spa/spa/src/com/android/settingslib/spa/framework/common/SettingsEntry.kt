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
import com.android.settingslib.spa.framework.BrowseActivity
import com.android.settingslib.spa.framework.util.navLink
import com.android.settingslib.spa.framework.util.normalize

const val INJECT_ENTRY_NAME = "INJECT"
const val ROOT_ENTRY_NAME = "ROOT"
const val ROOT_PAGE_NAME = "Root"

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
data class SettingsPage(
    // The unique id of this page, which is computed by name + normalized(arguments)
    val id: Int,

    // The name of the page, which is used to compute the unique id, and need to be stable.
    val name: String,

    // The display name of the page, for better readability.
    // By default, it is the same as name.
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
            return SettingsPageBuilder(name, parameter).setArguments(arguments).build()
        }
    }

    fun formatArguments(): String {
        val normalizedArguments = parameter.normalize(arguments)
        if (normalizedArguments == null || normalizedArguments.isEmpty) return "[No arguments]"
        return normalizedArguments.toString().removeRange(0, 6)
    }

    fun formatAll(): String {
        return "$displayName ${formatArguments()}"
    }

    fun buildRoute(highlightEntryName: String? = null): String {
        val highlightParam =
            if (highlightEntryName == null)
                ""
            else
                "?${BrowseActivity.HIGHLIGHT_ENTRY_PARAM_NAME}=$highlightEntryName"
        return name + parameter.navLink(arguments) + highlightParam
    }
}

/**
 * Defines data of a Settings entry.
 */
data class SettingsEntry(
    // The unique id of this entry, which is computed by name + owner + fromPage + toPage.
    val id: Int,

    // The name of the page, which is used to compute the unique id, and need to be stable.
    val name: String,

    // The owner page of this entry.
    val owner: SettingsPage,

    // The display name of the entry, for better readability.
    // By default, it is $owner:$name
    val displayName: String,

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
    fun formatAll(): String {
        val content = listOf<String>(
            "owner = ${owner.formatAll()}",
            "linkFrom = ${fromPage?.formatAll()}",
            "linkTo = ${toPage?.formatAll()}",
        )
        return content.joinToString("\n")
    }

    fun buildRoute(): String {
        // Open entry in its fromPage.
        val page = fromPage ?: owner
        return page.buildRoute(name)
    }
}

data class SettingsPageWithEntry(
    val page: SettingsPage,
    val entries: List<SettingsEntry>,
)

class SettingsPageBuilder(
    private val name: String,
    private val parameter: List<NamedNavArgument> = emptyList()
) {
    private var displayName: String? = null
    private var arguments: Bundle? = null

    fun build(): SettingsPage {
        val normArguments = parameter.normalize(arguments)
        return SettingsPage(
            id = "$name:${normArguments?.toString()}".toUniqueId(),
            name = name,
            displayName = displayName ?: name,
            parameter = parameter,
            arguments = arguments,
        )
    }

    fun setArguments(arguments: Bundle?): SettingsPageBuilder {
        this.arguments = arguments
        return this
    }
}

/**
 * The helper to build a Settings Entry instance.
 */
class SettingsEntryBuilder(private val name: String, private val owner: SettingsPage) {
    private var displayName: String? = null
    private var fromPage: SettingsPage? = null
    private var toPage: SettingsPage? = null
    private var isAllowSearch: Boolean? = null

    private var searchDataFn: () -> SearchData? = { null }
    private var uiLayoutFn: (@Composable () -> Unit) = {}

    fun build(): SettingsEntry {
        return SettingsEntry(
            id = "$name:${owner.id}(${fromPage?.id}-${toPage?.id})".toUniqueId(),
            displayName = displayName ?: "${owner.displayName}:$name",
            name = name,
            owner = owner,

            // linking data
            fromPage = fromPage,
            toPage = toPage,

            // attributes
            isAllowSearch = getIsSearchable(),

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

    private fun getIsSearchable(): Boolean = isAllowSearch ?: false

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

        fun createInject(owner: SettingsPage, entryName: String? = null): SettingsEntryBuilder {
            val name = entryName ?: "${INJECT_ENTRY_NAME}_${owner.name}"
            return createLinkTo(name, owner)
        }

        fun createRoot(owner: SettingsPage, entryName: String? = null): SettingsEntryBuilder {
            val name = entryName ?: "${ROOT_ENTRY_NAME}_${owner.name}"
            return createLinkTo(name, owner)
        }
    }
}

private fun String.toUniqueId(): Int {
    return this.hashCode()
}
