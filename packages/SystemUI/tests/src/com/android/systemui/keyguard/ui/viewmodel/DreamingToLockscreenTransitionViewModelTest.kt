/*
 * Copyright (C) 2022 The Android Open Source Project
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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.animation.Interpolators.EMPHASIZED_DECELERATE
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.FromDreamingTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.AnimationParams
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel.Companion.DREAM_OVERLAY_ALPHA
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel.Companion.DREAM_OVERLAY_TRANSLATION_Y
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel.Companion.LOCKSCREEN_ALPHA
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel.Companion.LOCKSCREEN_TRANSLATION_Y
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class DreamingToLockscreenTransitionViewModelTest : SysuiTestCase() {
    private lateinit var underTest: DreamingToLockscreenTransitionViewModel
    private lateinit var repository: FakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        val interactor = KeyguardTransitionInteractor(repository)
        underTest = DreamingToLockscreenTransitionViewModel(interactor)
    }

    @Test
    fun dreamOverlayTranslationY() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val pixels = 100
            val job =
                underTest.dreamOverlayTranslationY(pixels).onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(1f))

            // Only 3 values should be present, since the dream overlay runs for a small fraction
            // of the overall animation time
            assertThat(values.size).isEqualTo(3)
            assertThat(values[0])
                .isEqualTo(
                    EMPHASIZED_ACCELERATE.getInterpolation(
                        animValue(0f, DREAM_OVERLAY_TRANSLATION_Y)
                    ) * pixels
                )
            assertThat(values[1])
                .isEqualTo(
                    EMPHASIZED_ACCELERATE.getInterpolation(
                        animValue(0.3f, DREAM_OVERLAY_TRANSLATION_Y)
                    ) * pixels
                )
            assertThat(values[2])
                .isEqualTo(
                    EMPHASIZED_ACCELERATE.getInterpolation(
                        animValue(0.5f, DREAM_OVERLAY_TRANSLATION_Y)
                    ) * pixels
                )

            job.cancel()
        }

    @Test
    fun dreamOverlayFadeOut() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.dreamOverlayAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(1f))

            // Only two values should be present, since the dream overlay runs for a small fraction
            // of the overall animation time
            assertThat(values.size).isEqualTo(2)
            assertThat(values[0]).isEqualTo(1f - animValue(0f, DREAM_OVERLAY_ALPHA))
            assertThat(values[1]).isEqualTo(1f - animValue(0.1f, DREAM_OVERLAY_ALPHA))

            job.cancel()
        }

    @Test
    fun lockscreenFadeIn() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.lockscreenAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            // Should start running here...
            repository.sendTransitionStep(step(0.2f))
            repository.sendTransitionStep(step(0.3f))
            // ...up to here
            repository.sendTransitionStep(step(1f))

            // Only two values should be present, since the dream overlay runs for a small fraction
            // of the overall animation time
            assertThat(values.size).isEqualTo(2)
            assertThat(values[0]).isEqualTo(animValue(0.2f, LOCKSCREEN_ALPHA))
            assertThat(values[1]).isEqualTo(animValue(0.3f, LOCKSCREEN_ALPHA))

            job.cancel()
        }

    @Test
    fun lockscreenTranslationY() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val pixels = 100
            val job =
                underTest.lockscreenTranslationY(pixels).onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(4)
            assertThat(values[0])
                .isEqualTo(
                    -pixels +
                        EMPHASIZED_DECELERATE.getInterpolation(
                            animValue(0f, LOCKSCREEN_TRANSLATION_Y)
                        ) * pixels
                )
            assertThat(values[1])
                .isEqualTo(
                    -pixels +
                        EMPHASIZED_DECELERATE.getInterpolation(
                            animValue(0.3f, LOCKSCREEN_TRANSLATION_Y)
                        ) * pixels
                )
            assertThat(values[2])
                .isEqualTo(
                    -pixels +
                        EMPHASIZED_DECELERATE.getInterpolation(
                            animValue(0.5f, LOCKSCREEN_TRANSLATION_Y)
                        ) * pixels
                )
            assertThat(values[3])
                .isEqualTo(
                    -pixels +
                        EMPHASIZED_DECELERATE.getInterpolation(
                            animValue(1f, LOCKSCREEN_TRANSLATION_Y)
                        ) * pixels
                )

            job.cancel()
        }

    private fun animValue(stepValue: Float, params: AnimationParams): Float {
        val totalDuration = TO_LOCKSCREEN_DURATION
        val startValue = (params.startTime / totalDuration).toFloat()

        val multiplier = (totalDuration / params.duration).toFloat()
        return (stepValue - startValue) * multiplier
    }

    private fun step(value: Float): TransitionStep {
        return TransitionStep(
            from = KeyguardState.DREAMING,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = TransitionState.RUNNING,
            ownerName = "DreamingToLockscreenTransitionViewModelTest"
        )
    }
}
