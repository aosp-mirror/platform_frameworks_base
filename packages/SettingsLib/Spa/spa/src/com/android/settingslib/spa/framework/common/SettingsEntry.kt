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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.android.settingslib.spa.framework.compose.LocalNavController

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

/**
 * Defines data of a Settings entry.
 */
data class SettingsEntry(
    // The unique id of this entry, which is computed by name + owner + fromPage + toPage.
    val id: String,

    // The name of the entry, which is used to compute the unique id, and need to be stable.
    private val name: String,

    // The label of the entry, for better readability.
    // For migration mapping, this should match the android:key field in the old architecture
    // if applicable.
    val label: String,

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
