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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Be careful with the {FeatureFlagsReleaseRestarter} in this test. It has a call to System.exit()!
 */
@SmallTest
class ConditionalRestarterTest : SysuiTestCase() {
    private lateinit var restarter: ConditionalRestarter

    @Mock private lateinit var systemExitRestarter: SystemExitRestarter

    val restartDelayMs = 0L
    val dispatcher = StandardTestDispatcher()
    val testScope = TestScope(dispatcher)

    val conditionA = FakeCondition()
    val conditionB = FakeCondition()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        restarter =
            ConditionalRestarter(
                systemExitRestarter,
                setOf(conditionA, conditionB),
                restartDelayMs,
                testScope,
                dispatcher
            )
    }

    @Test
    fun restart_ImmediatelySatisfied() =
        testScope.runTest {
            conditionA.canRestart = true
            conditionB.canRestart = true
            restarter.restartSystemUI("Restart for test")
            advanceUntilIdle()
            verify(systemExitRestarter).restartSystemUI(any())
        }

    @Test
    fun restart_WaitsForConditionA() =
        testScope.runTest {
            conditionA.canRestart = false
            conditionB.canRestart = true

            restarter.restartSystemUI("Restart for test")
            advanceUntilIdle()
            // No restart occurs yet.
            verify(systemExitRestarter, never()).restartSystemUI(any())

            conditionA.canRestart = true
            conditionA.retryFn?.invoke()
            advanceUntilIdle()
            verify(systemExitRestarter).restartSystemUI(any())
        }

    @Test
    fun restart_WaitsForConditionB() =
        testScope.runTest {
            conditionA.canRestart = true
            conditionB.canRestart = false

            restarter.restartSystemUI("Restart for test")
            advanceUntilIdle()
            // No restart occurs yet.
            verify(systemExitRestarter, never()).restartSystemUI(any())

            conditionB.canRestart = true
            conditionB.retryFn?.invoke()
            advanceUntilIdle()
            verify(systemExitRestarter).restartSystemUI(any())
        }

    @Test
    fun restart_WaitsForAllConditions() =
        testScope.runTest {
            conditionA.canRestart = true
            conditionB.canRestart = false

            restarter.restartSystemUI("Restart for test")
            advanceUntilIdle()
            // No restart occurs yet.
            verify(systemExitRestarter, never()).restartSystemUI(any())

            // B becomes true, but A is now false
            conditionA.canRestart = false
            conditionB.canRestart = true
            conditionB.retryFn?.invoke()
            advanceUntilIdle()
            // No restart occurs yet.
            verify(systemExitRestarter, never()).restartSystemUI(any())

            conditionA.canRestart = true
            conditionA.retryFn?.invoke()
            advanceUntilIdle()
            verify(systemExitRestarter).restartSystemUI(any())
        }

    class FakeCondition : ConditionalRestarter.Condition {
        var retryFn: (() -> Unit)? = null
        var canRestart = false

        override fun canRestartNow(retryFn: () -> Unit): Boolean {
            this.retryFn = retryFn

            return canRestart
        }
    }
}
