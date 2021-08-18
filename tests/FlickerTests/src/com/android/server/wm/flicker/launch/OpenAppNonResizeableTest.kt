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

package com.android.server.wm.flicker.launch

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Launch an app while the phone is locked
 * To run this test: `atest FlickerTests:OpenAppNonResizeableTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class OpenAppNonResizeableTest(testSpec: FlickerTestParameter) : OpenAppTransition(testSpec) {
    override val testApp = NonResizeableAppHelper(instrumentation)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = {
            super.transition(this, it)
            setup {
                eachRun {
                    device.sleep()
                    wmHelper.waitForAppTransitionIdle()
                }
            }
            teardown {
                eachRun {
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                testApp.launchViaIntent(wmHelper)
                wmHelper.waitForFullScreenApp(testApp.component)
            }
        }

    @Presubmit
    @Test
    override fun navBarLayerIsVisible() {
        testSpec.assertLayersEnd {
            isVisible(WindowManagerStateHelper.NAV_BAR_COMPONENT)
        }
    }

    @Presubmit
    @Test
    fun nonResizableAppLayerBecomesVisible() {
        testSpec.assertLayers {
            this.notContains(testApp.component)
                    .then()
                    .isInvisible(testApp.component)
                    .then()
                    .isVisible(testApp.component)
        }
    }

    @Presubmit
    @Test
    fun nonResizableAppWindowBecomesVisible() {
        testSpec.assertWm {
            this.notContains(testApp.component)
                    .then()
                    .isAppWindowInvisible(testApp.component,
                            ignoreActivity = true, isOptional = true)
                    .then()
                    .isAppWindowVisible(testApp.component, ignoreActivity = true)
        }
    }

    @Presubmit
    @Test
    fun nonResizableAppWindowBecomesVisibleAtEnd() {
        testSpec.assertWmEnd {
            this.isVisible(testApp.component)
        }
    }

    @FlakyTest
    @Test
    override fun navBarWindowIsVisible() = super.navBarWindowIsVisible()

    @Postsubmit
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    @Postsubmit
    @Test
    override fun statusBarWindowIsVisible() = super.statusBarWindowIsVisible()

    @Postsubmit
    @Test
    override fun statusBarLayerIsVisible() = super.statusBarLayerIsVisible()

    @Postsubmit
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    @FlakyTest
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
            super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @FlakyTest
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    @FlakyTest
    @Test
    override fun focusChanges() = super.focusChanges()

    @FlakyTest
    @Test
    override fun appLayerReplacesLauncher() = super.appLayerReplacesLauncher()

    @FlakyTest
    @Test
    override fun appWindowReplacesLauncherAsTopWindow() =
            super.appWindowReplacesLauncherAsTopWindow()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(
                            repetitions = 5,
                            supportedNavigationModes =
                            listOf(WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY),
                            supportedRotations = listOf(Surface.ROTATION_0)
                    )
        }
    }
}