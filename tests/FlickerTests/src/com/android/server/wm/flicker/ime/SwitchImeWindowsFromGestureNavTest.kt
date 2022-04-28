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
import android.view.Display
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.common.WindowManagerConditionsFactory
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.Assume
import org.junit.Before

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
@Group4
@Presubmit
open class SwitchImeWindowsFromGestureNavTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = SimpleAppHelper(instrumentation)
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    @Before
    open fun before() {
        Assume.assumeFalse(isShellTransitionsEnabled)
    }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    this.setRotation(testSpec.startRotation)
                    testApp.launchViaIntent(wmHelper)
                    val testAppVisible = wmHelper.waitFor(
                        WindowManagerStateHelper.isAppFullScreen(testApp.component),
                        WindowManagerConditionsFactory.isAppTransitionIdle(
                            Display.DEFAULT_DISPLAY))
                    require(testAppVisible) {
                        "Expected ${testApp.component.toWindowName()} to be visible"
                    }

                    imeTestApp.launchViaIntent(wmHelper)
                    val imeAppVisible = wmHelper.waitFor(
                        WindowManagerStateHelper.isAppFullScreen(imeTestApp.component),
                        WindowManagerConditionsFactory.isAppTransitionIdle(
                            Display.DEFAULT_DISPLAY))
                    require(imeAppVisible) {
                        "Expected ${imeTestApp.component.toWindowName()} to be visible"
                    }

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
                val displayBounds = WindowUtils.getDisplayBounds(testSpec.startRotation)
                device.swipe(0, displayBounds.bounds.height,
                        displayBounds.bounds.width, displayBounds.bounds.height, 50)

                wmHelper.waitForFullScreenApp(testApp.component)
                wmHelper.waitForAppTransitionIdle()
                createTag(TAG_IME_INVISIBLE)
            }
            transitions {
                // [Step2]: Swipe left to back to imeTestApp task
                val displayBounds = WindowUtils.getDisplayBounds(testSpec.startRotation)
                device.swipe(displayBounds.bounds.width, displayBounds.bounds.height,
                        0, displayBounds.bounds.height, 50)
                wmHelper.waitForFullScreenApp(imeTestApp.component)
            }
        }
    }

    @Test
    fun imeAppWindowVisibility() {
        testSpec.assertWm {
            isAppWindowVisible(imeTestApp.component)
                .then()
                .isAppSnapshotStartingWindowVisibleFor(testApp.component, isOptional = true)
                .then()
                .isAppWindowVisible(testApp.component)
                .then()
                .isAppSnapshotStartingWindowVisibleFor(imeTestApp.component, isOptional = true)
                .then()
                .isAppWindowVisible(imeTestApp.component)
        }
    }

    @Test
    fun navBarLayerIsVisibleAroundSwitching() {
        testSpec.assertLayersStart {
            isVisible(FlickerComponentName.NAV_BAR)
        }
        testSpec.assertLayersEnd {
            isVisible(FlickerComponentName.NAV_BAR)
        }
    }

    @Test
    fun statusBarLayerIsVisibleAroundSwitching() {
        testSpec.assertLayersStart {
            isVisible(FlickerComponentName.STATUS_BAR)
        }
        testSpec.assertLayersEnd {
            isVisible(FlickerComponentName.STATUS_BAR)
        }
    }

    @Test
    fun imeLayerIsVisibleWhenSwitchingToImeApp() {
        testSpec.assertLayersStart {
            isVisible(FlickerComponentName.IME)
        }
        testSpec.assertLayersTag(TAG_IME_VISIBLE) {
            isVisible(FlickerComponentName.IME)
        }
        testSpec.assertLayersEnd {
            isVisible(FlickerComponentName.IME)
        }
    }

    @Test
    fun imeLayerIsInvisibleWhenSwitchingToTestApp() {
        testSpec.assertLayersTag(TAG_IME_INVISIBLE) {
            isInvisible(FlickerComponentName.IME)
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
                            ),
                            supportedRotations = listOf(Surface.ROTATION_0)
                    )
        }

        private const val TAG_IME_VISIBLE = "imeVisible"
        private const val TAG_IME_INVISIBLE = "imeInVisible"
    }
}
