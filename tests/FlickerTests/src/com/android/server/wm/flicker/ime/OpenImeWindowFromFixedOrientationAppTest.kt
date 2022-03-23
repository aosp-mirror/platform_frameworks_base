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
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.*
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window layer will become visible when switching from the fixed orientation activity.
 * To run this test: `atest FlickerTests:OpenImeWindowFromFixedOrientationAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class OpenImeWindowFromFixedOrientationAppTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fixedOrientationApp = FixedOrientationAppHelper(instrumentation)
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    fixedOrientationApp.launchViaIntent(wmHelper)
                    this.setRotation(Surface.ROTATION_90)
                }
            }
            transitions {
                imeTestApp.launchViaIntent(wmHelper)
            }
            teardown {
                test {
                    fixedOrientationApp.exit(wmHelper)
                }
            }
        }
    }

    @Postsubmit
    @Test
    fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    @Postsubmit
    @Test
    fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    @Postsubmit
    @Test
    fun imeWindowBecomesVisible() = testSpec.imeWindowBecomesVisible()

    @Postsubmit
    @Test
    fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales()

    @FlakyTest(bugId = 206753786)
    fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()

    @Postsubmit
    @Test
    fun imeLayerBecomesVisible() = testSpec.imeLayerBecomesVisible()

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
                            supportedRotations = listOf(Surface.ROTATION_0),
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            )
                    )
        }
    }
}