/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection

import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats

/**
 * A holder class for a [NotificationEntry] and an associated [DismissedByUserStats], used by
 * [NotifCollection] for handling dismissal.
 */
data class EntryWithDismissStats(val entry: NotificationEntry, val stats: DismissedByUserStats) {
    /**
     * Creates deep a copy of this object, but with the entry, key and rank updated to correspond to
     * the given entry.
     */
    fun copyForEntry(newEntry: NotificationEntry) =
        EntryWithDismissStats(
            entry = newEntry,
            stats =
                DismissedByUserStats(
                    stats.dismissalSurface,
                    stats.dismissalSentiment,
                    NotificationVisibility.obtain(
                        newEntry.key,
                        newEntry.ranking.rank,
                        stats.notificationVisibility.count,
                        /* visible= */ false,
                    ),
                ),
        )
}
