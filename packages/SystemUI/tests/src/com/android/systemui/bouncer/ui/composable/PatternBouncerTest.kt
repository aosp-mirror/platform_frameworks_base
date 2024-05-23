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

package com.android.systemui.bouncer.ui.composable

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.bouncer.ui.viewmodel.PatternBouncerViewModel
import com.android.systemui.kosmos.testScope
import com.android.systemui.motion.createSysUiComposeMotionTestRule
import com.android.systemui.testKosmos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.compose.ComposeRecordingSpec
import platform.test.motion.compose.MotionControl
import platform.test.motion.compose.feature
import platform.test.motion.compose.motionTestValueOfNode
import platform.test.motion.compose.recordMotion
import platform.test.motion.compose.runTest
import platform.test.motion.golden.DataPointTypes

@RunWith(AndroidJUnit4::class)
@LargeTest
class PatternBouncerTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @get:Rule val motionTestRule = createSysUiComposeMotionTestRule(kosmos)

    private val bouncerInteractor by lazy { kosmos.bouncerInteractor }
    private val viewModel by lazy {
        PatternBouncerViewModel(
            applicationContext = context,
            viewModelScope = kosmos.testScope.backgroundScope,
            interactor = bouncerInteractor,
            isInputEnabled = MutableStateFlow(true).asStateFlow(),
            onIntentionalUserInput = {},
        )
    }

    @Composable
    private fun PatternBouncerUnderTest() {
        PatternBouncer(viewModel, centerDotsVertically = true, modifier = Modifier.size(400.dp))
    }

    @Test
    fun entryAnimation() =
        motionTestRule.runTest {
            val motion =
                recordMotion(
                    content = { play -> if (play) PatternBouncerUnderTest() },
                    ComposeRecordingSpec.until(
                        recordBefore = false,
                        checkDone = { motionTestValueOfNode(MotionTestKeys.entryCompleted) }
                    ) {
                        feature(MotionTestKeys.dotAppearFadeIn, floatArray)
                        feature(MotionTestKeys.dotAppearMoveUp, floatArray)
                    }
                )

            assertThat(motion).timeSeriesMatchesGolden()
        }

    @Test
    fun animateFailure() =
        motionTestRule.runTest {
            val failureAnimationMotionControl =
                MotionControl(
                    delayReadyToPlay = {
                        // Skip entry animation.
                        awaitCondition { motionTestValueOfNode(MotionTestKeys.entryCompleted) }
                    },
                    delayRecording = {
                        // Trigger failure animation by calling onDragEnd without having recorded a
                        // pattern  before.
                        viewModel.onDragEnd()
                        // Failure animation starts when animateFailure flips to true...
                        viewModel.animateFailure.takeWhile { !it }.collect {}
                    }
                ) {
                    // ... and ends when the composable flips it back to false.
                    viewModel.animateFailure.takeWhile { it }.collect {}
                }

            val motion =
                recordMotion(
                    content = { PatternBouncerUnderTest() },
                    ComposeRecordingSpec(failureAnimationMotionControl) {
                        feature(MotionTestKeys.dotScaling, floatArray)
                    }
                )
            assertThat(motion).timeSeriesMatchesGolden()
        }

    companion object {
        val floatArray = DataPointTypes.listOf(DataPointTypes.float)
    }
}
