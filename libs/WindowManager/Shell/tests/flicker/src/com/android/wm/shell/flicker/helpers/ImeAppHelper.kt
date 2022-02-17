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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.helpers.FIND_TIMEOUT
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.testapp.Components

open class ImeAppHelper(instrumentation: Instrumentation) : BaseAppHelper(
    instrumentation,
    Components.ImeActivity.LABEL,
    Components.ImeActivity.COMPONENT.toFlickerComponent()
) {
    /**
     * Opens the IME and wait for it to be displayed
     *
     * @param wmHelper Helper used to wait for WindowManager states
     */
    @JvmOverloads
    open fun openIME(wmHelper: WindowManagerStateHelper? = null) {
        if (!isTelevision) {
            val editText = uiDevice.wait(
                Until.findObject(By.res(getPackage(), "plain_text_input")),
                FIND_TIMEOUT)

            require(editText != null) {
                "Text field not found, this usually happens when the device " +
                    "was left in an unknown state (e.g. in split screen)"
            }
            editText.click()
            waitAndAssertIMEShown(uiDevice, wmHelper)
        } else {
            // If we do the same thing as above - editText.click() - on TV, that's going to force TV
            // into the touch mode. We really don't want that.
            launchViaIntent(action = Components.ImeActivity.ACTION_OPEN_IME)
        }
    }

    protected fun waitAndAssertIMEShown(
        device: UiDevice,
        wmHelper: WindowManagerStateHelper? = null
    ) {
        if (wmHelper == null) {
            device.waitForIdle()
        } else {
            require(wmHelper.waitImeShown()) { "IME did not appear" }
        }
    }

    /**
     * Opens the IME and wait for it to be gone
     *
     * @param wmHelper Helper used to wait for WindowManager states
     */
    @JvmOverloads
    open fun closeIME(wmHelper: WindowManagerStateHelper? = null) {
        if (!isTelevision) {
            uiDevice.pressBack()
            // Using only the AccessibilityInfo it is not possible to identify if the IME is active
            if (wmHelper == null) {
                uiDevice.waitForIdle()
            } else {
                require(wmHelper.waitImeGone()) { "IME did did not close" }
            }
        } else {
            // While pressing the back button should close the IME on TV as well, it may also lead
            // to the app closing. So let's instead just ask the app to close the IME.
            launchViaIntent(action = Components.ImeActivity.ACTION_CLOSE_IME)
        }
    }
}