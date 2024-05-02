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

package com.android.server.wm.flicker.helpers

import android.graphics.Rect
import android.tools.device.apphelpers.IStandardAppHelper
import android.tools.helpers.SYSTEMUI_PACKAGE
import android.tools.traces.parsers.WindowManagerStateHelper
import android.tools.traces.wm.WindowingMode
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Wrapper class around App helper classes. This class adds functionality to the apps that the
 * desktop apps would have.
 */
open class DesktopModeAppHelper(private val innerHelper: IStandardAppHelper) :
    IStandardAppHelper by innerHelper {

    enum class Corners {
        LEFT_TOP,
        RIGHT_TOP,
        LEFT_BOTTOM,
        RIGHT_BOTTOM
    }

    private val TIMEOUT_MS = 3_000L
    private val CAPTION = "desktop_mode_caption"
    private val CAPTION_HANDLE = "caption_handle"
    private val MAXIMIZE_BUTTON = "maximize_window"
    private val MAXIMIZE_BUTTON_VIEW = "maximize_button_view"
    private val CLOSE_BUTTON = "close_window"

    private val caption: BySelector
        get() = By.res(SYSTEMUI_PACKAGE, CAPTION)

    /** Wait for an app moved to desktop to finish its transition. */
    private fun waitForAppToMoveToDesktop(wmHelper: WindowManagerStateHelper) {
        wmHelper
            .StateSyncBuilder()
            .withWindowSurfaceAppeared(innerHelper)
            .withFreeformApp(innerHelper)
            .withAppTransitionIdle()
            .waitForAndVerify()
    }

    /** Move an app to Desktop by dragging the app handle at the top. */
    fun enterDesktopWithDrag(
        wmHelper: WindowManagerStateHelper,
        device: UiDevice,
    ) {
        innerHelper.launchViaIntent(wmHelper)
        dragToDesktop(wmHelper, device)
        waitForAppToMoveToDesktop(wmHelper)
    }

    private fun dragToDesktop(wmHelper: WindowManagerStateHelper, device: UiDevice) {
        val windowRect = wmHelper.getWindowRegion(innerHelper).bounds
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

    /** Click maximise button on the app header for the given app. */
    fun maximiseDesktopApp(wmHelper: WindowManagerStateHelper, device: UiDevice) {
        val caption = getCaptionForTheApp(wmHelper, device)
        val maximizeButton =
            caption
                ?.children
                ?.find { it.resourceName.endsWith(MAXIMIZE_BUTTON_VIEW) }
                ?.children
                ?.get(0)
        maximizeButton?.click()
        wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()
    }
    /** Click close button on the app header for the given app. */
    fun closeDesktopApp(wmHelper: WindowManagerStateHelper, device: UiDevice) {
        val caption = getCaptionForTheApp(wmHelper, device)
        val closeButton = caption?.children?.find { it.resourceName.endsWith(CLOSE_BUTTON) }
        closeButton?.click()
        wmHelper
            .StateSyncBuilder()
            .withAppTransitionIdle()
            .withWindowSurfaceDisappeared(innerHelper)
            .waitForAndVerify()
    }

    private fun getCaptionForTheApp(
        wmHelper: WindowManagerStateHelper,
        device: UiDevice
    ): UiObject2? {
        if (
            wmHelper.getWindow(innerHelper)?.windowingMode !=
                WindowingMode.WINDOWING_MODE_FREEFORM.value
        )
            error("expected a freeform window with caption but window is not in freeform mode")
        val captions =
            device.wait(Until.findObjects(caption), TIMEOUT_MS)
                ?: error("Unable to find view $caption\n")

        return captions.find {
            wmHelper.getWindowRegion(innerHelper).bounds.contains(it.visibleBounds)
        }
    }

    /** Resize a desktop app from its corners. */
    fun cornerResize(
        wmHelper: WindowManagerStateHelper,
        device: UiDevice,
        corner: Corners,
        horizontalChange: Int,
        verticalChange: Int
    ) {
        val windowRect = wmHelper.getWindowRegion(innerHelper).bounds
        val (startX, startY) = getStartCoordinatesForCornerResize(windowRect, corner)

        // The position we want to drag to
        val endY = startY + verticalChange
        val endX = startX + horizontalChange

        // drag the specified corner of the window to the end coordinate.
        device.drag(startX, startY, endX, endY, 100)
        wmHelper
            .StateSyncBuilder()
            .withAppTransitionIdle()
            .waitForAndVerify()
    }

    private fun getStartCoordinatesForCornerResize(
        windowRect: Rect,
        corner: Corners
    ): Pair<Int, Int> {
        return when (corner) {
            Corners.LEFT_TOP -> Pair(windowRect.left, windowRect.top)
            Corners.RIGHT_TOP -> Pair(windowRect.right, windowRect.top)
            Corners.LEFT_BOTTOM -> Pair(windowRect.left, windowRect.bottom)
            Corners.RIGHT_BOTTOM -> Pair(windowRect.right, windowRect.bottom)
        }
    }
}
