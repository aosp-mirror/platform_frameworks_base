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

import android.os.Bundle
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusDoesNotChange
import com.android.server.wm.flicker.appWindowAlwaysVisibleOnTop
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SeamlessRotationAppHelper
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.layerAlwaysVisible
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Cycle through supported app rotations using seamless rotations.
 * To run this test: `atest FlickerTests:SeamlessAppRotationTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SeamlessAppRotationTest(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : RotationTransition(InstrumentationRegistry.getInstrumentation()) {
        override val testApp: StandardAppHelper
            get() = SeamlessRotationAppHelper(instrumentation)

        override fun getAppLaunchParams(configuration: Bundle): Map<String, String> = mapOf(
            ActivityOptions.EXTRA_STARVE_UI_THREAD to configuration.starveUiThread.toString()
        )

        private val testFactory = FlickerTestRunnerFactory.getInstance()

        private val Bundle.starveUiThread
            get() = this.getBoolean(ActivityOptions.EXTRA_STARVE_UI_THREAD, false)

        private fun Bundle.createConfig(starveUiThread: Boolean): Bundle {
            val config = this.deepCopy()
            config.putBoolean(ActivityOptions.EXTRA_STARVE_UI_THREAD, starveUiThread)
            return config
        }

        @JvmStatic
        private fun getConfigurations(): List<Bundle> {
            return testFactory.getConfigRotationTests().flatMap {
                val defaultRun = it.createConfig(starveUiThread = false)
                val busyUiRun = it.createConfig(starveUiThread = true)
                listOf(defaultRun, busyUiRun)
            }
        }

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val configurations = getConfigurations()
            val testSpec: FlickerBuilder.(Bundle) -> Unit = { configuration ->
                withTestName {
                    val extra = if (configuration.starveUiThread) {
                        "BUSY_UI_THREAD"
                    } else {
                        ""
                    }
                    buildTestTag("seamlessRotation", configuration, extraInfo = extra)
                }
                assertions {
                    windowManagerTrace {
                        navBarWindowIsAlwaysVisible(bugId = 140855415)
                        statusBarWindowIsAlwaysVisible(bugId = 140855415)
                        visibleWindowsShownMoreThanOneConsecutiveEntry()
                        appWindowAlwaysVisibleOnTop(testApp.`package`)
                    }

                    layersTrace {
                        navBarLayerIsAlwaysVisible(bugId = 140855415)
                        statusBarLayerIsAlwaysVisible(bugId = 140855415)
                        noUncoveredRegions(configuration.startRotation,
                            configuration.endRotation, allStates = false, bugId = 147659548)
                        navBarLayerRotatesAndScales(configuration.startRotation,
                            configuration.endRotation,
                            enabled = false)
                        statusBarLayerRotatesScales(configuration.startRotation,
                            configuration.endRotation, enabled = false)
                        visibleLayersShownMoreThanOneConsecutiveEntry(
                                enabled = configuration.startRotation == configuration.endRotation)
                        layerAlwaysVisible(testApp.`package`)
                    }

                    layersTrace {
                        val startingBounds = WindowUtils
                            .getDisplayBounds(configuration.startRotation)
                        val endingBounds = WindowUtils
                            .getDisplayBounds(configuration.endRotation)

                        all("appLayerRotates", bugId = 147659548) {
                            if (startingBounds == endingBounds) {
                                this.hasVisibleRegion(
                                    testApp.`package`, startingBounds)
                            } else {
                                this.hasVisibleRegion(testApp.`package`,
                                    startingBounds)
                                    .then()
                                    .hasVisibleRegion(testApp.`package`,
                                        endingBounds)
                            }
                        }

                        all("noUncoveredRegions", bugId = 147659548) {
                            if (startingBounds == endingBounds) {
                                this.coversAtLeastRegion(startingBounds)
                            } else {
                                this.coversAtLeastRegion(startingBounds)
                                    .then()
                                    .coversAtLeastRegion(endingBounds)
                            }
                        }
                    }

                    eventLog {
                        focusDoesNotChange(bugId = 151179149)
                    }
                }
            }

            return testFactory.buildRotationTest(instrumentation, transition, testSpec,
                deviceConfigurations = configurations, repetitions = 2)
        }
    }
}