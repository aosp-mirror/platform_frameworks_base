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
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME snapshot mechanism won't apply when transitioning from non-IME focused dialog activity.
 * To run this test: `atest FlickerTests:LaunchAppShowImeAndDialogThemeAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LaunchAppShowImeAndDialogThemeAppTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    testApp.launchViaIntent(wmHelper)
                    wmHelper.waitImeShown()
                    testApp.startDialogThemedActivity(wmHelper)
                }
            }
            teardown {
                eachRun {
                    testApp.exit()
                }
            }
            transitions {
                testApp.dismissDialog(wmHelper)
            }
        }
    }

    /**
     * Checks that [FlickerComponentName.IME] layer becomes visible during the transition
     */
    @FlakyTest(bugId = 215884488)
    @Test
    fun imeWindowIsAlwaysVisible() = testSpec.imeWindowIsAlwaysVisible()

    /**
     * Checks that [FlickerComponentName.IME] layer is visible at the end of the transition
     */
    @FlakyTest(bugId = 227142436)
    @Test
    fun imeLayerExistsEnd() {
        testSpec.assertLayersEnd {
            this.isVisible(FlickerComponentName.IME)
        }
    }

    /**
     * Checks that [FlickerComponentName.IME_SNAPSHOT] layer is invisible always.
     */
    @Presubmit
    @Test
    fun imeSnapshotNotVisible() {
        testSpec.assertLayers {
            this.isInvisible(FlickerComponentName.IME_SNAPSHOT)
        }
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
                            supportedRotations = listOf(Surface.ROTATION_0),
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY,
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            )
                    )
        }
    }
}
