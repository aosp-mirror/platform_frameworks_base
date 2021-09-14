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
import androidx.test.uiautomator.UiDevice
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper

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

    override fun open() {
        val expectedPackage = if (rotation.isRotated()) {
            imePackageName
        } else {
            getPackage()
        }
        launcherStrategy.launch(appName, expectedPackage)
    }
}
