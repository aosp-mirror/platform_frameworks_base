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

import android.platform.test.annotations.Presubmit
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.traces.common.ComponentNameMatcher
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window closing back to app window transitions.
 *
 * This test doesn't work on 90 degrees. According to the InputMethodService documentation:
 *
 *     Don't show if this is not explicitly requested by the user and the input method
 *     is fullscreen. That would be too disruptive.
 *
 * More details on b/190352379
 *
 * To run this test: `atest FlickerTests:CloseImeAutoOpenWindowToAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class CloseImeAutoOpenWindowToAppTest(testSpec: FlickerTestParameter) : BaseTest(testSpec) {
    private val testApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            eachRun {
                testApp.launchViaIntent(wmHelper)
            }
        }
        teardown {
            eachRun {
                testApp.exit(wmHelper)
            }
        }
        transitions {
            testApp.closeIME(wmHelper)
        }
    }

    @Presubmit
    @Test
    fun imeAppWindowIsAlwaysVisible() {
        testSpec.assertWm {
            this.isAppWindowOnTop(testApp)
        }
    }

    @Presubmit
    @Test
    fun imeLayerVisibleStart() {
        testSpec.assertLayersStart {
            this.isVisible(ComponentNameMatcher.IME)
        }
    }

    @Presubmit
    @Test
    fun imeLayerInvisibleEnd() {
        testSpec.assertLayersEnd {
            this.isInvisible(ComponentNameMatcher.IME)
        }
    }

    @Presubmit
    @Test
    fun imeLayerBecomesInvisible() = testSpec.imeLayerBecomesInvisible()

    @Presubmit
    @Test
    fun imeAppLayerIsAlwaysVisible() {
        testSpec.assertLayers {
            this.isVisible(testApp)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(
                    repetitions = 3,
                    // b/190352379 (IME doesn't show on app launch in 90 degrees)
                    supportedRotations = listOf(Surface.ROTATION_0),
                    supportedNavigationModes = listOf(
                        WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY,
                        WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                    )
                )
        }
    }
}
