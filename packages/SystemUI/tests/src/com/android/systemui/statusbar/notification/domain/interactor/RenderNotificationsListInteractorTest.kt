/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.domain.interactor

import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.RankingBuilder
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.shared.byKey
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class RenderNotificationsListInteractorTest : SysuiTestCase() {
    private val backgroundDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(backgroundDispatcher)

    private val notifsRepository = ActiveNotificationListRepository()
    private val notifsInteractor =
        ActiveNotificationsInteractor(notifsRepository, backgroundDispatcher)
    private val underTest =
        RenderNotificationListInteractor(
            notifsRepository,
            sectionStyleProvider = mock(),
        )

    @Test
    fun setRenderedList_preservesOrdering() =
        testScope.runTest {
            val notifs by collectLastValue(notifsInteractor.topLevelRepresentativeNotifications)
            val keys = (1..50).shuffled().map { "$it" }
            val entries = keys.map { mockNotificationEntry(key = it) }
            underTest.setRenderedList(entries)
            assertThat(notifs)
                .comparingElementsUsing(byKey)
                .containsExactlyElementsIn(keys)
                .inOrder()
        }

    @Test
    fun setRenderList_flatMapsRankings() =
        testScope.runTest {
            val ranks by collectLastValue(notifsInteractor.activeNotificationRanks)

            val single = mockNotificationEntry("single", 0)
            val group =
                mockGroupEntry(
                    key = "group",
                    summary = mockNotificationEntry("summary", 1),
                    children =
                        listOf(
                            mockNotificationEntry("child0", 2),
                            mockNotificationEntry("child1", 3),
                        ),
                )

            underTest.setRenderedList(listOf(single, group))

            assertThat(ranks)
                .containsExactlyEntriesIn(
                    mapOf(
                        "single" to 0,
                        "summary" to 1,
                        "child0" to 2,
                        "child1" to 3,
                    )
                )
        }

    @Test
    fun setRenderList_singleItems_mapsRankings() =
        testScope.runTest {
            val actual by collectLastValue(notifsInteractor.activeNotificationRanks)
            val expected =
                (0..10).shuffled().mapIndexed { index, value -> "$value" to index }.toMap()

            val entries = expected.map { (key, rank) -> mockNotificationEntry(key, rank) }

            underTest.setRenderedList(entries)

            assertThat(actual).containsAtLeastEntriesIn(expected)
        }

    @Test
    fun setRenderList_groupWithNoSummary_flatMapsRankings() =
        testScope.runTest {
            val actual by collectLastValue(notifsInteractor.activeNotificationRanks)
            val expected =
                (0..10).shuffled().mapIndexed { index, value -> "$value" to index }.toMap()

            val group =
                mockGroupEntry(
                    key = "group",
                    summary = null,
                    children = expected.map { (key, rank) -> mockNotificationEntry(key, rank) },
                )

            underTest.setRenderedList(listOf(group))

            assertThat(actual).containsAtLeastEntriesIn(expected)
        }
}

private fun mockGroupEntry(
    key: String,
    summary: NotificationEntry?,
    children: List<NotificationEntry>,
): GroupEntry {
    return mock<GroupEntry> {
        whenever(this.key).thenReturn(key)
        whenever(this.summary).thenReturn(summary)
        whenever(this.children).thenReturn(children)
    }
}

private fun mockNotificationEntry(key: String, rank: Int = 0): NotificationEntry {
    val mockSbn =
        mock<StatusBarNotification>() {
            whenever(notification).thenReturn(mock())
            whenever(packageName).thenReturn("com.android")
        }
    return mock<NotificationEntry> {
        whenever(this.key).thenReturn(key)
        whenever(this.icons).thenReturn(mock())
        whenever(this.representativeEntry).thenReturn(this)
        whenever(this.ranking).thenReturn(RankingBuilder().setRank(rank).build())
        whenever(this.sbn).thenReturn(mockSbn)
    }
}
