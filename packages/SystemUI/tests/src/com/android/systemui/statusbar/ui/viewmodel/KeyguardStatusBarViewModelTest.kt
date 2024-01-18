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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.data.repository.FakeKeyguardStatusBarRepository
import com.android.systemui.statusbar.domain.interactor.KeyguardStatusBarInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.verify

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardStatusBarViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardRepository = FakeKeyguardRepository()
    private val keyguardInteractor =
        KeyguardInteractor(
            keyguardRepository,
            mock<CommandQueue>(),
            PowerInteractorFactory.create().powerInteractor,
            kosmos.fakeSceneContainerFlags,
            FakeKeyguardBouncerRepository(),
            ConfigurationInteractor(FakeConfigurationRepository()),
            FakeShadeRepository(),
        ) {
            kosmos.sceneInteractor
        }
    private val keyguardStatusBarInteractor =
        KeyguardStatusBarInteractor(
            FakeKeyguardStatusBarRepository(),
        )
    private val batteryController = mock<BatteryController>()

    private val underTest =
        KeyguardStatusBarViewModel(
            testScope.backgroundScope,
            keyguardInteractor,
            keyguardStatusBarInteractor,
            batteryController,
        )

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
    fun isVisible_statusBarStateKeyguard_andNotDozing_true() =
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
