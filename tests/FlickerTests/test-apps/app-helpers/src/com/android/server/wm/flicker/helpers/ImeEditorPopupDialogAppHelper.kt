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
import android.tools.helpers.FIND_TIMEOUT
import android.tools.traces.component.ComponentNameMatcher
import android.tools.traces.parsers.WindowManagerStateHelper
import android.tools.traces.parsers.toFlickerComponent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions

class ImeEditorPopupDialogAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.Ime.EditorPopupDialogActivity.LABEL,
    component: ComponentNameMatcher =
        ActivityOptions.Ime.EditorPopupDialogActivity.COMPONENT.toFlickerComponent()
) : ImeAppHelper(instr, launcherName, component) {
    override fun openIME(wmHelper: WindowManagerStateHelper) {
        val editText = uiDevice.wait(Until.findObject(By.text("focused editText")), FIND_TIMEOUT)

        requireNotNull(editText) {
            "Text field not found, this usually happens when the device " +
                "was left in an unknown state (e.g. in split screen)"
        }
        editText.click()
        waitIMEShown(wmHelper)
    }

    fun dismissDialog(wmHelper: WindowManagerStateHelper) {
        val dismissButton = uiDevice.wait(Until.findObject(By.text("Dismiss")), FIND_TIMEOUT)

        // Pressing back key to dismiss the dialog
        if (dismissButton != null) {
            dismissButton.click()
            wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()
        }
    }
}
