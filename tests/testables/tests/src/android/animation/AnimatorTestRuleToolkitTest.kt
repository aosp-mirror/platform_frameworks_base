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

package android.animation

import android.util.FloatProperty
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.internal.dynamicanimation.animation.SpringAnimation
import com.android.internal.dynamicanimation.animation.SpringForce
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.MotionTestRule
import platform.test.motion.RecordedMotion
import platform.test.motion.golden.FeatureCapture
import platform.test.motion.golden.asDataPoint
import platform.test.motion.testing.createGoldenPathManager

@SmallTest
@RunWith(AndroidJUnit4::class)
class AnimatorTestRuleToolkitTest {
    companion object {
        private val GOLDEN_PATH_MANAGER =
            createGoldenPathManager("frameworks/base/tests/testables/tests/goldens")

        private val TEST_PROPERTY =
            object : FloatProperty<TestState>("value") {
                override fun get(state: TestState): Float {
                    return state.animatedValue
                }

                override fun setValue(state: TestState, value: Float) {
                    state.animatedValue = value
                }
            }
    }

    @get:Rule(order = 0) val animatorTestRule = AnimatorTestRule(this)
    @get:Rule(order = 1)
    val motionRule =
        MotionTestRule(AnimatorTestRuleToolkit(animatorTestRule, TestScope()), GOLDEN_PATH_MANAGER)

    @Test
    fun recordMotion_withAnimator() {
        val state = TestState()
        AnimatorSet().apply {
            duration = 500
            play(
                ValueAnimator.ofFloat(state.animatedValue, 0f).apply {
                    addUpdateListener { state.animatedValue = it.animatedValue as Float }
                }
            )
            getInstrumentation().runOnMainSync { start() }
        }

        val recordedMotion =
            record(state, MotionControl { awaitFrames(count = 26) }, sampleIntervalMs = 20L)

        motionRule.assertThat(recordedMotion).timeSeriesMatchesGolden("recordMotion_withAnimator")
    }

    @Test
    fun recordMotion_withSpring() {
        val state = TestState()
        var isDone = false
        SpringAnimation(state, TEST_PROPERTY).apply {
            spring =
                SpringForce(0f).apply {
                    stiffness = 500f
                    dampingRatio = 0.95f
                }

            setStartValue(1f)
            setMinValue(0f)
            setMaxValue(1f)
            minimumVisibleChange = 0.01f

            addEndListener { _, _, _, _ -> isDone = true }
            getInstrumentation().runOnMainSync { start() }
        }

        val recordedMotion =
            record(state, MotionControl { awaitCondition { isDone } }, sampleIntervalMs = 16L)

        motionRule.assertThat(recordedMotion).timeSeriesMatchesGolden("recordMotion_withSpring")
    }

    private fun record(
        state: TestState,
        motionControl: MotionControl,
        sampleIntervalMs: Long,
    ): RecordedMotion {
        var recordedMotion: RecordedMotion? = null
        getInstrumentation().runOnMainSync {
            recordedMotion =
                motionRule.recordMotion(
                    AnimatorRuleRecordingSpec(
                        state,
                        motionControl,
                        sampleIntervalMs,
                    ) {
                        feature(
                            FeatureCapture("value") { state -> state.animatedValue.asDataPoint() },
                            "value",
                        )
                    }
                )
        }
        return recordedMotion!!
    }

    data class TestState(var animatedValue: Float = 1f)
}
