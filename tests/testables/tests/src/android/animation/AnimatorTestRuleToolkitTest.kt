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

import android.graphics.Color
import android.platform.test.annotations.MotionTest
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.internal.dynamicanimation.animation.DynamicAnimation
import com.android.internal.dynamicanimation.animation.SpringAnimation
import com.android.internal.dynamicanimation.animation.SpringForce
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.MotionTestRule
import platform.test.motion.RecordedMotion
import platform.test.motion.testing.createGoldenPathManager
import platform.test.motion.view.ViewFeatureCaptures
import platform.test.screenshot.DeviceEmulationRule
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.DisplaySpec
import platform.test.screenshot.ScreenshotActivity
import platform.test.screenshot.ScreenshotTestRule

@SmallTest
@MotionTest
@RunWith(AndroidJUnit4::class)
class AnimatorTestRuleToolkitTest {
    companion object {
        private val GOLDEN_PATH_MANAGER =
            createGoldenPathManager("frameworks/base/tests/testables/tests/goldens")

        private val EMULATION_SPEC =
            DeviceEmulationSpec(DisplaySpec("phone", width = 320, height = 690, densityDpi = 160))
    }

    @get:Rule(order = 0) val deviceEmulationRule = DeviceEmulationRule(EMULATION_SPEC)
    @get:Rule(order = 1) val activityRule = ActivityScenarioRule(ScreenshotActivity::class.java)
    @get:Rule(order = 2) val animatorTestRule = AnimatorTestRule(this)
    @get:Rule(order = 3) val screenshotRule = ScreenshotTestRule(GOLDEN_PATH_MANAGER)
    @get:Rule(order = 4)
    val motionRule =
        MotionTestRule(
            AnimatorTestRuleToolkit(animatorTestRule, TestScope()) { activityRule.scenario },
            GOLDEN_PATH_MANAGER,
            bitmapDiffer = screenshotRule,
        )

    @Test
    fun recordFilmstrip_withAnimator() {
        val animatedBox = createScene()
        createAnimator(animatedBox).apply { getInstrumentation().runOnMainSync { start() } }

        val recordedMotion =
            record(
                animatedBox,
                MotionControl { awaitFrames(count = 26) },
                sampleIntervalMs = 20L,
                recordScreenshots = true,
            )

        motionRule.assertThat(recordedMotion).filmstripMatchesGolden("recordFilmstrip_withAnimator")
    }

    @Test
    fun recordTimeSeries_withAnimator() {
        val animatedBox = createScene()
        createAnimator(animatedBox).apply { getInstrumentation().runOnMainSync { start() } }

        val recordedMotion =
            record(
                animatedBox,
                MotionControl { awaitFrames(count = 26) },
                sampleIntervalMs = 20L,
                recordScreenshots = false,
            )

        motionRule
            .assertThat(recordedMotion)
            .timeSeriesMatchesGolden("recordTimeSeries_withAnimator")
    }

    @Test
    fun recordFilmstrip_withSpring() {
        val animatedBox = createScene()
        var isDone = false
        createSpring(animatedBox).apply {
            addEndListener { _, _, _, _ -> isDone = true }
            getInstrumentation().runOnMainSync { start() }
        }

        val recordedMotion =
            record(
                animatedBox,
                MotionControl { awaitCondition { isDone } },
                sampleIntervalMs = 16L,
                recordScreenshots = true,
            )

        motionRule.assertThat(recordedMotion).filmstripMatchesGolden("recordFilmstrip_withSpring")
    }

    @Test
    fun recordTimeSeries_withSpring() {
        val animatedBox = createScene()
        var isDone = false
        createSpring(animatedBox).apply {
            addEndListener { _, _, _, _ -> isDone = true }
            getInstrumentation().runOnMainSync { start() }
        }

        val recordedMotion =
            record(
                animatedBox,
                MotionControl { awaitCondition { isDone } },
                sampleIntervalMs = 16L,
                recordScreenshots = false,
            )

        motionRule.assertThat(recordedMotion).timeSeriesMatchesGolden("recordTimeSeries_withSpring")
    }

    private fun createScene(): ViewGroup {
        lateinit var sceneRoot: ViewGroup
        activityRule.scenario.onActivity { activity ->
            sceneRoot = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
            activity.setContentView(sceneRoot)
        }
        getInstrumentation().waitForIdleSync()
        return sceneRoot
    }

    private fun createAnimator(animatedBox: ViewGroup): AnimatorSet {
        return AnimatorSet().apply {
            duration = 500
            play(
                ValueAnimator.ofFloat(animatedBox.alpha, 0f).apply {
                    addUpdateListener { animatedBox.alpha = it.animatedValue as Float }
                }
            )
        }
    }

    private fun createSpring(animatedBox: ViewGroup): SpringAnimation {
        return SpringAnimation(animatedBox, DynamicAnimation.ALPHA).apply {
            spring =
                SpringForce(0f).apply {
                    stiffness = 500f
                    dampingRatio = 0.95f
                }

            setStartValue(animatedBox.alpha)
            setMinValue(0f)
            setMaxValue(1f)
            minimumVisibleChange = 0.01f
        }
    }

    private fun record(
        container: ViewGroup,
        motionControl: MotionControl,
        sampleIntervalMs: Long,
        recordScreenshots: Boolean,
    ): RecordedMotion {
        val visualCapture =
            if (recordScreenshots) {
                ::captureView
            } else {
                null
            }
        return motionRule.recordMotion(
            AnimatorRuleRecordingSpec(
                container,
                motionControl,
                sampleIntervalMs,
                visualCapture,
            ) {
                feature(ViewFeatureCaptures.alpha, "alpha")
            }
        )
    }
}
