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
import android.support.test.launcherhelper.ILauncherStrategy
import android.support.test.launcherhelper.LauncherStrategyFactory
import android.view.Display
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.common.WindowManagerConditionsFactory
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper

class TwoActivitiesAppHelper @JvmOverloads constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.BUTTON_ACTIVITY_LAUNCHER_NAME,
    component: FlickerComponentName =
        ActivityOptions.BUTTON_ACTIVITY_COMPONENT_NAME.toFlickerComponent(),
    launcherStrategy: ILauncherStrategy = LauncherStrategyFactory
        .getInstance(instr)
        .launcherStrategy
) : StandardAppHelper(instr, launcherName, component, launcherStrategy) {

    private val secondActivityComponent =
        ActivityOptions.SIMPLE_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME.toFlickerComponent()

    fun openSecondActivity(device: UiDevice, wmHelper: WindowManagerStateHelper) {
        val launchActivityButton = By.res(getPackage(), LAUNCH_SECOND_ACTIVITY)
        val button = device.wait(Until.findObject(launchActivityButton), FIND_TIMEOUT)

        require(button != null) {
            "Button not found, this usually happens when the device " +
                    "was left in an unknown state (e.g. in split screen)"
        }
        button.click()

        device.wait(Until.gone(launchActivityButton), FIND_TIMEOUT)
        wmHelper.waitFor(
            WindowManagerStateHelper.isAppFullScreen(secondActivityComponent),
            WindowManagerConditionsFactory.isAppTransitionIdle(Display.DEFAULT_DISPLAY),
            WindowManagerConditionsFactory.hasLayersAnimating().negate()
        )
    }

    companion object {
        private const val LAUNCH_SECOND_ACTIVITY = "launch_second_activity"
    }
}