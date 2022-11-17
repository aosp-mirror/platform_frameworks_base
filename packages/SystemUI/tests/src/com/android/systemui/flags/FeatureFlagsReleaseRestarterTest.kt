/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.flags

import android.test.suitebuilder.annotation.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP
import com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

/**
 * Be careful with the {FeatureFlagsReleaseRestarter} in this test. It has a call to System.exit()!
 */
@SmallTest
class FeatureFlagsReleaseRestarterTest : SysuiTestCase() {
    private lateinit var restarter: FeatureFlagsReleaseRestarter

    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var batteryController: BatteryController
    @Mock private lateinit var systemExitRestarter: SystemExitRestarter
    private val executor = FakeExecutor(FakeSystemClock())

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        restarter =
            FeatureFlagsReleaseRestarter(
                wakefulnessLifecycle,
                batteryController,
                executor,
                systemExitRestarter
            )
    }

    @Test
    fun testRestart_ScheduledWhenReady() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_ASLEEP)
        whenever(batteryController.isPluggedIn).thenReturn(true)

        assertThat(executor.numPending()).isEqualTo(0)
        restarter.restartSystemUI()
        assertThat(executor.numPending()).isEqualTo(1)
    }

    @Test
    fun testRestart_RestartsWhenIdle() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_ASLEEP)
        whenever(batteryController.isPluggedIn).thenReturn(true)

        restarter.restartSystemUI()
        verify(systemExitRestarter, never()).restartSystemUI()
        executor.advanceClockToLast()
        executor.runAllReady()
        verify(systemExitRestarter).restartSystemUI()
    }

    @Test
    fun testRestart_NotScheduledWhenAwake() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_AWAKE)
        whenever(batteryController.isPluggedIn).thenReturn(true)

        assertThat(executor.numPending()).isEqualTo(0)
        restarter.restartSystemUI()
        assertThat(executor.numPending()).isEqualTo(0)
    }

    @Test
    fun testRestart_NotScheduledWhenNotPluggedIn() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_ASLEEP)
        whenever(batteryController.isPluggedIn).thenReturn(false)

        assertThat(executor.numPending()).isEqualTo(0)
        restarter.restartSystemUI()
        assertThat(executor.numPending()).isEqualTo(0)
    }

    @Test
    fun testRestart_NotDoubleSheduled() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_ASLEEP)
        whenever(batteryController.isPluggedIn).thenReturn(true)

        assertThat(executor.numPending()).isEqualTo(0)
        restarter.restartSystemUI()
        restarter.restartSystemUI()
        assertThat(executor.numPending()).isEqualTo(1)
    }

    @Test
    fun testWakefulnessLifecycle_CanRestart() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_AWAKE)
        whenever(batteryController.isPluggedIn).thenReturn(true)
        assertThat(executor.numPending()).isEqualTo(0)
        restarter.restartSystemUI()

        val captor = ArgumentCaptor.forClass(WakefulnessLifecycle.Observer::class.java)
        verify(wakefulnessLifecycle).addObserver(captor.capture())

        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_ASLEEP)

        captor.value.onFinishedGoingToSleep()
        assertThat(executor.numPending()).isEqualTo(1)
    }

    @Test
    fun testBatteryController_CanRestart() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_ASLEEP)
        whenever(batteryController.isPluggedIn).thenReturn(false)
        assertThat(executor.numPending()).isEqualTo(0)
        restarter.restartSystemUI()

        val captor =
            ArgumentCaptor.forClass(BatteryController.BatteryStateChangeCallback::class.java)
        verify(batteryController).addCallback(captor.capture())

        whenever(batteryController.isPluggedIn).thenReturn(true)

        captor.value.onBatteryLevelChanged(0, true, true)
        assertThat(executor.numPending()).isEqualTo(1)
    }
}
