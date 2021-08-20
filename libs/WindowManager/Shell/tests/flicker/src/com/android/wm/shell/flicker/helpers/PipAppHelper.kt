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
import android.graphics.Rect
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.helpers.FIND_TIMEOUT
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
        // when entering pip, the dismiss button is visible at the start. to ensure the pip
        // animation is complete, wait until the pip dismiss button is no longer visible. 
        // b/176822698: dismiss-only state will be removed in the future
        uiDevice.wait(Until.gone(By.res(SYSTEMUI_PACKAGE, "dismiss")), FIND_TIMEOUT)
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

    private fun getWindowRect(wmHelper: WindowManagerStateHelper): Rect {
        val windowRegion = wmHelper.getWindowRegion(component)
        require(!windowRegion.isEmpty) {
            "Unable to find a PIP window in the current state"
        }
        return windowRegion.bounds
    }

    /**
     * Expands the pip window and dismisses it by clicking on the X button.
     */
    fun closePipWindow(wmHelper: WindowManagerStateHelper) {
        if (isTelevision) {
            uiDevice.closeTvPipWindow()
        } else {
            val windowRect = getWindowRect(wmHelper)
            uiDevice.click(windowRect.centerX(), windowRect.centerY())
            val exitPipObject = uiDevice.findObject(By.res(SYSTEMUI_PACKAGE, "dismiss"))
                    ?: error("PIP window dismiss button not found")
            val dismissButtonBounds = exitPipObject.visibleBounds
            uiDevice.click(dismissButtonBounds.centerX(), dismissButtonBounds.centerY())
        }

        // Wait for animation to complete.
        wmHelper.waitFor("!hasPipWindow") { !it.wmState.hasPipWindow() }
        wmHelper.waitForHomeActivityVisible()
    }

    /**
     * Close the pip window by pressing the expand button
     */
    fun expandPipWindowToApp(wmHelper: WindowManagerStateHelper) {
        val windowRect = getWindowRect(wmHelper)
        uiDevice.click(windowRect.centerX(), windowRect.centerY())
        // search and interact with the expand button
        val expandSelector = By.res(SYSTEMUI_PACKAGE, "expand_button")
        uiDevice.wait(Until.hasObject(expandSelector), FIND_TIMEOUT)
        val expandPipObject = uiDevice.findObject(expandSelector)
                ?: error("PIP window expand button not found")
        val expandButtonBounds = expandPipObject.visibleBounds
        uiDevice.click(expandButtonBounds.centerX(), expandButtonBounds.centerY())
        wmHelper.waitFor("!hasPipWindow") { !it.wmState.hasPipWindow() }
        wmHelper.waitForAppTransitionIdle()
    }

    /**
     * Double click on the PIP window to expand it
     */
    fun doubleClickPipWindow(wmHelper: WindowManagerStateHelper) {
        val windowRect = getWindowRect(wmHelper)
        uiDevice.click(windowRect.centerX(), windowRect.centerY())
        uiDevice.click(windowRect.centerX(), windowRect.centerY())
        wmHelper.waitForAppTransitionIdle()
    }

    companion object {
        private const val FOCUS_ATTEMPTS = 20
        private const val ENTER_PIP_BUTTON_ID = "enter_pip"
        private const val WITH_CUSTOM_ACTIONS_BUTTON_ID = "with_custom_actions"
        private const val MEDIA_SESSION_START_RADIO_BUTTON_ID = "media_session_start"
    }
}
