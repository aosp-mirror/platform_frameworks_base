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

import android.platform.test.annotations.PlatinumTest
import android.platform.test.annotations.Presubmit
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.traces.component.ComponentNameMatcher
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.navBarLayerPositionAtStartAndEnd
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window closing back to app window transitions.
 * To run this test: `atest FlickerTestsIme1:CloseImeToAppOnPressBackTest`
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CloseImeToAppOnPressBackTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    private val testApp = ImeAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            testApp.launchViaIntent(wmHelper)
            testApp.openIME(wmHelper)
        }
        teardown { testApp.exit(wmHelper) }
        transitions { testApp.closeIME(wmHelper) }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        flicker.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry(
                listOf(
                    ComponentNameMatcher.IME,
                    ComponentNameMatcher.SPLASH_SCREEN,
                    ComponentNameMatcher.SNAPSHOT
                )
            )
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarLayerPositionAtStartAndEnd() {
        Assume.assumeFalse(usesTaskbar)
        Assume.assumeFalse(flicker.scenario.isLandscapeOrSeascapeAtStart)
        flicker.navBarLayerPositionAtStartAndEnd()
    }

    @Presubmit
    @Test
    fun navBarLayerPositionAtStartAndEndLandscapeOrSeascapeAtStart() {
        Assume.assumeFalse(usesTaskbar)
        Assume.assumeTrue(flicker.scenario.isLandscapeOrSeascapeAtStart)
        flicker.navBarLayerPositionAtStartAndEnd()
    }

    @Presubmit @Test fun imeLayerBecomesInvisible() = flicker.imeLayerBecomesInvisible()

    @Presubmit
    @Test
    fun imeAppLayerIsAlwaysVisible() {
        flicker.assertLayers { this.isVisible(testApp) }
    }

    @Presubmit
    @Test
    fun imeAppWindowIsAlwaysVisible() {
        flicker.assertWm { this.isAppWindowOnTop(testApp) }
    }

    @Presubmit
    @Test
    @PlatinumTest(focusArea = "ime")
    override fun cujCompleted() {
        super.cujCompleted()
        imeLayerBecomesInvisible()
        imeAppLayerIsAlwaysVisible()
        imeAppWindowIsAlwaysVisible()
        runAndIgnoreAssumptionViolation { navBarLayerPositionAtStartAndEnd() }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
