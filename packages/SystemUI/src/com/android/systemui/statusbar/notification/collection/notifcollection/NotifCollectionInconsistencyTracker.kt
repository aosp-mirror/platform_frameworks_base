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

package com.android.systemui.statusbar.notification.collection.notifcollection

import android.service.notification.NotificationListenerService.RankingMap
import android.util.ArrayMap
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import java.io.PrintWriter

class NotifCollectionInconsistencyTracker(val logger: NotifCollectionLogger) {
    fun attach(
        collectedKeySetAccessor: () -> Set<String>,
        coalescedKeySetAccessor: () -> Set<String>,
    ) {
        if (attached) {
            throw RuntimeException("attach() called twice")
        }
        attached = true
        this.collectedKeySetAccessor = collectedKeySetAccessor
        this.coalescedKeySetAccessor = coalescedKeySetAccessor
    }

    fun logNewMissingNotifications(rankingMap: RankingMap) {
        val currentCollectedKeys = collectedKeySetAccessor()
        val currentCoalescedKeys = coalescedKeySetAccessor()
        val newMissingNotifications = rankingMap.orderedKeys.asSequence()
            .filter { it !in currentCollectedKeys }
            .filter { it !in currentCoalescedKeys }
            .toSet()
        maybeLogMissingNotifications(missingNotifications, newMissingNotifications)
        missingNotifications = newMissingNotifications
    }

    @VisibleForTesting
    fun maybeLogMissingNotifications(
        oldMissingKeys: Set<String>,
        newMissingKeys: Set<String>,
    ) {
        if (oldMissingKeys.isEmpty() && newMissingKeys.isEmpty()) return
        if (oldMissingKeys == newMissingKeys) return
        (oldMissingKeys - newMissingKeys).sorted().let { justFound ->
            if (justFound.isNotEmpty()) {
                logger.logFoundNotifications(justFound, newMissingKeys.size)
            }
        }
        (newMissingKeys - oldMissingKeys).sorted().let { goneMissing ->
            if (goneMissing.isNotEmpty()) {
                logger.logMissingNotifications(goneMissing, newMissingKeys.size)
            }
        }
    }

    fun logNewInconsistentRankings(
        currentEntriesWithoutRankings: ArrayMap<String, NotificationEntry>?,
        rankingMap: RankingMap,
    ) {
        maybeLogInconsistentRankings(
            notificationsWithoutRankings,
            currentEntriesWithoutRankings ?: emptyMap(),
            rankingMap
        )
        notificationsWithoutRankings = currentEntriesWithoutRankings?.keys ?: emptySet()
    }

    @VisibleForTesting
    fun maybeLogInconsistentRankings(
        oldKeysWithoutRankings: Set<String>,
        newEntriesWithoutRankings: Map<String, NotificationEntry>,
        rankingMap: RankingMap,
    ) {
        if (oldKeysWithoutRankings.isEmpty() && newEntriesWithoutRankings.isEmpty()) return
        if (oldKeysWithoutRankings == newEntriesWithoutRankings.keys) return
        val newlyConsistent: List<String> = oldKeysWithoutRankings
            .mapNotNull { key ->
                key.takeIf { key !in newEntriesWithoutRankings }
                    .takeIf { key in rankingMap.orderedKeys }
            }.sorted()
        if (newlyConsistent.isNotEmpty()) {
            val totalInconsistent: Int = newEntriesWithoutRankings.size
            logger.logRecoveredRankings(newlyConsistent, totalInconsistent)
        }
        val newlyInconsistent: List<NotificationEntry> = newEntriesWithoutRankings
            .mapNotNull { (key, entry) ->
                entry.takeIf { key !in oldKeysWithoutRankings }
            }.sortedBy { it.key }
        if (newlyInconsistent.isNotEmpty()) {
            val totalInconsistent: Int = newEntriesWithoutRankings.size
            logger.logMissingRankings(newlyInconsistent, totalInconsistent, rankingMap)
        }
    }

    fun dump(pw: PrintWriter) {
        pw.println("notificationsWithoutRankings: ${notificationsWithoutRankings.size}")
        for (key in notificationsWithoutRankings) {
            pw.println("\t * : $key")
        }
        pw.println("missingNotifications: ${missingNotifications.size}")
        for (key in missingNotifications) {
            pw.println("\t * : $key")
        }
    }

    private var attached: Boolean = false
    private lateinit var collectedKeySetAccessor: (() -> Set<String>)
    private lateinit var coalescedKeySetAccessor: (() -> Set<String>)
    private var notificationsWithoutRankings = emptySet<String>()
    private var missingNotifications = emptySet<String>()
}
