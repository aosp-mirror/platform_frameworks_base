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
import android.support.test.launcherhelper.ILauncherStrategy
import android.support.test.launcherhelper.LauncherStrategyFactory
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.Assert

open class ImeAppHelper(
    instr: Instrumentation,
    launcherName: String = "ImeApp",
    launcherStrategy: ILauncherStrategy = LauncherStrategyFactory
            .getInstance(instr)
            .launcherStrategy
) : StandardAppHelper(instr, launcherName, launcherStrategy) {
    open fun openIME(device: UiDevice) {
        val editText = device.wait(
                Until.findObject(By.res(getPackage(), "plain_text_input")),
                FIND_TIMEOUT)
        Assert.assertNotNull("Text field not found, this usually happens when the device " +
                "was left in an unknown state (e.g. in split screen)", editText)
        editText.click()
        if (!WindowManagerStateHelper().waitImeWindowShown()) {
            Assert.fail("IME did not appear")
        }
    }

    open fun closeIME(device: UiDevice) {
        device.pressBack()
        // Using only the AccessibilityInfo it is not possible to identify if the IME is active
        if (!WindowManagerStateHelper().waitImeWindowGone()) {
            Assert.fail("IME did not close")
        }
    }
}