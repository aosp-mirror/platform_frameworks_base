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

package com.android.settingslib.spa.debug

import com.android.settingslib.spa.framework.common.EntrySearchData
import com.android.settingslib.spa.framework.common.EntryStatusData
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryRepository
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.util.normalize

private fun EntrySearchData.debugContent(): String {
    val content = listOf(
        "search_title = $title",
        "search_keyword = $keyword",
    )
    return content.joinToString("\n")
}

private fun EntryStatusData.debugContent(): String {
    val content = listOf(
        "is_disabled = $isDisabled",
        "is_switch_off = $isSwitchOff",
    )
    return content.joinToString("\n")
}

fun SettingsPage.debugArguments(): String {
    val normArguments = parameter.normalize(arguments, eraseRuntimeValues = true)
    if (normArguments == null || normArguments.isEmpty) return "[No arguments]"
    return normArguments.toString().removeRange(0, 6)
}

fun SettingsPage.debugBrief(): String {
    return displayName
}

fun SettingsEntry.debugBrief(): String {
    return "${owner.displayName}:$label"
}

fun SettingsEntry.debugContent(entryRepository: SettingsEntryRepository): String {
    val searchData = getSearchData()
    val statusData = getStatusData()
    val entryPathWithLabel = entryRepository.getEntryPathWithLabel(id)
    val entryPathWithTitle = entryRepository.getEntryPathWithTitle(id,
        searchData?.title ?: label)
    val content = listOf(
        "------ STATIC ------",
        "id = $id",
        "owner = ${owner.debugBrief()} ${owner.debugArguments()}",
        "linkFrom = ${fromPage?.debugBrief()} ${fromPage?.debugArguments()}",
        "linkTo = ${toPage?.debugBrief()} ${toPage?.debugArguments()}",
        "hierarchy_path = $entryPathWithLabel",
        "------ ATTRIBUTION ------",
        "allowSearch = $isAllowSearch",
        "isSearchDynamic = $isSearchDataDynamic",
        "isSearchMutable = $hasMutableStatus",
        "------ SEARCH ------",
        "search_path = $entryPathWithTitle",
        searchData?.debugContent() ?: "no search data",
        statusData?.debugContent() ?: "no status data",
    )
    return content.joinToString("\n")
}
