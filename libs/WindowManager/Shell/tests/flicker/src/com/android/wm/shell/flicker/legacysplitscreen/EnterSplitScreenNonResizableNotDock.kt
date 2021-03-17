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

import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.canSplitScreen
import com.android.server.wm.flicker.helpers.openQuickstep
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.dockedStackDividerIsInvisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.Assert
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open non-resizable activity will auto exit split screen mode
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenNonResizableNotDock`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FlakyTest(bugId = 173875043)
class EnterSplitScreenNonResizableNotDock(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            super.transition(this, configuration)
            teardown {
                eachRun {
                    nonResizeableApp.exit(wmHelper)
                }
            }
            transitions {
                nonResizeableApp.launchViaIntent(wmHelper)
                device.openQuickstep(wmHelper)
                if (device.canSplitScreen(wmHelper)) {
                    Assert.fail("Non-resizeable app should not enter split screen")
                }
            }
        }

    override val ignoredWindows: List<String>
        get() = listOf(LAUNCHER_PACKAGE_NAME,
            WindowManagerStateHelper.SPLASH_SCREEN_NAME,
            WindowManagerStateHelper.SNAPSHOT_WINDOW_NAME,
            nonResizeableApp.defaultWindowName,
            splitScreenApp.defaultWindowName)

    @Test
    fun dockedStackDividerIsInvisible() = testSpec.dockedStackDividerIsInvisible()

    @FlakyTest(bugId = 178447631)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @Test
    fun appWindowIsVisible() {
        testSpec.assertWmEnd {
            isInvisible(nonResizeableApp.defaultWindowName)
        }
    }

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