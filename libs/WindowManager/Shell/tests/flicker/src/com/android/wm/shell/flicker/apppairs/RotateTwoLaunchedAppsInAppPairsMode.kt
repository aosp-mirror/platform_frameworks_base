/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.flicker.apppairs

import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.appPairsDividerIsVisible
import com.android.wm.shell.flicker.appPairsPrimaryBoundsIsVisible
import com.android.wm.shell.flicker.appPairsSecondaryBoundsIsVisible
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open apps to app pairs and rotate.
 * To run this test: `atest WMShellFlickerTests:RotateTwoLaunchedAppsInAppPairsMode`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RotateTwoLaunchedAppsInAppPairsMode(
    testName: String,
    flickerProvider: () -> Flicker,
    cleanUp: Boolean
) : FlickerTestRunner(testName, flickerProvider, cleanUp) {
    companion object : RotateTwoLaunchedAppsTransition(
        InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testSpec: FlickerBuilder.(Bundle) -> Unit = { configuration ->
                withTestName {
                    buildTestTag("testRotateAndEnterAppPairsMode", configuration)
                }
                transitions {
                    executeShellCommand(composePairsCommand(
                        primaryTaskId, secondaryTaskId, true /* pair */))
                    SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
                    setRotation(configuration.endRotation)
                }
                assertions {
                    layersTrace {
                        navBarLayerRotatesAndScales(Surface.ROTATION_0, configuration.endRotation)
                        statusBarLayerRotatesScales(Surface.ROTATION_0, configuration.endRotation)
                        appPairsDividerIsVisible()
                        appPairsPrimaryBoundsIsVisible(configuration.endRotation,
                            primaryApp.defaultWindowName, 172776659)
                        appPairsSecondaryBoundsIsVisible(configuration.endRotation,
                            secondaryApp.defaultWindowName, 172776659)
                    }
                    windowManagerTrace {
                        navBarWindowIsAlwaysVisible()
                        statusBarWindowIsAlwaysVisible()
                        end {
                            isVisible(primaryApp.defaultWindowName)
                                .isVisible(secondaryApp.defaultWindowName)
                        }
                    }
                }
            }

            return FlickerTestRunnerFactory(instrumentation,
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_90, Surface.ROTATION_270)
            ).buildTest(transition, testSpec)
        }
    }
}