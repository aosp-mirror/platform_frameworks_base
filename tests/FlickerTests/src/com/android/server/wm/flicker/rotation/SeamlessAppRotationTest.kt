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

import android.platform.test.annotations.Presubmit
import android.view.WindowManager
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SeamlessRotationAppHelper
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test opening an app and cycling through app rotations using seamless rotations
 *
 * Currently runs:
 *      0 -> 90 degrees
 *      0 -> 90 degrees (with starved UI thread)
 *      90 -> 0 degrees
 *      90 -> 0 degrees (with starved UI thread)
 *
 * Actions:
 *     Launch an app in fullscreen and supporting seamless rotation (via intent)
 *     Set initial device orientation
 *     Start tracing
 *     Change device orientation
 *     Stop tracing
 *
 * To run this test: `atest FlickerTests:SeamlessAppRotationTest`
 *
 * To run only the presubmit assertions add: `--
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Presubmit`
 *
 * To run only the postsubmit assertions add: `--
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Postsubmit`
 *
 * To run only the flaky assertions add: `--
 *      --module-arg FlickerTests:include-annotation:androidx.test.filters.FlakyTest`
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [RotationTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group3
class SeamlessAppRotationTest(
    testSpec: FlickerTestParameter
) : RotationTransition(testSpec) {
    override val testApp = SeamlessRotationAppHelper(instrumentation)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = {
            super.transition(this, it)
            setup {
                test {
                    testApp.launchViaIntent(wmHelper,
                        stringExtras = mapOf(
                            ActivityOptions.EXTRA_STARVE_UI_THREAD to it.starveUiThread.toString())
                    )
                }
            }
        }

    /**
     * Checks that [testApp] window is always in full screen
     */
    @Presubmit
    @Test
    fun appWindowFullScreen() {
        testSpec.assertWm {
            this.invoke("isFullScreen") {
                val appWindow = it.windowState(testApp.`package`)
                val flags = appWindow.windowState?.attributes?.flags ?: 0
                appWindow.verify("isFullScreen")
                    .that(flags.and(WindowManager.LayoutParams.FLAG_FULLSCREEN))
                    .isGreaterThan(0)
            }
        }
    }

    /**
     * Checks that [testApp] window is always with seamless rotation
     */
    @Presubmit
    @Test
    fun appWindowSeamlessRotation() {
        testSpec.assertWm {
            this.invoke("isRotationSeamless") {
                val appWindow = it.windowState(testApp.`package`)
                val rotationAnimation = appWindow.windowState?.attributes?.rotationAnimation ?: 0
                appWindow.verify("isRotationSeamless")
                    .that(rotationAnimation
                        .and(WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS))
                    .isGreaterThan(0)
            }
        }
    }

    /**
     * Checks that [testApp] window is always visible
     */
    @Presubmit
    @Test
    fun appLayerAlwaysVisible() {
        testSpec.assertLayers {
            isVisible(testApp.component)
        }
    }

    /**
     * Checks that [testApp] layer covers the entire screen during the whole transition
     */
    @Presubmit
    @Test
    fun appLayerRotates() {
        testSpec.assertLayers {
            this.invoke("entireScreenCovered") { entry ->
                entry.entry.displays.map { display ->
                    entry.visibleRegion(testApp.component).coversExactly(display.layerStackSpace)
                }
            }
        }
    }

    /**
     * Checks that the [FlickerComponentName.STATUS_BAR] window is invisible during the whole
     * transition
     */
    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysInvisible() {
        testSpec.assertWm {
            this.isAboveAppWindowInvisible(FlickerComponentName.STATUS_BAR)
        }
    }

    /**
     * Checks that the [FlickerComponentName.STATUS_BAR] layer is invisible during the whole
     * transition
     */
    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysInvisible() {
        testSpec.assertLayers {
            this.isInvisible(FlickerComponentName.STATUS_BAR)
        }
    }

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    companion object {
        private val Map<String, Any?>.starveUiThread
            get() = this.getOrDefault(ActivityOptions.EXTRA_STARVE_UI_THREAD, false) as Boolean

        private fun FlickerTestParameter.createConfig(
            starveUiThread: Boolean
        ): MutableMap<String, Any?> {
            val config = this.config.toMutableMap()
            config[ActivityOptions.EXTRA_STARVE_UI_THREAD] = starveUiThread
            return config
        }

        /**
         * Creates the test configurations for seamless rotation based on the default rotation
         * tests from [FlickerTestParameterFactory.getConfigRotationTests], but adding an
         * additional flag ([ActivityOptions.EXTRA_STARVE_UI_THREAD]) to indicate if the app
         * should starve the UI thread of not
         */
        @JvmStatic
        private fun getConfigurations(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigRotationTests(repetitions = 2)
                .flatMap {
                    val defaultRun = it.createConfig(starveUiThread = false)
                    val busyUiRun = it.createConfig(starveUiThread = true)
                    listOf(
                        FlickerTestParameter(defaultRun),
                        FlickerTestParameter(busyUiRun,
                            name = "${FlickerTestParameter.defaultName(busyUiRun)}_BUSY_UI_THREAD"
                        )
                    )
                }
        }

        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return getConfigurations()
        }
    }
}
