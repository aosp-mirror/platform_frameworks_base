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

import android.util.MathUtils
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.ShadeTestUtil
import com.android.systemui.shade.shadeTestUtil
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope

val Kosmos.keyguardWindowBlurTestUtil by
    Kosmos.Fixture {
        KeyguardWindowBlurTestUtil(
            shadeTestUtil = shadeTestUtil,
            fakeKeyguardTransitionRepository = fakeKeyguardTransitionRepository,
            fakeKeyguardRepository = fakeKeyguardRepository,
            testScope = testScope,
        )
    }

class KeyguardWindowBlurTestUtil(
    private val shadeTestUtil: ShadeTestUtil,
    private val fakeKeyguardTransitionRepository: FakeKeyguardTransitionRepository,
    private val fakeKeyguardRepository: FakeKeyguardRepository,
    private val testScope: TestScope,
) {

    suspend fun assertTransitionToBlurRadius(
        transitionProgress: List<Float>,
        startValue: Float,
        endValue: Float,
        actualValuesProvider: () -> List<Float>,
        transitionFactory: (value: Float, state: TransitionState) -> TransitionStep,
        checkInterpolatedValues: Boolean = true,
    ) {
        val transitionSteps =
            listOf(
                transitionFactory(transitionProgress.first(), STARTED),
                *transitionProgress.drop(1).map { transitionFactory(it, RUNNING) }.toTypedArray(),
            )
        fakeKeyguardTransitionRepository.sendTransitionSteps(transitionSteps, testScope)

        val interpolationFunction = { step: Float -> MathUtils.lerp(startValue, endValue, step) }

        if (checkInterpolatedValues) {
            assertThat(actualValuesProvider.invoke())
                .containsExactly(*transitionProgress.map(interpolationFunction).toTypedArray())
                .inOrder()
        } else {
            assertThat(actualValuesProvider.invoke()).contains(endValue)
        }
    }

    suspend fun assertNoBlurRadiusTransition(
        transitionProgress: List<Float>,
        actualValuesProvider: () -> List<Float>,
        transitionFactory: (value: Float, state: TransitionState) -> TransitionStep,
    ) {
        val transitionSteps =
            listOf(
                transitionFactory(transitionProgress.first(), STARTED),
                *transitionProgress.drop(1).map { transitionFactory(it, RUNNING) }.toTypedArray(),
            )
        fakeKeyguardTransitionRepository.sendTransitionSteps(transitionSteps, testScope)

        assertThat(actualValuesProvider.invoke()).isEmpty()
    }

    fun shadeExpanded(expanded: Boolean) {
        if (expanded) {
            shadeTestUtil.setQsExpansion(1f)
        } else {
            fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setQsExpansion(0f)
            shadeTestUtil.setLockscreenShadeExpansion(0f)
        }
    }
}
