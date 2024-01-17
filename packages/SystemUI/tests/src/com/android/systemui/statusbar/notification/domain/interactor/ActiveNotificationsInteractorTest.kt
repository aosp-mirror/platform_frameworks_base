/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.statusbar.notification.collection.render.NotifStats
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.setActiveNotifs
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import org.junit.Test

@SmallTest
class ActiveNotificationsInteractorTest : SysuiTestCase() {

    @Component(modules = [SysUITestModule::class])
    @SysUISingleton
    interface TestComponent : SysUITestComponent<ActiveNotificationsInteractor> {
        val activeNotificationListRepository: ActiveNotificationListRepository

        @Component.Factory
        interface Factory {
            fun create(@BindsInstance test: SysuiTestCase): TestComponent
        }
    }

    private val testComponent: TestComponent =
        DaggerActiveNotificationsInteractorTest_TestComponent.factory().create(test = this)

    @Test
    fun testAllNotificationsCount() =
        testComponent.runTest {
            val count by collectLastValue(underTest.allNotificationsCount)

            activeNotificationListRepository.setActiveNotifs(5)
            runCurrent()

            assertThat(count).isEqualTo(5)
            assertThat(underTest.allNotificationsCountValue).isEqualTo(5)
        }

    @Test
    fun testAreAnyNotificationsPresent_isTrue() =
        testComponent.runTest {
            val areAnyNotificationsPresent by collectLastValue(underTest.areAnyNotificationsPresent)

            activeNotificationListRepository.setActiveNotifs(2)
            runCurrent()

            assertThat(areAnyNotificationsPresent).isTrue()
            assertThat(underTest.areAnyNotificationsPresentValue).isTrue()
        }

    @Test
    fun testAreAnyNotificationsPresent_isFalse() =
        testComponent.runTest {
            val areAnyNotificationsPresent by collectLastValue(underTest.areAnyNotificationsPresent)

            activeNotificationListRepository.setActiveNotifs(0)
            runCurrent()

            assertThat(areAnyNotificationsPresent).isFalse()
            assertThat(underTest.areAnyNotificationsPresentValue).isFalse()
        }

    @Test
    fun testActiveNotificationRanks_sizeMatches() {
        testComponent.runTest {
            val activeNotificationRanks by collectLastValue(underTest.activeNotificationRanks)

            activeNotificationListRepository.setActiveNotifs(5)
            runCurrent()

            assertThat(activeNotificationRanks!!.size).isEqualTo(5)
        }
    }

    @Test
    fun testHasClearableNotifications_whenHasClearableAlertingNotifs() =
        testComponent.runTest {
            val hasClearable by collectLastValue(underTest.hasClearableNotifications)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = true,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = false,
                )
            runCurrent()

            assertThat(hasClearable).isTrue()
        }

    @Test
    fun testHasClearableNotifications_whenHasClearableSilentNotifs() =
        testComponent.runTest {
            val hasClearable by collectLastValue(underTest.hasClearableNotifications)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            runCurrent()

            assertThat(hasClearable).isTrue()
        }

    @Test
    fun testHasClearableNotifications_whenHasNoClearableNotifs() =
        testComponent.runTest {
            val hasClearable by collectLastValue(underTest.hasClearableNotifications)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = false,
                )
            runCurrent()

            assertThat(hasClearable).isFalse()
        }
}
