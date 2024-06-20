/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.tools.NavBar
import android.tools.Rotation
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.flicker.subject.region.RegionSubject
import android.tools.helpers.WindowUtils
import android.tools.traces.component.ComponentNameMatcher
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeShownOnAppStartHelper
import com.android.server.wm.flicker.testapp.ActivityOptions.Ime.Default.ACTION_TOGGLE_ORIENTATION
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window shown on the app with fixing portrait orientation.
 * To run this test: `atest FlickerTestsIme2:OpenImeWindowToFixedPortraitAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenImeWindowToFixedPortraitAppTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    private val testApp = ImeShownOnAppStartHelper(instrumentation, flicker.scenario.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            testApp.launchViaIntent(wmHelper)
            testApp.openIME(wmHelper)
            // Enable letterbox when the app calls setRequestedOrientation
            device.executeShellCommand("cmd window set-ignore-orientation-request true")
        }
        transitions {
            broadcastActionTrigger.doAction(ACTION_TOGGLE_ORIENTATION)
            // Ensure app relaunching transition finished and the IME was shown
            testApp.waitIMEShown(wmHelper)
        }
        teardown {
            testApp.exit()
            device.executeShellCommand("cmd window set-ignore-orientation-request false")
        }
    }

    @Presubmit
    @Test
    fun imeLayerVisibleStart() {
        flicker.assertLayersStart { this.isVisible(ComponentNameMatcher.IME) }
    }

    @Presubmit
    @Test
    fun imeLayerExistsEnd() {
        flicker.assertLayersEnd { this.isVisible(ComponentNameMatcher.IME) }
    }

    @Presubmit
    @Test
    fun imeLayerVisibleRegionKeepsTheSame() {
        var imeLayerVisibleRegionBeforeTransition: RegionSubject? = null
        flicker.assertLayersStart {
            imeLayerVisibleRegionBeforeTransition = this.visibleRegion(ComponentNameMatcher.IME)
        }
        flicker.assertLayersEnd {
            this.visibleRegion(ComponentNameMatcher.IME)
                .coversExactly(imeLayerVisibleRegionBeforeTransition!!.region)
        }
    }

    @Presubmit
    @Test
    fun appWindowWithLetterboxCoversExactlyOnScreen() {
        val displayBounds = WindowUtils.getDisplayBounds(flicker.scenario.startRotation)
        flicker.assertLayersEnd {
            this.visibleRegion(testApp.or(ComponentNameMatcher.LETTERBOX))
                .coversExactly(displayBounds)
        }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                supportedRotations =
                    listOf(
                        Rotation.ROTATION_90,
                    ),
                supportedNavigationModes = listOf(NavBar.MODE_3BUTTON, NavBar.MODE_GESTURAL)
            )
    }
}
