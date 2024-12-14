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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

/**
 * Be careful with the {FeatureFlagsReleaseRestarter} in this test. It has a call to System.exit()!
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScreenIdleConditionTest : SysuiTestCase() {
    private lateinit var condition: ScreenIdleCondition

    @Mock private lateinit var powerInteractor: PowerInteractor
    private val isAsleep = MutableStateFlow(false)

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val testScope: TestScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(powerInteractor.isAsleep).thenReturn(isAsleep)
        condition = ScreenIdleCondition({ powerInteractor })
    }

    @Test
    fun testCondition_awake() =
        testScope.runTest {
            val canRestart by collectLastValue(condition.canRestartNow)

            isAsleep.emit(false)

            assertThat(canRestart).isFalse()
        }

    @Test
    fun testCondition_asleep() =
        testScope.runTest {
            val canRestart by collectLastValue(condition.canRestartNow)

            isAsleep.emit(true)

            assertThat(canRestart).isTrue()
        }

    @Test
    fun testCondition_invokesRetry() =
        testScope.runTest {
            val canRestart by collectLastValue(condition.canRestartNow)

            isAsleep.emit(false)

            assertThat(canRestart).isFalse()

            isAsleep.emit(true)

            assertThat(canRestart).isTrue()
        }
}
