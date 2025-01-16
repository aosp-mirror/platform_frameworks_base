/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.setActiveNotifs
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationRebindingTrackerTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val activeNotificationRepository = kosmos.activeNotificationListRepository

    private val underTest: NotificationRebindingTracker = kosmos.notificationRebindingTracker

    @Before
    fun setup() {
        underTest.start()
    }

    @Test
    fun rebindingInProgressCount_noneStarted_isZero() =
        testScope.runTest {
            val count by collectLastValue(underTest.rebindingInProgressCount)

            assertThat(count).isEqualTo(0)
        }

    @Test
    fun rebindingInProgressCount_oneStarted_isOne() =
        testScope.runTest {
            val count by collectLastValue(underTest.rebindingInProgressCount)
            activeNotificationRepository.setActiveNotifs(1)

            underTest.trackRebinding("0")

            assertThat(count).isEqualTo(1)
        }

    @Test
    fun rebindingInProgressCount_oneStartedThenFinished_goesFromOneToZero() =
        testScope.runTest {
            val count by collectLastValue(underTest.rebindingInProgressCount)
            activeNotificationRepository.setActiveNotifs(1)

            val endRebinding = underTest.trackRebinding("0")

            assertThat(count).isEqualTo(1)

            endRebinding.onFinished()

            assertThat(count).isEqualTo(0)
        }

    @Test
    fun rebindingInProgressCount_twoStarted_goesToTwo() =
        testScope.runTest {
            val count by collectLastValue(underTest.rebindingInProgressCount)
            activeNotificationRepository.setActiveNotifs(2)

            underTest.trackRebinding("0")
            underTest.trackRebinding("1")

            assertThat(count).isEqualTo(2)
        }

    @Test
    fun rebindingInProgressCount_twoStarted_oneNotActiveAnymore_goesToZero() =
        testScope.runTest {
            val count by collectLastValue(underTest.rebindingInProgressCount)
            activeNotificationRepository.setActiveNotifs(2)

            val finishFirstRebinding = underTest.trackRebinding("0")
            underTest.trackRebinding("1")

            assertThat(count).isEqualTo(2)

            activeNotificationRepository.setActiveNotifs(1)

            assertThat(count).isEqualTo(1)

            finishFirstRebinding.onFinished()

            assertThat(count).isEqualTo(0)
        }
}
