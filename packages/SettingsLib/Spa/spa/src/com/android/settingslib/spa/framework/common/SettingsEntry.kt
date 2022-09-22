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
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.android.settingslib.spa.framework.BrowseActivity.Companion.HIGHLIGHT_ENTRY_PARAM_NAME

const val INJECT_ENTRY_NAME = "INJECT"
const val ROOT_ENTRY_NAME = "ROOT"

/**
 * Defines data of a Settings entry.
 */
data class SettingsEntry(
    // The unique id of this entry, which is computed by name + owner + fromPage + toPage.
    val id: String,

    // The name of the page, which is used to compute the unique id, and need to be stable.
    private val name: String,

    // The display name of the page, for better readability.
    val displayName: String,

    // The owner page of this entry.
    val owner: SettingsPage,

    // Defines linking of Settings entries
    val fromPage: SettingsPage? = null,
    val toPage: SettingsPage? = null,

    /**
     * ========================================
     * Defines entry attributes here.
     * ========================================
     */
    val isAllowSearch: Boolean = false,

    /**
     * ========================================
     * Defines entry APIs to get data here.
     * ========================================
     */

    /**
     * API to get Search related data for this entry.
     * Returns null if this entry is not available for the search at the moment.
     */
    private val searchDataImpl: (arguments: Bundle?) -> EntrySearchData? = { null },

    /**
     * API to Render UI of this entry directly. For now, we use it in the internal injection, to
     * support the case that the injection page owner wants to maintain both data and UI of the
     * injected entry. In the long term, we may deprecate the @Composable Page() API in SPP, and
     * use each entries' UI rendering function in the page instead.
     */
    private val uiLayoutImpl: (@Composable (arguments: Bundle?) -> Unit) = {},
) {
    fun formatContent(): String {
        val content = listOf(
            "id = $id",
            "owner = ${owner.formatDisplayTitle()}",
            "linkFrom = ${fromPage?.formatDisplayTitle()}",
            "linkTo = ${toPage?.formatDisplayTitle()}",
            "${getSearchData()?.format()}",
        )
        return content.joinToString("\n")
    }

    fun displayTitle(): String {
        return "${owner.displayName}:$displayName"
    }

    private fun containerPage(): SettingsPage {
        // The Container page of the entry, which is the from-page or
        // the owner-page if from-page is unset.
        return fromPage ?: owner
    }

    fun buildRoute(): String {
        return containerPage().buildRoute(id)
    }

    fun hasRuntimeParam(): Boolean {
        return containerPage().hasRuntimeParam()
    }

    private fun fullArgument(runtimeArguments: Bundle? = null): Bundle {
        val arguments = Bundle()
        if (owner.arguments != null) arguments.putAll(owner.arguments)
        // Put runtime args later, which can override page args.
        if (runtimeArguments != null) arguments.putAll(runtimeArguments)
        return arguments
    }

    fun getSearchData(runtimeArguments: Bundle? = null): EntrySearchData? {
        return searchDataImpl(fullArgument(runtimeArguments))
    }

    @Composable
    fun UiLayout(runtimeArguments: Bundle? = null) {
        val context = LocalContext.current
        val highlight = rememberSaveable {
            mutableStateOf(runtimeArguments?.getString(HIGHLIGHT_ENTRY_PARAM_NAME) == id)
        }
        if (highlight.value) {
            highlight.value = false
            // TODO: Add highlight entry logic
            Toast.makeText(context, "entry $id highlighted", Toast.LENGTH_SHORT).show()
        }
        uiLayoutImpl(fullArgument(runtimeArguments))
    }
}

/**
 * The helper to build a Settings Entry instance.
 */
class SettingsEntryBuilder(private val name: String, private val owner: SettingsPage) {
    private var displayName = name
    private var fromPage: SettingsPage? = null
    private var toPage: SettingsPage? = null

    // Attributes
    private var isAllowSearch: Boolean = false

    // Functions
    private var searchDataFn: (arguments: Bundle?) -> EntrySearchData? = { null }
    private var uiLayoutFn: (@Composable (arguments: Bundle?) -> Unit) = { }

    fun build(): SettingsEntry {
        return SettingsEntry(
            id = id(),
            name = name,
            owner = owner,
            displayName = displayName,

            // linking data
            fromPage = fromPage,
            toPage = toPage,

            // attributes
            isAllowSearch = isAllowSearch,

            // functions
            searchDataImpl = searchDataFn,
            uiLayoutImpl = uiLayoutFn,
        )
    }

    fun setDisplayName(displayName: String): SettingsEntryBuilder {
        this.displayName = displayName
        return this
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

    fun setMarco(fn: (arguments: Bundle?) -> EntryMarco): SettingsEntryBuilder {
        setSearchDataFn { fn(it).getSearchData() }
        setUiLayoutFn {
            val marco = remember { fn(it) }
            marco.UiLayout()
        }
        return this
    }

    fun setSearchDataFn(fn: (arguments: Bundle?) -> EntrySearchData?): SettingsEntryBuilder {
        this.searchDataFn = fn
        return this
    }

    fun setUiLayoutFn(fn: @Composable (arguments: Bundle?) -> Unit): SettingsEntryBuilder {
        this.uiLayoutFn = fn
        return this
    }

    // The unique id of this entry, which is computed by name + owner + fromPage + toPage.
    private fun id(): String {
        return "$name:${owner.id}(${fromPage?.id}-${toPage?.id})".toHashId()
    }

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

        fun create(owner: SettingsPage, entryName: String, displayName: String? = null):
            SettingsEntryBuilder {
            return SettingsEntryBuilder(entryName, owner).setDisplayName(displayName ?: entryName)
        }

        fun createInject(owner: SettingsPage, displayName: String? = null): SettingsEntryBuilder {
            val name = displayName ?: "${INJECT_ENTRY_NAME}_${owner.displayName}"
            return createLinkTo(INJECT_ENTRY_NAME, owner).setDisplayName(name)
        }

        fun createRoot(owner: SettingsPage, displayName: String? = null): SettingsEntryBuilder {
            val name = displayName ?: "${ROOT_ENTRY_NAME}_${owner.displayName}"
            return createLinkTo(ROOT_ENTRY_NAME, owner).setDisplayName(name)
        }
    }
}
