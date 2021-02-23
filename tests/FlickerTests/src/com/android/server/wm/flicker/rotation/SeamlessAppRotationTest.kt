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

package com.android.server.wm.flicker.rotation

import android.os.Bundle
import android.platform.test.annotations.Presubmit
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.appWindowAlwaysVisibleOnTop
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusDoesNotChange
import com.android.server.wm.flicker.helpers.SeamlessRotationAppHelper
import com.android.server.wm.flicker.layerAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Cycle through supported app rotations using seamless rotations.
 * To run this test: `atest FlickerTests:SeamlessAppRotationTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SeamlessAppRotationTest(
    testSpec: FlickerTestParameter
) : RotationTransition(testSpec) {
    override val testApp = SeamlessRotationAppHelper(instrumentation)

    override fun getAppLaunchParams(configuration: Bundle): Map<String, String> = mapOf(
        ActivityOptions.EXTRA_STARVE_UI_THREAD to configuration.starveUiThread.toString()
    )

    @Presubmit
    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @Presubmit
    @Test
    fun appWindowAlwaysVisibleOnTop() = testSpec.appWindowAlwaysVisibleOnTop(testApp.`package`)

    @Presubmit
    @Test
    fun layerAlwaysVisible() = testSpec.layerAlwaysVisible(testApp.`package`)

    @FlakyTest(bugId = 140855415)
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @FlakyTest(bugId = 140855415)
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @FlakyTest(bugId = 140855415)
    @Test
    fun navBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @FlakyTest(bugId = 140855415)
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsAlwaysVisible()

    @FlakyTest(bugId = 147659548)
    @Test
    fun noUncoveredRegions() = testSpec.noUncoveredRegions(testSpec.config.startRotation,
        testSpec.config.endRotation, allStates = false)

    @FlakyTest
    @Test
    fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales(
        testSpec.config.startRotation, testSpec.config.endRotation)

    @FlakyTest
    @Test
    fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales(
        testSpec.config.startRotation, testSpec.config.endRotation)

    @FlakyTest
    @Test
    fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleLayersShownMoreThanOneConsecutiveEntry()

    @FlakyTest(bugId = 147659548)
    @Test
    fun appLayerRotates() {
        testSpec.assertLayers {
            this.hasVisibleRegion(testApp.`package`, startingPos)
        }
    }

    @FlakyTest(bugId = 151179149)
    @Test
    fun focusDoesNotChange() = testSpec.focusDoesNotChange()

    companion object {
        private val testFactory = FlickerTestParameterFactory.getInstance()

        private val Bundle.starveUiThread
            get() = this.getBoolean(ActivityOptions.EXTRA_STARVE_UI_THREAD, false)

        private fun FlickerTestParameter.createConfig(starveUiThread: Boolean): Bundle {
            val config = this.config.deepCopy()
            config.putBoolean(ActivityOptions.EXTRA_STARVE_UI_THREAD, starveUiThread)
            return config
        }

        @JvmStatic
        private fun getConfigurations(): List<FlickerTestParameter> {
            return testFactory.getConfigRotationTests(repetitions = 2).flatMap {
                val defaultRun = it.createConfig(starveUiThread = false)
                val busyUiRun = it.createConfig(starveUiThread = true)
                listOf(
                    FlickerTestParameter(defaultRun),
                    FlickerTestParameter(busyUiRun,
                        name = "${FlickerTestParameter.defaultName(busyUiRun)}_BUSY_UI_THREAD"
                    )
                )
            }
        }

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return getConfigurations()
        }
    }
}
