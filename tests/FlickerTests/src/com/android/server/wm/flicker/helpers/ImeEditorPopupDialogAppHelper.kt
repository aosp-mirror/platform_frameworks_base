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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper

class ImeEditorPopupDialogAppHelper @JvmOverloads constructor(
    instr: Instrumentation,
    private val rotation: Int,
    private val imePackageName: String = IME_PACKAGE,
    launcherName: String = ActivityOptions.EDITOR_POPUP_DIALOG_ACTIVITY_LAUNCHER_NAME,
    component: FlickerComponentName =
            ActivityOptions.EDITOR_POPUP_DIALOG_ACTIVITY_COMPONENT_NAME.toFlickerComponent()
) : ImeAppHelper(instr, launcherName, component) {
    override fun openIME(
        device: UiDevice,
        wmHelper: WindowManagerStateHelper?
    ) {
        val editText = device.wait(Until.findObject(By.text("focused editText")), FIND_TIMEOUT)

        require(editText != null) {
            "Text field not found, this usually happens when the device " +
                    "was left in an unknown state (e.g. in split screen)"
        }
        editText.click()
        waitIMEShown(device, wmHelper)
    }

    fun dismissDialog(wmHelper: WindowManagerStateHelper) {
        val dismissButton = uiDevice.wait(
                Until.findObject(By.text("Dismiss")), FIND_TIMEOUT)

        // Pressing back key to dismiss the dialog
        if (dismissButton != null) {
            dismissButton.click()
            wmHelper.waitForAppTransitionIdle()
        }
    }
}