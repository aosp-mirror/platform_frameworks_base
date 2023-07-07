/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm.flicker.rotation

import android.platform.test.annotations.PlatinumTest
import android.platform.test.annotations.Presubmit
import android.tools.common.ScenarioBuilder
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import android.view.WindowManager
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.helpers.SeamlessRotationAppHelper
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test opening an app and cycling through app rotations using seamless rotations
 *
 * Currently, runs:
 * ```
 *      0 -> 90 degrees
 *      0 -> 90 degrees (with starved UI thread)
 *      90 -> 0 degrees
 *      90 -> 0 degrees (with starved UI thread)
 * ```
 *
 * Actions:
 * ```
 *     Launch an app in fullscreen and supporting seamless rotation (via intent)
 *     Set initial device orientation
 *     Start tracing
 *     Change device orientation
 *     Stop tracing
 * ```
 *
 * To run this test: `atest FlickerTests:SeamlessAppRotationTest`
 *
 * To run only the presubmit assertions add: `--
 *
 * ```
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Presubmit`
 * ```
 *
 * To run only the postsubmit assertions add: `--
 *
 * ```
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Postsubmit`
 * ```
 *
 * To run only the flaky assertions add: `--
 *
 * ```
 *      --module-arg FlickerTests:include-annotation:androidx.test.filters.FlakyTest`
 * ```
 *
 * Notes:
 * ```
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [RotationTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class SeamlessAppRotationTest(flicker: FlickerTest) : RotationTransition(flicker) {
    override val testApp = SeamlessRotationAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                testApp.launchViaIntent(
                    wmHelper,
                    stringExtras =
                        mapOf(
                            ActivityOptions.SeamlessRotation.EXTRA_STARVE_UI_THREAD to
                                flicker.starveUiThread.toString()
                        )
                )
            }
        }

    /** Checks that [testApp] window is always in full screen */
    @Presubmit
    @Test
    fun appWindowFullScreen() {
        flicker.assertWm {
            this.invoke("isFullScreen") {
                val appWindow =
                    it.windowState(testApp.`package`)
                        ?: error("App window for package ${testApp.`package`} not found")
                val flags = appWindow.windowState.attributes.flags
                appWindow
                    .check { "isFullScreen" }
                    .that(flags.and(WindowManager.LayoutParams.FLAG_FULLSCREEN))
                    .isGreater(0)
            }
        }
    }

    /** Checks that [testApp] window is always with seamless rotation */
    @Presubmit
    @Test
    fun appWindowSeamlessRotation() {
        flicker.assertWm {
            this.invoke("isRotationSeamless") {
                val appWindow =
                    it.windowState(testApp.`package`)
                        ?: error("App window for package ${testApp.`package`} not found")
                val rotationAnimation = appWindow.windowState.attributes.rotationAnimation
                appWindow
                    .check { "isRotationSeamless" }
                    .that(
                        rotationAnimation.and(
                            WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
                        )
                    )
                    .isGreater(0)
            }
        }
    }

    /** Checks that [testApp] window is always visible */
    @Presubmit
    @Test
    fun appLayerAlwaysVisible() {
        flicker.assertLayers { isVisible(testApp) }
    }

    /** Checks that [testApp] layer covers the entire screen during the whole transition */
    @Presubmit
    @Test
    fun appLayerRotates() {
        flicker.assertLayers {
            this.invoke("entireScreenCovered") { entry ->
                entry.entry.displays.map { display ->
                    entry.visibleRegion(testApp).coversExactly(display.layerStackSpace)
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. App is full screen")
    override fun statusBarLayerPositionAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. App is full screen")
    override fun statusBarLayerIsVisibleAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. App is full screen")
    override fun statusBarWindowIsAlwaysVisible() {}

    /**
     * Checks that the [ComponentNameMatcher.STATUS_BAR] window is invisible during the whole
     * transition
     */
    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysInvisible() {
        flicker.assertWm { this.isAboveAppWindowInvisible(ComponentNameMatcher.STATUS_BAR) }
    }

    /**
     * Checks that the [ComponentNameMatcher.STATUS_BAR] layer is invisible during the whole
     * transition
     */
    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysInvisible() {
        flicker.assertLayers { this.isInvisible(ComponentNameMatcher.STATUS_BAR) }
    }

    /** Checks that the focus doesn't change during animation */
    @Presubmit
    @Test
    fun focusDoesNotChange() {
        flicker.assertEventLog { this.focusDoesNotChange() }
    }

    @Test
    @PlatinumTest(focusArea = "framework")
    override fun cujCompleted() {
        appWindowFullScreen()
        appWindowSeamlessRotation()
        focusDoesNotChange()
        statusBarLayerIsAlwaysInvisible()
        statusBarWindowIsAlwaysInvisible()
        appLayerRotates_StartingPos()
        appLayerRotates_EndingPos()
        entireScreenCovered()
        visibleLayersShownMoreThanOneConsecutiveEntry()
        visibleWindowsShownMoreThanOneConsecutiveEntry()

        runAndIgnoreAssumptionViolation { appLayerRotates() }
        runAndIgnoreAssumptionViolation { appLayerAlwaysVisible() }
        runAndIgnoreAssumptionViolation { navBarLayerIsVisibleAtStartAndEnd() }
        runAndIgnoreAssumptionViolation { navBarWindowIsAlwaysVisible() }
        runAndIgnoreAssumptionViolation { navBarLayerPositionAtStartAndEnd() }
        runAndIgnoreAssumptionViolation { taskBarLayerIsVisibleAtStartAndEnd() }
        runAndIgnoreAssumptionViolation { taskBarWindowIsAlwaysVisible() }
    }

    companion object {
        private val FlickerTest.starveUiThread
            get() =
                getConfigValue<Boolean>(ActivityOptions.SeamlessRotation.EXTRA_STARVE_UI_THREAD)
                    ?: false

        @JvmStatic
        protected fun createConfig(
            sourceConfig: FlickerTest,
            starveUiThread: Boolean
        ): FlickerTest {
            val originalScenario = sourceConfig.initialize("createConfig")
            val nameExt = if (starveUiThread) "_BUSY_UI_THREAD" else ""
            val newConfig =
                ScenarioBuilder()
                    .fromScenario(originalScenario)
                    .withExtraConfig(
                        ActivityOptions.SeamlessRotation.EXTRA_STARVE_UI_THREAD,
                        starveUiThread
                    )
                    .withDescriptionOverride("${originalScenario.description}$nameExt")
            return FlickerTest(newConfig)
        }

        /**
         * Creates the test configurations for seamless rotation based on the default rotation tests
         * from [FlickerTestFactory.rotationTests], but adding a flag (
         * [ActivityOptions.SeamlessRotation.EXTRA_STARVE_UI_THREAD]) to indicate if the app should
         * starve the UI thread of not
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.rotationTests().flatMap { sourceConfig ->
                val defaultRun = createConfig(sourceConfig, starveUiThread = false)
                val busyUiRun = createConfig(sourceConfig, starveUiThread = true)
                listOf(defaultRun, busyUiRun)
            }
        }
    }
}
