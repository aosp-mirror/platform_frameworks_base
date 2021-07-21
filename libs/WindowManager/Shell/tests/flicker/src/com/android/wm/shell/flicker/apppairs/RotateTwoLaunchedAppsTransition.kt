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

package com.android.wm.shell.flicker.apppairs

import android.view.Surface
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.Test

abstract class RotateTwoLaunchedAppsTransition(
    testSpec: FlickerTestParameter
) : AppPairsTransition(testSpec) {
    override val nonResizeableApp: SplitScreenHelper?
        get() = null

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = {
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    this.setRotation(Surface.ROTATION_0)
                    primaryApp.launchViaIntent()
                    secondaryApp.launchViaIntent()
                    updateTasksId()
                }
            }
            teardown {
                eachRun {
                    executeShellCommand(composePairsCommand(
                        primaryTaskId, secondaryTaskId, pair = false))
                    primaryApp.exit(wmHelper)
                    secondaryApp.exit(wmHelper)
                }
            }
        }

    @FlakyTest
    @Test
    override fun navBarLayerIsAlwaysVisible() {
        super.navBarLayerIsAlwaysVisible()
    }

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() {
        super.navBarLayerRotatesAndScales()
    }
}