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
import android.content.Intent
import android.graphics.Region
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.tools.device.apphelpers.BasePipAppHelper
import android.tools.helpers.FIND_TIMEOUT
import android.tools.helpers.SYSTEMUI_PACKAGE
import android.tools.traces.ConditionsFactory
import android.tools.traces.component.ComponentNameMatcher
import android.tools.traces.component.IComponentMatcher
import android.tools.traces.parsers.WindowManagerStateHelper
import android.tools.traces.parsers.toFlickerComponent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions

open class PipAppHelper(
    instrumentation: Instrumentation,
    appName: String = ActivityOptions.Pip.LABEL,
    componentNameMatcher: ComponentNameMatcher = ActivityOptions.Pip.COMPONENT.toFlickerComponent(),
) : BasePipAppHelper(instrumentation, appName, componentNameMatcher) {
    private val mediaSessionManager: MediaSessionManager
        get() =
            context.getSystemService(MediaSessionManager::class.java)
                ?: error("Could not get MediaSessionManager")

    private val mediaController: MediaController?
        get() =
            mediaSessionManager.getActiveSessions(null).firstOrNull {
                it.packageName == packageName
            }

    /**
     * Launches the app through an intent instead of interacting with the launcher and waits until
     * the app window is in PIP mode
     */
    @JvmOverloads
    fun launchViaIntentAndWaitForPip(
        wmHelper: WindowManagerStateHelper,
        launchedAppComponentMatcherOverride: IComponentMatcher? = null,
        action: String? = null,
        stringExtras: Map<String, String>
    ) {
        launchViaIntent(
            wmHelper,
            launchedAppComponentMatcherOverride,
            action,
            stringExtras
        )

        wmHelper
            .StateSyncBuilder()
            .withWindowSurfaceAppeared(this)
            .add(ConditionsFactory.isWMStateComplete())
            .withPipShown()
            .waitForAndVerify()
    }

    /** Expand the PIP window back to full screen via intent and wait until the app is visible */
    fun exitPipToFullScreenViaIntent(wmHelper: WindowManagerStateHelper) = launchViaIntent(wmHelper)

    fun changeAspectRatio(wmHelper: WindowManagerStateHelper) {
        val intent = Intent("com.android.wm.shell.flicker.testapp.ASPECT_RATIO")
        context.sendBroadcast(intent)
        // Wait on WMHelper on size change upon aspect ratio change
        val windowRect = getWindowRect(wmHelper)
        wmHelper
            .StateSyncBuilder()
            .add("pipAspectRatioChanged") {
                val pipAppWindow =
                    it.wmState.visibleWindows.firstOrNull { window ->
                        this.windowMatchesAnyOf(window)
                    }
                        ?: return@add false
                val pipRegion = pipAppWindow.frameRegion
                return@add pipRegion != Region(windowRect)
            }
            .waitForAndVerify()
    }

    fun clickEnterPipButton(wmHelper: WindowManagerStateHelper) {
        clickObject(ENTER_PIP_BUTTON_ID)

        // Wait on WMHelper or simply wait for 3 seconds
        wmHelper.StateSyncBuilder().withPipShown().waitForAndVerify()
        // when entering pip, the dismiss button is visible at the start. to ensure the pip
        // animation is complete, wait until the pip dismiss button is no longer visible.
        // b/176822698: dismiss-only state will be removed in the future
        uiDevice.wait(Until.gone(By.res(SYSTEMUI_PACKAGE, "dismiss")), FIND_TIMEOUT)
    }

    fun enableEnterPipOnUserLeaveHint() {
        clickObject(ENTER_PIP_ON_USER_LEAVE_HINT)
    }

    fun enableAutoEnterForPipActivity() {
        clickObject(ENTER_PIP_AUTOENTER)
    }

    fun clickStartMediaSessionButton() {
        clickObject(MEDIA_SESSION_START_RADIO_BUTTON_ID)
    }

    fun setSourceRectHint() {
        clickObject(SOURCE_RECT_HINT)
    }

    fun checkWithCustomActionsCheckbox() =
        uiDevice
            .findObject(By.res(packageName, WITH_CUSTOM_ACTIONS_BUTTON_ID))
            ?.takeIf { it.isCheckable }
            ?.apply { if (!isChecked) clickObject(WITH_CUSTOM_ACTIONS_BUTTON_ID) }
            ?: error("'With custom actions' checkbox not found")

    fun pauseMedia() =
        mediaController?.transportControls?.pause() ?: error("No active media session found")

    fun stopMedia() =
        mediaController?.transportControls?.stop() ?: error("No active media session found")

    @Deprecated(
        "Use PipAppHelper.closePipWindow(wmHelper) instead",
        ReplaceWith("closePipWindow(wmHelper)")
    )
    open fun closePipWindow() {
        closePipWindow(WindowManagerStateHelper(instrumentation))
    }

    companion object {
        private const val TAG = "PipAppHelper"
        private const val ENTER_PIP_BUTTON_ID = "enter_pip"
        private const val WITH_CUSTOM_ACTIONS_BUTTON_ID = "with_custom_actions"
        private const val MEDIA_SESSION_START_RADIO_BUTTON_ID = "media_session_start"
        private const val ENTER_PIP_ON_USER_LEAVE_HINT = "enter_pip_on_leave_manual"
        private const val ENTER_PIP_AUTOENTER = "enter_pip_on_leave_autoenter"
        private const val SOURCE_RECT_HINT = "set_source_rect_hint"
    }
}