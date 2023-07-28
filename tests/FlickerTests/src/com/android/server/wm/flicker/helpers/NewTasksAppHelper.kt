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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.device.helpers.FIND_TIMEOUT
import android.tools.device.traces.parsers.WindowManagerStateHelper
import android.tools.device.traces.parsers.toFlickerComponent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions

class NewTasksAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.LaunchNewTask.LABEL,
    component: ComponentNameMatcher = ActivityOptions.LaunchNewTask.COMPONENT.toFlickerComponent()
) : StandardAppHelper(instr, launcherName, component) {
    fun openNewTask(device: UiDevice, wmHelper: WindowManagerStateHelper) {
        val button =
            device.wait(Until.findObject(By.res(packageName, "launch_new_task")), FIND_TIMEOUT)

        requireNotNull(button) {
            "Button not found, this usually happens when the device " +
                "was left in an unknown state (e.g. in split screen)"
        }
        button.click()
        wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()
    }
}
