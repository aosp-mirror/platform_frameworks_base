/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.unfold

import android.os.PowerManager
import android.os.SystemProperties
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.foldables.FoldLockSettingAvailabilityProvider
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.DeviceStateRepository.DeviceState
import com.android.systemui.display.data.repository.fakeDeviceStateRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setScreenPowerState
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.util.animation.data.repository.fakeAnimationStatusRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FoldLightRevealOverlayAnimationTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope: TestScope = kosmos.testScope
    private val fakeDeviceStateRepository = kosmos.fakeDeviceStateRepository
    private val powerInteractor = kosmos.powerInteractor
    private val fakeAnimationStatusRepository = kosmos.fakeAnimationStatusRepository
    private val mockControllerFactory = kosmos.fullscreenLightRevealAnimationControllerFactory
    private val mockFullScreenController = kosmos.fullscreenLightRevealAnimationController
    private val mockFoldLockSettingAvailabilityProvider =
        mock<FoldLockSettingAvailabilityProvider>()
    private val onOverlayReady = mock<Runnable>()
    private lateinit var foldLightRevealAnimation: FoldLightRevealOverlayAnimation

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(mockFoldLockSettingAvailabilityProvider.isFoldLockBehaviorAvailable)
            .thenReturn(true)
        fakeAnimationStatusRepository.onAnimationStatusChanged(true)

        foldLightRevealAnimation =
            FoldLightRevealOverlayAnimation(
                kosmos.testDispatcher,
                fakeDeviceStateRepository,
                powerInteractor,
                testScope.backgroundScope,
                fakeAnimationStatusRepository,
                mockControllerFactory,
                mockFoldLockSettingAvailabilityProvider
            )
        foldLightRevealAnimation.init()
    }

    @Test
    fun foldToScreenOn_playFoldAnimation() =
        testScope.runTest {
            foldDeviceToScreenOff()
            turnScreenOn()

            verifyFoldAnimationPlayed()
        }

    @Test
    fun foldToAod_doNotPlayFoldAnimation() =
        testScope.runTest {
            foldDeviceToScreenOff()
            emitLastWakefulnessEventStartingToSleep()
            advanceTimeBy(SHORT_DELAY_MS)
            turnScreenOn()
            advanceTimeBy(ANIMATION_DURATION)

            verifyFoldAnimationDidNotPlay()
        }

    @Test
    fun foldToScreenOff_doNotPlayFoldAnimation() =
        testScope.runTest {
            foldDeviceToScreenOff()
            emitLastWakefulnessEventStartingToSleep()
            advanceTimeBy(SHORT_DELAY_MS)
            advanceTimeBy(ANIMATION_DURATION)

            verifyFoldAnimationDidNotPlay()
        }

    @Test
    fun foldToScreenOnWithDelay_doNotPlayFoldAnimation() =
        testScope.runTest {
            foldDeviceToScreenOff()
            foldLightRevealAnimation.onScreenTurningOn {}
            advanceTimeBy(WAIT_FOR_ANIMATION_TIMEOUT_MS)
            powerInteractor.setScreenPowerState(ScreenPowerState.SCREEN_ON)
            advanceTimeBy(SHORT_DELAY_MS)
            advanceTimeBy(ANIMATION_DURATION)

            verifyFoldAnimationDidNotPlay()
        }

    @Test
    fun immediateUnfoldAfterFold_removeOverlayAfterCancellation() =
        testScope.runTest {
            foldDeviceToScreenOff()
            foldLightRevealAnimation.onScreenTurningOn {}
            advanceTimeBy(SHORT_DELAY_MS)
            advanceTimeBy(ANIMATION_DURATION)
            fakeDeviceStateRepository.emit(DeviceState.HALF_FOLDED)
            advanceTimeBy(SHORT_DELAY_MS)
            powerInteractor.setScreenPowerState(ScreenPowerState.SCREEN_ON)

            verifyOverlayWasRemoved()
        }

    @Test
    fun foldToScreenOn_removeOverlayAfterCompletion() =
        testScope.runTest {
            foldDeviceToScreenOff()
            turnScreenOn()
            advanceTimeBy(ANIMATION_DURATION)

            verifyOverlayWasRemoved()
        }

    @Test
    fun unfold_immediatelyRunRunnable() =
        testScope.runTest {
            foldLightRevealAnimation.onScreenTurningOn(onOverlayReady)

            verify(onOverlayReady).run()
        }

    private suspend fun TestScope.foldDeviceToScreenOff() {
        fakeDeviceStateRepository.emit(DeviceState.HALF_FOLDED)
        powerInteractor.setScreenPowerState(ScreenPowerState.SCREEN_ON)
        advanceTimeBy(SHORT_DELAY_MS)
        fakeDeviceStateRepository.emit(DeviceState.FOLDED)
        advanceTimeBy(SHORT_DELAY_MS)
        powerInteractor.setScreenPowerState(ScreenPowerState.SCREEN_OFF)
        advanceTimeBy(SHORT_DELAY_MS)
    }

    private fun TestScope.turnScreenOn() {
        foldLightRevealAnimation.onScreenTurningOn {}
        advanceTimeBy(SHORT_DELAY_MS)
        powerInteractor.setScreenPowerState(ScreenPowerState.SCREEN_ON)
        advanceTimeBy(SHORT_DELAY_MS)
    }

    private fun emitLastWakefulnessEventStartingToSleep() =
        powerInteractor.setAsleepForTest(PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD)

    private fun verifyFoldAnimationPlayed() =
        verify(mockFullScreenController, atLeast(1)).updateRevealAmount(any())

    private fun verifyFoldAnimationDidNotPlay() =
        verify(mockFullScreenController, never()).updateRevealAmount(any())

    private fun verifyOverlayWasRemoved() =
        verify(mockFullScreenController, atLeast(1)).ensureOverlayRemoved()

    private companion object {
        const val WAIT_FOR_ANIMATION_TIMEOUT_MS = 2000L
        val ANIMATION_DURATION: Long
            get() = SystemProperties.getLong("persist.fold_animation_duration", 200L)
        const val SHORT_DELAY_MS = 50L
    }
}
