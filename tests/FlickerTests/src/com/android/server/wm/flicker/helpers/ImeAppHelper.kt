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
import android.content.ComponentName
import android.support.test.launcherhelper.ILauncherStrategy
import android.support.test.launcherhelper.LauncherStrategyFactory
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper

open class ImeAppHelper @JvmOverloads constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.IME_ACTIVITY_LAUNCHER_NAME,
    component: ComponentName = ActivityOptions.IME_ACTIVITY_COMPONENT_NAME,
    launcherStrategy: ILauncherStrategy = LauncherStrategyFactory
            .getInstance(instr)
            .launcherStrategy
) : StandardAppHelper(instr, launcherName, component, launcherStrategy) {
    /**
     * Opens the IME and wait for it to be displayed
     *
     * @param device UIDevice instance to interact with the device
     * @param wmHelper Helper used to wait for WindowManager states
     */
    @JvmOverloads
    open fun openIME(device: UiDevice, wmHelper: WindowManagerStateHelper? = null) {
        val editText = device.wait(
            Until.findObject(By.res(getPackage(), "plain_text_input")),
            FIND_TIMEOUT)

        require(editText != null) {
            "Text field not found, this usually happens when the device " +
                "was left in an unknown state (e.g. in split screen)"
        }
        editText.click()
        waitIMEShown(device, wmHelper)
    }

    protected fun waitIMEShown(
        device: UiDevice,
        wmHelper: WindowManagerStateHelper? = null
    ) {
        if (wmHelper == null) {
            device.waitForIdle()
        } else {
            wmHelper.waitImeShown()
            wmHelper.waitForAppTransitionIdle()
        }
    }

    /**
     * Opens the IME and wait for it to be gone
     *
     * @param device UIDevice instance to interact with the device
     * @param wmHelper Helper used to wait for WindowManager states
     */
    @JvmOverloads
    open fun closeIME(device: UiDevice, wmHelper: WindowManagerStateHelper? = null) {
        device.pressBack()
        // Using only the AccessibilityInfo it is not possible to identify if the IME is active
        if (wmHelper == null) {
            device.waitForIdle()
        } else {
            wmHelper.waitImeGone()
        }
    }
}