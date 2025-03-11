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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.data.repository.windowRootViewVisibilityRepository
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.setActiveNotifs
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class NotificationLoggerViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val testScope = kosmos.testScope
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val powerInteractor = kosmos.powerInteractor
    private val windowRootViewVisibilityRepository = kosmos.windowRootViewVisibilityRepository

    private val underTest by lazy { kosmos.notificationListLoggerViewModel }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Test
    @DisableSceneContainer
    fun isLockscreenOrShadeInteractive_deviceActiveAndShadeIsInteractive_true() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            windowRootViewVisibilityRepository.setIsLockscreenOrShadeVisible(true)

            val actual by collectLastValue(underTest.isLockscreenOrShadeInteractive)

            assertThat(actual).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun isLockscreenOrShadeInteractive_deviceIsAsleepAndShadeIsInteractive_false() =
        testScope.runTest {
            powerInteractor.setAsleepForTest()
            windowRootViewVisibilityRepository.setIsLockscreenOrShadeVisible(true)

            val actual by collectLastValue(underTest.isLockscreenOrShadeInteractive)

            assertThat(actual).isFalse()
        }

    @Test
    @DisableSceneContainer
    fun isLockscreenOrShadeInteractive_deviceActiveAndShadeIsNotInteractive_false() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            windowRootViewVisibilityRepository.setIsLockscreenOrShadeVisible(false)

            val actual by collectLastValue(underTest.isLockscreenOrShadeInteractive)

            assertThat(actual).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun isLockscreenOrShadeInteractive_deviceAwakeAndShadeIsDisplayed_true() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            kosmos.setSceneTransition(Idle(Scenes.Shade))

            val actual by collectLastValue(underTest.isLockscreenOrShadeInteractive)

            assertThat(actual).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun isLockscreenOrShadeInteractive_deviceAwakeAndLockScreenIsDisplayed_true() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))

            val actual by collectLastValue(underTest.isLockscreenOrShadeInteractive)

            assertThat(actual).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun isLockscreenOrShadeInteractive_deviceIsAsleepOnLockscreen_false() =
        testScope.runTest {
            powerInteractor.setAsleepForTest()
            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))

            val actual by collectLastValue(underTest.isLockscreenOrShadeInteractive)

            assertThat(actual).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun isLockscreenOrShadeInteractive_deviceActiveAndShadeIsClosed() =
        testScope.runTest {
            powerInteractor.setAwakeForTest()
            kosmos.setSceneTransition(Idle(Scenes.Gone))

            val actual by collectLastValue(underTest.isLockscreenOrShadeInteractive)

            assertThat(actual).isFalse()
        }

    @Test
    fun activeNotifications_hasNotifications() =
        testScope.runTest {
            activeNotificationListRepository.setActiveNotifs(5)

            val notifs by collectLastValue(underTest.activeNotifications)

            assertThat(notifs).hasSize(5)
            requireNotNull(notifs).forEachIndexed { i, notif ->
                assertThat(notif.key).isEqualTo("$i")
            }
        }

    @Test
    fun activeNotifications_isEmpty() =
        testScope.runTest {
            activeNotificationListRepository.setActiveNotifs(0)

            val notifications by collectLastValue(underTest.activeNotifications)

            assertThat(notifications).isEmpty()
        }

    @Test
    fun activeNotificationRanks_hasNotifications() =
        testScope.runTest {
            val keys = (0..4).map { "$it" }
            activeNotificationListRepository.setActiveNotifs(5)

            val rankingsMap by collectLastValue(underTest.activeNotificationRanks)

            assertThat(rankingsMap).hasSize(5)
            keys.forEachIndexed { rank, key -> assertThat(rankingsMap).containsEntry(key, rank) }
        }

    @Test
    fun activeNotificationRanks_isEmpty() =
        testScope.runTest {
            activeNotificationListRepository.setActiveNotifs(0)

            val rankingsMap by collectLastValue(underTest.activeNotificationRanks)

            assertThat(rankingsMap).isEmpty()
        }

    @Test
    fun isOnLockScreen_true() =
        testScope.runTest {
            keyguardRepository.setKeyguardShowing(true)

            val isOnLockScreen by collectLastValue(underTest.isOnLockScreen)

            assertThat(isOnLockScreen).isTrue()
        }

    @Test
    fun isOnLockScreen_false() =
        testScope.runTest {
            keyguardRepository.setKeyguardShowing(false)

            val isOnLockScreen by collectLastValue(underTest.isOnLockScreen)

            assertThat(isOnLockScreen).isFalse()
        }
}
