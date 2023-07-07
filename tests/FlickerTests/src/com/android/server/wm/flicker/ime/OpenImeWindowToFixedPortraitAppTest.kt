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
import android.tools.common.NavBar
import android.tools.common.Rotation
import android.tools.common.flicker.subject.region.RegionSubject
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import android.tools.device.helpers.WindowUtils
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeShownOnAppStartHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window shown on the app with fixing portrait orientation. To run this test: `atest
 * FlickerTests:OpenImeWindowToFixedPortraitAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenImeWindowToFixedPortraitAppTest(flicker: FlickerTest) : BaseTest(flicker) {
    private val testApp = ImeShownOnAppStartHelper(instrumentation, flicker.scenario.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            testApp.launchViaIntent(wmHelper)
            testApp.openIME(wmHelper)
            // Enable letterbox when the app calls setRequestedOrientation
            device.executeShellCommand("cmd window set-ignore-orientation-request true")
        }
        transitions { testApp.toggleFixPortraitOrientation(wmHelper) }
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
         * See [FlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations =
                    listOf(
                        Rotation.ROTATION_90,
                    ),
                supportedNavigationModes = listOf(NavBar.MODE_3BUTTON, NavBar.MODE_GESTURAL)
            )
        }
    }
}
