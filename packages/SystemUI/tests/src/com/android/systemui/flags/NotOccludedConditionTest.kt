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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

/**
 * Be careful with the {FeatureFlagsReleaseRestarter} in this test. It has a call to System.exit()!
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class NotOccludedConditionTest : SysuiTestCase() {
    private lateinit var condition: NotOccludedCondition

    @Mock private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    private val transitionValue = MutableStateFlow(0f)

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val testScope: TestScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(keyguardTransitionInteractor.transitionValue(KeyguardState.OCCLUDED))
            .thenReturn(transitionValue)
        condition = NotOccludedCondition({ keyguardTransitionInteractor })
        testScope.runCurrent()
    }

    @Test
    fun testCondition_occluded() =
        testScope.runTest {
            val canRestart by collectLastValue(condition.canRestartNow)

            transitionValue.emit(1f)
            assertThat(canRestart).isFalse()
        }

    @Test
    fun testCondition_notOccluded() =
        testScope.runTest {
            val canRestart by collectLastValue(condition.canRestartNow)

            transitionValue.emit(0f)
            assertThat(canRestart).isTrue()
        }

    @Test
    fun testCondition_invokesRetry() =
        testScope.runTest {
            val canRestart by collectLastValue(condition.canRestartNow)

            transitionValue.emit(1f)

            assertThat(canRestart).isFalse()

            transitionValue.emit(0f)

            assertThat(canRestart).isTrue()
        }
}
