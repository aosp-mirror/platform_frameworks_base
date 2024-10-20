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

package com.android.systemui.animation

import android.animation.AnimatorRuleRecordingSpec
import android.animation.AnimatorTestRuleToolkit
import android.animation.MotionControl
import android.animation.recordMotion
import android.graphics.drawable.GradientDrawable
import android.platform.test.annotations.MotionTest
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.EmptyTestActivity
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.MotionTestRule
import platform.test.motion.RecordedMotion
import platform.test.motion.view.DrawableFeatureCaptures
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.screenshot.GoldenPathManager
import platform.test.screenshot.PathConfig

@SmallTest
@MotionTest
@RunWith(ParameterizedAndroidJunit4::class)
class TransitionAnimatorTest(val useSpring: Boolean) : SysuiTestCase() {
    companion object {
        private const val GOLDENS_PATH = "frameworks/base/packages/SystemUI/tests/goldens"

        @get:Parameters(name = "{0}")
        @JvmStatic
        val useSpringValues = booleanArrayOf(false, true).toList()
    }

    private val kosmos = Kosmos()
    private val pathManager = GoldenPathManager(context, GOLDENS_PATH, pathConfig = PathConfig())
    private val transitionAnimator =
        TransitionAnimator(
            kosmos.fakeExecutor,
            ActivityTransitionAnimator.TIMINGS,
            ActivityTransitionAnimator.INTERPOLATORS,
            ActivityTransitionAnimator.SPRING_TIMINGS,
            ActivityTransitionAnimator.SPRING_INTERPOLATORS,
        )
    private val withSpring =
        if (useSpring) {
            "_withSpring"
        } else {
            ""
        }

    @get:Rule(order = 1) val activityRule = ActivityScenarioRule(EmptyTestActivity::class.java)
    @get:Rule(order = 2) val animatorTestRule = android.animation.AnimatorTestRule(this)
    @get:Rule(order = 3)
    val motionRule =
        MotionTestRule(AnimatorTestRuleToolkit(animatorTestRule, kosmos.testScope), pathManager)

    @Test
    fun backgroundAnimation_whenLaunching() {
        val backgroundLayer = GradientDrawable().apply { alpha = 0 }
        val animator =
            setUpTest(backgroundLayer, isLaunching = true).apply {
                getInstrumentation().runOnMainSync { start() }
            }

        val recordedMotion = recordMotion(backgroundLayer, animator)

        motionRule
            .assertThat(recordedMotion)
            .timeSeriesMatchesGolden("backgroundAnimation_whenLaunching$withSpring")
    }

    @Test
    fun backgroundAnimation_whenReturning() {
        val backgroundLayer = GradientDrawable().apply { alpha = 0 }
        val animator =
            setUpTest(backgroundLayer, isLaunching = false).apply {
                getInstrumentation().runOnMainSync { start() }
            }

        val recordedMotion = recordMotion(backgroundLayer, animator)

        motionRule
            .assertThat(recordedMotion)
            .timeSeriesMatchesGolden("backgroundAnimation_whenReturning$withSpring")
    }

    @Test
    fun backgroundAnimationWithoutFade_whenLaunching() {
        val backgroundLayer = GradientDrawable().apply { alpha = 0 }
        val animator =
            setUpTest(backgroundLayer, isLaunching = true, fadeWindowBackgroundLayer = false)
                .apply { getInstrumentation().runOnMainSync { start() } }

        val recordedMotion = recordMotion(backgroundLayer, animator)

        motionRule
            .assertThat(recordedMotion)
            .timeSeriesMatchesGolden("backgroundAnimationWithoutFade_whenLaunching$withSpring")
    }

    @Test
    fun backgroundAnimationWithoutFade_whenReturning() {
        val backgroundLayer = GradientDrawable().apply { alpha = 0 }
        val animator =
            setUpTest(backgroundLayer, isLaunching = false, fadeWindowBackgroundLayer = false)
                .apply { getInstrumentation().runOnMainSync { start() } }

        val recordedMotion = recordMotion(backgroundLayer, animator)

        motionRule
            .assertThat(recordedMotion)
            .timeSeriesMatchesGolden("backgroundAnimationWithoutFade_whenReturning$withSpring")
    }

    private fun setUpTest(
        backgroundLayer: GradientDrawable,
        isLaunching: Boolean,
        fadeWindowBackgroundLayer: Boolean = true,
    ): TransitionAnimator.Animation {
        lateinit var transitionContainer: ViewGroup
        activityRule.scenario.onActivity { activity ->
            transitionContainer = FrameLayout(activity).apply { setBackgroundColor(0x00FF00) }
            activity.setContentView(transitionContainer)
        }
        waitForIdleSync()

        val controller = TestController(transitionContainer, isLaunching)
        return transitionAnimator.createAnimation(
            controller,
            controller.createAnimatorState(),
            createEndState(transitionContainer),
            backgroundLayer,
            fadeWindowBackgroundLayer,
            useSpring = useSpring,
        )
    }

    private fun createEndState(container: ViewGroup): TransitionAnimator.State {
        val containerLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)
        return TransitionAnimator.State(
            left = containerLocation[0],
            top = containerLocation[1],
            right = containerLocation[0] + 320,
            bottom = containerLocation[1] + 690,
            topCornerRadius = 0f,
            bottomCornerRadius = 0f,
        )
    }

    private fun recordMotion(
        backgroundLayer: GradientDrawable,
        animation: TransitionAnimator.Animation,
    ): RecordedMotion {
        fun record(motionControl: MotionControl, sampleIntervalMs: Long): RecordedMotion {
            return motionRule.recordMotion(
                AnimatorRuleRecordingSpec(backgroundLayer, motionControl, sampleIntervalMs) {
                    feature(DrawableFeatureCaptures.bounds, "bounds")
                    feature(DrawableFeatureCaptures.cornerRadii, "corner_radii")
                    feature(DrawableFeatureCaptures.alpha, "alpha")
                }
            )
        }

        val motionControl: MotionControl
        val sampleIntervalMs: Long
        if (useSpring) {
            assertTrue { animation is TransitionAnimator.MultiSpringAnimation }
            motionControl = MotionControl {
                awaitCondition { (animation as TransitionAnimator.MultiSpringAnimation).isDone }
            }
            sampleIntervalMs = 16L
        } else {
            assertTrue { animation is TransitionAnimator.InterpolatedAnimation }
            motionControl = MotionControl { awaitFrames(count = 26) }
            sampleIntervalMs = 20L
        }

        var recording: RecordedMotion? = null
        getInstrumentation().runOnMainSync { recording = record(motionControl, sampleIntervalMs) }
        return recording!!
    }
}

/**
 * A simple implementation of [TransitionAnimator.Controller] which throws if it is called outside
 * of the main thread.
 */
private class TestController(
    override var transitionContainer: ViewGroup,
    override val isLaunching: Boolean,
) : TransitionAnimator.Controller {
    override fun createAnimatorState(): TransitionAnimator.State {
        val containerLocation = IntArray(2)
        transitionContainer.getLocationOnScreen(containerLocation)
        return TransitionAnimator.State(
            left = containerLocation[0] + 100,
            top = containerLocation[1] + 300,
            right = containerLocation[0] + 200,
            bottom = containerLocation[1] + 400,
            topCornerRadius = 10f,
            bottomCornerRadius = 20f,
        )
    }
}
