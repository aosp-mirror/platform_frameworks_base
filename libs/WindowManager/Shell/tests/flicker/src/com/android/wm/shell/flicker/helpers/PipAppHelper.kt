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
import android.view.KeyEvent.KEYCODE_WINDOW
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.helpers.closePipWindow
import com.android.server.wm.flicker.helpers.hasPipWindow
import com.android.wm.shell.flicker.SYSTEM_UI_PACKAGE_NAME
import com.android.wm.shell.flicker.TEST_APP_PIP_ACTIVITY_LABEL
import com.android.wm.shell.flicker.testapp.Components
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail

class PipAppHelper(
    instrumentation: Instrumentation
) : BaseAppHelper(
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

    fun clickButton(resourceId: String) =
            uiDevice.findObject(By.res(packageName, resourceId))?.click()
                ?: fail("$resourceId button is not found")

    fun clickEnterPipButton() {
        clickButton("enter_pip")

        // TODO(b/172321238): remove this check once hasPipWindow is fixed on TVs
        if (!isTelevision) {
            uiDevice.hasPipWindow()
        } else {
            // Simply wait for 3 seconds
            SystemClock.sleep(3_000)
        }
    }

    fun clickStartMediaSessionButton() {
        val startButton = uiDevice.findObject(By.res(packageName, "media_session_start"))
        assertNotNull("Start button not found, this usually happens when the device " +
                "was left in an unknown state (e.g. in split screen)", startButton)
        startButton.click()
    }

    fun checkWithCustomActionsCheckbox() = uiDevice
            .findObject(By.res(packageName, "with_custom_actions"))
            ?.takeIf { it.isCheckable }
            ?.apply { if (!isChecked) click() }
            ?: error("'With custom actions' checkbox not found")

    fun pauseMedia() = mediaController?.transportControls?.pause()
            ?: error("No active media session found")

    fun stopMedia() = mediaController?.transportControls?.stop()
            ?: error("No active media session found")

    fun closePipWindow() {
        // TODO(b/172321238): remove this check once and simply call closePipWindow once the TV
        //  logic is integrated there.
        if (!isTelevision) {
            uiDevice.closePipWindow()
        } else {
            // Bring up Pip menu
            uiDevice.pressKeyCode(KEYCODE_WINDOW)

            // Wait for the menu to come up and render the close button
            val closeButton = uiDevice.wait(
                    Until.findObject(By.res(SYSTEM_UI_PACKAGE_NAME, "close_button")), 3_000)
            assertNotNull("Pip menu close button is not found", closeButton)
            closeButton.click()

            waitUntilClosed()
        }
    }
}
