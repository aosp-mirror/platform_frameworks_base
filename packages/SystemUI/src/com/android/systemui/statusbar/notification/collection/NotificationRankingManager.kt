/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_MIN
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.notification.NotificationFilter
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.notification.logging.NotifEvent
import com.android.systemui.statusbar.notification.logging.NotifLog
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_ALERTING
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_PEOPLE
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_SILENT
import com.android.systemui.statusbar.phone.NotificationGroupManager
import com.android.systemui.statusbar.policy.HeadsUpManager
import dagger.Lazy
import java.util.Objects
import javax.inject.Inject

private const val TAG = "NotifRankingManager"

/**
 * NotificationRankingManager is responsible for holding on to the most recent [RankingMap], and
 * updating SystemUI's set of [NotificationEntry]s with their own ranking. It also sorts and filters
 * a set of entries (but retains none of them). We also set buckets on the entries here since
 * bucketing is tied closely to sorting.
 *
 * For the curious: this class is one iteration closer to null of what used to be called
 * NotificationData.java.
 */
open class NotificationRankingManager @Inject constructor(
    private val mediaManagerLazy: Lazy<NotificationMediaManager>,
    private val groupManager: NotificationGroupManager,
    private val headsUpManager: HeadsUpManager,
    private val notifFilter: NotificationFilter,
    private val notifLog: NotifLog,
    sectionsFeatureManager: NotificationSectionsFeatureManager,
    private val peopleNotificationIdentifier: PeopleNotificationIdentifier,
    private val highPriorityProvider: HighPriorityProvider
) {

    var rankingMap: RankingMap? = null
        protected set
    private val mediaManager by lazy {
        mediaManagerLazy.get()
    }
    private val usePeopleFiltering: Boolean = sectionsFeatureManager.isFilteringEnabled()
    private val rankingComparator: Comparator<NotificationEntry> = Comparator { a, b ->
        val na = a.sbn
        val nb = b.sbn
        val aRank = a.ranking.rank
        val bRank = b.ranking.rank

        val aIsPeople = a.isPeopleNotification()
        val bIsPeople = b.isPeopleNotification()

        val aMedia = isImportantMedia(a)
        val bMedia = isImportantMedia(b)

        val aSystemMax = a.isSystemMax()
        val bSystemMax = b.isSystemMax()

        val aHeadsUp = a.isRowHeadsUp
        val bHeadsUp = b.isRowHeadsUp

        val aIsHighPriority = a.isHighPriority()
        val bIsHighPriority = b.isHighPriority()

        when {
            usePeopleFiltering && aIsPeople != bIsPeople -> if (aIsPeople) -1 else 1
            aHeadsUp != bHeadsUp -> if (aHeadsUp) -1 else 1
            // Provide consistent ranking with headsUpManager
            aHeadsUp -> headsUpManager.compare(a, b)
            // Upsort current media notification.
            aMedia != bMedia -> if (aMedia) -1 else 1
            // Upsort PRIORITY_MAX system notifications
            aSystemMax != bSystemMax -> if (aSystemMax) -1 else 1
            aIsHighPriority != bIsHighPriority ->
                -1 * aIsHighPriority.compareTo(bIsHighPriority)
            aRank != bRank -> aRank - bRank
            else -> nb.notification.`when`.compareTo(na.notification.`when`)
        }
    }

    private fun isImportantMedia(entry: NotificationEntry): Boolean {
        val importance = entry.ranking.importance
        return entry.key == mediaManager.mediaNotificationKey && importance > IMPORTANCE_MIN
    }

    fun updateRanking(
        newRankingMap: RankingMap?,
        entries: Collection<NotificationEntry>,
        reason: String
    ): List<NotificationEntry> {
        val eSeq = entries.asSequence()

        // TODO: may not be ideal to guard on null here, but this code is implementing exactly what
        // NotificationData used to do
        if (newRankingMap != null) {
            rankingMap = newRankingMap
            updateRankingForEntries(eSeq)
        }

        val filtered: Sequence<NotificationEntry>
        synchronized(this) {
            filtered = filterAndSortLocked(eSeq, reason)
        }

        return filtered.toList()
    }

    /** Uses the [rankingComparator] to sort notifications which aren't filtered */
    private fun filterAndSortLocked(
        entries: Sequence<NotificationEntry>,
        reason: String
    ): Sequence<NotificationEntry> {
        notifLog.log(NotifEvent.FILTER_AND_SORT, reason)

        return entries.filter { !notifFilter.shouldFilterOut(it) }
                .sortedWith(rankingComparator)
                .map {
                    assignBucketForEntry(it)
                    it
                }
    }

    private fun assignBucketForEntry(entry: NotificationEntry) {
        val isHeadsUp = entry.isRowHeadsUp
        val isMedia = isImportantMedia(entry)
        val isSystemMax = entry.isSystemMax()
        setBucket(entry, isHeadsUp, isMedia, isSystemMax)
    }

    private fun setBucket(
        entry: NotificationEntry,
        isHeadsUp: Boolean,
        isMedia: Boolean,
        isSystemMax: Boolean
    ) {
        if (usePeopleFiltering && entry.isPeopleNotification()) {
            entry.bucket = BUCKET_PEOPLE
        } else if (isHeadsUp || isMedia || isSystemMax || entry.isHighPriority()) {
            entry.bucket = BUCKET_ALERTING
        } else {
            entry.bucket = BUCKET_SILENT
        }
    }

    private fun updateRankingForEntries(entries: Sequence<NotificationEntry>) {
        rankingMap?.let { rankingMap ->
            synchronized(entries) {
                entries.forEach { entry ->
                    val newRanking = Ranking()
                    if (!rankingMap.getRanking(entry.key, newRanking)) {
                        return@forEach
                    }
                    entry.ranking = newRanking

                    val oldSbn = entry.sbn.cloneLight()
                    val newOverrideGroupKey = newRanking.overrideGroupKey
                    if (!Objects.equals(oldSbn.overrideGroupKey, newOverrideGroupKey)) {
                        entry.sbn.overrideGroupKey = newOverrideGroupKey
                        // TODO: notify group manager here?
                        groupManager.onEntryUpdated(entry, oldSbn)
                    }
                }
            }
        }
    }

    private fun NotificationEntry.isPeopleNotification() =
            sbn.isPeopleNotification()
    private fun StatusBarNotification.isPeopleNotification() =
            peopleNotificationIdentifier.isPeopleNotification(this)

    private fun NotificationEntry.isHighPriority() =
            highPriorityProvider.isHighPriority(this)
}

// Convenience functions
private fun NotificationEntry.isSystemMax(): Boolean {
    return importance >= IMPORTANCE_HIGH && sbn.isSystemNotification()
}

private fun StatusBarNotification.isSystemNotification(): Boolean {
    return "android" == packageName || "com.android.systemui" == packageName
}
