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

package com.android.server.wm.flicker.service.notification.scenarios

import android.app.Instrumentation
import android.tools.device.traces.parsers.WindowManagerStateHelper
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.launcher3.tapl.LauncherInstrumentation

object NotificationUtils {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tapl = LauncherInstrumentation()
    private val wmHelper = WindowManagerStateHelper(instrumentation)
    private val device = UiDevice.getInstance(instrumentation)

    fun openNotification(openingNotificationsFromLockScreen: Boolean) {
        var startY = 10
        var endY = 3 * device.displayHeight / 4
        var steps = 25
        if (openingNotificationsFromLockScreen) {
            val wm: WindowManager =
                instrumentation.context.getSystemService(WindowManager::class.java)
                    ?: error("Unable to connect to WindowManager service")
            val metricInsets = wm.currentWindowMetrics.windowInsets
            val insets =
                metricInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                )

            startY = insets.top + 100
            endY = device.displayHeight / 2
            steps = 4
        }

        // Swipe down to show the notification shade
        val x = device.displayWidth / 2
        device.swipe(x, startY, x, endY, steps)
        device.waitForIdle(2000)
        instrumentation.uiAutomation.syncInputTransactions()

        // Launch the activity by clicking the notification
        val notification =
            device.wait(Until.findObject(By.text("Flicker Test Notification")), 2000L)
        notification?.click() ?: error("Notification not found")
        instrumentation.uiAutomation.syncInputTransactions()
    }
}
