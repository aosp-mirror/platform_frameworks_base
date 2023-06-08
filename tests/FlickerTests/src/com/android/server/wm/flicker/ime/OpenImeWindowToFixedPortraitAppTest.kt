/*
 * Copyright (C) 2023 The Android Open Source Project
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
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.traces.region.RegionSubject
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window shown on the app with fixing portrait orientation.
 * To run this test: `atest FlickerTests:OpenImeWindowToFixedPortraitAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class OpenImeWindowToFixedPortraitAppTest (private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    testApp.launchViaIntent(wmHelper)
                    testApp.openIME(device, wmHelper)
                    // Enable letterbox when the app calls setRequestedOrientation
                    device.executeShellCommand("cmd window set-ignore-orientation-request true")
                }
            }
            transitions {
                testApp.toggleFixPortraitOrientation(wmHelper)
            }
            teardown {
                eachRun {
                    testApp.exit()
                    device.executeShellCommand("cmd window set-ignore-orientation-request false")
                }
            }
        }
    }

    @Postsubmit
    @Test
    fun imeLayerVisibleStart() {
        testSpec.assertLayersStart {
            this.isVisible(FlickerComponentName.IME)
        }
    }

    @Postsubmit
    @Test
    fun imeLayerExistsEnd() {
        testSpec.assertLayersEnd {
            this.isVisible(FlickerComponentName.IME)
        }
    }

    @Postsubmit
    @Test
    fun imeLayerVisibleRegionKeepsTheSame() {
        var imeLayerVisibleRegionBeforeTransition: RegionSubject? = null
        testSpec.assertLayersStart {
            imeLayerVisibleRegionBeforeTransition = this.visibleRegion(FlickerComponentName.IME)
        }
        testSpec.assertLayersEnd {
            this.visibleRegion(FlickerComponentName.IME)
                    .coversExactly(imeLayerVisibleRegionBeforeTransition!!.region)
        }
    }

    @Postsubmit
    @Test
    fun appWindowWithLetterboxCoversExactlyOnScreen() {
        val displayBounds = WindowUtils.getDisplayBounds(testSpec.startRotation)
        testSpec.assertLayersEnd {
            this.visibleRegion(testApp.component, FlickerComponentName.LETTERBOX)
                    .coversExactly(displayBounds)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(
                            supportedRotations = listOf(Surface.ROTATION_90, Surface.ROTATION_270),
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY,
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            )
                    )
        }
    }
}