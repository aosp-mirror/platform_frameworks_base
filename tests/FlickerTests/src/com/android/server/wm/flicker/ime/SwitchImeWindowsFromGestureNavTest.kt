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

package com.android.server.wm.flicker.ime

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry

import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible

import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME windows switching with 2-Buttons or gestural navigation.
 * To run this test: `atest FlickerTests:SwitchImeWindowsFromGestureNavTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class SwitchImeWindowsFromGestureNavTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = SimpleAppHelper(instrumentation)
    private val imeTestApp = ImeAppHelper(instrumentation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    this.setRotation(testSpec.config.startRotation)
                    testApp.launchViaIntent(wmHelper)
                    imeTestApp.launchViaIntent(wmHelper)
                    imeTestApp.openIME(device, wmHelper)
                }
            }
            teardown {
                eachRun {
                    device.pressHome()
                    wmHelper.waitForHomeActivityVisible()
                }
                test {
                    imeTestApp.exit(wmHelper)
                }
            }
            transitions {
                // [Step1]: Swipe right from imeTestApp to testApp task
                val displayBounds = WindowUtils.getDisplayBounds(testSpec.config.startRotation)
                val displayCenterX = displayBounds.bounds.width() / 2
                device.swipe(displayCenterX, displayBounds.bounds.height(),
                        displayBounds.bounds.width(), displayBounds.bounds.height(), 20)
                wmHelper.waitForFullScreenApp(testApp.component)
            }
            transitions {
                // [Step2]: Swipe left to back to imeTestApp task
                val displayBounds = WindowUtils.getDisplayBounds(testSpec.config.startRotation)
                val displayCenterX = displayBounds.bounds.width() / 2
                device.swipe(displayBounds.bounds.width(), displayBounds.bounds.height(),
                        displayCenterX, displayBounds.bounds.height(), 20)
                wmHelper.waitForFullScreenApp(imeTestApp.component)
            }
        }
    }

    @FlakyTest
    @Test
    fun imeAppWindowIsAlwaysVisible() = testSpec.imeAppWindowIsAlwaysVisible(imeTestApp)

    @FlakyTest
    @Test
    fun imeLayerBecomesVisible() = testSpec.imeLayerBecomesVisible()

    @FlakyTest
    @Test
    fun imeLayerBecomesInvisible() = testSpec.imeLayerBecomesInvisible()

    @Presubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @FlakyTest
    @Test
    fun navBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @FlakyTest
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsAlwaysVisible()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(
                            repetitions = 3,
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY,
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            )
                    )
        }
    }
}
