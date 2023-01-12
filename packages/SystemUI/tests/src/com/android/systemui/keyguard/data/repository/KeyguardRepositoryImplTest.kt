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
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.common.shared.model.Position
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.doze.DozeHost
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardRepositoryImplTest : SysuiTestCase() {

    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var dozeHost: DozeHost
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock private lateinit var dozeTransitionListener: DozeTransitionListener
    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var dreamOverlayCallbackController: DreamOverlayCallbackController
    @Mock private lateinit var dozeParameters: DozeParameters

    private lateinit var underTest: KeyguardRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            KeyguardRepositoryImpl(
                statusBarStateController,
                dozeHost,
                wakefulnessLifecycle,
                biometricUnlockController,
                keyguardStateController,
                keyguardUpdateMonitor,
                dozeTransitionListener,
                dozeParameters,
                authController,
                dreamOverlayCallbackController,
            )
    }

    @Test
    fun animateBottomAreaDozingTransitions() =
        runTest(UnconfinedTestDispatcher()) {
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
        runTest(UnconfinedTestDispatcher()) {
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
        runTest(UnconfinedTestDispatcher()) {
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
        runTest(UnconfinedTestDispatcher()) {
            whenever(keyguardStateController.isShowing).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isKeyguardShowing.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()
            assertThat(underTest.isKeyguardShowing()).isFalse()

            val captor = argumentCaptor<KeyguardStateController.Callback>()
            verify(keyguardStateController).addCallback(captor.capture())

            whenever(keyguardStateController.isShowing).thenReturn(true)
            captor.value.onKeyguardShowingChanged()
            assertThat(latest).isTrue()
            assertThat(underTest.isKeyguardShowing()).isTrue()

            whenever(keyguardStateController.isShowing).thenReturn(false)
            captor.value.onKeyguardShowingChanged()
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
        runTest(UnconfinedTestDispatcher()) {
            whenever(keyguardStateController.isOccluded).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isKeyguardOccluded.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            val captor = argumentCaptor<KeyguardStateController.Callback>()
            verify(keyguardStateController).addCallback(captor.capture())

            whenever(keyguardStateController.isOccluded).thenReturn(true)
            captor.value.onKeyguardShowingChanged()
            assertThat(latest).isTrue()

            whenever(keyguardStateController.isOccluded).thenReturn(false)
            captor.value.onKeyguardShowingChanged()
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isDozing() =
        runTest(UnconfinedTestDispatcher()) {
            var latest: Boolean? = null
            val job = underTest.isDozing.onEach { latest = it }.launchIn(this)

            val captor = argumentCaptor<DozeHost.Callback>()
            verify(dozeHost).addCallback(captor.capture())

            captor.value.onDozingChanged(true)
            assertThat(latest).isTrue()

            captor.value.onDozingChanged(false)
            assertThat(latest).isFalse()

            job.cancel()
            verify(dozeHost).removeCallback(captor.value)
        }

    @Test
    fun `isDozing - starts with correct initial value for isDozing`() =
        runTest(UnconfinedTestDispatcher()) {
            var latest: Boolean? = null

            whenever(statusBarStateController.isDozing).thenReturn(true)
            var job = underTest.isDozing.onEach { latest = it }.launchIn(this)
            assertThat(latest).isTrue()
            job.cancel()

            whenever(statusBarStateController.isDozing).thenReturn(false)
            job = underTest.isDozing.onEach { latest = it }.launchIn(this)
            assertThat(latest).isFalse()
            job.cancel()
        }

    @Test
    fun dozeAmount() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()
            val job = underTest.linearDozeAmount.onEach(values::add).launchIn(this)

            val captor = argumentCaptor<StatusBarStateController.StateListener>()
            verify(statusBarStateController).addCallback(captor.capture())

            captor.value.onDozeAmountChanged(0.433f, 0.4f)
            captor.value.onDozeAmountChanged(0.498f, 0.5f)
            captor.value.onDozeAmountChanged(0.661f, 0.65f)

            assertThat(values).isEqualTo(listOf(0f, 0.433f, 0.498f, 0.661f))

            job.cancel()
            verify(statusBarStateController).removeCallback(captor.value)
        }

    @Test
    fun wakefulness() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<WakefulnessModel>()
            val job = underTest.wakefulness.onEach(values::add).launchIn(this)

            val captor = argumentCaptor<WakefulnessLifecycle.Observer>()
            verify(wakefulnessLifecycle).addObserver(captor.capture())

            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_WAKING)
            captor.value.onStartedWakingUp()

            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE)
            captor.value.onFinishedWakingUp()

            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP)
            captor.value.onStartedGoingToSleep()

            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_ASLEEP)
            captor.value.onFinishedGoingToSleep()

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
            verify(wakefulnessLifecycle).removeObserver(captor.value)
        }

    @Test
    fun isUdfpsSupported() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(keyguardUpdateMonitor.isUdfpsSupported).thenReturn(true)
            assertThat(underTest.isUdfpsSupported()).isTrue()

            whenever(keyguardUpdateMonitor.isUdfpsSupported).thenReturn(false)
            assertThat(underTest.isUdfpsSupported()).isFalse()
        }

    @Test
    fun isBouncerShowing() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(keyguardStateController.isBouncerShowing).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isBouncerShowing.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            val captor = argumentCaptor<KeyguardStateController.Callback>()
            verify(keyguardStateController).addCallback(captor.capture())

            whenever(keyguardStateController.isBouncerShowing).thenReturn(true)
            captor.value.onBouncerShowingChanged()
            assertThat(latest).isTrue()

            whenever(keyguardStateController.isBouncerShowing).thenReturn(false)
            captor.value.onBouncerShowingChanged()
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isKeyguardGoingAway() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(keyguardStateController.isKeyguardGoingAway).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isKeyguardGoingAway.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            val captor = argumentCaptor<KeyguardStateController.Callback>()
            verify(keyguardStateController).addCallback(captor.capture())

            whenever(keyguardStateController.isKeyguardGoingAway).thenReturn(true)
            captor.value.onKeyguardGoingAwayChanged()
            assertThat(latest).isTrue()

            whenever(keyguardStateController.isKeyguardGoingAway).thenReturn(false)
            captor.value.onKeyguardGoingAwayChanged()
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isDreamingFromKeyguardUpdateMonitor() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(keyguardUpdateMonitor.isDreaming()).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isDreaming.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(captor.capture())

            captor.value.onDreamingStateChanged(true)
            assertThat(latest).isTrue()

            captor.value.onDreamingStateChanged(false)
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isDreamingFromDreamOverlayCallbackController() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(dreamOverlayCallbackController.isDreaming).thenReturn(false)
            var latest: Boolean? = null
            val job = underTest.isDreamingWithOverlay.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            val listener =
                withArgCaptor<DreamOverlayCallbackController.Callback> {
                    verify(dreamOverlayCallbackController).addCallback(capture())
                }

            listener.onStartDream()
            assertThat(latest).isTrue()

            listener.onWakeUp()
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun biometricUnlockState() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<BiometricUnlockModel>()
            val job = underTest.biometricUnlockState.onEach(values::add).launchIn(this)

            val captor = argumentCaptor<BiometricUnlockController.BiometricModeListener>()
            verify(biometricUnlockController).addBiometricModeListener(captor.capture())

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
            verify(biometricUnlockController).removeBiometricModeListener(captor.value)
        }

    @Test
    fun dozeTransitionModel() =
        runTest(UnconfinedTestDispatcher()) {
            // For the initial state
            whenever(dozeTransitionListener.oldState).thenReturn(DozeMachine.State.UNINITIALIZED)
            whenever(dozeTransitionListener.newState).thenReturn(DozeMachine.State.UNINITIALIZED)

            val values = mutableListOf<DozeTransitionModel>()
            val job = underTest.dozeTransitionModel.onEach(values::add).launchIn(this)

            val listener =
                withArgCaptor<DozeTransitionCallback> {
                    verify(dozeTransitionListener).addCallback(capture())
                }

            // These don't have to reflect real transitions from the DozeMachine. Only that the
            // transitions are properly emitted
            listener.onDozeTransition(DozeMachine.State.INITIALIZED, DozeMachine.State.DOZE)
            listener.onDozeTransition(DozeMachine.State.DOZE, DozeMachine.State.DOZE_AOD)
            listener.onDozeTransition(DozeMachine.State.DOZE_AOD_DOCKED, DozeMachine.State.FINISH)
            listener.onDozeTransition(
                DozeMachine.State.DOZE_REQUEST_PULSE,
                DozeMachine.State.DOZE_PULSING
            )
            listener.onDozeTransition(
                DozeMachine.State.DOZE_SUSPEND_TRIGGERS,
                DozeMachine.State.DOZE_PULSE_DONE
            )
            listener.onDozeTransition(
                DozeMachine.State.DOZE_AOD_PAUSING,
                DozeMachine.State.DOZE_AOD_PAUSED
            )

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
            verify(dozeTransitionListener).removeCallback(listener)
        }

    @Test
    fun fingerprintSensorLocation() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Point?>()
            val job = underTest.fingerprintSensorLocation.onEach(values::add).launchIn(this)

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
                }
                .also { dispatchedSensorLocations ->
                    assertThat(values).isEqualTo(listOf(null) + dispatchedSensorLocations)
                }

            job.cancel()
        }

    @Test
    fun faceSensorLocation() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Point?>()
            val job = underTest.faceSensorLocation.onEach(values::add).launchIn(this)

            val captor = argumentCaptor<AuthController.Callback>()
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
                }
                .also { dispatchedSensorLocations ->
                    assertThat(values).isEqualTo(listOf(null) + dispatchedSensorLocations)
                }

            job.cancel()
        }

    @Test
    fun biometricUnlockSource() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<BiometricUnlockSource?>()
            val job = underTest.biometricUnlockSource.onEach(values::add).launchIn(this)

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
