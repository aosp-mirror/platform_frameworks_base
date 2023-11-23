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

import android.app.Instrumentation
import android.tools.common.Rotation
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.traces.component.IComponentMatcher
import android.tools.device.helpers.FIND_TIMEOUT
import android.tools.device.helpers.IME_PACKAGE
import android.tools.device.traces.parsers.WindowManagerStateHelper
import android.tools.device.traces.parsers.toFlickerComponent
import android.view.WindowInsets.Type.ime
import android.view.WindowInsets.Type.navigationBars
import android.view.WindowInsets.Type.statusBars
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions
import java.util.regex.Pattern

class ImeShownOnAppStartHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    private val rotation: Rotation,
    private val imePackageName: String = IME_PACKAGE,
    launcherName: String = ActivityOptions.Ime.AutoFocusActivity.LABEL,
    component: ComponentNameMatcher =
        ActivityOptions.Ime.AutoFocusActivity.COMPONENT.toFlickerComponent()
) : ImeAppHelper(instr, launcherName, component) {
    override fun openIME(wmHelper: WindowManagerStateHelper) {
        // do nothing (the app is focused automatically)
        waitIMEShown(wmHelper)
    }

    override fun launchViaIntent(
        wmHelper: WindowManagerStateHelper,
        launchedAppComponentMatcherOverride: IComponentMatcher?,
        action: String?,
        stringExtras: Map<String, String>,
        waitConditionsBuilder: WindowManagerStateHelper.StateSyncBuilder
    ) {
        super.launchViaIntent(
            wmHelper,
            launchedAppComponentMatcherOverride,
            action,
            stringExtras,
            waitConditionsBuilder
        )
        waitIMEShown(wmHelper)
    }

    override fun open() {
        val expectedPackage =
            if (rotation.isRotated()) {
                imePackageName
            } else {
                packageName
            }
        open(expectedPackage)
    }

    fun dismissDialog(wmHelper: WindowManagerStateHelper) {
        val dialog = uiDevice.wait(Until.findObject(By.text("Dialog for test")), FIND_TIMEOUT)

        // Pressing back key to dismiss the dialog
        if (dialog != null) {
            uiDevice.pressBack()
            wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()
        }
    }

    fun getInsetsVisibleFromDialog(type: Int): Boolean {
        val insetsVisibilityTextView =
            uiDevice.wait(Until.findObject(By.res("android:id/text1")), FIND_TIMEOUT)
        if (insetsVisibilityTextView != null) {
            val visibility = insetsVisibilityTextView.text.toString()
            val matcher =
                when (type) {
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
                return matcher.group(1) == "VISIBLE"
            }
        }
        return false
    }
}
