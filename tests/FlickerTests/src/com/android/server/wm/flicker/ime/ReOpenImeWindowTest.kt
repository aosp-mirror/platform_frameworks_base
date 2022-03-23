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

package com.android.server.wm.flicker.ime

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.launcherWindowBecomesInvisible
import com.android.server.wm.flicker.appLayerReplacesLauncher
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window opening transitions.
 * To run this test: `atest FlickerTests:ReOpenImeWindowTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class ReOpenImeWindowTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = ImeAppAutoFocusHelper(instrumentation, testSpec.config.startRotation)
    private val testAppComponentName = ActivityOptions.IME_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                test {
                    testApp.launchViaIntent(wmHelper)
                    testApp.openIME(device, wmHelper)
                }
                eachRun {
                    device.pressRecentApps()
                    wmHelper.waitImeWindowGone()
                    wmHelper.waitForAppTransitionIdle()
                    this.setRotation(testSpec.config.startRotation)
                }
            }
            transitions {
                device.reopenAppFromOverview(wmHelper)
                wmHelper.waitImeWindowShown()
            }
            teardown {
                test {
                    testApp.exit()
                }
            }
        }
    }

    @Presubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        testSpec.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry()
        }
    }

    @Presubmit
    @Test
    fun launcherWindowBecomesInvisible() = testSpec.launcherWindowBecomesInvisible()

    @Presubmit
    @Test
    fun imeWindowIsAlwaysVisible() = testSpec.imeWindowIsAlwaysVisible(true)

    @Presubmit
    @Test
    fun imeAppWindowIsAlwaysVisible() = testSpec.imeAppWindowIsAlwaysVisible(testApp, true)

    @Presubmit
    @Test
    // During testing the launcher is always in portrait mode
    fun noUncoveredRegions() = testSpec.noUncoveredRegions(testSpec.config.startRotation,
        testSpec.config.endRotation)

    @Presubmit
    @Test
    fun navBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    fun imeLayerIsAlwaysVisible() = testSpec.imeLayerIsAlwaysVisible(true)

    @Presubmit
    @Test
    fun appLayerReplacesLauncher() =
        testSpec.appLayerReplacesLauncher(testAppComponentName.className)

    @Presubmit
    @Test
    fun navBarLayerRotatesAndScales() {
        testSpec.navBarLayerRotatesAndScales(Surface.ROTATION_0, testSpec.config.endRotation)
    }

    @Presubmit
    @Test
    fun statusBarLayerRotatesScales() {
        testSpec.statusBarLayerRotatesScales(Surface.ROTATION_0, testSpec.config.endRotation)
    }

    @Presubmit
    @Test
    fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        testSpec.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry()
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(
                    repetitions = 1,
                    supportedRotations = listOf(Surface.ROTATION_0),
                    supportedNavigationModes = listOf(
                        WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                    )
                )
        }
    }
}
