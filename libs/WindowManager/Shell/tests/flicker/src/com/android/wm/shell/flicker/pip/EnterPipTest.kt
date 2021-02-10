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

package com.android.wm.shell.flicker.pip

import android.os.Bundle
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.startRotation
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch.
 * To run this test: `atest WMShellFlickerTests:EnterPipTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterPipTest(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : PipTransitionBase(InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val baseConfig = getTransitionLaunch(
                eachRun = true, stringExtras = emptyMap())
            val testSpec: FlickerBuilder.(Bundle) -> Unit = { configuration ->
                transitions {
                    pipApp.clickEnterPipButton()
                    pipApp.expandPipWindow(wmHelper)
                }
                assertions {
                    presubmit {
                        windowManagerTrace {
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()

                            all("pipWindowBecomesVisible") {
                                this.showsAppWindow(pipApp.defaultWindowName)
                            }
                        }

                        layersTrace {
                            statusBarLayerIsAlwaysVisible()
                            statusBarLayerRotatesScales(configuration.startRotation,
                                Surface.ROTATION_0)
                        }

                        layersTrace {
                            all("pipLayerBecomesVisible") {
                                this.showsLayer(pipApp.launcherName)
                            }
                        }
                    }

                    flaky {
                        layersTrace {
                            navBarLayerIsAlwaysVisible(bugId = 140855415)
                            noUncoveredRegions(configuration.startRotation, Surface.ROTATION_0)
                            navBarLayerRotatesAndScales(configuration.startRotation,
                                Surface.ROTATION_0, bugId = 140855415)
                        }
                    }
                }
            }

            return FlickerTestRunnerFactory.getInstance().buildTest(instrumentation, baseConfig,
                testSpec, supportedRotations = listOf(Surface.ROTATION_0),
                repetitions = 5)
        }
    }
}