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

import android.util.Log
import java.util.LinkedList

private const val TAG = "EntryRepository"
private const val MAX_ENTRY_SIZE = 5000
private const val MAX_ENTRY_DEPTH = 10

data class SettingsPageWithEntry(
    val page: SettingsPage,
    val entries: List<SettingsEntry>,
    // The inject entry, which to-page is current page.
    val injectEntry: SettingsEntry,
)

/**
 * The repository to maintain all Settings entries
 */
class SettingsEntryRepository(sppRepository: SettingsPageProviderRepository) {
    // Map of entry unique Id to entry
    private val entryMap: Map<String, SettingsEntry>

    // Map of Settings page to its contained entries.
    private val pageWithEntryMap: Map<String, SettingsPageWithEntry>

    init {
        Log.d(TAG, "Initialize")
        entryMap = mutableMapOf()
        pageWithEntryMap = mutableMapOf()

        val nullPage = NullPageProvider.createSettingsPage()
        val entryQueue = LinkedList<SettingsEntry>()
        for (page in sppRepository.getAllRootPages()) {
            val rootEntry =
                SettingsEntryBuilder.createRoot(owner = page).setLink(fromPage = nullPage).build()
            if (!entryMap.containsKey(rootEntry.id)) {
                entryQueue.push(rootEntry)
                entryMap.put(rootEntry.id, rootEntry)
            }
        }

        while (entryQueue.isNotEmpty() && entryMap.size < MAX_ENTRY_SIZE) {
            val entry = entryQueue.pop()
            val page = entry.toPage
            if (page == null || pageWithEntryMap.containsKey(page.id)) continue
            val spp = sppRepository.getProviderOrNull(page.sppName) ?: continue
            val newEntries = spp.buildEntry(page.arguments)
            // The page id could be existed already, if there are 2+ pages go to the same one.
            // For now, override the previous ones, which means only the last from-page is kept.
            // TODO: support multiple from-pages if necessary.
            pageWithEntryMap[page.id] = SettingsPageWithEntry(
                page = page,
                entries = newEntries,
                injectEntry = entry
            )
            for (newEntry in newEntries) {
                if (!entryMap.containsKey(newEntry.id)) {
                    entryQueue.push(newEntry)
                    entryMap.put(newEntry.id, newEntry)
                }
            }
        }

        Log.d(
            TAG,
            "Initialize Completed: ${entryMap.size} entries in ${pageWithEntryMap.size} pages"
        )
    }

    fun getAllPageWithEntry(): Collection<SettingsPageWithEntry> {
        return pageWithEntryMap.values
    }

    fun getPageWithEntry(pageId: String): SettingsPageWithEntry? {
        return pageWithEntryMap[pageId]
    }

    fun getAllEntries(): Collection<SettingsEntry> {
        return entryMap.values
    }

    fun getEntry(entryId: String): SettingsEntry? {
        return entryMap[entryId]
    }

    private fun getEntryPath(entryId: String): List<SettingsEntry> {
        val entryPath = ArrayList<SettingsEntry>()
        var currentEntry = entryMap[entryId]
        while (currentEntry != null && entryPath.size < MAX_ENTRY_DEPTH) {
            entryPath.add(currentEntry)
            val currentPage = currentEntry.containerPage()
            currentEntry = pageWithEntryMap[currentPage.id]?.injectEntry
        }
        return entryPath
    }

    fun getEntryPathWithLabel(entryId: String): List<String> {
        val entryPath = getEntryPath(entryId)
        return entryPath.map { it.label }
    }

    fun getEntryPathWithTitle(entryId: String, defaultTitle: String): List<String> {
        val entryPath = getEntryPath(entryId)
        return entryPath.map {
            if (it.toPage == null)
                defaultTitle
            else {
                it.toPage.getTitle()
            }
        }
    }
}
