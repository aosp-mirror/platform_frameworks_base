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
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.service.PlatformConsts
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
 * More details on b/190352379
 *
 * To run this test: `atest FlickerTests:CloseImeAutoOpenWindowToHomeTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class CloseImeAutoOpenWindowToHomeTest(flicker: FlickerTest) : BaseTest(flicker) {
    private val testApp = ImeAppAutoFocusHelper(instrumentation, flicker.scenario.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            testApp.launchViaIntent(wmHelper)
        }
        teardown { testApp.exit(wmHelper) }
        transitions {
            tapl.goHome()
            wmHelper.StateSyncBuilder().withHomeActivityVisible().withImeGone().waitForAndVerify()
        }
    }

    @Presubmit
    @Test
    fun imeAppWindowBecomesInvisible() {
        flicker.assertWm { this.isAppWindowOnTop(testApp).then().isAppWindowNotOnTop(testApp) }
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
    fun imeAppLayerBecomesInvisible() {
        flicker.assertLayers { this.isVisible(testApp).then().isInvisible(testApp) }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                // b/190352379 (IME doesn't show on app launch in 90 degrees)
                supportedRotations = listOf(PlatformConsts.Rotation.ROTATION_0)
            )
        }
    }
}
