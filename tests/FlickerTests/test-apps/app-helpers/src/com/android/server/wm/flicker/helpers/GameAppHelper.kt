/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.traces.component.ComponentNameMatcher
import android.tools.traces.parsers.WindowManagerStateHelper
import android.tools.traces.parsers.toFlickerComponent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.testapp.ActivityOptions

class GameAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.Game.LABEL,
    component: ComponentNameMatcher = ActivityOptions.Game.COMPONENT.toFlickerComponent()
) : StandardAppHelper(instr, launcherName, component) {

    /**
     * Swipes down in the mock game app.
     *
     * @return true if the swipe operation is successful.
     */
    fun swipeDown(): Boolean {
        val gameView =
            uiDevice.wait(Until.findObject(By.res(packageName, GAME_APP_VIEW_RES)), WAIT_TIME_MS)
        require(gameView != null) { "Mock game app view not found." }

        val bound = gameView.getVisibleBounds()
        return uiDevice.swipe(bound.centerX(), 0, bound.centerX(), bound.centerY(), SWIPE_STEPS)
    }

    /**
     * Switches to a recent app by quick switch gesture. This function can be used in both portrait
     * and landscape mode.
     *
     * @param wmHelper Helper used to get window region.
     * @param direction UiAutomator Direction enum to indicate the swipe direction.
     * @return true if the swipe operation is successful.
     */
    fun switchToPreviousAppByQuickSwitchGesture(
        wmHelper: WindowManagerStateHelper,
        direction: Direction
    ): Boolean {
        val ratioForScreenBottom = 0.99
        val fullView = wmHelper.getWindowRegion(componentMatcher)
        require(!fullView.isEmpty) { "Target $componentMatcher view not found." }

        val bound = fullView.bounds
        val targetYPos = bound.bottom * ratioForScreenBottom
        val endX =
            when (direction) {
                Direction.LEFT -> bound.left
                Direction.RIGHT -> bound.right
                else -> {
                    throw IllegalStateException("Only left or right direction is allowed.")
                }
            }
        return uiDevice.swipe(
            bound.centerX(),
            targetYPos.toInt(),
            endX,
            targetYPos.toInt(),
            SWIPE_STEPS
        )
    }

    /**
     * Waits for view idel with timeout, then checkes the target object whether visible on screen.
     *
     * @param packageName The targe application's package name.
     * @param identifier The resource id of the target object.
     * @param timeout The timeout duration in milliseconds.
     * @return true if the target object exists.
     */
    @JvmOverloads
    fun isTargetObjVisible(
        packageName: String,
        identifier: String,
        timeout: Long = WAIT_TIME_MS
    ): Boolean {
        uiDevice.waitForIdle(timeout)
        return uiDevice.hasObject(By.res(packageName, identifier))
    }

    companion object {
        private const val GAME_APP_VIEW_RES = "container"
        private const val WAIT_TIME_MS = 3_000L
        private const val SWIPE_STEPS = 30
    }
}
