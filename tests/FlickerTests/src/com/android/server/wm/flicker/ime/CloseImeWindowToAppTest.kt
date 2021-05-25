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

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import org.junit.Assume
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window closing back to app window transitions.
 * To run this test: `atest FlickerTests:CloseImeWindowToAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 185400889)
@Group2
class CloseImeWindowToAppTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = ImeAppHelper(instrumentation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                test {
                    testApp.launchViaIntent()
                }
                eachRun {
                    testApp.openIME(device, wmHelper)
                }
            }
            teardown {
                test {
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                testApp.closeIME(device, wmHelper)
            }
        }
    }

    @FlakyTest
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @FlakyTest
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        testSpec.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry(listOf(IME_WINDOW_TITLE,
                WindowManagerStateHelper.SPLASH_SCREEN_NAME,
                WindowManagerStateHelper.SNAPSHOT_WINDOW_NAME))
        }
    }

    @Presubmit
    @Test
    fun imeAppWindowIsAlwaysVisible() = testSpec.imeAppWindowIsAlwaysVisible(testApp)

    @Presubmit
    @Test
    fun navBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @FlakyTest
    @Test
    fun noUncoveredRegions() = testSpec.noUncoveredRegions(testSpec.config.startRotation)

    @Presubmit
    @Test
    fun navBarLayerRotatesAndScales() {
        Assume.assumeFalse(testSpec.isRotated)
        testSpec.navBarLayerRotatesAndScales(testSpec.config.startRotation)
    }

    @FlakyTest
    @Test
    fun navBarLayerRotatesAndScales_Flaky() {
        Assume.assumeTrue(testSpec.isRotated)
        testSpec.navBarLayerRotatesAndScales(testSpec.config.startRotation)
    }

    @Presubmit
    @Test
    fun statusBarLayerRotatesScales() {
        Assume.assumeFalse(testSpec.isRotated)
        testSpec.statusBarLayerRotatesScales(testSpec.config.startRotation)
    }

    @FlakyTest
    @Test
    fun statusBarLayerRotatesScales_Flaky() {
        Assume.assumeTrue(testSpec.isRotated)
        testSpec.statusBarLayerRotatesScales(testSpec.config.startRotation)
    }

    @FlakyTest
    @Test
    fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        testSpec.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry()
        }
    }

    @Presubmit
    @Test
    fun imeLayerBecomesInvisible() = testSpec.imeLayerBecomesInvisible()

    @Presubmit
    @Test
    fun imeAppLayerIsAlwaysVisible() = testSpec.imeAppLayerIsAlwaysVisible(testApp)

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(repetitions = 5)
        }
    }
}