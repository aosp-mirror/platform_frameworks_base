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

import android.platform.test.annotations.Postsubmit
import android.provider.Settings
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.dockedStackDividerIsVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test enter split screen from non-resizable activity. When the device supports
 * non-resizable in multi window, there should be a button to enter split screen for non-resizable
 * activity.
 *
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenSupportNonResizable`
 */
@Postsubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
class EnterSplitScreenSupportNonResizable(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {
    var prevSupportNonResizableInMultiWindow = 0

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            cleanSetup(this, configuration)
            setup {
                eachRun {
                    nonResizeableApp.launchViaIntent(wmHelper)
                }
            }
            transitions {
                device.launchSplitScreen(wmHelper)
            }
        }

    override val ignoredWindows: List<String>
        get() = listOf(LAUNCHER_PACKAGE_NAME,
                WindowManagerStateHelper.SPLASH_SCREEN_NAME,
                WindowManagerStateHelper.SNAPSHOT_WINDOW_NAME,
                nonResizeableApp.defaultWindowName,
                splitScreenApp.defaultWindowName)

    @Before
    fun setup() {
        prevSupportNonResizableInMultiWindow = Settings.Global.getInt(context.contentResolver,
                Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW)
        if (prevSupportNonResizableInMultiWindow != 1) {
            // Support non-resizable in multi window
            Settings.Global.putInt(context.contentResolver,
                    Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW, 1)
        }
    }

    @After
    fun teardown() {
        Settings.Global.putInt(context.contentResolver,
                Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW,
                prevSupportNonResizableInMultiWindow)
    }

    @FlakyTest(bugId = 178447631)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
            super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @Test
    fun dockedStackDividerIsVisible() = testSpec.dockedStackDividerIsVisible()

    @Test
    fun appWindowIsVisible() {
        testSpec.assertWmEnd {
            isVisible(nonResizeableApp.defaultWindowName)
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