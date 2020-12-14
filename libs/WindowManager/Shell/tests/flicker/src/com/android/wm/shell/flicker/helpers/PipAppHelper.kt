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
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import com.android.server.wm.flicker.helpers.closePipWindow
import com.android.server.wm.flicker.helpers.hasPipWindow
import com.android.wm.shell.flicker.TEST_APP_PIP_ACTIVITY_LABEL
import com.android.wm.shell.flicker.pip.tv.closeTvPipWindow
import com.android.wm.shell.flicker.pip.tv.isFocusedOrHasFocusedChild
import com.android.wm.shell.flicker.testapp.Components
import org.junit.Assert.fail

class PipAppHelper(instrumentation: Instrumentation) : BaseAppHelper(
        instrumentation,
        TEST_APP_PIP_ACTIVITY_LABEL,
        Components.PipActivity()
) {
    private val mediaSessionManager: MediaSessionManager
        get() = context.getSystemService(MediaSessionManager::class.java)
                ?: error("Could not get MediaSessionManager")

    private val mediaController: MediaController?
        get() = mediaSessionManager.getActiveSessions(null).firstOrNull {
            it.packageName == packageName
        }

    fun clickObject(resId: String) {
        val selector = By.res(packageName, resId)
        val obj = uiDevice.findObject(selector) ?: error("Could not find `$resId` object")

        if (!isTelevision) {
            obj.click()
        } else {
            focusOnObject(selector) || error("Could not focus on `$resId` object")
            uiDevice.pressDPadCenter()
        }
    }

    private fun focusOnObject(selector: BySelector): Boolean {
        // We expect all the focusable UI elements to be arranged in a way so that it is possible
        // to "cycle" over all them by clicking the D-Pad DOWN button, going back up to "the top"
        // from "the bottom".
        repeat(FOCUS_ATTEMPTS) {
            uiDevice.findObject(selector)?.apply { if (isFocusedOrHasFocusedChild) return true }
                    ?: error("The object we try to focus on is gone.")

            uiDevice.pressDPadDown()
            uiDevice.waitForIdle()
        }
        return false
    }

    fun clickEnterPipButton() {
        clickObject(ENTER_PIP_BUTTON_ID)

        // TODO(b/172321238): remove this check once hasPipWindow is fixed on TVs
        if (!isTelevision) {
            uiDevice.hasPipWindow()
        } else {
            // Simply wait for 3 seconds
            SystemClock.sleep(3_000)
        }
    }

    fun clickStartMediaSessionButton() {
        clickObject(MEDIA_SESSION_START_RADIO_BUTTON_ID)
    }

    fun checkWithCustomActionsCheckbox() = uiDevice
            .findObject(By.res(packageName, WITH_CUSTOM_ACTIONS_BUTTON_ID))
                ?.takeIf { it.isCheckable }
                ?.apply { if (!isChecked) clickObject(WITH_CUSTOM_ACTIONS_BUTTON_ID) }
                ?: error("'With custom actions' checkbox not found")

    fun pauseMedia() = mediaController?.transportControls?.pause()
            ?: error("No active media session found")

    fun stopMedia() = mediaController?.transportControls?.stop()
            ?: error("No active media session found")

    fun closePipWindow() {
        if (isTelevision) {
            uiDevice.closeTvPipWindow()
        } else {
            uiDevice.closePipWindow()
        }

        if (!waitUntilClosed()) {
            fail("Couldn't close Pip")
        }
    }

    companion object {
        private const val FOCUS_ATTEMPTS = 20
        private const val ENTER_PIP_BUTTON_ID = "enter_pip"
        private const val WITH_CUSTOM_ACTIONS_BUTTON_ID = "with_custom_actions"
        private const val MEDIA_SESSION_START_RADIO_BUTTON_ID = "media_session_start"
    }
}
