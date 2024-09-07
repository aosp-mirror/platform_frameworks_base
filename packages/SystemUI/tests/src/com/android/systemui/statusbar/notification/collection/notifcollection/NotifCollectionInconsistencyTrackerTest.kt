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
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.util.mockito.eq
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class NotifCollectionInconsistencyTrackerTest : SysuiTestCase() {
    private val logger = spy(NotifCollectionLogger(logcatLogBuffer()))
    private val entry1: NotificationEntry = NotificationEntryBuilder().setId(1).build()
    private val entry2: NotificationEntry = NotificationEntryBuilder().setId(2).build()
    private val collectionSet = mutableSetOf<String>()
    private val coalescedSet = mutableSetOf<String>()
    private lateinit var inconsistencyTracker: NotifCollectionInconsistencyTracker

    private fun mapOfEntries(vararg entries: NotificationEntry): Map<String, NotificationEntry> =
        entries.associateBy { it.key }

    private fun rankingMapOf(vararg entries: NotificationEntry): RankingMap =
        RankingMap(entries.map { it.ranking }.toTypedArray())

    @Before
    fun setUp() {
        inconsistencyTracker = NotifCollectionInconsistencyTracker(logger)
        inconsistencyTracker.attach({ collectionSet }, { coalescedSet })
        collectionSet.clear()
        coalescedSet.clear()
    }

    @Test
    fun maybeLogInconsistentRankings_logsNewlyInconsistentRanking() {
        val rankingMap = rankingMapOf(entry1)
        inconsistencyTracker.maybeLogInconsistentRankings(
            oldKeysWithoutRankings = emptySet(),
            newEntriesWithoutRankings = mapOfEntries(entry2),
            rankingMap = rankingMap
        )
        verify(logger).logMissingRankings(
            newlyInconsistentEntries = eq(listOf(entry2)),
            totalInconsistent = eq(1),
            rankingMap = eq(rankingMap),
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun maybeLogInconsistentRankings_doesNotLogAlreadyInconsistentRanking() {
        inconsistencyTracker.maybeLogInconsistentRankings(
            oldKeysWithoutRankings = setOf(entry2.key),
            newEntriesWithoutRankings = mapOfEntries(entry2),
            rankingMap = rankingMapOf(entry1)
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun maybeLogInconsistentRankings_logsWhenRankingIsAdded() {
        inconsistencyTracker.maybeLogInconsistentRankings(
            oldKeysWithoutRankings = setOf(entry2.key),
            newEntriesWithoutRankings = mapOfEntries(),
            rankingMap = rankingMapOf(entry1, entry2)
        )
        verify(logger).logRecoveredRankings(
            newlyConsistentKeys = eq(listOf(entry2.key)),
            totalInconsistent = eq(0),
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun maybeLogInconsistentRankings_doesNotLogsWhenEntryIsRemoved() {
        inconsistencyTracker.maybeLogInconsistentRankings(
            oldKeysWithoutRankings = setOf(entry2.key),
            newEntriesWithoutRankings = mapOfEntries(),
            rankingMap = rankingMapOf(entry1)
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun maybeLogMissingNotifications_logsNewlyMissingNotifications() {
        inconsistencyTracker.maybeLogMissingNotifications(
            oldMissingKeys = setOf("a"),
            newMissingKeys = setOf("a", "b"),
        )
        verify(logger).logMissingNotifications(
            newlyMissingKeys = eq(listOf("b")),
            totalMissing = eq(2),
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun maybeLogMissingNotifications_logsNoLongerMissingNotifications() {
        inconsistencyTracker.maybeLogMissingNotifications(
            oldMissingKeys = setOf("a", "b"),
            newMissingKeys = setOf("a"),
        )
        verify(logger).logFoundNotifications(
            newlyFoundKeys = eq(listOf("b")),
            totalMissing = eq(1),
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun maybeLogMissingNotifications_logsBothAtOnce() {
        inconsistencyTracker.maybeLogMissingNotifications(
            oldMissingKeys = setOf("a"),
            newMissingKeys = setOf("b"),
        )
        verify(logger).logFoundNotifications(
            newlyFoundKeys = eq(listOf("a")),
            totalMissing = eq(1),
        )
        verify(logger).logMissingNotifications(
            newlyMissingKeys = eq(listOf("b")),
            totalMissing = eq(1),
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun maybeLogMissingNotifications_logsNothingWhenNoChange() {
        inconsistencyTracker.maybeLogMissingNotifications(
            oldMissingKeys = setOf("a"),
            newMissingKeys = setOf("a"),
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun logNewMissingNotifications_doesNotLogForConsistentSets() {
        inconsistencyTracker.logNewMissingNotifications(rankingMapOf())
        verifyNoMoreInteractions(logger)

        collectionSet.add(entry1.key)
        inconsistencyTracker.logNewMissingNotifications(rankingMapOf(entry1))
        verifyNoMoreInteractions(logger)

        coalescedSet.add(entry2.key)
        inconsistencyTracker.logNewMissingNotifications(rankingMapOf(entry1, entry2))
        verifyNoMoreInteractions(logger)

        coalescedSet.add(entry1.key)
        inconsistencyTracker.logNewMissingNotifications(rankingMapOf(entry1, entry2))
        verifyNoMoreInteractions(logger)

        coalescedSet.remove(entry1.key)
        collectionSet.remove(entry1.key)
        inconsistencyTracker.logNewMissingNotifications(rankingMapOf(entry2))
        verifyNoMoreInteractions(logger)

        coalescedSet.remove(entry2.key)
        inconsistencyTracker.logNewMissingNotifications(rankingMapOf())
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun logNewMissingNotifications_logsAsExpected() {
        inconsistencyTracker.logNewMissingNotifications(rankingMapOf())
        verifyNoMoreInteractions(logger)

        collectionSet.add(entry1.key)
        inconsistencyTracker.logNewMissingNotifications(rankingMapOf(entry1, entry2))
        verify(logger).logMissingNotifications(
            newlyMissingKeys = eq(listOf(entry2.key)),
            totalMissing = eq(1),
        )
        verifyNoMoreInteractions(logger)
        clearInvocations(logger)

        inconsistencyTracker.logNewMissingNotifications(rankingMapOf(entry1, entry2))
        verifyNoMoreInteractions(logger)

        coalescedSet.add(entry2.key)
        inconsistencyTracker.logNewMissingNotifications(rankingMapOf(entry1, entry2))
        verify(logger).logFoundNotifications(
            newlyFoundKeys = eq(listOf(entry2.key)),
            totalMissing = eq(0),
        )
        verifyNoMoreInteractions(logger)
        clearInvocations(logger)
    }
}
