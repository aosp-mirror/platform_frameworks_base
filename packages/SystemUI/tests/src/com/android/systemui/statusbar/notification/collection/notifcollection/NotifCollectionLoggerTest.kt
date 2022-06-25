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
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotifCollectionLoggerTest : SysuiTestCase() {
    private val logger: NotifCollectionLogger = mock()
    private val entry1: NotificationEntry = NotificationEntryBuilder().setId(1).build()
    private val entry2: NotificationEntry = NotificationEntryBuilder().setId(2).build()

    private fun mapOfEntries(vararg entries: NotificationEntry): Map<String, NotificationEntry> =
        entries.associateBy { it.key }

    private fun rankingMapOf(vararg entries: NotificationEntry): RankingMap =
        RankingMap(entries.map { it.ranking }.toTypedArray())

    @Test
    fun testMaybeLogInconsistentRankings_logsNewlyInconsistentRanking() {
        val rankingMap = rankingMapOf(entry1)
        maybeLogInconsistentRankings(
            logger = logger,
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
    fun testMaybeLogInconsistentRankings_doesNotLogAlreadyInconsistentRanking() {
        maybeLogInconsistentRankings(
            logger = logger,
            oldKeysWithoutRankings = setOf(entry2.key),
            newEntriesWithoutRankings = mapOfEntries(entry2),
            rankingMap = rankingMapOf(entry1)
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun testMaybeLogInconsistentRankings_logsWhenRankingIsAdded() {
        maybeLogInconsistentRankings(
            logger = logger,
            oldKeysWithoutRankings = setOf(entry2.key),
            newEntriesWithoutRankings = mapOfEntries(),
            rankingMap = rankingMapOf(entry1, entry2)
        )
        verify(logger).logRecoveredRankings(
            newlyConsistentKeys = eq(listOf(entry2.key)),
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun testMaybeLogInconsistentRankings_doesNotLogsWhenEntryIsRemoved() {
        maybeLogInconsistentRankings(
            logger = logger,
            oldKeysWithoutRankings = setOf(entry2.key),
            newEntriesWithoutRankings = mapOfEntries(),
            rankingMap = rankingMapOf(entry1)
        )
        verifyNoMoreInteractions(logger)
    }
}
