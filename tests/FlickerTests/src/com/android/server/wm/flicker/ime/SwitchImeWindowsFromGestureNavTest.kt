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
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry

import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.NAV_BAR_LAYER_NAME
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.STATUS_BAR_LAYER_NAME

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
@Presubmit
class SwitchImeWindowsFromGestureNavTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = SimpleAppHelper(instrumentation)
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, testSpec.config.startRotation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    this.setRotation(testSpec.config.startRotation)
                    testApp.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(testApp.component)
                    wmHelper.waitForAppTransitionIdle()

                    imeTestApp.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(testApp.component)
                    wmHelper.waitForAppTransitionIdle()

                    imeTestApp.openIME(device, wmHelper)
                }
            }
            teardown {
                eachRun {
                    device.pressHome()
                    wmHelper.waitForHomeActivityVisible()
                    testApp.exit()
                    imeTestApp.exit()
                }
            }
            transitions {
                // [Step1]: Swipe right from imeTestApp to testApp task
                createTag(TAG_IME_VISIBLE)
                val displayBounds = WindowUtils.getDisplayBounds(testSpec.config.startRotation)
                device.swipe(0, displayBounds.bounds.height(),
                        displayBounds.bounds.width(), displayBounds.bounds.height(), 50)

                wmHelper.waitForFullScreenApp(testApp.component)
                wmHelper.waitForAppTransitionIdle()
                createTag(Companion.TAG_IME_INVISIBLE)
            }
            transitions {
                // [Step2]: Swipe left to back to imeTestApp task
                val displayBounds = WindowUtils.getDisplayBounds(testSpec.config.startRotation)
                device.swipe(displayBounds.bounds.width(), displayBounds.bounds.height(),
                        0, displayBounds.bounds.height(), 50)
                wmHelper.waitForFullScreenApp(imeTestApp.component)
            }
        }
    }

    @Test
    fun imeAppWindowAlwaysVisible() {
        testSpec.assertWm {
            this.showsAppWindowOnTop(testApp.getPackage())
                    .then()
                    .showsAppWindow(testApp.getPackage())
        }
    }

    @Test
    fun navBarLayerIsVisibleAroundSwitching() {
        testSpec.assertLayersStart {
            isVisible(NAV_BAR_LAYER_NAME)
        }
        testSpec.assertLayersEnd {
            isVisible(NAV_BAR_LAYER_NAME)
        }
    }

    @Test
    fun statusBarLayerIsVisibleAroundSwitching() {
        testSpec.assertLayersStart {
            isVisible(STATUS_BAR_LAYER_NAME)
        }
        testSpec.assertLayersEnd {
            isVisible(STATUS_BAR_LAYER_NAME)
        }
    }

    @Test
    fun imeLayerIsVisibleWhenSwitchingToImeApp() {
        testSpec.assertLayersStart {
            isVisible(IME_WINDOW_TITLE)
        }
        testSpec.assertLayersTag(TAG_IME_VISIBLE) {
            isVisible(IME_WINDOW_TITLE)
        }
        testSpec.assertLayersEnd {
            isVisible(IME_WINDOW_TITLE)
        }
    }

    @Test
    fun imeLayerIsInvisibleWhenSwitchingToTestApp() {
        testSpec.assertLayersTag(TAG_IME_INVISIBLE) {
            isInvisible(IME_WINDOW_TITLE)
        }
    }

    @Test
    fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    @Test
    fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(
                            repetitions = 3,
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            )
                    )
        }

        private const val TAG_IME_VISIBLE = "imeVisible"
        private const val TAG_IME_INVISIBLE = "imeInVisible"
    }
}
