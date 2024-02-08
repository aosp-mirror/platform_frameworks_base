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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.notification.collection.render.NotifStats
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.setActiveNotifs
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ActiveNotificationsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository

    private val underTest = kosmos.activeNotificationsInteractor

    @Test
    fun testAllNotificationsCount() =
        testScope.runTest {
            val count by collectLastValue(underTest.allNotificationsCount)

            activeNotificationListRepository.setActiveNotifs(5)
            runCurrent()

            assertThat(count).isEqualTo(5)
            assertThat(underTest.allNotificationsCountValue).isEqualTo(5)
        }

    @Test
    fun areAnyNotificationsPresent_isTrue() =
        testScope.runTest {
            val areAnyNotificationsPresent by collectLastValue(underTest.areAnyNotificationsPresent)

            activeNotificationListRepository.setActiveNotifs(2)
            runCurrent()

            assertThat(areAnyNotificationsPresent).isTrue()
            assertThat(underTest.areAnyNotificationsPresentValue).isTrue()
        }

    @Test
    fun areAnyNotificationsPresent_isFalse() =
        testScope.runTest {
            val areAnyNotificationsPresent by collectLastValue(underTest.areAnyNotificationsPresent)

            activeNotificationListRepository.setActiveNotifs(0)
            runCurrent()

            assertThat(areAnyNotificationsPresent).isFalse()
            assertThat(underTest.areAnyNotificationsPresentValue).isFalse()
        }

    @Test
    fun testActiveNotificationRanks_sizeMatches() {
        testScope.runTest {
            val activeNotificationRanks by collectLastValue(underTest.activeNotificationRanks)

            activeNotificationListRepository.setActiveNotifs(5)
            runCurrent()

            assertThat(activeNotificationRanks!!.size).isEqualTo(5)
        }
    }

    @Test
    fun clearableNotifications_whenHasClearableAlertingNotifs() =
        testScope.runTest {
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
    fun hasClearableNotifications_whenHasClearableSilentNotifs() =
        testScope.runTest {
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
    fun testHasClearableNotifications_whenHasNoNotifs() =
        testScope.runTest {
            val hasClearable by collectLastValue(underTest.hasClearableNotifications)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 0,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = false,
                )
            runCurrent()

            assertThat(hasClearable).isFalse()
        }

    @Test
    fun hasClearableAlertingNotifications_whenHasClearableSilentNotifs() =
        testScope.runTest {
            val hasClearable by collectLastValue(underTest.hasClearableAlertingNotifications)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            runCurrent()

            assertThat(hasClearable).isFalse()
        }

    @Test
    fun hasClearableAlertingNotifications_whenHasNoClearableNotifs() =
        testScope.runTest {
            val hasClearable by collectLastValue(underTest.hasClearableAlertingNotifications)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = true,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = true,
                    hasClearableSilentNotifs = false,
                )
            runCurrent()

            assertThat(hasClearable).isFalse()
        }

    @Test
    fun hasClearableAlertingNotifications_whenHasAlertingNotifs() =
        testScope.runTest {
            val hasClearable by collectLastValue(underTest.hasClearableAlertingNotifications)

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
    fun hasNonClearableSilentNotifications_whenHasNonClearableSilentNotifs() =
        testScope.runTest {
            val hasNonClearable by collectLastValue(underTest.hasNonClearableSilentNotifications)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = true,
                    hasClearableSilentNotifs = false,
                )
            runCurrent()

            assertThat(hasNonClearable).isTrue()
        }

    @Test
    fun testHasNonClearableSilentNotifications_whenHasClearableSilentNotifs() =
        testScope.runTest {
            val hasNonClearable by collectLastValue(underTest.hasNonClearableSilentNotifications)

            activeNotificationListRepository.notifStats.value =
                NotifStats(
                    numActiveNotifs = 2,
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            runCurrent()

            assertThat(hasNonClearable).isFalse()
        }
}
