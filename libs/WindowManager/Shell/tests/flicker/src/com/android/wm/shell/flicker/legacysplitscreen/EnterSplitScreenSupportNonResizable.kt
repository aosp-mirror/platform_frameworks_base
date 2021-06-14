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

import android.content.ComponentName
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.dockedStackDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.helpers.MultiWindowHelper.Companion.resetMultiWindowConfig
import com.android.wm.shell.flicker.helpers.MultiWindowHelper.Companion.setSupportsNonResizableMultiWindow
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
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@Group2
class EnterSplitScreenSupportNonResizable(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {

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

    override val ignoredWindows: List<ComponentName>
        get() = listOf(LAUNCHER_COMPONENT,
                WindowManagerStateHelper.SPLASH_SCREEN_COMPONENT,
                WindowManagerStateHelper.SNAPSHOT_COMPONENT,
                nonResizeableApp.component,
                splitScreenApp.component)

    @Before
    override fun setup() {
        super.setup()
        setSupportsNonResizableMultiWindow(instrumentation, 1)
    }

    @After
    override fun teardown() {
        super.teardown()
        resetMultiWindowConfig(instrumentation)
    }

    @Presubmit
    @Test
    fun dockedStackDividerIsVisibleAtEnd() = testSpec.dockedStackDividerIsVisibleAtEnd()

    @Presubmit
    @Test
    fun appWindowIsVisible() {
        testSpec.assertWmEnd {
            isVisible(nonResizeableApp.component)
        }
    }

    @Postsubmit
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