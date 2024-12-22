/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.tools.helpers.SYSTEMUI_PACKAGE
import android.tools.traces.component.ComponentNameMatcher
import android.tools.traces.parsers.WindowManagerStateHelper
import android.tools.traces.parsers.toFlickerComponent
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions
import java.util.regex.Pattern

class StartMediaProjectionAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.StartMediaProjectionActivity.LABEL,
    component: ComponentNameMatcher =
        ActivityOptions.StartMediaProjectionActivity.COMPONENT.toFlickerComponent()
) : StandardAppHelper(instr, launcherName, component) {
    private val packageManager = instr.context.packageManager

    fun startEntireScreenMediaProjection(wmHelper: WindowManagerStateHelper) {
        clickStartMediaProjectionButton()
        chooseEntireScreenOption()
        startScreenSharing()
        wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()
    }

    fun startSingleAppMediaProjection(
        wmHelper: WindowManagerStateHelper,
        targetApp: StandardAppHelper
    ) {
        clickStartMediaProjectionButton()
        chooseSingleAppOption()
        startScreenSharing()
        selectTargetApp(targetApp.appName)
        wmHelper
            .StateSyncBuilder()
            .withAppTransitionIdle()
            .withWindowSurfaceAppeared(targetApp)
            .waitForAndVerify()
    }

    private fun clickStartMediaProjectionButton() {
        findObject(By.res(packageName, START_MEDIA_PROJECTION_BUTTON_ID)).also { it.click() }
    }

    private fun chooseEntireScreenOption() {
        findObject(By.res(SCREEN_SHARE_OPTIONS_PATTERN)).also { it.click() }

        val entireScreenString = getSysUiResourceString(ENTIRE_SCREEN_STRING_RES_NAME)
        findObject(By.text(entireScreenString)).also { it.click() }
    }

    private fun selectTargetApp(targetAppName: String) {
        // Scroll to to find target app to launch then click app icon it to start capture
        val scrollable = UiScrollable(UiSelector().scrollable(true))
        try {
            scrollable.scrollForward()
            if (!scrollable.scrollIntoView(UiSelector().text(targetAppName))) {
                Log.e(TAG, "Didn't find target app when scrolling")
                return
            }
        } catch (e: UiObjectNotFoundException) {
            Log.d(TAG, "There was no scrolling (UI may not be scrollable")
        }

        findObject(By.text(targetAppName)).also { it.click() }
    }

    private fun chooseSingleAppOption() {
        findObject(By.res(SCREEN_SHARE_OPTIONS_PATTERN)).also { it.click() }

        val singleAppString = getSysUiResourceString(SINGLE_APP_STRING_RES_NAME)
        findObject(By.text(singleAppString)).also { it.click() }
    }

    private fun startScreenSharing() {
        findObject(By.res(ACCEPT_RESOURCE_ID)).also { it.click() }
    }

    private fun findObject(selector: BySelector): UiObject2 =
        uiDevice.wait(Until.findObject(selector), TIMEOUT) ?: error("Can't find object $selector")

    private fun getSysUiResourceString(resName: String): String =
        with(packageManager.getResourcesForApplication(SYSTEMUI_PACKAGE)) {
            getString(getIdentifier(resName, "string", SYSTEMUI_PACKAGE))
        }

    companion object {
        const val TAG: String = "StartMediaProjectionAppHelper"
        const val TIMEOUT: Long = 5000L
        const val ACCEPT_RESOURCE_ID: String = "android:id/button1"
        const val START_MEDIA_PROJECTION_BUTTON_ID: String = "button_start_mp"
        val SCREEN_SHARE_OPTIONS_PATTERN: Pattern =
            Pattern.compile("$SYSTEMUI_PACKAGE:id/screen_share_mode_(options|spinner)")
        const val ENTIRE_SCREEN_STRING_RES_NAME: String =
            "screen_share_permission_dialog_option_entire_screen"
        const val SINGLE_APP_STRING_RES_NAME: String =
            "screen_share_permission_dialog_option_single_app"
    }
}
