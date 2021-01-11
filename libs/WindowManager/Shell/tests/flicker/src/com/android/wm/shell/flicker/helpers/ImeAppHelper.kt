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
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.helpers.FIND_TIMEOUT
import com.android.wm.shell.flicker.TEST_APP_IME_ACTIVITY_ACTION_CLOSE_IME
import com.android.wm.shell.flicker.TEST_APP_IME_ACTIVITY_ACTION_OPEN_IME
import com.android.wm.shell.flicker.TEST_APP_IME_ACTIVITY_LABEL
import com.android.wm.shell.flicker.testapp.Components
import org.junit.Assert

open class ImeAppHelper(
    instrumentation: Instrumentation
) : BaseAppHelper(
        instrumentation,
        TEST_APP_IME_ACTIVITY_LABEL,
        Components.ImeActivity()
) {
    fun openIME() {
        if (!isTelevision) {
            val editText = uiDevice.wait(
                    Until.findObject(By.res(getPackage(), "plain_text_input")),
                    FIND_TIMEOUT)
            Assert.assertNotNull("Text field not found, this usually happens when the device " +
                    "was left in an unknown state (e.g. in split screen)", editText)
            editText.click()
        } else {
            // If we do the same thing as above - editText.click() - on TV, that's going to force TV
            // into the touch mode. We really don't want that.
            launchViaIntent(action = TEST_APP_IME_ACTIVITY_ACTION_OPEN_IME)
        }
    }

    fun closeIME() {
        if (!isTelevision) {
            uiDevice.pressBack()
        } else {
            // While pressing the back button should close the IME on TV as well, it may also lead
            // to the app closing. So let's instead just ask the app to close the IME.
            launchViaIntent(action = TEST_APP_IME_ACTIVITY_ACTION_CLOSE_IME)
        }
    }
}