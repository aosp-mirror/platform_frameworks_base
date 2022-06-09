/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.FlakyTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerPositionEnd
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.snapshotStartingWindowLayerCoversExactlyOnApp
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window layer will become visible when switching from the fixed orientation activity
 * (e.g. Launcher activity).
 * To run this test: `atest FlickerTests:OpenImeWindowFromFixedOrientationAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class OpenImeWindowFromFixedOrientationAppTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)
    private val taplInstrumentation = LauncherInstrumentation()

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                test {
                    // Launch the activity with expecting IME will be shown.
                    imeTestApp.launchViaIntent(wmHelper)
                }
                eachRun {
                    // Swiping out the IME activity to home.
                    taplInstrumentation.goHome()
                    wmHelper.waitForHomeActivityVisible()
                }
            }
            transitions {
                // Bring the exist IME activity to the front in landscape mode device rotation.
                setRotation(Surface.ROTATION_90)
                imeTestApp.launchViaIntent(wmHelper)
            }
            teardown {
                test {
                    imeTestApp.exit(wmHelper)
                }
            }
        }
    }

    @Presubmit
    @Test
    fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    @Presubmit
    @Test
    fun imeWindowBecomesVisible() = testSpec.imeWindowBecomesVisible()

    @Presubmit
    @Test
    fun navBarLayerRotatesAndScales() = testSpec.navBarLayerPositionEnd()

    @FlakyTest(bugId = 206753786)
    fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()

    @Presubmit
    @Test
    fun imeLayerBecomesVisible() = testSpec.imeLayerBecomesVisible()

    @Postsubmit
    @Test
    fun snapshotStartingWindowLayerCoversExactlyOnApp() {
        testSpec.snapshotStartingWindowLayerCoversExactlyOnApp(imeTestApp.component)
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigNonRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(
                            repetitions = 3,
                            supportedRotations = listOf(Surface.ROTATION_90),
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            )
                    )
        }
    }
}