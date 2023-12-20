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
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GoneToDreamingTransitionViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeKeyguardTransitionRepository
    private val underTest = kosmos.goneToDreamingTransitionViewModel

    @Test
    fun runTest() =
        testScope.runTest {
            val values by collectValues(underTest.lockscreenAlpha)

            repository.sendTransitionSteps(
                listOf(
                    // Should start running here...
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.1f),
                    step(0.2f),
                    step(0.3f),
                    // ...up to here
                    step(1f),
                ),
                testScope,
            )

            // Only three values should be present, since the dream overlay runs for a small
            // fraction of the overall animation time
            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun lockscreenTranslationY() =
        testScope.runTest {
            val pixels = 100
            val values by collectValues(underTest.lockscreenTranslationY(pixels))

            repository.sendTransitionSteps(
                listOf(
                    // Should start running here...
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.3f),
                    step(0.5f),
                    // And a final reset event on CANCEL
                    step(0.8f, TransitionState.CANCELED)
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 100f)) }
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.GONE,
            to = KeyguardState.DREAMING,
            value = value,
            transitionState = state,
            ownerName = "GoneToDreamingTransitionViewModelTest"
        )
    }
}
