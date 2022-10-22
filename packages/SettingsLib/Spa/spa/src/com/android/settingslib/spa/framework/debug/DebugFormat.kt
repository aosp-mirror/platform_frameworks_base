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

package com.android.settingslib.spa.framework.debug

import com.android.settingslib.spa.framework.common.EntryStatusData
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryRepository

fun EntryStatusData.debugContent(): String {
    val content = listOf(
        "is_disabled = $isDisabled",
        "is_switch_off = $isSwitchOff",
    )
    return content.joinToString("\n")
}

fun SettingsEntry.debugContent(entryRepository: SettingsEntryRepository): String {
    val searchData = getSearchData()
    val statusData = getStatusData()
    val entryPathWithName = entryRepository.getEntryPathWithDisplayName(id)
    val entryPathWithTitle = entryRepository.getEntryPathWithTitle(id,
        searchData?.title ?: displayName)
    val content = listOf(
        "------ STATIC ------",
        "id = $id",
        "owner = ${owner.formatDisplayTitle()}",
        "linkFrom = ${fromPage?.formatDisplayTitle()}",
        "linkTo = ${toPage?.formatDisplayTitle()}",
        "hierarchy_path = $entryPathWithName",
        "------ SEARCH ------",
        "search_path = $entryPathWithTitle",
        "${searchData?.format()}",
        "${statusData?.debugContent()}"
    )
    return content.joinToString("\n")
}
