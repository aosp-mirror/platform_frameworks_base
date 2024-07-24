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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LockscreenToOccludedTransitionViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope
    private lateinit var repository: FakeKeyguardTransitionRepository
    private lateinit var shadeRepository: ShadeRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var configurationRepository: FakeConfigurationRepository
    private lateinit var underTest: LockscreenToOccludedTransitionViewModel

    @Before
    fun setUp() {
        repository = kosmos.fakeKeyguardTransitionRepository
        shadeRepository = kosmos.shadeRepository
        keyguardRepository = kosmos.fakeKeyguardRepository
        configurationRepository = kosmos.fakeConfigurationRepository
        underTest = kosmos.lockscreenToOccludedTransitionViewModel
    }

    @Test
    fun lockscreenFadeOut() =
        testScope.runTest {
            val values by collectValues(underTest.lockscreenAlpha)
            repository.sendTransitionSteps(
                steps =
                    listOf(
                        step(0f, TransitionState.STARTED), // Should start running here...
                        step(0f),
                        step(.1f),
                        step(.4f),
                        step(.7f), // ...up to here
                        step(1f),
                    ),
                testScope = testScope,
            )
            // Only 5 values should be present, since the dream overlay runs for a small fraction
            // of the overall animation time
            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun lockscreenTranslationY() =
        testScope.runTest {
            configurationRepository.setDimensionPixelSize(
                R.dimen.lockscreen_to_occluded_transition_lockscreen_translation_y,
                100
            )
            val values by collectValues(underTest.lockscreenTranslationY)
            repository.sendTransitionSteps(
                steps =
                    listOf(
                        step(0f, TransitionState.STARTED), // Should start running here...
                        step(0f),
                        step(.3f),
                        step(.5f),
                        step(1f), // ...up to here
                    ),
                testScope = testScope,
            )
            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 100f)) }
        }

    @Test
    fun lockscreenTranslationYIsCanceled() =
        testScope.runTest {
            configurationRepository.setDimensionPixelSize(
                R.dimen.lockscreen_to_occluded_transition_lockscreen_translation_y,
                100
            )
            val values by collectValues(underTest.lockscreenTranslationY)
            repository.sendTransitionSteps(
                steps =
                    listOf(
                        step(0f, TransitionState.STARTED),
                        step(0f),
                        step(.3f),
                        step(0.3f, TransitionState.CANCELED),
                    ),
                testScope = testScope,
            )
            assertThat(values.size).isEqualTo(3)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 100f)) }

            // Cancel will reset the translation
            assertThat(values[2]).isEqualTo(0)
        }

    @Test
    fun deviceEntryParentViewAlpha_shadeExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.deviceEntryParentViewAlpha)
            shadeExpanded(true)
            runCurrent()

            // immediately 0f
            repository.sendTransitionSteps(
                steps =
                    listOf(
                        step(0f, TransitionState.STARTED),
                        step(.5f),
                        step(1f, TransitionState.FINISHED)
                    ),
                testScope = testScope,
            )

            values.forEach { assertThat(it).isEqualTo(0f) }
        }

    @Test
    fun deviceEntryParentViewAlpha_shadeNotExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.deviceEntryParentViewAlpha)
            shadeExpanded(false)
            runCurrent()

            // fade out
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(actual).isEqualTo(1f)

            repository.sendTransitionStep(step(.2f))
            assertThat(actual).isIn(Range.open(.1f, .9f))

            // alpha is 1f before the full transition starts ending
            repository.sendTransitionStep(step(0.8f))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(actual).isEqualTo(0f)
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING,
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.OCCLUDED,
            value = value,
            transitionState = state,
            ownerName = "LockscreenToOccludedTransitionViewModelTest"
        )
    }

    private fun shadeExpanded(expanded: Boolean) {
        if (expanded) {
            shadeRepository.setQsExpansion(1f)
        } else {
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLockscreenShadeExpansion(0f)
        }
    }
}
