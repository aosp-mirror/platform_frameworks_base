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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.notification.collection.ListEntry
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

@SmallTest
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
            val entries =
                keys.map {
                    mock<ListEntry> {
                        val mockRep =
                            mock<NotificationEntry> {
                                whenever(key).thenReturn(it)
                                whenever(sbn).thenReturn(mock())
                                whenever(icons).thenReturn(mock())
                            }
                        whenever(representativeEntry).thenReturn(mockRep)
                    }
                }
            underTest.setRenderedList(entries)
            assertThat(notifs)
                .comparingElementsUsing(byKey)
                .containsExactlyElementsIn(keys)
                .inOrder()
        }
}
