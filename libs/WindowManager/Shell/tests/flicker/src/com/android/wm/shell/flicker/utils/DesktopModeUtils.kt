/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.flicker.utils

import android.tools.device.apphelpers.StandardAppHelper
import android.tools.helpers.SYSTEMUI_PACKAGE
import android.tools.traces.component.IComponentMatcher
import android.tools.traces.parsers.WindowManagerStateHelper
import android.tools.traces.wm.WindowingMode
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Provides a collection of utility functions for desktop mode testing.
 */
object DesktopModeUtils {
    private const val TIMEOUT_MS = 3_000L
    private const val CAPTION = "desktop_mode_caption"
    private const val CAPTION_HANDLE = "caption_handle"
    private const val MAXIMIZE_BUTTON = "maximize_button_view"

    private val captionFullscreen: BySelector
        get() = By.res(SYSTEMUI_PACKAGE, CAPTION)
    private val captionHandle: BySelector
        get() = By.res(SYSTEMUI_PACKAGE, CAPTION_HANDLE)
    private val maximizeButton: BySelector
        get() = By.res(SYSTEMUI_PACKAGE, MAXIMIZE_BUTTON)

    /**
     * Wait for an app moved to desktop to finish its transition.
     */
    private fun waitForAppToMoveToDesktop(
        wmHelper: WindowManagerStateHelper,
        currentApp: IComponentMatcher,
    ) {
        wmHelper
            .StateSyncBuilder()
            .withWindowSurfaceAppeared(currentApp)
            .withFreeformApp(currentApp)
            .withAppTransitionIdle()
            .waitForAndVerify()
    }

    /**
     * Click maximise button on the app header for the given app.
     */
    fun maximiseDesktopApp(
        wmHelper: WindowManagerStateHelper,
        device: UiDevice,
        currentApp: StandardAppHelper
    ) {
        if (wmHelper.getWindow(currentApp)?.windowingMode
            != WindowingMode.WINDOWING_MODE_FREEFORM.value)
            error("expected a freeform window to maximise but window is not in freefrom mode")

        val maximizeButton =
            device.wait(Until.findObject(maximizeButton), TIMEOUT_MS)
                ?: error("Unable to find view $maximizeButton\n")
        maximizeButton.click()
    }

    /**
     * Move an app to Desktop by dragging the app handle at the top.
     */
    fun enterDesktopWithDrag(
        wmHelper: WindowManagerStateHelper,
        device: UiDevice,
        currentApp: StandardAppHelper,
    ) {
        currentApp.launchViaIntent(wmHelper)
        dragToDesktop(wmHelper, currentApp, device)
        waitForAppToMoveToDesktop(wmHelper, currentApp)
    }

    private fun dragToDesktop(
        wmHelper: WindowManagerStateHelper,
        currentApp: StandardAppHelper,
        device: UiDevice
    ) {
        val windowRect = wmHelper.getWindowRegion(currentApp).bounds
        val startX = windowRect.centerX()

        // Start dragging a little under the top to prevent dragging the notification shade.
        val startY = 10

        val displayRect =
            wmHelper.currentState.wmState.getDefaultDisplay()?.displayRect
                ?: throw IllegalStateException("Default display is null")

        // The position we want to drag to
        val endY = displayRect.centerY() / 2

        // drag the window to move to desktop
        device.drag(startX, startY, startX, endY, 100)
    }
}
