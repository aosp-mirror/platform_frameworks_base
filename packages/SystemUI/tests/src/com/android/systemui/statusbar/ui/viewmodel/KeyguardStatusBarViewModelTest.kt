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

package com.android.systemui.statusbar.ui.viewmodel

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.domain.interactor.keyguardStatusBarInteractor
import com.android.systemui.statusbar.notification.data.repository.FakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.statusbar.notification.stack.data.repository.setNotifications
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.batteryController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedAndroidJunit4::class)
class KeyguardStatusBarViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val faceAuthRepository by lazy { kosmos.fakeDeviceEntryFaceAuthRepository }
    private val headsUpRepository by lazy { kosmos.headsUpNotificationRepository }
    private val headsUpNotificationInteractor by lazy { kosmos.headsUpNotificationInteractor }
    private val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    private val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    private val keyguardInteractor by lazy { kosmos.keyguardInteractor }
    private val keyguardStatusBarInteractor by lazy { kosmos.keyguardStatusBarInteractor }
    private val batteryController = kosmos.batteryController

    lateinit var underTest: KeyguardStatusBarViewModel

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

    @Before
    fun setup() {
        underTest =
            KeyguardStatusBarViewModel(
                testScope.backgroundScope,
                headsUpNotificationInteractor,
                keyguardInteractor,
                keyguardStatusBarInteractor,
                batteryController,
            )
    }

    @Test
    fun isVisible_dozing_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            keyguardRepository.setIsDozing(true)

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_statusBarStateShade_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE)

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_statusBarStateShadeLocked_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_headsUpStatusBarShown_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            // WHEN HUN displayed on the bypass lock screen
            headsUpRepository.setNotifications(FakeHeadsUpRowRepository("key 0", isPinned = true))
            keyguardTransitionRepository.emitInitialStepsFromOff(KeyguardState.LOCKSCREEN)
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            faceAuthRepository.isBypassEnabled.value = true

            // THEN KeyguardStatusBar is NOT visible to make space for HeadsUpStatusBar
            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_statusBarStateKeyguard_andNotDozing_andNotShowingHeadsUpStatusBar_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            keyguardRepository.setIsDozing(false)

            assertThat(latest).isTrue()
        }

    @Test
    fun isBatteryCharging_matchesCallback() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isBatteryCharging)
            runCurrent()

            val captor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(captor))
            val callback = captor.value

            callback.onBatteryLevelChanged(
                /* level= */ 2,
                /* pluggedIn= */ false,
                /* charging= */ true,
            )

            assertThat(latest).isTrue()

            callback.onBatteryLevelChanged(
                /* level= */ 2,
                /* pluggedIn= */ true,
                /* charging= */ false,
            )

            assertThat(latest).isFalse()
        }

    @Test
    fun isBatteryCharging_unregistersWhenNotListening() =
        testScope.runTest {
            val job = underTest.isBatteryCharging.launchIn(this)
            runCurrent()

            val captor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(captor))

            job.cancel()
            runCurrent()

            verify(batteryController).removeCallback(captor.value)
        }
}
