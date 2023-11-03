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

package com.android.server.wm.flicker.launch.common

import android.tools.common.Rotation
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.rules.RemoveAllTasksButHomeRule

abstract class OpenAppFromIconTransition(flicker: LegacyFlickerTest) :
    OpenAppFromLauncherTransition(flicker) {
    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                if (flicker.scenario.isTablet) {
                    tapl.setExpectedRotation(flicker.scenario.startRotation.value)
                } else {
                    tapl.setExpectedRotation(Rotation.ROTATION_0.value)
                }
                RemoveAllTasksButHomeRule.removeAllTasksButHome()
            }
            transitions {
                tapl
                    .goHome()
                    .switchToAllApps()
                    .getAppIcon(testApp.appName)
                    .launch(testApp.packageName)
            }
            teardown { testApp.exit(wmHelper) }
        }
}
