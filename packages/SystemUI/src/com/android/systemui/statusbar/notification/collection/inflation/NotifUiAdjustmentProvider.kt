/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.inflation

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import javax.inject.Inject

/**
 * A class which provides an adjustment object to the preparation coordinator which is uses
 * to ensure that notifications are reinflated when ranking-derived information changes.
 */
@SysUISingleton
open class NotifUiAdjustmentProvider @Inject constructor(
    private val sectionStyleProvider: SectionStyleProvider
) {

    private fun isEntryMinimized(entry: NotificationEntry): Boolean {
        val section = entry.section ?: error("Entry must have a section to determine if minimized")
        val parent = entry.parent ?: error("Entry must have a parent to determine if minimized")
        val isMinimizedSection = sectionStyleProvider.isMinimizedSection(section)
        val isTopLevelEntry = parent == GroupEntry.ROOT_ENTRY
        val isGroupSummary = parent.summary == entry
        return isMinimizedSection && (isTopLevelEntry || isGroupSummary)
    }

    /**
     * Returns a adjustment object for the given entry.  This can be compared to a previous instance
     * from the same notification using [NotifUiAdjustment.needReinflate] to determine if it
     * should be reinflated.
     */
    fun calculateAdjustment(entry: NotificationEntry) = NotifUiAdjustment(
        key = entry.key,
        smartActions = entry.ranking.smartActions,
        smartReplies = entry.ranking.smartReplies,
        isConversation = entry.ranking.isConversation,
        isMinimized = isEntryMinimized(entry)
    )
}