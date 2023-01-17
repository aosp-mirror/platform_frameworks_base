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

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.android.settingslib.spa.framework.compose.LocalNavController

private const val INJECT_ENTRY_NAME = "INJECT"
private const val ROOT_ENTRY_NAME = "ROOT"

interface EntryData {
    val pageId: String?
        get() = null
    val entryId: String?
        get() = null
    val isHighlighted: Boolean
        get() = false
    val arguments: Bundle?
        get() = null
}

val LocalEntryDataProvider =
    compositionLocalOf<EntryData> { object : EntryData {} }

typealias UiLayerRenderer = @Composable (arguments: Bundle?) -> Unit
typealias StatusDataGetter = (arguments: Bundle?) -> EntryStatusData?
typealias SearchDataGetter = (arguments: Bundle?) -> EntrySearchData?
typealias SliceDataGetter = (sliceUri: Uri, arguments: Bundle?) -> EntrySliceData?

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

    // Indicate whether the search indexing data of entry is dynamic.
    val isSearchDataDynamic: Boolean = false,

    // Indicate whether the status of entry is mutable.
    // If so, for instance, we'll reindex its status for search.
    val hasMutableStatus: Boolean = false,

    // Indicate whether the entry has SliceProvider support.
    val hasSliceSupport: Boolean = false,

    /**
     * ========================================
     * Defines entry APIs to get data here.
     * ========================================
     */

    /**
     * API to get the status data of the entry, such as isDisabled / isSwitchOff.
     * Returns null if this entry do NOT have any status.
     */
    private val statusDataImpl: StatusDataGetter = { null },

    /**
     * API to get Search indexing data for this entry, such as title / keyword.
     * Returns null if this entry do NOT support search.
     */
    private val searchDataImpl: SearchDataGetter = { null },

    /**
     * API to get Slice data of this entry. The Slice data is implemented as a LiveData,
     * and is associated with the Slice's lifecycle (pin / unpin) by the framework.
     */
    private val sliceDataImpl: SliceDataGetter = { _: Uri, _: Bundle? -> null },

    /**
     * API to Render UI of this entry directly. For now, we use it in the internal injection, to
     * support the case that the injection page owner wants to maintain both data and UI of the
     * injected entry. In the long term, we may deprecate the @Composable Page() API in SPP, and
     * use each entries' UI rendering function in the page instead.
     */
    private val uiLayoutImpl: UiLayerRenderer = {},
) {
    fun containerPage(): SettingsPage {
        // The Container page of the entry, which is the from-page or
        // the owner-page if from-page is unset.
        return fromPage ?: owner
    }

    private fun fullArgument(runtimeArguments: Bundle? = null): Bundle {
        return Bundle().apply {
            if (owner.arguments != null) putAll(owner.arguments)
            // Put runtime args later, which can override page args.
            if (runtimeArguments != null) putAll(runtimeArguments)
        }
    }

    fun getStatusData(runtimeArguments: Bundle? = null): EntryStatusData? {
        return statusDataImpl(fullArgument(runtimeArguments))
    }

    fun getSearchData(runtimeArguments: Bundle? = null): EntrySearchData? {
        return searchDataImpl(fullArgument(runtimeArguments))
    }

    fun getSliceData(sliceUri: Uri, runtimeArguments: Bundle? = null): EntrySliceData? {
        return sliceDataImpl(sliceUri, fullArgument(runtimeArguments))
    }

    @Composable
    fun UiLayout(runtimeArguments: Bundle? = null) {
        val arguments = remember { fullArgument(runtimeArguments) }
        CompositionLocalProvider(provideLocalEntryData(arguments)) {
            uiLayoutImpl(arguments)
        }
    }

    @Composable
    private fun provideLocalEntryData(arguments: Bundle): ProvidedValue<EntryData> {
        val controller = LocalNavController.current
        return LocalEntryDataProvider provides remember {
            object : EntryData {
                override val pageId = containerPage().id
                override val entryId = id
                override val isHighlighted = controller.highlightEntryId == id
                override val arguments = arguments
            }
        }
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
    private var isSearchDataDynamic: Boolean = false
    private var hasMutableStatus: Boolean = false
    private var hasSliceSupport: Boolean = false

    // Functions
    private var uiLayoutFn: UiLayerRenderer = { }
    private var statusDataFn: StatusDataGetter = { null }
    private var searchDataFn: SearchDataGetter = { null }
    private var sliceDataFn: SliceDataGetter = { _: Uri, _: Bundle? -> null }

    fun build(): SettingsEntry {
        val page = fromPage ?: owner
        val isEnabled = page.isEnabled()
        return SettingsEntry(
            id = id(),
            name = name,
            owner = owner,
            displayName = displayName,

            // linking data
            fromPage = fromPage,
            toPage = toPage,

            // attributes
            isAllowSearch = isEnabled && isAllowSearch,
            isSearchDataDynamic = isSearchDataDynamic,
            hasMutableStatus = hasMutableStatus,
            hasSliceSupport = isEnabled && hasSliceSupport,

            // functions
            statusDataImpl = statusDataFn,
            searchDataImpl = searchDataFn,
            sliceDataImpl = sliceDataFn,
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

    fun setIsSearchDataDynamic(isDynamic: Boolean): SettingsEntryBuilder {
        this.isSearchDataDynamic = isDynamic
        return this
    }

    fun setHasMutableStatus(hasMutableStatus: Boolean): SettingsEntryBuilder {
        this.hasMutableStatus = hasMutableStatus
        return this
    }

    fun setMacro(fn: (arguments: Bundle?) -> EntryMacro): SettingsEntryBuilder {
        setStatusDataFn { fn(it).getStatusData() }
        setSearchDataFn { fn(it).getSearchData() }
        setUiLayoutFn {
            val macro = remember { fn(it) }
            macro.UiLayout()
        }
        return this
    }

    fun setStatusDataFn(fn: StatusDataGetter): SettingsEntryBuilder {
        this.statusDataFn = fn
        return this
    }

    fun setSearchDataFn(fn: SearchDataGetter): SettingsEntryBuilder {
        this.searchDataFn = fn
        this.isAllowSearch = true
        return this
    }

    fun clearSearchDataFn(): SettingsEntryBuilder {
        this.searchDataFn = { null }
        this.isAllowSearch = false
        return this
    }

    fun setSliceDataFn(fn: SliceDataGetter): SettingsEntryBuilder {
        this.sliceDataFn = fn
        this.hasSliceSupport = true
        return this
    }

    fun setUiLayoutFn(fn: UiLayerRenderer): SettingsEntryBuilder {
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
