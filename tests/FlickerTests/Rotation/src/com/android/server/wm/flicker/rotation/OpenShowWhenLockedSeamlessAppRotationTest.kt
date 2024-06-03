/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.platform.test.annotations.Presubmit
import android.tools.NavBar
import android.tools.Rotation
import android.tools.flicker.assertions.FlickerTest
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.flicker.rules.ChangeDisplayOrientationRule
import android.tools.traces.component.ComponentNameMatcher
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.SeamlessRotationAppHelper
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test opening an app over lockscreen with rotation change using seamless rotations.
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenShowWhenLockedSeamlessAppRotationTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    val testApp = SeamlessRotationAppHelper(instrumentation)

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                device.sleep()
                wmHelper.StateSyncBuilder().withoutTopVisibleAppWindows().waitForAndVerify()
                device.wakeUp()
                val originalRotation = device.displayRotation
                ChangeDisplayOrientationRule.setRotation(Rotation.ROTATION_90)
                Assume.assumeTrue("Assume that lockscreen uses fixed orientation",
                        originalRotation == device.displayRotation)
            }
            transitions {
                // The activity is show-when-locked, so the requested orientation will be changed
                // from NOSENSOR(keyguard) to UNSPECIFIED(activity). Then the fixed-user-rotation
                // (by setRotation) will take effect to rotate the display.
                testApp.launchViaIntent(wmHelper)
            }
            teardown { testApp.exit(wmHelper) }
        }

    @Presubmit
    @Test
    fun notContainsRotationAnimation() {
        flicker.assertLayers {
            // Verifies that com.android.wm.shell.transition.ScreenRotationAnimation is not used.
            notContains(ComponentNameMatcher("", "Animation leash of screenshot rotation"))
        }
    }

    // Ignore the assertions which are included in SeamlessAppRotationTest.
    @Test
    @Ignore("Uninterested")
    override fun statusBarLayerPositionAtStartAndEnd() {}

    @Test
    @Ignore("Uninterested")
    override fun statusBarLayerIsVisibleAtStartAndEnd() {}

    @Test
    @Ignore("Uninterested")
    override fun statusBarWindowIsAlwaysVisible() {}

    @Test
    @Ignore("Uninterested")
    override fun navBarLayerPositionAtStartAndEnd() {}

    @Test
    @Ignore("Uninterested")
    override fun navBarLayerIsVisibleAtStartAndEnd() {}

    @Test
    @Ignore("Uninterested")
    override fun navBarWindowIsVisibleAtStartAndEnd() {}

    @Test
    @Ignore("Uninterested")
    override fun navBarWindowIsAlwaysVisible() {}

    @Test
    @Ignore("Uninterested")
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {}

    @Test
    @Ignore("Uninterested")
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {}

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            // The rotation will be controlled by the setup of test.
            return LegacyFlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_0),
                supportedNavigationModes = listOf(NavBar.MODE_GESTURAL)
            )
        }
    }
}
