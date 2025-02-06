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

import android.content.res.Configuration
import android.util.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.blurConfig
import com.android.systemui.kosmos.collectValues
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
        kosmos.runTest {
            val values by collectValues(underTest.keyguardAlpha)
            assertThat(values).isEmpty()

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

            assertThat(values).hasSize(3)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun lockscreenTranslationX() =
        kosmos.runTest {
            configurationRepository.setDimensionPixelSize(
                R.dimen.lockscreen_to_hub_transition_lockscreen_translation_x,
                -100,
            )
            val configuration = mock<Configuration>()
            whenever(configuration.layoutDirection).thenReturn(LayoutDirection.LTR)
            configurationRepository.onConfigurationChange(configuration)
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

    @Test
    @DisableSceneContainer
    fun blurBecomesMaxValueImmediately() =
        kosmos.runTest {
            val values by collectValues(underTest.windowBlurRadius)

            keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = blurConfig.minBlurRadiusPx,
                endValue = blurConfig.maxBlurRadiusPx,
                actualValuesProvider = { values },
                transitionFactory = { step, transitionState ->
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.GLANCEABLE_HUB,
                        value = step,
                        transitionState = transitionState,
                        ownerName = "LockscreenToGlanceableHubTransitionViewModelTest",
                    )
                },
                checkInterpolatedValues = false,
            )
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING,
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.GLANCEABLE_HUB,
            value = value,
            transitionState = state,
            ownerName = this::class.java.simpleName,
        )
    }
}
