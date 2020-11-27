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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.FEATURE_LEANBACK
import android.content.pm.PackageManager.FEATURE_LEANBACK_ONLY
import android.support.test.launcherhelper.LauncherStrategyFactory
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.wm.shell.flicker.testapp.Components

abstract class BaseAppHelper(
    instrumentation: Instrumentation,
    launcherName: String,
    private val componentsInfo: Components.ComponentsInfo
) : StandardAppHelper(
        instrumentation,
        Components.PACKAGE_NAME,
        launcherName,
        LauncherStrategyFactory.getInstance(instrumentation).launcherStrategy
) {
    protected val uiDevice: UiDevice = UiDevice.getInstance(instrumentation)

    protected val context: Context
        get() = mInstrumentation.context

    private val appSelector = By.pkg(packageName).depth(0)

    protected val isTelevision: Boolean
        get() = context.packageManager.run {
            hasSystemFeature(FEATURE_LEANBACK) || hasSystemFeature(FEATURE_LEANBACK_ONLY)
        }

    val defaultWindowName: String
        get() = componentsInfo.activityName

    val label: String
        get() = context.packageManager.run {
            getApplicationLabel(getApplicationInfo(packageName, 0)).toString()
        }

    val ui: UiObject2?
        get() = uiDevice.findObject(appSelector)

    fun launchViaIntent(stringExtras: Map<String, String> = mapOf()) {
        val intent = openAppIntent
        stringExtras.forEach() {
            intent.putExtra(it.key, it.value)
        }
        context.startActivity(intent)

        uiDevice.wait(Until.hasObject(appSelector), APP_LAUNCH_WAIT_TIME_MS)
    }

    fun waitUntilClosed(): Boolean {
        return uiDevice.wait(Until.gone(appSelector), APP_CLOSE_WAIT_TIME_MS)
    }

    override fun getOpenAppIntent(): Intent {
        val intent = Intent()
        intent.component = componentsInfo.componentName
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    companion object {
        private const val APP_LAUNCH_WAIT_TIME_MS = 10_000L
        private const val APP_CLOSE_WAIT_TIME_MS = 3_000L
    }
}
