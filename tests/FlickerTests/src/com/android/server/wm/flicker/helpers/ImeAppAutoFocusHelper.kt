/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.view.WindowInsets.Type.ime
import android.view.WindowInsets.Type.navigationBars
import android.view.WindowInsets.Type.statusBars

import android.app.Instrumentation
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper

import java.util.regex.Pattern

class ImeAppAutoFocusHelper @JvmOverloads constructor(
    instr: Instrumentation,
    private val rotation: Int,
    private val imePackageName: String = IME_PACKAGE,
    launcherName: String = ActivityOptions.IME_ACTIVITY_AUTO_FOCUS_LAUNCHER_NAME,
    component: FlickerComponentName =
        ActivityOptions.IME_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME.toFlickerComponent()
) : ImeAppHelper(instr, launcherName, component) {
    override fun openIME(
        device: UiDevice,
        wmHelper: WindowManagerStateHelper?
    ) {
        // do nothing (the app is focused automatically)
        waitIMEShown(device, wmHelper)
    }

    override fun launchViaIntent(
        wmHelper: WindowManagerStateHelper,
        expectedWindowName: String,
        action: String?,
        stringExtras: Map<String, String>
    ) {
        super.launchViaIntent(wmHelper, expectedWindowName, action, stringExtras)
        waitIMEShown(uiDevice, wmHelper)
    }

    override fun open() {
        val expectedPackage = if (rotation.isRotated()) {
            imePackageName
        } else {
            getPackage()
        }
        launcherStrategy.launch(appName, expectedPackage)
    }

    fun startDialogThemedActivity(wmHelper: WindowManagerStateHelper) {
        val button = uiDevice.wait(Until.findObject(By.res(getPackage(),
                "start_dialog_themed_activity_btn")), FIND_TIMEOUT)

        require(button != null) {
            "Button not found, this usually happens when the device " +
                    "was left in an unknown state (e.g. Screen turned off)"
        }
        button.click()
        wmHelper.waitForAppTransitionIdle()
        wmHelper.waitForFullScreenApp(
                ActivityOptions.DIALOG_THEMED_ACTIVITY_COMPONENT_NAME.toFlickerComponent())
        mInstrumentation.waitForIdleSync()
    }
    fun dismissDialog(wmHelper: WindowManagerStateHelper) {
        val dialog = uiDevice.wait(
                Until.findObject(By.text("Dialog for test")), FIND_TIMEOUT)

        // Pressing back key to dismiss the dialog
        if (dialog != null) {
            uiDevice.pressBack()
            wmHelper.waitForAppTransitionIdle()
        }
    }
    fun getInsetsVisibleFromDialog(type: Int): Boolean {
        var insetsVisibilityTextView = uiDevice.wait(
                Until.findObject(By.res("android:id/text1")), FIND_TIMEOUT)
        if (insetsVisibilityTextView != null) {
            var visibility = insetsVisibilityTextView.text.toString()
            val matcher = when (type) {
                ime() -> {
                    Pattern.compile("IME\\: (VISIBLE|INVISIBLE)").matcher(visibility)
                }
                statusBars() -> {
                    Pattern.compile("StatusBar\\: (VISIBLE|INVISIBLE)").matcher(visibility)
                }
                navigationBars() -> {
                    Pattern.compile("NavBar\\: (VISIBLE|INVISIBLE)").matcher(visibility)
                }
                else -> null
            }
            if (matcher != null && matcher.find()) {
                return matcher.group(1).equals("VISIBLE")
            }
        }
        return false
    }
}
