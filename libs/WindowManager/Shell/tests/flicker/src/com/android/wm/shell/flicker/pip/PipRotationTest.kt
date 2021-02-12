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

import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.startRotation
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip Stack in bounds after rotations.
 * To run this test: `atest WMShellFlickerTests:PipRotationTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PipRotationTest(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : PipTransitionBase(InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val fixedApp = FixedAppHelper(instrumentation)
            val testSpec = getTransition(eachRun = false) { configuration ->
                setup {
                    test {
                        fixedApp.launchViaIntent(wmHelper)
                    }
                    eachRun {
                        setRotation(configuration.startRotation)
                    }
                }
                transitions {
                    setRotation(configuration.endRotation)
                }
                teardown {
                    eachRun {
                        setRotation(Surface.ROTATION_0)
                    }
                }
                assertions {
                    val startingBounds = WindowUtils.getDisplayBounds(configuration.startRotation)
                    val endingBounds = WindowUtils.getDisplayBounds(configuration.endRotation)

                    presubmit {
                        windowManagerTrace {
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()
                        }

                        layersTrace {
                            noUncoveredRegions(configuration.startRotation,
                                configuration.endRotation, allStates = false)
                        }
                    }

                    flaky {
                        layersTrace {
                            navBarLayerIsAlwaysVisible(bugId = 140855415)
                            statusBarLayerIsAlwaysVisible(bugId = 140855415)
                            navBarLayerRotatesAndScales(configuration.startRotation,
                                configuration.endRotation, bugId = 140855415)
                            statusBarLayerRotatesScales(configuration.startRotation,
                                configuration.endRotation, bugId = 140855415)

                            start("appLayerRotates_StartingBounds", bugId = 140855415) {
                                hasVisibleRegion(fixedApp.defaultWindowName, startingBounds)
                                coversAtMostRegion(startingBounds, pipApp.defaultWindowName)
                            }
                            end("appLayerRotates_EndingBounds", bugId = 140855415) {
                                hasVisibleRegion(fixedApp.defaultWindowName, endingBounds)
                                coversAtMostRegion(endingBounds, pipApp.defaultWindowName)
                            }
                        }
                    }
                }
            }

            return FlickerTestRunnerFactory.getInstance().buildRotationTest(instrumentation,
                testSpec, supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90),
                repetitions = 5)
        }
    }
}