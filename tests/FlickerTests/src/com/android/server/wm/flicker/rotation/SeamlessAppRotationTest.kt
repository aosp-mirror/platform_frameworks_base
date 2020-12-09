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

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusDoesNotChange
import com.android.server.wm.flicker.appWindowAlwaysVisibleOnTop
import com.android.server.wm.flicker.layerAlwaysVisible
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.stopPackage
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.repetitions
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
    testName: String,
    flickerSpec: Flicker
) : FlickerTestRunner(testName, flickerSpec) {
    companion object {
        private const val APP_LAUNCH_TIMEOUT: Long = 10000

        private val Bundle.intent: Intent?
            get() = this.getParcelable(Intent::class.java.simpleName)

        private val Bundle.intentPackageName: String
            get() = this.intent?.component?.packageName ?: ""

        private val Bundle.intentId get() = if (this.intent?.getBooleanExtra(
                ActivityOptions.EXTRA_STARVE_UI_THREAD, false) == true) {
            "BUSY_UI_THREAD"
        } else {
            ""
        }

        private fun Bundle.createConfig(starveUiThread: Boolean): Bundle {
            val config = this.deepCopy()
            val intent = Intent()
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.component = ComponentName("com.android.server.wm.flicker.testapp",
                "com.android.server.wm.flicker.testapp.SeamlessRotationActivity")

            intent.putExtra(ActivityOptions.EXTRA_STARVE_UI_THREAD, starveUiThread)

            config.putParcelable(Intent::class.java.simpleName, intent)
            return config
        }

        @JvmStatic
        private fun FlickerTestRunnerFactory.getConfigurations(): List<Bundle> {
            return this.getConfigRotationTests().flatMap {
                val defaultRun = it.createConfig(starveUiThread = false)
                val busyUiRun = it.createConfig(starveUiThread = true)
                listOf(defaultRun, busyUiRun)
            }
        }

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val factory = FlickerTestRunnerFactory(instrumentation)
            val configurations = factory.getConfigurations()
            return factory.buildRotationTest(configurations) { configuration ->
                withTestName {
                    buildTestTag("seamlessRotation_" + configuration.intentId,
                        app = null, configuration = configuration)
                }
                repeat { configuration.repetitions }
                setup {
                    test {
                        device.wakeUpAndGoToHomeScreen()
                        instrumentation.targetContext.startActivity(configuration.intent)
                        val searchQuery = By.pkg(configuration.intent?.component?.packageName)
                            .depth(0)
                        device.wait(Until.hasObject(searchQuery), APP_LAUNCH_TIMEOUT)
                    }
                    eachRun {
                        this.setRotation(configuration.startRotation)
                    }
                }
                teardown {
                    test {
                        this.setRotation(Surface.ROTATION_0)
                        stopPackage(
                            instrumentation.targetContext,
                            configuration.intent?.component?.packageName
                                ?: error("Unable to determine package name for intent"))
                    }
                }
                transitions {
                    this.setRotation(configuration.endRotation)
                }
                assertions {
                    windowManagerTrace {
                        navBarWindowIsAlwaysVisible(bugId = 140855415)
                        statusBarWindowIsAlwaysVisible(bugId = 140855415)
                        visibleWindowsShownMoreThanOneConsecutiveEntry()
                        appWindowAlwaysVisibleOnTop(configuration.intentPackageName)
                    }

                    layersTrace {
                        navBarLayerIsAlwaysVisible(bugId = 140855415)
                        statusBarLayerIsAlwaysVisible(bugId = 140855415)
                        noUncoveredRegions(configuration.startRotation,
                            configuration.endRotation, allStates = false, bugId = 147659548)
                        navBarLayerRotatesAndScales(configuration.startRotation,
                            configuration.endRotation)
                        statusBarLayerRotatesScales(configuration.startRotation,
                            configuration.endRotation, enabled = false)
                        visibleLayersShownMoreThanOneConsecutiveEntry(
                                enabled = configuration.startRotation == configuration.endRotation)
                        layerAlwaysVisible(configuration.intentPackageName)
                    }

                    layersTrace {
                        val startingBounds = WindowUtils
                            .getDisplayBounds(configuration.startRotation)
                        val endingBounds = WindowUtils
                            .getDisplayBounds(configuration.endRotation)

                        all("appLayerRotates", bugId = 147659548) {
                            if (startingBounds == endingBounds) {
                                this.hasVisibleRegion(
                                    configuration.intentPackageName, startingBounds)
                            } else {
                                this.hasVisibleRegion(configuration.intentPackageName,
                                    startingBounds)
                                    .then()
                                    .hasVisibleRegion(configuration.intentPackageName,
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
        }
    }
}