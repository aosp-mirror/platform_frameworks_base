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
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.wm.shell.flicker.dockedStackDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test enter split screen from a detached recent task
 *
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenFromDetachedRecentTask`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@Group4
class EnterSplitScreenFromDetachedRecentTask(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            cleanSetup(this, configuration)
            setup {
                eachRun {
                    splitScreenApp.launchViaIntent(wmHelper)
                    // Press back to remove the task, but it should still be shown in recent.
                    device.pressBack()
                }
            }
            transitions {
                device.launchSplitScreen(wmHelper)
            }
        }

    override val ignoredWindows: List<FlickerComponentName>
        get() = listOf(LAUNCHER_COMPONENT,
                FlickerComponentName.SPLASH_SCREEN,
                FlickerComponentName.SNAPSHOT,
                splitScreenApp.component)

    @Presubmit
    @Test
    fun dockedStackDividerIsVisibleAtEnd() = testSpec.dockedStackDividerIsVisibleAtEnd()

    @Presubmit
    @Test
    fun appWindowIsVisible() {
        testSpec.assertWmEnd {
            isVisible(splitScreenApp.component)
        }
    }

    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
            super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                    repetitions = SplitScreenHelper.TEST_REPETITIONS,
                    supportedRotations = listOf(Surface.ROTATION_0) // bugId = 179116910
            )
        }
    }
}