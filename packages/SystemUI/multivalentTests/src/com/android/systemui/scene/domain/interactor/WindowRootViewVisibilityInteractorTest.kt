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

package com.android.systemui.scene.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.data.repository.WindowRootViewVisibilityRepository
import com.android.systemui.statusbar.NotificationPresenter
import com.android.systemui.statusbar.notification.init.NotificationsController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class WindowRootViewVisibilityInteractorTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val iStatusBarService = mock<IStatusBarService>()
    private val executor = FakeExecutor(FakeSystemClock())
    private val windowRootViewVisibilityRepository =
        WindowRootViewVisibilityRepository(iStatusBarService, executor)
    private val keyguardRepository = FakeKeyguardRepository()
    private val headsUpManager = mock<HeadsUpManager>()
    private val notificationPresenter = mock<NotificationPresenter>()
    private val notificationsController = mock<NotificationsController>()
    private val powerInteractor = PowerInteractorFactory.create().powerInteractor

    private val underTest =
        WindowRootViewVisibilityInteractor(
                testScope.backgroundScope,
                windowRootViewVisibilityRepository,
                keyguardRepository,
                headsUpManager,
                powerInteractor,
            )
            .apply { setUp(notificationPresenter, notificationsController) }

    @Test
    fun isLockscreenOrShadeVisible_true() {
        underTest.setIsLockscreenOrShadeVisible(true)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isTrue()
    }

    @Test
    fun isLockscreenOrShadeVisible_false() {
        underTest.setIsLockscreenOrShadeVisible(false)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isFalse()
    }

    @Test
    fun isLockscreenOrShadeVisible_matchesRepo() {
        windowRootViewVisibilityRepository.setIsLockscreenOrShadeVisible(true)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isTrue()

        windowRootViewVisibilityRepository.setIsLockscreenOrShadeVisible(false)

        assertThat(underTest.isLockscreenOrShadeVisible.value).isFalse()
    }

    @Test
    fun isLockscreenOrShadeVisibleAndInteractive_notVisible_false() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)
            powerInteractor.setAwakeForTest()

            underTest.setIsLockscreenOrShadeVisible(false)

            assertThat(actual).isFalse()
        }

    @Test
    fun isLockscreenOrShadeVisibleAndInteractive_deviceAsleep_false() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)
            underTest.setIsLockscreenOrShadeVisible(true)

            powerInteractor.setAsleepForTest()

            assertThat(actual).isFalse()
        }

    @Test
    fun isLockscreenOrShadeVisibleAndInteractive_visibleAndAwake_true() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)

            underTest.setIsLockscreenOrShadeVisible(true)
            powerInteractor.setAwakeForTest()

            assertThat(actual).isTrue()
        }

    @Test
    fun isLockscreenOrShadeVisibleAndInteractive_visibleAndStartingToWake_true() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)

            underTest.setIsLockscreenOrShadeVisible(true)
            powerInteractor.setAwakeForTest()

            assertThat(actual).isTrue()
        }

    @Test
    fun isLockscreenOrShadeVisibleAndStartingToSleep_false() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isLockscreenOrShadeVisibleAndInteractive)

            underTest.setIsLockscreenOrShadeVisible(true)
            powerInteractor.setAsleepForTest()

            assertThat(actual).isFalse()
        }

    @Test
    fun lockscreenShadeInteractive_statusBarServiceNotified() =
        testScope.runTest {
            underTest.start()

            makeLockscreenShadeVisible()
            testScope.runCurrent()
            executor.runAllReady()

            verify(iStatusBarService).onPanelRevealed(any(), any())
        }

    @Test
    fun lockscreenShadeNotInteractive_statusBarServiceNotified() =
        testScope.runTest {
            underTest.start()

            // First, make the shade visible
            makeLockscreenShadeVisible()
            testScope.runCurrent()
            reset(iStatusBarService)

            // WHEN lockscreen or shade is no longer visible
            underTest.setIsLockscreenOrShadeVisible(false)
            testScope.runCurrent()
            executor.runAllReady()

            // THEN status bar service is notified
            verify(iStatusBarService).onPanelHidden()
        }

    @Test
    fun lockscreenShadeInteractive_presenterCollapsed_notifEffectsNotCleared() =
        testScope.runTest {
            underTest.start()
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)

            whenever(notificationPresenter.isPresenterFullyCollapsed).thenReturn(true)

            makeLockscreenShadeVisible()

            val shouldClearNotifEffects = argumentCaptor<Boolean>()
            verify(iStatusBarService).onPanelRevealed(shouldClearNotifEffects.capture(), any())
            assertThat(shouldClearNotifEffects.value).isFalse()
        }

    @Test
    fun lockscreenShadeInteractive_nullPresenter_notifEffectsNotCleared() =
        testScope.runTest {
            underTest.start()
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)

            underTest.setUp(presenter = null, notificationsController)

            makeLockscreenShadeVisible()

            val shouldClearNotifEffects = argumentCaptor<Boolean>()
            verify(iStatusBarService).onPanelRevealed(shouldClearNotifEffects.capture(), any())
            assertThat(shouldClearNotifEffects.value).isFalse()
        }

    @Test
    fun lockscreenShadeInteractive_stateKeyguard_notifEffectsNotCleared() =
        testScope.runTest {
            underTest.start()
            whenever(notificationPresenter.isPresenterFullyCollapsed).thenReturn(false)

            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            makeLockscreenShadeVisible()

            val shouldClearNotifEffects = argumentCaptor<Boolean>()
            verify(iStatusBarService).onPanelRevealed(shouldClearNotifEffects.capture(), any())
            assertThat(shouldClearNotifEffects.value).isFalse()
        }

    @Test
    fun lockscreenShadeInteractive_stateShade_presenterNotCollapsed_notifEffectsCleared() =
        testScope.runTest {
            underTest.start()
            whenever(notificationPresenter.isPresenterFullyCollapsed).thenReturn(false)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE)

            makeLockscreenShadeVisible()

            val shouldClearNotifEffects = argumentCaptor<Boolean>()
            verify(iStatusBarService).onPanelRevealed(shouldClearNotifEffects.capture(), any())
            assertThat(shouldClearNotifEffects.value).isTrue()
        }

    @Test
    fun lockscreenShadeInteractive_stateShadeLocked_presenterNotCollapsed_notifEffectsCleared() =
        testScope.runTest {
            underTest.start()
            whenever(notificationPresenter.isPresenterFullyCollapsed).thenReturn(false)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)

            makeLockscreenShadeVisible()

            val shouldClearNotifEffects = argumentCaptor<Boolean>()
            verify(iStatusBarService).onPanelRevealed(shouldClearNotifEffects.capture(), any())
            assertThat(shouldClearNotifEffects.value).isTrue()
        }

    @Test
    fun lockscreenShadeInteractive_hasHeadsUpAndNotifPresenterCollapsed_notifCountOne() =
        testScope.runTest {
            underTest.start()

            whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(true)
            whenever(notificationPresenter.isPresenterFullyCollapsed).thenReturn(true)
            whenever(notificationsController.getActiveNotificationsCount()).thenReturn(4)

            makeLockscreenShadeVisible()

            val notifCount = argumentCaptor<Int>()
            verify(iStatusBarService).onPanelRevealed(any(), notifCount.capture())
            assertThat(notifCount.value).isEqualTo(1)
        }

    @Test
    fun lockscreenShadeInteractive_hasHeadsUpAndNullPresenter_notifCountOne() =
        testScope.runTest {
            underTest.start()

            whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(true)
            underTest.setUp(presenter = null, notificationsController)

            makeLockscreenShadeVisible()

            val notifCount = argumentCaptor<Int>()
            verify(iStatusBarService).onPanelRevealed(any(), notifCount.capture())
            assertThat(notifCount.value).isEqualTo(1)
        }

    @Test
    fun lockscreenShadeInteractive_noHeadsUp_notifCountMatchesNotifController() =
        testScope.runTest {
            underTest.start()
            whenever(notificationPresenter.isPresenterFullyCollapsed).thenReturn(true)

            whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(false)
            whenever(notificationsController.getActiveNotificationsCount()).thenReturn(9)

            makeLockscreenShadeVisible()

            val notifCount = argumentCaptor<Int>()
            verify(iStatusBarService).onPanelRevealed(any(), notifCount.capture())
            assertThat(notifCount.value).isEqualTo(9)
        }

    @Test
    fun lockscreenShadeInteractive_notifPresenterNotCollapsed_notifCountMatchesNotifController() =
        testScope.runTest {
            underTest.start()
            whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(true)

            whenever(notificationPresenter.isPresenterFullyCollapsed).thenReturn(false)
            whenever(notificationsController.getActiveNotificationsCount()).thenReturn(8)

            makeLockscreenShadeVisible()

            val notifCount = argumentCaptor<Int>()
            verify(iStatusBarService).onPanelRevealed(any(), notifCount.capture())
            assertThat(notifCount.value).isEqualTo(8)
        }

    @Test
    fun lockscreenShadeInteractive_noHeadsUp_noNotifController_notifCountZero() =
        testScope.runTest {
            underTest.start()
            whenever(notificationPresenter.isPresenterFullyCollapsed).thenReturn(true)

            whenever(headsUpManager.hasPinnedHeadsUp()).thenReturn(false)
            underTest.setUp(notificationPresenter, notificationsController = null)

            makeLockscreenShadeVisible()

            val notifCount = argumentCaptor<Int>()
            verify(iStatusBarService).onPanelRevealed(any(), notifCount.capture())
            assertThat(notifCount.value).isEqualTo(0)
        }

    private fun makeLockscreenShadeVisible() {
        underTest.setIsLockscreenOrShadeVisible(true)
        powerInteractor.setAwakeForTest()
        testScope.runCurrent()
        executor.runAllReady()
    }
}
