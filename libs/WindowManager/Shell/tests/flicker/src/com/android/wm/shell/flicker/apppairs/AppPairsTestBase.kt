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

package com.android.wm.shell.flicker.apppairs

import android.system.helpers.ActivityHelper
import android.util.Log
import com.android.compatibility.common.util.SystemUtil
import com.android.wm.shell.flicker.NonRotationTestBase
import com.android.wm.shell.flicker.TEST_APP_NONRESIZEABLE_LABEL
import com.android.wm.shell.flicker.TEST_APP_SPLITSCREEN_PRIMARY_LABEL
import com.android.wm.shell.flicker.TEST_APP_SPLITSCREEN_SECONDARY_LABEL
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import com.android.wm.shell.flicker.testapp.Components
import java.io.IOException

abstract class AppPairsTestBase(
    rotationName: String,
    rotation: Int
) : NonRotationTestBase(rotationName, rotation) {
    val activityHelper = ActivityHelper.getInstance()

    val appPairsHelper = AppPairsHelper(instrumentation,
            TEST_APP_SPLITSCREEN_PRIMARY_LABEL,
            Components.SplitScreenActivity())
    val primaryApp = SplitScreenHelper(instrumentation,
            TEST_APP_SPLITSCREEN_PRIMARY_LABEL,
            Components.SplitScreenActivity())
    val secondaryApp = SplitScreenHelper(instrumentation,
            TEST_APP_SPLITSCREEN_SECONDARY_LABEL,
            Components.SplitScreenSecondaryActivity())
    val nonResizeableApp = SplitScreenHelper(instrumentation,
        TEST_APP_NONRESIZEABLE_LABEL,
        Components.NonResizeableActivity())

    val primaryAppComponent = primaryApp.openAppIntent.component
    val secondaryAppComponent = secondaryApp.openAppIntent.component
    val nonResizeableAppComponent = nonResizeableApp.openAppIntent.component

    var primaryTaskId = ""
    var secondaryTaskId = ""
    var nonResizeableTaskId = ""

    fun composePairsCommand(
        primaryApp: String,
        secondaryApp: String,
        pair: Boolean
    ): String = buildString {
        // dumpsys activity service SystemUIService WMShell {pair|unpair} ${TASK_ID_1} ${TASK_ID_2}
        append("dumpsys activity service SystemUIService WMShell ")
        if (pair) {
            append("pair ")
        } else {
            append("unpair ")
        }
        append(primaryApp + " " + secondaryApp)
    }

    fun executeShellCommand(cmd: String) {
        try {
            SystemUtil.runShellCommand(instrumentation, cmd)
        } catch (e: IOException) {
            Log.d("AppPairsTest", "executeShellCommand error!" + e)
        }
    }

    fun getTaskIdForActivity(pkgName: String, activityName: String): Int {
        return activityHelper.getTaskIdForActivity(pkgName, activityName)
    }
}
