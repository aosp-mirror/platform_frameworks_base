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
import com.android.server.wm.flicker.focusChanges
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
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
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
 * Test warm launch app.
 * To run this test: `atest FlickerTests:OpenAppWarmTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppWarmTest(private val testSpec: FlickerTestParameter) {
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
                    // wmHelper.waitForFullScreenApp(testApp.component)
                }
                eachRun {
                    device.pressHome()
                    wmHelper.waitForHomeActivityVisible()
                    this.setRotation(testSpec.config.startRotation)
                }
            }
            transitions {
                testApp.launchViaIntent(wmHelper)
                wmHelper.waitForFullScreenApp(testApp.component)
            }
            teardown {
                eachRun {
                    this.setRotation(Surface.ROTATION_0)
                }
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

    @FlakyTest
    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @Presubmit
    @Test
    fun appWindowReplacesLauncherAsTopWindow() =
        testSpec.appWindowReplacesLauncherAsTopWindow(testApp)

    @Presubmit
    @Test
    fun wallpaperWindowBecomesInvisible() = testSpec.wallpaperWindowBecomesInvisible()

    @Presubmit
    @Test
    // During testing the launcher is always in portrait mode
    fun noUncoveredRegions() = testSpec.noUncoveredRegions(testSpec.config.startRotation,
        Surface.ROTATION_0)

    @Presubmit
    @Test
    fun navBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsAlwaysVisible()

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

    @FlakyTest
    @Test
    fun navBarLayerRotatesAndScales_Flaky() {
        Assume.assumeTrue(testSpec.isRotated)
        testSpec.navBarLayerRotatesAndScales(testSpec.config.startRotation, Surface.ROTATION_0)
    }

    @Presubmit
    @Test
    fun statusBarLayerRotatesScales() {
        Assume.assumeFalse(testSpec.isRotated)
        testSpec.statusBarLayerRotatesScales(testSpec.config.startRotation, Surface.ROTATION_0)
    }

    @FlakyTest
    @Test
    fun statusBarLayerRotatesScales_Flaky() {
        Assume.assumeTrue(testSpec.isRotated)
        testSpec.statusBarLayerRotatesScales(testSpec.config.startRotation, Surface.ROTATION_0)
    }

    @Presubmit
    @Test
    fun focusChanges() = testSpec.focusChanges("NexusLauncherActivity", testApp.`package`)

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests()
        }
    }
}
