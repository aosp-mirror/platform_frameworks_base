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

package com.android.server.wm.flicker.launch

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusChanges
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.wallpaperWindowBecomesInvisible
import com.android.server.wm.flicker.appLayerReplacesWallpaperLayer
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Launch an app from the recents app view (the overview)
 * To run this test: `atest FlickerTests:OpenAppFromOverviewTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppFromOverviewTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = SimpleAppHelper(instrumentation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            withTestName { testSpec.name }
            repeat { testSpec.config.repetitions }
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    testApp.launchViaIntent(wmHelper)
                }
                eachRun {
                    device.pressHome()
                    wmHelper.waitForAppTransitionIdle()
                    device.pressRecentApps()
                    wmHelper.waitForAppTransitionIdle()
                    this.setRotation(testSpec.config.startRotation)
                }
            }
            transitions {
                device.reopenAppFromOverview(wmHelper)
                wmHelper.waitForFullScreenApp(testApp.component)
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

    @Test
    fun appWindowReplacesLauncherAsTopWindow() =
        testSpec.appWindowReplacesLauncherAsTopWindow(testApp)

    @Test
    fun wallpaperWindowBecomesInvisible() = testSpec.wallpaperWindowBecomesInvisible()

    @Presubmit
    @Test
    fun appLayerReplacesWallpaperLayer() =
        testSpec.appLayerReplacesWallpaperLayer(testApp.`package`)

    @Presubmit
    @Test
    fun navBarLayerRotatesAndScales() {
        Assume.assumeFalse(testSpec.isRotated)
        testSpec.navBarLayerRotatesAndScales(testSpec.config.startRotation, Surface.ROTATION_0)
    }

    @Presubmit
    @Test
    fun statusBarLayerRotatesScales() {
        Assume.assumeFalse(testSpec.isRotated)
        testSpec.statusBarLayerRotatesScales(testSpec.config.startRotation, Surface.ROTATION_0)
    }

    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysVisible() {
        Assume.assumeTrue(testSpec.isRotated)
        testSpec.statusBarLayerIsAlwaysVisible()
    }

    @Presubmit
    @Test
    fun navBarLayerIsAlwaysVisible() {
        Assume.assumeTrue(testSpec.isRotated)
        testSpec.navBarLayerIsAlwaysVisible()
    }

    @Presubmit
    @Test
    fun focusChanges() = testSpec.focusChanges("NexusLauncherActivity", testApp.`package`)

    @Presubmit
    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        Assume.assumeFalse(testSpec.isRotated)
        testSpec.visibleWindowsShownMoreThanOneConsecutiveEntry()
    }

    @FlakyTest
    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry_Flaky() {
        Assume.assumeTrue(testSpec.isRotated)
        testSpec.visibleWindowsShownMoreThanOneConsecutiveEntry()
    }

    @Presubmit
    @Test
    fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        Assume.assumeFalse(testSpec.isRotated)
        testSpec.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    @FlakyTest
    @Test
    fun visibleLayersShownMoreThanOneConsecutiveEntry_Flaky() {
        Assume.assumeTrue(testSpec.isRotated)
        testSpec.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    @Presubmit
    @Test
    fun noUncoveredRegions() = testSpec.noUncoveredRegions(Surface.ROTATION_0,
        testSpec.config.endRotation)

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(repetitions = 5)
        }
    }
}