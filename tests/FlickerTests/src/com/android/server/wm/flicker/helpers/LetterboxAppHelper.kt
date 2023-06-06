/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.tools.common.datatypes.Region
import android.tools.common.datatypes.Rect
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.device.helpers.FIND_TIMEOUT
import android.tools.device.helpers.SYSTEMUI_PACKAGE
import android.tools.device.traces.parsers.WindowManagerStateHelper
import android.tools.device.traces.parsers.toFlickerComponent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions

class LetterboxAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.NonResizeablePortraitActivity.LABEL,
    component: ComponentNameMatcher =
        ActivityOptions.NonResizeablePortraitActivity.COMPONENT.toFlickerComponent()
) : StandardAppHelper(instr, launcherName, component) {

    private val gestureHelper: GestureHelper = GestureHelper(mInstrumentation)

    fun clickRestart(wmHelper: WindowManagerStateHelper) {
        val restartButton =
            uiDevice.wait(
                Until.findObject(By.res(SYSTEMUI_PACKAGE, "size_compat_restart_button")),
                FIND_TIMEOUT
            )
        restartButton?.run { restartButton.click() } ?: error("Restart button not found")

        // size compat mode restart confirmation dialog button
        val restartDialogButton =
            uiDevice.wait(
                Until.findObject(
                    By.res(SYSTEMUI_PACKAGE, "letterbox_restart_dialog_restart_button")
                ),
                FIND_TIMEOUT
            )
        restartDialogButton?.run { restartDialogButton.click() }
            ?: error("Restart dialog button not found")
        wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()
    }

    fun repositionHorizontally(displayBounds: Rect, right: Boolean) {
        val x = if (right) displayBounds.right - BOUNDS_OFFSET else BOUNDS_OFFSET
        reposition(x.toFloat(), displayBounds.centerY().toFloat())
    }

    fun repositionVertically(displayBounds: Rect, bottom: Boolean) {
        val y = if (bottom) displayBounds.bottom - BOUNDS_OFFSET else BOUNDS_OFFSET
        reposition(displayBounds.centerX().toFloat(), y.toFloat())
    }

    private fun reposition(x: Float, y: Float) {
        val coords = GestureHelper.Tuple(x, y)
        require(gestureHelper.tap(coords, 2)) { "Failed to reposition letterbox app" }
    }

    fun waitForAppToMoveHorizontallyTo(
        wmHelper: WindowManagerStateHelper,
        displayBounds: Rect,
        right: Boolean
    ) {
        wmHelper.StateSyncBuilder().add("letterboxAppRepositioned") {
            val letterboxAppWindow = getWindowRegion(wmHelper)
            val appRegionBounds = letterboxAppWindow.bounds
            val appWidth = appRegionBounds.width
            return@add if (right) appRegionBounds.left == displayBounds.right - appWidth &&
                    appRegionBounds.right == displayBounds.right
                else appRegionBounds.left == displayBounds.left &&
                    appRegionBounds.right == displayBounds.left + appWidth
        }.waitForAndVerify()
    }

    fun waitForAppToMoveVerticallyTo(
        wmHelper: WindowManagerStateHelper,
        displayBounds: Rect,
        navBarHeight: Int,
        bottom: Boolean
    ) {
        wmHelper.StateSyncBuilder().add("letterboxAppRepositioned") {
            val letterboxAppWindow = getWindowRegion(wmHelper)
            val appRegionBounds = letterboxAppWindow.bounds
            val appHeight = appRegionBounds.height
            return@add if (bottom) appRegionBounds.bottom == displayBounds.bottom &&
                    appRegionBounds.top == (displayBounds.bottom - appHeight + navBarHeight)
                else appRegionBounds.top == displayBounds.top &&
                    appRegionBounds.bottom == displayBounds.top + appHeight
        }.waitForAndVerify()
    }

    private fun getWindowRegion(wmHelper: WindowManagerStateHelper): Region {
        val windowRegion = wmHelper.getWindowRegion(this)
        require(!windowRegion.isEmpty) {
            "Unable to find letterbox app window in the current state"
        }
        return windowRegion
    }

    companion object {
        private const val BOUNDS_OFFSET: Int = 100
    }
}
