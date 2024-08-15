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

import android.animation.AnimatorSet
import android.graphics.drawable.GradientDrawable
import android.platform.test.annotations.MotionTest
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.EmptyTestActivity
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.MotionTestRule
import platform.test.motion.RecordedMotion
import platform.test.motion.view.AnimationSampling.Companion.evenlySampled
import platform.test.motion.view.DrawableFeatureCaptures
import platform.test.motion.view.ViewRecordingSpec.Companion.captureWithoutScreenshot
import platform.test.motion.view.ViewToolkit
import platform.test.motion.view.record
import platform.test.screenshot.DeviceEmulationRule
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.DisplaySpec
import platform.test.screenshot.GoldenPathManager
import platform.test.screenshot.PathConfig

@SmallTest
@MotionTest
@RunWith(AndroidJUnit4::class)
class TransitionAnimatorTest : SysuiTestCase() {
    companion object {
        private const val GOLDENS_PATH = "frameworks/base/packages/SystemUI/tests/goldens"

        private val emulationSpec =
            DeviceEmulationSpec(
                DisplaySpec(
                    "phone",
                    width = 320,
                    height = 690,
                    densityDpi = 160,
                )
            )
    }

    private val kosmos = Kosmos()
    private val pathManager = GoldenPathManager(context, GOLDENS_PATH, pathConfig = PathConfig())
    private val transitionAnimator =
        TransitionAnimator(
            kosmos.fakeExecutor,
            ActivityTransitionAnimator.TIMINGS,
            ActivityTransitionAnimator.INTERPOLATORS
        )

    @get:Rule(order = 0) val deviceEmulationRule = DeviceEmulationRule(emulationSpec)
    @get:Rule(order = 1) val activityRule = ActivityScenarioRule(EmptyTestActivity::class.java)
    @get:Rule(order = 2)
    val motionRule = MotionTestRule(ViewToolkit { activityRule.scenario }, pathManager)

    @Test
    fun backgroundAnimation_whenLaunching() {
        val backgroundLayer = GradientDrawable().apply { alpha = 0 }
        val animator = setUpTest(backgroundLayer, isLaunching = true)

        val recordedMotion = recordMotion(backgroundLayer, animator)

        motionRule.assertThat(recordedMotion).timeSeriesMatchesGolden()
    }

    @Test
    fun backgroundAnimation_whenReturning() {
        val backgroundLayer = GradientDrawable().apply { alpha = 0 }
        val animator = setUpTest(backgroundLayer, isLaunching = false)

        val recordedMotion = recordMotion(backgroundLayer, animator)

        motionRule.assertThat(recordedMotion).timeSeriesMatchesGolden()
    }

    @Test
    fun backgroundAnimationWithoutFade_whenLaunching() {
        val backgroundLayer = GradientDrawable().apply { alpha = 0 }
        val animator =
            setUpTest(backgroundLayer, isLaunching = true, fadeWindowBackgroundLayer = false)

        val recordedMotion = recordMotion(backgroundLayer, animator)

        motionRule.assertThat(recordedMotion).timeSeriesMatchesGolden()
    }

    @Test
    fun backgroundAnimationWithoutFade_whenReturning() {
        val backgroundLayer = GradientDrawable().apply { alpha = 0 }
        val animator =
            setUpTest(backgroundLayer, isLaunching = false, fadeWindowBackgroundLayer = false)

        val recordedMotion = recordMotion(backgroundLayer, animator)

        motionRule.assertThat(recordedMotion).timeSeriesMatchesGolden()
    }

    private fun setUpTest(
        backgroundLayer: GradientDrawable,
        isLaunching: Boolean,
        fadeWindowBackgroundLayer: Boolean = true,
    ): AnimatorSet {
        lateinit var transitionContainer: ViewGroup
        activityRule.scenario.onActivity { activity ->
            transitionContainer = FrameLayout(activity).apply { setBackgroundColor(0x00FF00) }
            activity.setContentView(transitionContainer)
        }
        waitForIdleSync()

        val controller = TestController(transitionContainer, isLaunching)
        val animator =
            transitionAnimator.createAnimator(
                controller,
                createEndState(transitionContainer),
                backgroundLayer,
                fadeWindowBackgroundLayer
            )
        return AnimatorSet().apply {
            duration = animator.duration
            play(animator)
        }
    }

    private fun createEndState(container: ViewGroup): TransitionAnimator.State {
        val containerLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)
        return TransitionAnimator.State(
            left = containerLocation[0],
            top = containerLocation[1],
            right = containerLocation[0] + emulationSpec.display.width,
            bottom = containerLocation[1] + emulationSpec.display.height,
            topCornerRadius = 0f,
            bottomCornerRadius = 0f
        )
    }

    private fun recordMotion(
        backgroundLayer: GradientDrawable,
        animator: AnimatorSet
    ): RecordedMotion {
        return motionRule.record(
            animator,
            backgroundLayer.captureWithoutScreenshot(evenlySampled(20)) {
                feature(DrawableFeatureCaptures.bounds, "bounds")
                feature(DrawableFeatureCaptures.cornerRadii, "corner_radii")
                feature(DrawableFeatureCaptures.alpha, "alpha")
            }
        )
    }
}

/**
 * A simple implementation of [TransitionAnimator.Controller] which throws if it is called outside
 * of the main thread.
 */
private class TestController(
    override var transitionContainer: ViewGroup,
    override val isLaunching: Boolean
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
            bottomCornerRadius = 20f
        )
    }
}
