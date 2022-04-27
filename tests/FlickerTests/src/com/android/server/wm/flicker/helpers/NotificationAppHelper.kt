/*
 * Copyright (C) 2022 The Android Open Source Project
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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper

class NotificationAppHelper @JvmOverloads constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.NOTIFICATION_ACTIVITY_LAUNCHER_NAME,
    component: FlickerComponentName =
            ActivityOptions.NOTIFICATION_ACTIVITY_COMPONENT_NAME.toFlickerComponent(),
    launcherStrategy: ILauncherStrategy = LauncherStrategyFactory
            .getInstance(instr)
            .launcherStrategy
) : StandardAppHelper(instr, launcherName, component, launcherStrategy) {
    fun postNotification(device: UiDevice, wmHelper: WindowManagerStateHelper) {
        val button = device.wait(
                Until.findObject(By.res(getPackage(), "post_notification")),
                FIND_TIMEOUT)

        require(button != null) {
            "Post notification button not found, this usually happens when the device " +
                    "was left in an unknown state (e.g. in split screen)"
        }
        button.click()

        device.wait(Until.findObject(By.text("Flicker Test Notification")), FIND_TIMEOUT)
                ?: error("Flicker Notification not found")
    }
}