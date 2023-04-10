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
import com.android.systemui.statusbar.policy.BatteryController
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
class PluggedInConditionTest : SysuiTestCase() {
    private lateinit var condition: PluggedInCondition

    @Mock private lateinit var batteryController: BatteryController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        condition = PluggedInCondition(batteryController)
    }

    @Test
    fun testCondition_unplugged() {
        whenever(batteryController.isPluggedIn).thenReturn(false)

        assertThat(condition.canRestartNow({})).isFalse()
    }

    @Test
    fun testCondition_pluggedIn() {
        whenever(batteryController.isPluggedIn).thenReturn(true)

        assertThat(condition.canRestartNow({})).isTrue()
    }

    @Test
    fun testCondition_invokesRetry() {
        whenever(batteryController.isPluggedIn).thenReturn(false)
        var retried = false
        val retryFn = { retried = true }

        // No restart yet, but we do register a listener now.
        assertThat(condition.canRestartNow(retryFn)).isFalse()
        val captor =
            ArgumentCaptor.forClass(BatteryController.BatteryStateChangeCallback::class.java)
        verify(batteryController).addCallback(captor.capture())

        whenever(batteryController.isPluggedIn).thenReturn(true)

        captor.value.onBatteryLevelChanged(0, true, true)
        assertThat(retried).isTrue()
    }
}
