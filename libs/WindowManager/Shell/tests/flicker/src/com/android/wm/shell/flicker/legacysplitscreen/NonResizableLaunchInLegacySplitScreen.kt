/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.flicker.legacysplitscreen

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.appWindowBecomesInVisible
import com.android.server.wm.flicker.appWindowBecomesVisible
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.layerBecomesInvisible
import com.android.server.wm.flicker.layerBecomesVisible
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.DOCKED_STACK_DIVIDER
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launch non resizable activity in split screen mode will trigger exit split screen mode
 * (Non resizable activity launch via intent)
 * To run this test: `atest WMShellFlickerTests:NonResizableLaunchInLegacySplitScreen`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NonResizableLaunchInLegacySplitScreen(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            cleanSetup(this, configuration)
            setup {
                eachRun {
                    splitScreenApp.launchViaIntent(wmHelper)
                    device.launchSplitScreen(wmHelper)
                }
            }
            transitions {
                nonResizeableApp.launchViaIntent(wmHelper)
                wmHelper.waitForAppTransitionIdle()
            }
        }

    override val ignoredWindows: List<String>
        get() = listOf(DOCKED_STACK_DIVIDER, LAUNCHER_PACKAGE_NAME, LETTERBOX_NAME,
            nonResizeableApp.defaultWindowName, splitScreenApp.defaultWindowName,
            WindowManagerStateHelper.SPLASH_SCREEN_NAME,
            WindowManagerStateHelper.SNAPSHOT_WINDOW_NAME)

    @Presubmit
    @Test
    fun layerBecomesVisible() = testSpec.layerBecomesVisible(nonResizeableApp.defaultWindowName)

    @Presubmit
    @Test
    fun layerBecomesInvisible() = testSpec.layerBecomesInvisible(splitScreenApp.defaultWindowName)

    @FlakyTest(bugId = 178447631)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @Presubmit
    @Test
    fun appWindowBecomesVisible() =
        testSpec.appWindowBecomesVisible(nonResizeableApp.defaultWindowName)

    @Presubmit
    @Test
    fun appWindowBecomesInVisible() =
        testSpec.appWindowBecomesInVisible(splitScreenApp.defaultWindowName)

    @FlakyTest(bugId = 178447631)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_0)) // b/178685668
        }
    }
}
