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
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Cycle through supported app rotations using seamless rotations.
 * To run this test: `atest FlickerTests:SeamlessAppRotationTest`
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

    @Presubmit
    @Test
    fun appLayerAlwaysVisible() {
        testSpec.assertLayers {
            isVisible(testApp.component)
        }
    }

    @FlakyTest(bugId = 185400889)
    @Test
    fun appLayerRotates() {
        testSpec.assertLayers {
            this.coversExactly(startingPos, testApp.component)
                .then()
                .coversExactly(endingPos, testApp.component)
        }
    }

    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysInvisible() {
        testSpec.assertWm {
            this.isAboveAppWindowInvisible(WindowManagerStateHelper.STATUS_BAR_COMPONENT)
        }
    }

    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysInvisible() {
        testSpec.assertLayers {
            this.isInvisible(WindowManagerStateHelper.STATUS_BAR_COMPONENT)
        }
    }

    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        super.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    @FlakyTest
    @Test
    override fun navBarWindowIsVisible() {
        super.navBarWindowIsVisible()
    }

    @FlakyTest
    @Test
    override fun navBarLayerIsVisible() {
        super.navBarLayerIsVisible()
    }

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    companion object {
        private val testFactory = FlickerTestParameterFactory.getInstance()

        private val Map<String, Any?>.starveUiThread
            get() = this.getOrDefault(ActivityOptions.EXTRA_STARVE_UI_THREAD, false) as Boolean

        private fun FlickerTestParameter.createConfig(
            starveUiThread: Boolean
        ): MutableMap<String, Any?> {
            val config = this.config.toMutableMap()
            config[ActivityOptions.EXTRA_STARVE_UI_THREAD] = starveUiThread
            return config
        }

        @JvmStatic
        private fun getConfigurations(): List<FlickerTestParameter> {
            return testFactory.getConfigRotationTests(repetitions = 2).flatMap {
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

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return getConfigurations()
        }
    }
}
