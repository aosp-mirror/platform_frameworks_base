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

import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Position
import com.android.systemui.doze.DozeHost
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
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
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController

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
            )
    }

    @Test
    fun animateBottomAreaDozingTransitions() = runBlockingTest {
        assertThat(underTest.animateBottomAreaDozingTransitions.value).isEqualTo(false)

        underTest.setAnimateDozingTransitions(true)
        assertThat(underTest.animateBottomAreaDozingTransitions.value).isTrue()

        underTest.setAnimateDozingTransitions(false)
        assertThat(underTest.animateBottomAreaDozingTransitions.value).isFalse()

        underTest.setAnimateDozingTransitions(true)
        assertThat(underTest.animateBottomAreaDozingTransitions.value).isTrue()
    }

    @Test
    fun bottomAreaAlpha() = runBlockingTest {
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
    fun clockPosition() = runBlockingTest {
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
    fun isKeyguardShowing() = runBlockingTest {
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
    fun isDozing() = runBlockingTest {
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
    fun `isDozing - starts with correct initial value for isDozing`() = runBlockingTest {
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
    fun dozeAmount() = runBlockingTest {
        val values = mutableListOf<Float>()
        val job = underTest.dozeAmount.onEach(values::add).launchIn(this)

        val captor = argumentCaptor<StatusBarStateController.StateListener>()
        verify(statusBarStateController).addCallback(captor.capture())

        captor.value.onDozeAmountChanged(0.433f, 0.4f)
        captor.value.onDozeAmountChanged(0.498f, 0.5f)
        captor.value.onDozeAmountChanged(0.661f, 0.65f)

        assertThat(values).isEqualTo(listOf(0f, 0.4f, 0.5f, 0.65f))

        job.cancel()
        verify(statusBarStateController).removeCallback(captor.value)
    }

    @Test
    fun wakefulness() = runBlockingTest {
        val values = mutableListOf<WakefulnessModel>()
        val job = underTest.wakefulnessState.onEach(values::add).launchIn(this)

        val captor = argumentCaptor<WakefulnessLifecycle.Observer>()
        verify(wakefulnessLifecycle).addObserver(captor.capture())

        captor.value.onStartedWakingUp()
        captor.value.onFinishedWakingUp()
        captor.value.onStartedGoingToSleep()
        captor.value.onFinishedGoingToSleep()

        assertThat(values)
            .isEqualTo(
                listOf(
                    // Initial value will be ASLEEP
                    WakefulnessModel.ASLEEP,
                    WakefulnessModel.STARTING_TO_WAKE,
                    WakefulnessModel.AWAKE,
                    WakefulnessModel.STARTING_TO_SLEEP,
                    WakefulnessModel.ASLEEP,
                )
            )

        job.cancel()
        verify(wakefulnessLifecycle).removeObserver(captor.value)
    }

    @Test
    fun isUdfpsSupported() = runBlockingTest {
        whenever(keyguardUpdateMonitor.isUdfpsSupported).thenReturn(true)
        assertThat(underTest.isUdfpsSupported()).isTrue()

        whenever(keyguardUpdateMonitor.isUdfpsSupported).thenReturn(false)
        assertThat(underTest.isUdfpsSupported()).isFalse()
    }

    @Test
    fun isBouncerShowing() = runBlockingTest {
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
    fun isKeyguardGoingAway() = runBlockingTest {
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
    fun biometricUnlockState() = runBlockingTest {
        val values = mutableListOf<BiometricUnlockModel>()
        val job = underTest.biometricUnlockState.onEach(values::add).launchIn(this)

        val captor = argumentCaptor<BiometricUnlockController.BiometricModeListener>()
        verify(biometricUnlockController).addBiometricModeListener(captor.capture())

        captor.value.onModeChanged(BiometricUnlockController.MODE_NONE)
        captor.value.onModeChanged(BiometricUnlockController.MODE_WAKE_AND_UNLOCK)
        captor.value.onModeChanged(BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING)
        captor.value.onModeChanged(BiometricUnlockController.MODE_SHOW_BOUNCER)
        captor.value.onModeChanged(BiometricUnlockController.MODE_ONLY_WAKE)
        captor.value.onModeChanged(BiometricUnlockController.MODE_UNLOCK_COLLAPSING)
        captor.value.onModeChanged(BiometricUnlockController.MODE_DISMISS_BOUNCER)
        captor.value.onModeChanged(BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM)

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
}
