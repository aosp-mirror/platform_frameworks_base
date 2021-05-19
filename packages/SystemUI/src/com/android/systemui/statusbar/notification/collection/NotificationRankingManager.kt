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

import android.app.Notification
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_MIN
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.notification.NotificationEntryManager.KeyguardEnvironment
import com.android.systemui.statusbar.notification.NotificationEntryManagerLogger
import com.android.systemui.statusbar.notification.NotificationFilter
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.collection.legacy.LegacyNotificationRanker
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_NON_PERSON
import com.android.systemui.statusbar.notification.stack.BUCKET_ALERTING
import com.android.systemui.statusbar.notification.stack.BUCKET_FOREGROUND_SERVICE
import com.android.systemui.statusbar.notification.stack.BUCKET_PEOPLE
import com.android.systemui.statusbar.notification.stack.BUCKET_SILENT
import com.android.systemui.statusbar.notification.stack.PriorityBucket
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
    private val groupManager: NotificationGroupManagerLegacy,
    private val headsUpManager: HeadsUpManager,
    private val notifFilter: NotificationFilter,
    private val logger: NotificationEntryManagerLogger,
    private val sectionsFeatureManager: NotificationSectionsFeatureManager,
    private val peopleNotificationIdentifier: PeopleNotificationIdentifier,
    private val highPriorityProvider: HighPriorityProvider,
    private val keyguardEnvironment: KeyguardEnvironment
) : LegacyNotificationRanker {

    override var rankingMap: RankingMap? = null
        protected set
    private val mediaManager by lazy {
        mediaManagerLazy.get()
    }
    private val usePeopleFiltering: Boolean
        get() = sectionsFeatureManager.isFilteringEnabled()
    private val rankingComparator: Comparator<NotificationEntry> = Comparator { a, b ->
        val na = a.sbn
        val nb = b.sbn
        val aRank = a.ranking.rank
        val bRank = b.ranking.rank

        val aIsFsn = a.isColorizedForegroundService()
        val bIsFsn = b.isColorizedForegroundService()

        val aCall = a.isImportantCall()
        val bCall = b.isImportantCall()

        val aPersonType = a.getPeopleNotificationType()
        val bPersonType = b.getPeopleNotificationType()

        val aMedia = a.isImportantMedia()
        val bMedia = b.isImportantMedia()

        val aSystemMax = a.isSystemMax()
        val bSystemMax = b.isSystemMax()

        val aHeadsUp = a.isRowHeadsUp
        val bHeadsUp = b.isRowHeadsUp

        val aIsHighPriority = a.isHighPriority()
        val bIsHighPriority = b.isHighPriority()
        when {
            aHeadsUp != bHeadsUp -> if (aHeadsUp) -1 else 1
            // Provide consistent ranking with headsUpManager
            aHeadsUp -> headsUpManager.compare(a, b)
            aIsFsn != bIsFsn -> if (aIsFsn) -1 else 1
            aCall != bCall -> if (aCall) -1 else 1
            usePeopleFiltering && aPersonType != bPersonType ->
                peopleNotificationIdentifier.compareTo(aPersonType, bPersonType)
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

    override fun updateRanking(
        newRankingMap: RankingMap?,
        entries: Collection<NotificationEntry>,
        reason: String
    ): List<NotificationEntry> {
        // TODO: may not be ideal to guard on null here, but this code is implementing exactly what
        // NotificationData used to do
        if (newRankingMap != null) {
            rankingMap = newRankingMap
            updateRankingForEntries(entries)
        }
        return synchronized(this) {
            filterAndSortLocked(entries, reason)
        }
    }

    override fun isNotificationForCurrentProfiles(
        entry: NotificationEntry
    ): Boolean {
        return keyguardEnvironment.isNotificationForCurrentProfiles(entry.sbn)
    }

    /** Uses the [rankingComparator] to sort notifications which aren't filtered */
    private fun filterAndSortLocked(
        entries: Collection<NotificationEntry>,
        reason: String
    ): List<NotificationEntry> {
        logger.logFilterAndSort(reason)
        val filtered = entries.asSequence()
                .filterNot(this::filter)
                .sortedWith(rankingComparator)
                .toList()
        entries.forEach { it.bucket = getBucketForEntry(it) }
        return filtered
    }

    private fun filter(entry: NotificationEntry): Boolean {
        val filtered = notifFilter.shouldFilterOut(entry)
        if (filtered) {
            // notification is removed from the list, so we reset its initialization time
            entry.resetInitializationTime()
        }
        return filtered
    }

    @PriorityBucket
    private fun getBucketForEntry(entry: NotificationEntry): Int {
        val isImportantCall = entry.isImportantCall()
        val isHeadsUp = entry.isRowHeadsUp
        val isMedia = entry.isImportantMedia()
        val isSystemMax = entry.isSystemMax()
        return when {
            entry.isColorizedForegroundService() || isImportantCall -> BUCKET_FOREGROUND_SERVICE
            usePeopleFiltering && entry.isConversation() -> BUCKET_PEOPLE
            isHeadsUp || isMedia || isSystemMax || entry.isHighPriority() -> BUCKET_ALERTING
            else -> BUCKET_SILENT
        }
    }

    private fun updateRankingForEntries(entries: Iterable<NotificationEntry>) {
        rankingMap?.let { rankingMap ->
            synchronized(entries) {
                for (entry in entries) {
                    val newRanking = Ranking()
                    if (!rankingMap.getRanking(entry.key, newRanking)) {
                        continue
                    }
                    entry.ranking = newRanking

                    val newOverrideGroupKey = newRanking.overrideGroupKey
                    if (!Objects.equals(entry.sbn.overrideGroupKey, newOverrideGroupKey)) {
                        val oldGroupKey = entry.sbn.groupKey
                        val oldIsGroup = entry.sbn.isGroup
                        val oldIsGroupSummary = entry.sbn.notification.isGroupSummary
                        entry.sbn.overrideGroupKey = newOverrideGroupKey
                        groupManager.onEntryUpdated(entry, oldGroupKey, oldIsGroup,
                                oldIsGroupSummary)
                    }
                }
            }
        }
    }

    private fun NotificationEntry.isImportantMedia() =
            key == mediaManager.mediaNotificationKey && importance > IMPORTANCE_MIN

    private fun NotificationEntry.isConversation() = getPeopleNotificationType() != TYPE_NON_PERSON

    private fun NotificationEntry.getPeopleNotificationType() =
            peopleNotificationIdentifier.getPeopleNotificationType(this)

    private fun NotificationEntry.isHighPriority() =
            highPriorityProvider.isHighPriority(this)
}

// Convenience functions
private fun NotificationEntry.isSystemMax() =
        importance >= IMPORTANCE_HIGH && sbn.isSystemNotification()

private fun StatusBarNotification.isSystemNotification() =
        "android" == packageName || "com.android.systemui" == packageName

private fun NotificationEntry.isImportantCall() =
        sbn.notification.extras?.getString(Notification.EXTRA_TEMPLATE) ==
                "android.app.Notification\$CallStyle" && importance > IMPORTANCE_MIN

private fun NotificationEntry.isColorizedForegroundService() = sbn.notification.run {
    isForegroundService && isColorized && importance > IMPORTANCE_MIN
}
