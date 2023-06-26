/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.data.repository

import android.graphics.Point
import android.hardware.biometrics.BiometricSourceType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.RoboPilotTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.common.shared.model.Position
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.doze.DozeMachine
import com.android.systemui.doze.DozeTransitionCallback
import com.android.systemui.doze.DozeTransitionListener
import com.android.systemui.dreams.DreamOverlayCallbackController
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RoboPilotTest
@RunWith(AndroidJUnit4::class)
class KeyguardRepositoryImplTest : SysuiTestCase() {

    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock private lateinit var dozeTransitionListener: DozeTransitionListener
    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var dreamOverlayCallbackController: DreamOverlayCallbackController
    @Mock private lateinit var dozeParameters: DozeParameters
    private val mainDispatcher = StandardTestDispatcher()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: KeyguardRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            KeyguardRepositoryImpl(
                statusBarStateController,
                wakefulnessLifecycle,
                biometricUnlockController,
                keyguardStateController,
                keyguardUpdateMonitor,
                dozeTransitionListener,
                dozeParameters,
                authController,
                dreamOverlayCallbackController,
                mainDispatcher,
                testScope.backgroundScope,
            )
    }

    @Test
    fun animateBottomAreaDozingTransitions() =
        testScope.runTest {
            assertThat(underTest.animateBottomAreaDozingTransitions.value).isEqualTo(false)

            underTest.setAnimateDozingTransitions(true)
            assertThat(underTest.animateBottomAreaDozingTransitions.value).isTrue()

            underTest.setAnimateDozingTransitions(false)
            assertThat(underTest.animateBottomAreaDozingTransitions.value).isFalse()

            underTest.setAnimateDozingTransitions(true)
            assertThat(underTest.animateBottomAreaDozingTransitions.value).isTrue()
        }

    @Test
    fun bottomAreaAlpha() =
        testScope.runTest {
            assertThat(underTest.bottomAreaAlpha.value).isEqualTo(1f)

            underTest.setBottomAreaAlpha(0.1f)
            assertThat(underTest.bottomAreaAlpha.value).isEqualTo(0.1f)

            underTest.setBottomAreaAlpha(0.2f)
            assertThat(underTest.bottomAreaAlpha.value).isEqualTo(0.2f)

            underTest.setBottomAreaAlpha(0.3f)
            assertThat(underTest.bottomAreaAlpha.value).isEqualTo(0.3f)

            underTest.setBottomAreaAlpha(0.5f)
            assertThat(underTest.bottomAreaAlpha.value).isEqualTo(0.5f)

            underTest.setBottomAreaAlpha(1.0f)
            assertThat(underTest.bottomAreaAlpha.value).isEqualTo(1f)
        }

    @Test
    fun clockPosition() =
        testScope.runTest {
            assertThat(underTest.clockPosition.value).isEqualTo(Position(0, 0))

            underTest.setClockPosition(0, 1)
            assertThat(underTest.clockPosition.value).isEqualTo(Position(0, 1))

            underTest.setClockPosition(1, 9)
            assertThat(underTest.clockPosition.value).isEqualTo(Position(1, 9))

            underTest.setClockPosition(1, 0)
            assertThat(underTest.clockPosition.value).isEqualTo(Position(1, 0))

            underTest.setClockPosition(3, 1)
            assertThat(underTest.clockPosition.value).isEqualTo(Position(3, 1))
        }

    @Test
    fun isKeyguardShowing() =
        testScope.runTest {
            whenever(keyguardStateController.isShowing).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isKeyguardShowing.onEach { latest = it }.launchIn(this)

            runCurrent()
            assertThat(latest).isFalse()
            assertThat(underTest.isKeyguardShowing()).isFalse()

            val captor = argumentCaptor<KeyguardStateController.Callback>()
            verify(keyguardStateController).addCallback(captor.capture())

            whenever(keyguardStateController.isShowing).thenReturn(true)
            captor.value.onKeyguardShowingChanged()
            runCurrent()
            assertThat(latest).isTrue()
            assertThat(underTest.isKeyguardShowing()).isTrue()

            whenever(keyguardStateController.isShowing).thenReturn(false)
            captor.value.onKeyguardShowingChanged()
            runCurrent()
            assertThat(latest).isFalse()
            assertThat(underTest.isKeyguardShowing()).isFalse()

            job.cancel()
        }

    @Test
    fun isAodAvailable() = runTest {
        val flow = underTest.isAodAvailable
        var isAodAvailable = collectLastValue(flow)
        runCurrent()

        val callback =
            withArgCaptor<DozeParameters.Callback> { verify(dozeParameters).addCallback(capture()) }

        whenever(dozeParameters.getAlwaysOn()).thenReturn(false)
        callback.onAlwaysOnChange()
        assertThat(isAodAvailable()).isEqualTo(false)

        whenever(dozeParameters.getAlwaysOn()).thenReturn(true)
        callback.onAlwaysOnChange()
        assertThat(isAodAvailable()).isEqualTo(true)

        flow.onCompletion { verify(dozeParameters).removeCallback(callback) }
    }

    @Test
    fun isKeyguardOccluded() =
        testScope.runTest {
            whenever(keyguardStateController.isOccluded).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isKeyguardOccluded.onEach { latest = it }.launchIn(this)

            runCurrent()
            assertThat(latest).isFalse()

            val captor = argumentCaptor<KeyguardStateController.Callback>()
            verify(keyguardStateController).addCallback(captor.capture())

            whenever(keyguardStateController.isOccluded).thenReturn(true)
            captor.value.onKeyguardShowingChanged()
            runCurrent()
            assertThat(latest).isTrue()

            whenever(keyguardStateController.isOccluded).thenReturn(false)
            captor.value.onKeyguardShowingChanged()
            runCurrent()
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isKeyguardUnlocked() =
        testScope.runTest {
            whenever(keyguardStateController.isUnlocked).thenReturn(false)
            val isKeyguardUnlocked by collectLastValue(underTest.isKeyguardUnlocked)

            runCurrent()
            assertThat(isKeyguardUnlocked).isFalse()

            val captor = argumentCaptor<KeyguardStateController.Callback>()
            verify(keyguardStateController).addCallback(captor.capture())

            whenever(keyguardStateController.isUnlocked).thenReturn(true)
            captor.value.onUnlockedChanged()
            runCurrent()
            assertThat(isKeyguardUnlocked).isTrue()

            whenever(keyguardStateController.isUnlocked).thenReturn(false)
            captor.value.onKeyguardShowingChanged()
            runCurrent()
            assertThat(isKeyguardUnlocked).isFalse()
        }

    @Test
    fun isDozing() =
        testScope.runTest {
            underTest.setIsDozing(true)
            assertThat(underTest.isDozing.value).isEqualTo(true)

            underTest.setIsDozing(false)
            assertThat(underTest.isDozing.value).isEqualTo(false)
        }

    @Test
    fun isDozing_startsWithCorrectInitialValueForIsDozing() =
        testScope.runTest {
            assertThat(underTest.lastDozeTapToWakePosition.value).isEqualTo(null)

            val expectedPoint = Point(100, 200)
            underTest.setLastDozeTapToWakePosition(expectedPoint)
            assertThat(underTest.lastDozeTapToWakePosition.value).isEqualTo(expectedPoint)
        }

    @Test
    fun dozeAmount() =
        testScope.runTest {
            val values = mutableListOf<Float>()
            val job = underTest.linearDozeAmount.onEach(values::add).launchIn(this)

            val captor = argumentCaptor<StatusBarStateController.StateListener>()
            runCurrent()
            verify(statusBarStateController).addCallback(captor.capture())

            captor.value.onDozeAmountChanged(0.433f, 0.4f)
            runCurrent()
            captor.value.onDozeAmountChanged(0.498f, 0.5f)
            runCurrent()
            captor.value.onDozeAmountChanged(0.661f, 0.65f)
            runCurrent()

            assertThat(values).isEqualTo(listOf(0f, 0.433f, 0.498f, 0.661f))

            job.cancel()
            runCurrent()
            verify(statusBarStateController).removeCallback(captor.value)
        }

    @Test
    fun wakefulness() =
        testScope.runTest {
            val values = mutableListOf<WakefulnessModel>()
            val job = underTest.wakefulness.onEach(values::add).launchIn(this)

            runCurrent()
            val captor = argumentCaptor<WakefulnessLifecycle.Observer>()
            verify(wakefulnessLifecycle).addObserver(captor.capture())

            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_WAKING)
            captor.value.onStartedWakingUp()
            runCurrent()

            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE)
            captor.value.onFinishedWakingUp()
            runCurrent()

            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP)
            captor.value.onStartedGoingToSleep()
            runCurrent()

            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_ASLEEP)
            captor.value.onFinishedGoingToSleep()
            runCurrent()

            assertThat(values.map { it.state })
                .isEqualTo(
                    listOf(
                        // Initial value will be ASLEEP
                        WakefulnessState.ASLEEP,
                        WakefulnessState.STARTING_TO_WAKE,
                        WakefulnessState.AWAKE,
                        WakefulnessState.STARTING_TO_SLEEP,
                        WakefulnessState.ASLEEP,
                    )
                )

            job.cancel()
        }

    @Test
    fun isUdfpsSupported() =
        testScope.runTest {
            whenever(keyguardUpdateMonitor.isUdfpsSupported).thenReturn(true)
            assertThat(underTest.isUdfpsSupported()).isTrue()

            whenever(keyguardUpdateMonitor.isUdfpsSupported).thenReturn(false)
            assertThat(underTest.isUdfpsSupported()).isFalse()
        }

    @Test
    fun isKeyguardGoingAway() =
        testScope.runTest {
            whenever(keyguardStateController.isKeyguardGoingAway).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isKeyguardGoingAway.onEach { latest = it }.launchIn(this)
            runCurrent()
            assertThat(latest).isFalse()

            val captor = argumentCaptor<KeyguardStateController.Callback>()
            verify(keyguardStateController).addCallback(captor.capture())

            whenever(keyguardStateController.isKeyguardGoingAway).thenReturn(true)
            captor.value.onKeyguardGoingAwayChanged()
            runCurrent()
            assertThat(latest).isTrue()

            whenever(keyguardStateController.isKeyguardGoingAway).thenReturn(false)
            captor.value.onKeyguardGoingAwayChanged()
            runCurrent()
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isDreamingFromKeyguardUpdateMonitor() =
        TestScope(mainDispatcher).runTest {
            whenever(keyguardUpdateMonitor.isDreaming()).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isDreaming.onEach { latest = it }.launchIn(this)

            runCurrent()
            assertThat(latest).isFalse()

            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(captor.capture())

            captor.value.onDreamingStateChanged(true)
            runCurrent()
            assertThat(latest).isTrue()

            captor.value.onDreamingStateChanged(false)
            runCurrent()
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isDreamingFromDreamOverlayCallbackController() =
        testScope.runTest {
            whenever(dreamOverlayCallbackController.isDreaming).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isDreamingWithOverlay.onEach { latest = it }.launchIn(this)

            runCurrent()
            assertThat(latest).isFalse()

            val listener =
                withArgCaptor<DreamOverlayCallbackController.Callback> {
                    verify(dreamOverlayCallbackController).addCallback(capture())
                }

            listener.onStartDream()
            runCurrent()
            assertThat(latest).isTrue()

            listener.onWakeUp()
            runCurrent()
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun biometricUnlockState() =
        testScope.runTest {
            val values = mutableListOf<BiometricUnlockModel>()
            val job = underTest.biometricUnlockState.onEach(values::add).launchIn(this)

            runCurrent()
            val captor = argumentCaptor<BiometricUnlockController.BiometricUnlockEventsListener>()
            verify(biometricUnlockController).addListener(captor.capture())

            listOf(
                    BiometricUnlockController.MODE_NONE,
                    BiometricUnlockController.MODE_WAKE_AND_UNLOCK,
                    BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING,
                    BiometricUnlockController.MODE_SHOW_BOUNCER,
                    BiometricUnlockController.MODE_ONLY_WAKE,
                    BiometricUnlockController.MODE_UNLOCK_COLLAPSING,
                    BiometricUnlockController.MODE_DISMISS_BOUNCER,
                    BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM,
                )
                .forEach {
                    whenever(biometricUnlockController.mode).thenReturn(it)
                    captor.value.onModeChanged(it)
                    runCurrent()
                }

            assertThat(values)
                .isEqualTo(
                    listOf(
                        // Initial value will be NONE, followed by onModeChanged() call
                        BiometricUnlockModel.NONE,
                        BiometricUnlockModel.NONE,
                        BiometricUnlockModel.WAKE_AND_UNLOCK,
                        BiometricUnlockModel.WAKE_AND_UNLOCK_PULSING,
                        BiometricUnlockModel.SHOW_BOUNCER,
                        BiometricUnlockModel.ONLY_WAKE,
                        BiometricUnlockModel.UNLOCK_COLLAPSING,
                        BiometricUnlockModel.DISMISS_BOUNCER,
                        BiometricUnlockModel.WAKE_AND_UNLOCK_FROM_DREAM,
                    )
                )

            job.cancel()
            runCurrent()
            verify(biometricUnlockController).removeListener(captor.value)
        }

    @Test
    fun dozeTransitionModel() =
        testScope.runTest {
            // For the initial state
            whenever(dozeTransitionListener.oldState).thenReturn(DozeMachine.State.UNINITIALIZED)
            whenever(dozeTransitionListener.newState).thenReturn(DozeMachine.State.UNINITIALIZED)

            val values = mutableListOf<DozeTransitionModel>()
            val job = underTest.dozeTransitionModel.onEach(values::add).launchIn(this)

            runCurrent()
            val listener =
                withArgCaptor<DozeTransitionCallback> {
                    verify(dozeTransitionListener).addCallback(capture())
                }

            // These don't have to reflect real transitions from the DozeMachine. Only that the
            // transitions are properly emitted
            listener.onDozeTransition(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE)
            runCurrent()
            listener.onDozeTransition(DozeMachine.State.DOZE, DozeMachine.State.DOZE_AOD)
            runCurrent()
            listener.onDozeTransition(DozeMachine.State.DOZE_AOD_DOCKED, DozeMachine.State.FINISH)
            runCurrent()
            listener.onDozeTransition(
                DozeMachine.State.DOZE_REQUEST_PULSE,
                DozeMachine.State.DOZE_PULSING
            )
            runCurrent()
            listener.onDozeTransition(
                DozeMachine.State.DOZE_SUSPEND_TRIGGERS,
                DozeMachine.State.DOZE_PULSE_DONE
            )
            runCurrent()
            listener.onDozeTransition(
                DozeMachine.State.DOZE_AOD_PAUSING,
                DozeMachine.State.DOZE_AOD_PAUSED
            )
            runCurrent()

            assertThat(values)
                .isEqualTo(
                    listOf(
                        // Initial value will be UNINITIALIZED
                        DozeTransitionModel(
                            DozeStateModel.UNINITIALIZED,
                            DozeStateModel.UNINITIALIZED
                        ),
                        DozeTransitionModel(DozeStateModel.INITIALIZED, DozeStateModel.DOZE),
                        DozeTransitionModel(DozeStateModel.DOZE, DozeStateModel.DOZE_AOD),
                        DozeTransitionModel(DozeStateModel.DOZE_AOD_DOCKED, DozeStateModel.FINISH),
                        DozeTransitionModel(
                            DozeStateModel.DOZE_REQUEST_PULSE,
                            DozeStateModel.DOZE_PULSING
                        ),
                        DozeTransitionModel(
                            DozeStateModel.DOZE_SUSPEND_TRIGGERS,
                            DozeStateModel.DOZE_PULSE_DONE
                        ),
                        DozeTransitionModel(
                            DozeStateModel.DOZE_AOD_PAUSING,
                            DozeStateModel.DOZE_AOD_PAUSED
                        ),
                    )
                )

            job.cancel()
            runCurrent()
            verify(dozeTransitionListener).removeCallback(listener)
        }

    @Test
    fun fingerprintSensorLocation() =
        testScope.runTest {
            val values = mutableListOf<Point?>()
            val job = underTest.fingerprintSensorLocation.onEach(values::add).launchIn(this)

            runCurrent()
            val captor = argumentCaptor<AuthController.Callback>()
            verify(authController).addCallback(captor.capture())

            // An initial, null value should be initially emitted so that flows combined with this
            // one
            // emit values immediately. The sensor location is expected to be nullable, so anyone
            // consuming it should handle that properly.
            assertThat(values).isEqualTo(listOf(null))

            listOf(Point(500, 500), Point(0, 0), null, Point(250, 250))
                .onEach {
                    whenever(authController.fingerprintSensorLocation).thenReturn(it)
                    captor.value.onFingerprintLocationChanged()
                    runCurrent()
                }
                .also { dispatchedSensorLocations ->
                    assertThat(values).isEqualTo(listOf(null) + dispatchedSensorLocations)
                }

            job.cancel()
        }

    @Test
    fun faceSensorLocation() =
        testScope.runTest {
            val values = mutableListOf<Point?>()
            val job = underTest.faceSensorLocation.onEach(values::add).launchIn(this)

            val captor = argumentCaptor<AuthController.Callback>()
            runCurrent()
            verify(authController).addCallback(captor.capture())

            // An initial, null value should be initially emitted so that flows combined with this
            // one
            // emit values immediately. The sensor location is expected to be nullable, so anyone
            // consuming it should handle that properly.
            assertThat(values).isEqualTo(listOf(null))

            listOf(
                    Point(500, 500),
                    Point(0, 0),
                    null,
                    Point(250, 250),
                )
                .onEach {
                    whenever(authController.faceSensorLocation).thenReturn(it)
                    captor.value.onFaceSensorLocationChanged()
                    runCurrent()
                }
                .also { dispatchedSensorLocations ->
                    assertThat(values).isEqualTo(listOf(null) + dispatchedSensorLocations)
                }

            job.cancel()
        }

    @Test
    fun biometricUnlockSource() =
        testScope.runTest {
            val values = mutableListOf<BiometricUnlockSource?>()
            val job = underTest.biometricUnlockSource.onEach(values::add).launchIn(this)

            runCurrent()
            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(captor.capture())

            // An initial, null value should be initially emitted so that flows combined with this
            // one
            // emit values immediately. The biometric unlock source is expected to be nullable, so
            // anyone consuming it should handle that properly.
            assertThat(values).isEqualTo(listOf(null))

            listOf(
                    BiometricSourceType.FINGERPRINT,
                    BiometricSourceType.IRIS,
                    null,
                    BiometricSourceType.FACE,
                    BiometricSourceType.FINGERPRINT,
                )
                .onEach { biometricSourceType ->
                    captor.value.onBiometricAuthenticated(0, biometricSourceType, false)
                    runCurrent()
                }

            assertThat(values)
                .isEqualTo(
                    listOf(
                        null,
                        BiometricUnlockSource.FINGERPRINT_SENSOR,
                        BiometricUnlockSource.FACE_SENSOR,
                        null,
                        BiometricUnlockSource.FACE_SENSOR,
                        BiometricUnlockSource.FINGERPRINT_SENSOR,
                    )
                )

            job.cancel()
        }
}
