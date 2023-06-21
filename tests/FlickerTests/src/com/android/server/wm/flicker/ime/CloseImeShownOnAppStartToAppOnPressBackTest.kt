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

import android.platform.test.annotations.Presubmit
import android.tools.common.Rotation
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeShownOnAppStartHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window closing back to app window transitions.
 *
 * This test doesn't work on 90 degrees. According to the InputMethodService documentation:
 * ```
 *     Don't show if this is not explicitly requested by the user and the input method
 *     is fullscreen. That would be too disruptive.
 * ```
 *
 * More details on b/190352379
 *
 * To run this test: `atest FlickerTests:CloseImeAutoOpenWindowToAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class CloseImeShownOnAppStartToAppOnPressBackTest(flicker: LegacyFlickerTest) :
    BaseTest(flicker) {
    private val testApp = ImeShownOnAppStartHelper(instrumentation, flicker.scenario.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup { testApp.launchViaIntent(wmHelper) }
        teardown { testApp.exit(wmHelper) }
        transitions { testApp.closeIME(wmHelper) }
    }

    @Presubmit
    @Test
    fun imeAppWindowIsAlwaysVisible() {
        flicker.assertWm { this.isAppWindowOnTop(testApp) }
    }

    @Presubmit
    @Test
    fun imeLayerVisibleStart() {
        flicker.assertLayersStart { this.isVisible(ComponentNameMatcher.IME) }
    }

    @Presubmit
    @Test
    fun imeLayerInvisibleEnd() {
        flicker.assertLayersEnd { this.isInvisible(ComponentNameMatcher.IME) }
    }

    @Presubmit @Test fun imeLayerBecomesInvisible() = flicker.imeLayerBecomesInvisible()

    @Presubmit
    @Test
    fun imeAppLayerIsAlwaysVisible() {
        flicker.assertLayers { this.isVisible(testApp) }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                // b/190352379 (IME doesn't show on app launch in 90 degrees)
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
    }
}
