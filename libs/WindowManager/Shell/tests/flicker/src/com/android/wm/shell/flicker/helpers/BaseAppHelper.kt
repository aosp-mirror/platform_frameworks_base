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

package com.android.wm.shell.flicker.helpers

import android.app.Instrumentation
import android.content.ComponentName
import android.content.pm.PackageManager.FEATURE_LEANBACK
import android.content.pm.PackageManager.FEATURE_LEANBACK_ONLY
import android.support.test.launcherhelper.LauncherStrategyFactory
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.android.compatibility.common.util.SystemUtil
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.traces.parser.toWindowName
import java.io.IOException

abstract class BaseAppHelper(
    instrumentation: Instrumentation,
    launcherName: String,
    component: ComponentName
) : StandardAppHelper(
    instrumentation,
    launcherName,
    component,
    LauncherStrategyFactory.getInstance(instrumentation).launcherStrategy
) {
    private val appSelector = By.pkg(component.packageName).depth(0)

    protected val isTelevision: Boolean
        get() = context.packageManager.run {
            hasSystemFeature(FEATURE_LEANBACK) || hasSystemFeature(FEATURE_LEANBACK_ONLY)
        }

    val defaultWindowName: String
        get() = component.toWindowName()

    val ui: UiObject2?
        get() = uiDevice.findObject(appSelector)

    fun waitUntilClosed(): Boolean {
        return uiDevice.wait(Until.gone(appSelector), APP_CLOSE_WAIT_TIME_MS)
    }

    companion object {
        private const val APP_CLOSE_WAIT_TIME_MS = 3_000L

        fun executeShellCommand(instrumentation: Instrumentation, cmd: String) {
            try {
                SystemUtil.runShellCommand(instrumentation, cmd)
            } catch (e: IOException) {
                Log.e("BaseAppHelper", "executeShellCommand error! $e")
            }
        }
    }
}
