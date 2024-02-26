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
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.helpers.FIND_TIMEOUT
import android.tools.traces.component.ComponentNameMatcher
import android.tools.traces.parsers.WindowManagerStateHelper
import android.tools.traces.parsers.toFlickerComponent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions

class TwoActivitiesAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.LaunchNewActivity.LABEL,
    component: ComponentNameMatcher =
        ActivityOptions.LaunchNewActivity.COMPONENT.toFlickerComponent()
) : StandardAppHelper(instr, launcherName, component) {

    private val secondActivityComponent =
        ActivityOptions.SimpleActivity.COMPONENT.toFlickerComponent()

    fun openSecondActivity(device: UiDevice, wmHelper: WindowManagerStateHelper) {
        val launchActivityButton = By.res(packageName, LAUNCH_SECOND_ACTIVITY)
        val button = device.wait(Until.findObject(launchActivityButton), FIND_TIMEOUT)

        requireNotNull(button) {
            "Button not found, this usually happens when the device " +
                "was left in an unknown state (e.g. in split screen)"
        }
        button.click()

        device.wait(Until.gone(launchActivityButton), FIND_TIMEOUT)
        wmHelper.StateSyncBuilder().withFullScreenApp(secondActivityComponent).waitForAndVerify()
    }

    companion object {
        private const val LAUNCH_SECOND_ACTIVITY = "launch_second_activity"
    }
}
