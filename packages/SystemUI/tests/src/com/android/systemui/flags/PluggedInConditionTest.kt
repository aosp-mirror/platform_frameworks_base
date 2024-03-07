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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.policy.BatteryController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val testScope: TestScope = TestScope(testDispatcher)
    private val callbackCaptor =
        ArgumentCaptor.forClass(BatteryController.BatteryStateChangeCallback::class.java)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        condition = PluggedInCondition({ batteryController })
    }

    @Test
    fun testCondition_unplugged() =
        testScope.runTest {
            whenever(batteryController.isPluggedIn).thenReturn(false)

            val canRestart by collectLastValue(condition.canRestartNow)

            assertThat(canRestart).isFalse()
        }

    @Test
    fun testCondition_pluggedIn() =
        testScope.runTest {
            whenever(batteryController.isPluggedIn).thenReturn(true)

            val canRestart by collectLastValue(condition.canRestartNow)

            assertThat(canRestart).isTrue()
        }

    @Test
    fun testCondition_invokesRetry() =
        testScope.runTest {
            whenever(batteryController.isPluggedIn).thenReturn(false)

            val canRestart by collectLastValue(condition.canRestartNow)

            assertThat(canRestart).isFalse()

            verify(batteryController).addCallback(callbackCaptor.capture())

            callbackCaptor.value.onBatteryLevelChanged(0, true, false)

            assertThat(canRestart).isTrue()
        }
}
