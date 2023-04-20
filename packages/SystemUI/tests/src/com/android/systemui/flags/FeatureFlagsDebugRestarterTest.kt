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
class FeatureFlagsDebugRestarterTest : SysuiTestCase() {
    private lateinit var restarter: FeatureFlagsDebugRestarter

    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var systemExitRestarter: SystemExitRestarter

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        restarter = FeatureFlagsDebugRestarter(wakefulnessLifecycle, systemExitRestarter)
    }

    @Test
    fun testRestart_ImmediateWhenAsleep() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_ASLEEP)
        restarter.restartSystemUI()
        verify(systemExitRestarter).restartSystemUI()
    }

    @Test
    fun testRestart_WaitsForSceenOff() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_AWAKE)

        restarter.restartSystemUI()
        verify(systemExitRestarter, never()).restartSystemUI()

        val captor = ArgumentCaptor.forClass(WakefulnessLifecycle.Observer::class.java)
        verify(wakefulnessLifecycle).addObserver(captor.capture())

        captor.value.onFinishedGoingToSleep()

        verify(systemExitRestarter).restartSystemUI()
    }
}
