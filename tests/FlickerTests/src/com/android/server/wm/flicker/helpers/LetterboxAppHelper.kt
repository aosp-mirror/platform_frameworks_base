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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.device.helpers.FIND_TIMEOUT
import android.tools.device.helpers.SYSTEMUI_PACKAGE
import android.tools.device.traces.parsers.WindowManagerStateHelper
import android.tools.device.traces.parsers.toFlickerComponent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions

class LetterboxAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.NonResizeablePortraitActivity.LABEL,
    component: ComponentNameMatcher =
        ActivityOptions.NonResizeablePortraitActivity.COMPONENT.toFlickerComponent()
) : StandardAppHelper(instr, launcherName, component) {

    fun clickRestart(wmHelper: WindowManagerStateHelper) {
        val restartButton =
            uiDevice.wait(
                Until.findObject(By.res(SYSTEMUI_PACKAGE, "size_compat_restart_button")),
                FIND_TIMEOUT
            )
        restartButton?.run { restartButton.click() } ?: error("Restart button not found")

        // size compat mode restart confirmation dialog button
        val restartDialogButton =
            uiDevice.wait(
                Until.findObject(
                    By.res(SYSTEMUI_PACKAGE, "letterbox_restart_dialog_restart_button")
                ),
                FIND_TIMEOUT
            )
        restartDialogButton?.run { restartDialogButton.click() }
            ?: error("Restart dialog button not found")
        wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()
    }
}
