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
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.*
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeFalse
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window layer will be associated with the app task when going to the overview screen.
 * To run this test: `atest FlickerTests:OpenImeWindowToOverViewTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
class OpenImeWindowToOverViewTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    imeTestApp.launchViaIntent(wmHelper)
                }
            }
            transitions {
                device.pressRecentApps()
                waitForRecentsActivityVisible(wmHelper)
            }
            teardown {
                test {
                    device.pressHome()
                    imeTestApp.exit(wmHelper)
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
    fun imeWindowIsAlwaysVisible() {
        testSpec.imeWindowIsAlwaysVisible()
    }

    @Postsubmit
    @Test
    fun navBarLayerIsVisible() = testSpec.navBarLayerIsVisible()

    @Postsubmit
    @Test
    fun statusBarLayerIsVisibleInPortrait() {
        assumeFalse(testSpec.isLandscapeOrSeascapeAtStart)
        testSpec.statusBarLayerIsVisible()
    }

    @Postsubmit
    @Test
    fun statusBarLayerIsInVisibleInLandscape() {
        assumeTrue(testSpec.isLandscapeOrSeascapeAtStart)
        testSpec.assertLayersStart {
            this.isVisible(FlickerComponentName.STATUS_BAR)
        }
        testSpec.assertLayersEnd {
            this.isInvisible(FlickerComponentName.STATUS_BAR)
        }
    }

    @Postsubmit
    @Test
    fun imeLayerIsVisibleAndAssociatedWithAppWidow() {
        testSpec.assertLayersStart {
            isVisible(FlickerComponentName.IME).visibleRegion(FlickerComponentName.IME)
                    .coversAtMost(isVisible(imeTestApp.component)
                            .visibleRegion(imeTestApp.component).region)
        }
        testSpec.assertLayers {
            this.invoke("imeLayerIsVisibleAndAlignAppWidow") {
                val imeVisibleRegion = it.visibleRegion(FlickerComponentName.IME)
                val appVisibleRegion = it.visibleRegion(imeTestApp.component)
                if (imeVisibleRegion.region.isNotEmpty) {
                    it.isVisible(FlickerComponentName.IME)
                    imeVisibleRegion.coversAtMost(appVisibleRegion.region)
                }
            }
        }
    }

    private fun waitForRecentsActivityVisible(
        wmHelper: WindowManagerStateHelper
    ) {
        val waitMsg = "state of Recents activity to be visible"
        require(
                wmHelper.waitFor(waitMsg) {
                    it.wmState.homeActivity?.let { act ->
                        it.wmState.isActivityVisible(act.name)
                    } == true ||
                            it.wmState.recentsActivity?.let { act ->
                                it.wmState.isActivityVisible(act.name)
                            } == true
                }
        ) { "Recents activity should be visible" }
        wmHelper.waitForAppTransitionIdle()
        // Ensure WindowManagerService wait until all animations have completed
        instrumentation.uiAutomation.syncInputTransactions()
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
                            repetitions = 1,
                            supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90),
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY,
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            )
                    )
        }
    }
}