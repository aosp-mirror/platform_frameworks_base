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
package com.android.systemui.flags

import android.test.suitebuilder.annotation.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP
import com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

/**
 * Be careful with the {FeatureFlagsReleaseRestarter} in this test. It has a call to System.exit()!
 */
@SmallTest
class ScreenIdleConditionTest : SysuiTestCase() {
    private lateinit var condition: ScreenIdleCondition

    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        condition = ScreenIdleCondition(wakefulnessLifecycle)
    }

    @Test
    fun testCondition_awake() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_AWAKE)

        assertThat(condition.canRestartNow {}).isFalse()
    }

    @Test
    fun testCondition_asleep() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_ASLEEP)

        assertThat(condition.canRestartNow {}).isTrue()
    }

    @Test
    fun testCondition_invokesRetry() {
        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_AWAKE)
        var retried = false
        val retryFn = { retried = true }

        // No restart yet, but we do register a listener now.
        assertThat(condition.canRestartNow(retryFn)).isFalse()
        val captor = ArgumentCaptor.forClass(WakefulnessLifecycle.Observer::class.java)
        verify(wakefulnessLifecycle).addObserver(captor.capture())

        whenever(wakefulnessLifecycle.wakefulness).thenReturn(WAKEFULNESS_ASLEEP)

        captor.value.onFinishedGoingToSleep()
        assertThat(retried).isTrue()
    }
}
