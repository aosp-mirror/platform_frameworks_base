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

package com.android.systemui.statusbar.notification.collection.listbuilder

import com.android.systemui.statusbar.notification.collection.ListEntry

object ShadeListBuilderHelper {
    fun getSectionSubLists(entries: List<ListEntry>): Iterable<List<ListEntry>> =
        getContiguousSubLists(entries, minLength = 1) { it.sectionIndex }

    inline fun <T : Any, K : Any> getContiguousSubLists(
        itemList: List<T>,
        minLength: Int = 1,
        key: (T) -> K,
    ): Iterable<List<T>> {
        val subLists = mutableListOf<List<T>>()
        val numEntries = itemList.size
        var currentSectionStartIndex = 0
        var currentSectionKey: K? = null
        for (i in 0 until numEntries) {
            val sectionKey = key(itemList[i])
            if (currentSectionKey == null) {
                currentSectionKey = sectionKey
            } else if (currentSectionKey != sectionKey) {
                val length = i - currentSectionStartIndex
                if (length >= minLength) {
                    subLists.add(itemList.subList(currentSectionStartIndex, i))
                }
                currentSectionStartIndex = i
                currentSectionKey = sectionKey
            }
        }
        val length = numEntries - currentSectionStartIndex
        if (length >= minLength) {
            subLists.add(itemList.subList(currentSectionStartIndex, numEntries))
        }
        return subLists
    }
}
