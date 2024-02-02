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

package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.AuthenticateOptions
import android.hardware.biometrics.IBiometricContextListener
import android.hardware.biometrics.IBiometricContextListener.FoldState
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.display.data.repository.fakeDeviceStateRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class LogContextInteractorImplTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val deviceStateRepository = kosmos.fakeDeviceStateRepository
    private val udfpsOverlayInteractor = kosmos.udfpsOverlayInteractor
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository

    private lateinit var interactor: LogContextInteractorImpl

    @Before
    fun setup() {
        interactor =
            LogContextInteractorImpl(
                testScope.backgroundScope,
                deviceStateRepository,
                kosmos.keyguardTransitionInteractor,
                udfpsOverlayInteractor,
            )
    }

    @Test
    fun isAodChanges() =
        testScope.runTest {
            val isAod = collectLastValue(interactor.isAod)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OFF)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DOZING)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DREAMING)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)
            assertThat(isAod()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.LOCKSCREEN)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.GONE)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OCCLUDED)
            assertThat(isAod()).isFalse()
        }

    @Test
    fun isAwakeChanges() =
        testScope.runTest {
            val isAwake = collectLastValue(interactor.isAwake)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OFF)
            assertThat(isAwake()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DOZING)
            assertThat(isAwake()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DREAMING)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)
            assertThat(isAwake()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.LOCKSCREEN)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.GONE)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OCCLUDED)
            assertThat(isAwake()).isTrue()
        }

    @Test
    fun displayStateChanges() =
        testScope.runTest {
            val displayState = collectLastValue(interactor.displayState)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OFF)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_NO_UI)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DOZING)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_NO_UI)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DREAMING)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_SCREENSAVER)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_AOD)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.LOCKSCREEN)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.GONE)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_UNKNOWN)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OCCLUDED)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)
        }

    @Test
    fun isHardwareIgnoringTouchesChanges() =
        testScope.runTest {
            val isHardwareIgnoringTouches by collectLastValue(interactor.isHardwareIgnoringTouches)

            udfpsOverlayInteractor.setHandleTouches(true)
            assertThat(isHardwareIgnoringTouches).isFalse()

            udfpsOverlayInteractor.setHandleTouches(false)
            assertThat(isHardwareIgnoringTouches).isTrue()
        }

    @Test
    fun foldStateChanges() =
        testScope.runTest {
            val foldState by collectLastValue(interactor.foldState)

            deviceStateRepository.emit(DeviceStateRepository.DeviceState.HALF_FOLDED)
            assertThat(foldState).isEqualTo(FoldState.HALF_OPENED)

            deviceStateRepository.emit(DeviceStateRepository.DeviceState.CONCURRENT_DISPLAY)
            assertThat(foldState).isEqualTo(FoldState.FULLY_OPENED)

            deviceStateRepository.emit(DeviceStateRepository.DeviceState.UNFOLDED)
            assertThat(foldState).isEqualTo(FoldState.FULLY_OPENED)

            deviceStateRepository.emit(DeviceStateRepository.DeviceState.FOLDED)
            assertThat(foldState).isEqualTo(FoldState.FULLY_CLOSED)

            deviceStateRepository.emit(DeviceStateRepository.DeviceState.REAR_DISPLAY)
            assertThat(foldState).isEqualTo(FoldState.FULLY_OPENED)

            deviceStateRepository.emit(DeviceStateRepository.DeviceState.UNKNOWN)
            assertThat(foldState).isEqualTo(FoldState.UNKNOWN)
        }

    @Test
    fun contextSubscriberChanges() =
        testScope.runTest {
            deviceStateRepository.emit(DeviceStateRepository.DeviceState.FOLDED)
            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)

            var folded: Int? = null
            var displayState: Int? = null
            var ignoreTouches: Boolean? = null
            val job =
                interactor.addBiometricContextListener(
                    object : IBiometricContextListener.Stub() {
                        override fun onFoldChanged(foldState: Int) {
                            folded = foldState
                        }

                        override fun onDisplayStateChanged(newDisplayState: Int) {
                            displayState = newDisplayState
                        }

                        override fun onHardwareIgnoreTouchesChanged(newIgnoreTouches: Boolean) {
                            ignoreTouches = newIgnoreTouches
                        }
                    }
                )
            runCurrent()

            assertThat(folded).isEqualTo(FoldState.FULLY_CLOSED)
            assertThat(displayState).isEqualTo(AuthenticateOptions.DISPLAY_STATE_AOD)
            assertThat(ignoreTouches).isFalse()

            deviceStateRepository.emit(DeviceStateRepository.DeviceState.HALF_FOLDED)
            keyguardTransitionRepository.startTransitionTo(KeyguardState.LOCKSCREEN)
            runCurrent()

            assertThat(folded).isEqualTo(FoldState.HALF_OPENED)
            assertThat(displayState).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)

            udfpsOverlayInteractor.setHandleTouches(false)
            runCurrent()

            assertThat(ignoreTouches).isTrue()

            job.cancel()

            // stale updates should be ignored
            deviceStateRepository.emit(DeviceStateRepository.DeviceState.UNFOLDED)
            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)
            runCurrent()

            assertThat(folded).isEqualTo(FoldState.HALF_OPENED)
            assertThat(displayState).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)
        }
}

private suspend fun FakeKeyguardTransitionRepository.startTransitionTo(newState: KeyguardState) =
    sendTransitionStep(TransitionStep(to = newState, transitionState = TransitionState.STARTED))
