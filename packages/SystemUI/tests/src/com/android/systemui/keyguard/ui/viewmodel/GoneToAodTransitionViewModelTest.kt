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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GoneToAodTransitionViewModelTest : SysuiTestCase() {
    private lateinit var underTest: GoneToAodTransitionViewModel
    private lateinit var repository: FakeKeyguardTransitionRepository
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        repository = FakeKeyguardTransitionRepository()
        val interactor =
            KeyguardTransitionInteractorFactory.create(
                    scope = testScope.backgroundScope,
                    repository = repository,
                )
                .keyguardTransitionInteractor
        underTest = GoneToAodTransitionViewModel(interactor)
    }

    @Test
    fun enterFromTopTranslationY() =
        testScope.runTest {
            val pixels = -100f
            val enterFromTopTranslationY by
                collectLastValue(underTest.enterFromTopTranslationY(pixels.toInt()))

            // The animation should only start > halfway through
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(enterFromTopTranslationY).isEqualTo(pixels)

            repository.sendTransitionStep(step(0.5f))
            assertThat(enterFromTopTranslationY).isEqualTo(pixels)

            repository.sendTransitionStep(step(.85f))
            assertThat(enterFromTopTranslationY).isIn(Range.closed(pixels, 0f))

            // At the end, the translation should be complete and set to zero
            repository.sendTransitionStep(step(1f))
            assertThat(enterFromTopTranslationY).isEqualTo(0f)
        }

    @Test
    fun enterFromTopAnimationAlpha() =
        testScope.runTest {
            val enterFromTopAnimationAlpha by collectLastValue(underTest.enterFromTopAnimationAlpha)

            // The animation should only start > halfway through
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(enterFromTopAnimationAlpha).isEqualTo(0f)

            repository.sendTransitionStep(step(0.5f))
            assertThat(enterFromTopAnimationAlpha).isEqualTo(0f)

            repository.sendTransitionStep(step(.85f))
            assertThat(enterFromTopAnimationAlpha).isIn(Range.closed(0f, 1f))

            repository.sendTransitionStep(step(1f))
            assertThat(enterFromTopAnimationAlpha).isEqualTo(1f)
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.GONE,
            to = KeyguardState.AOD,
            value = value,
            transitionState = state,
            ownerName = "GoneToAodTransitionViewModelTest"
        )
    }
}
