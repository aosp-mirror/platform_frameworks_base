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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class GlanceableHubToDreamingTransitionViewModelTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    val underTest by lazy { kosmos.glanceableHubToDreamingTransitionViewModel }

    @Test
    fun dreamOverlayAlpha() =
        testScope.runTest {
            val values by collectValues(underTest.dreamOverlayAlpha)
            assertThat(values).isEmpty()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    // Should start running here...
                    step(0.1f),
                    step(0.5f),
                    // Up to here...
                    step(1f),
                ),
                testScope,
            )

            assertThat(values).hasSize(2)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun dreamOverlayTranslationX() =
        testScope.runTest {
            val config: Configuration = mock()
            whenever(config.layoutDirection).thenReturn(LayoutDirection.LTR)
            configurationRepository.onConfigurationChange(config)
            configurationRepository.setDimensionPixelSize(
                R.dimen.hub_to_dreaming_transition_dream_overlay_translation_x,
                100
            )

            val values by collectValues(underTest.dreamOverlayTranslationX)
            assertThat(values).isEmpty()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.3f),
                    step(0.6f),
                ),
                testScope,
            )

            assertThat(values).hasSize(3)
            values.forEach { assertThat(it).isIn(Range.closed(-100f, 0f)) }
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.GLANCEABLE_HUB,
            to = KeyguardState.DREAMING,
            value = value,
            transitionState = state,
            ownerName = GlanceableHubToDreamingTransitionViewModelTest::class.java.simpleName
        )
    }
}
