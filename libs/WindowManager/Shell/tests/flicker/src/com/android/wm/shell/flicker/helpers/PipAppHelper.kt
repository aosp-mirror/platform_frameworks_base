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
import com.android.server.wm.flicker.helpers.SYSTEMUI_PACKAGE
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.pip.tv.closeTvPipWindow
import com.android.wm.shell.flicker.pip.tv.isFocusedOrHasFocusedChild
import com.android.wm.shell.flicker.testapp.Components

class PipAppHelper(instrumentation: Instrumentation) : BaseAppHelper(
    instrumentation,
    Components.PipActivity.LABEL,
    Components.PipActivity.COMPONENT
) {
    private val mediaSessionManager: MediaSessionManager
        get() = context.getSystemService(MediaSessionManager::class.java)
                ?: error("Could not get MediaSessionManager")

    private val mediaController: MediaController?
        get() = mediaSessionManager.getActiveSessions(null).firstOrNull {
            it.packageName == component.packageName
        }

    fun clickObject(resId: String) {
        val selector = By.res(component.packageName, resId)
        val obj = uiDevice.findObject(selector) ?: error("Could not find `$resId` object")

        if (!isTelevision) {
            obj.click()
        } else {
            focusOnObject(selector) || error("Could not focus on `$resId` object")
            uiDevice.pressDPadCenter()
        }
    }

    /** {@inheritDoc}  */
    override fun launchViaIntent(
        wmHelper: WindowManagerStateHelper,
        expectedWindowName: String,
        action: String?,
        stringExtras: Map<String, String>
    ) {
        super.launchViaIntent(wmHelper, expectedWindowName, action, stringExtras)
        wmHelper.waitFor("hasPipWindow") { it.wmState.hasPipWindow() }
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

    @JvmOverloads
    fun clickEnterPipButton(wmHelper: WindowManagerStateHelper? = null) {
        clickObject(ENTER_PIP_BUTTON_ID)

        // Wait on WMHelper or simply wait for 3 seconds
        wmHelper?.waitFor("hasPipWindow") { it.wmState.hasPipWindow() } ?: SystemClock.sleep(3_000)
    }

    fun clickStartMediaSessionButton() {
        clickObject(MEDIA_SESSION_START_RADIO_BUTTON_ID)
    }

    fun checkWithCustomActionsCheckbox() = uiDevice
            .findObject(By.res(component.packageName, WITH_CUSTOM_ACTIONS_BUTTON_ID))
                ?.takeIf { it.isCheckable }
                ?.apply { if (!isChecked) clickObject(WITH_CUSTOM_ACTIONS_BUTTON_ID) }
                ?: error("'With custom actions' checkbox not found")

    fun pauseMedia() = mediaController?.transportControls?.pause()
            ?: error("No active media session found")

    fun stopMedia() = mediaController?.transportControls?.stop()
            ?: error("No active media session found")

    @Deprecated("Use PipAppHelper.closePipWindow(wmHelper) instead",
        ReplaceWith("closePipWindow(wmHelper)"))
    fun closePipWindow() {
        if (isTelevision) {
            uiDevice.closeTvPipWindow()
        } else {
            closePipWindow(WindowManagerStateHelper(mInstrumentation))
        }
    }

    /**
     * Expands the pip window and dismisses it by clicking on the X button.
     *
     * Note, currently the View coordinates reported by the accessibility are relative to
     * the window, so the correct coordinates need to be calculated
     *
     * For example, in a PIP window located at Rect(508, 1444 - 1036, 1741), the
     * dismiss button coordinates are shown as Rect(650, 0 - 782, 132), with center in
     * Point(716, 66), instead of Point(970, 1403)
     *
     * See b/179337864
     */
    fun closePipWindow(wmHelper: WindowManagerStateHelper) {
        if (isTelevision) {
            uiDevice.closeTvPipWindow()
        } else {
            expandPipWindow(wmHelper)
            val exitPipObject = uiDevice.findObject(By.res(SYSTEMUI_PACKAGE, "dismiss"))
            requireNotNull(exitPipObject) { "PIP window dismiss button not found" }
            val dismissButtonBounds = exitPipObject.visibleBounds
            uiDevice.click(dismissButtonBounds.centerX(), dismissButtonBounds.centerY())
        }

        // Wait for animation to complete.
        wmHelper.waitFor("!hasPipWindow") { !it.wmState.hasPipWindow() }
        wmHelper.waitForHomeActivityVisible()
    }

    /**
     * Click once on the PIP window to expand it
     */
    fun expandPipWindow(wmHelper: WindowManagerStateHelper) {
        val windowRegion = wmHelper.getWindowRegion(component)
        require(!windowRegion.isEmpty) {
            "Unable to find a PIP window in the current state"
        }
        val windowRect = windowRegion.bounds
        uiDevice.click(windowRect.centerX(), windowRect.centerY())
        // Ensure WindowManagerService wait until all animations have completed
        wmHelper.waitForAppTransitionIdle()
        mInstrumentation.uiAutomation.syncInputTransactions()
    }

    /**
     * Double click on the PIP window to reopen to app
     */
    fun expandPipWindowToApp(wmHelper: WindowManagerStateHelper) {
        val windowRegion = wmHelper.getWindowRegion(component)
        require(!windowRegion.isEmpty) {
            "Unable to find a PIP window in the current state"
        }
        val windowRect = windowRegion.bounds
        uiDevice.click(windowRect.centerX(), windowRect.centerY())
        uiDevice.click(windowRect.centerX(), windowRect.centerY())
        wmHelper.waitFor("!hasPipWindow") { !it.wmState.hasPipWindow() }
        wmHelper.waitForAppTransitionIdle()
    }

    companion object {
        private const val FOCUS_ATTEMPTS = 20
        private const val ENTER_PIP_BUTTON_ID = "enter_pip"
        private const val WITH_CUSTOM_ACTIONS_BUTTON_ID = "with_custom_actions"
        private const val MEDIA_SESSION_START_RADIO_BUTTON_ID = "media_session_start"
    }
}
