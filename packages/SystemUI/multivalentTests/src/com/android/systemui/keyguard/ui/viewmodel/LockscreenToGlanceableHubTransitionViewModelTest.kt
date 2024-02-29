/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class LockscreenToGlanceableHubTransitionViewModelTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    val configurationRepository = kosmos.fakeConfigurationRepository
    val underTest by lazy { kosmos.lockscreenToGlanceableHubTransitionViewModel }

    @Test
    fun lockscreenFadeOut() =
        testScope.runTest {
            val values by collectValues(underTest.keyguardAlpha)
            assertThat(values).containsExactly(1f)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    // Should start running here
                    step(0f, TransitionState.STARTED),
                    step(0.1f),
                    step(0.2f),
                    // ...up to here
                    step(0.3f),
                    step(0.4f),
                    step(0.5f),
                    step(0.6f),
                    step(0.7f),
                    step(0.8f),
                    // ...up to here
                    step(1f),
                ),
                testScope,
            )

            assertThat(values).hasSize(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun lockscreenTranslationX() =
        testScope.runTest {
            configurationRepository.setDimensionPixelSize(
                R.dimen.lockscreen_to_hub_transition_lockscreen_translation_x,
                -100
            )
            val values by collectValues(underTest.keyguardTranslationX)
            assertThat(values).isEmpty()

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.3f),
                    step(0.5f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values).hasSize(5)
            values.forEach { assertThat(it.value).isIn(Range.closed(-100f, 0f)) }
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.GLANCEABLE_HUB,
            value = value,
            transitionState = state,
            ownerName = this::class.java.simpleName
        )
    }
}
