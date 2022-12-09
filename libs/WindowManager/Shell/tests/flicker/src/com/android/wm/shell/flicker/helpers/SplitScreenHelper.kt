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
import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.SYSTEM_UI_PACKAGE_NAME
import com.android.wm.shell.flicker.testapp.Components
import org.junit.Assert

class SplitScreenHelper(
    instrumentation: Instrumentation,
    activityLabel: String,
    componentsInfo: FlickerComponentName
) : BaseAppHelper(instrumentation, activityLabel, componentsInfo) {

    companion object {
        const val TEST_REPETITIONS = 1
        const val TIMEOUT_MS = 3_000L
        const val DRAG_DURATION_MS = 1_000L
        const val NOTIFICATION_SCROLLER = "notification_stack_scroller"
        const val GESTURE_STEP_MS = 16L

        private val notificationScrollerSelector: BySelector
            get() = By.res(SYSTEM_UI_PACKAGE_NAME, NOTIFICATION_SCROLLER)
        private val notificationContentSelector: BySelector
            get() = By.text("Notification content")

        fun getPrimary(instrumentation: Instrumentation): SplitScreenHelper =
            SplitScreenHelper(
                instrumentation,
                Components.SplitScreenActivity.LABEL,
                Components.SplitScreenActivity.COMPONENT.toFlickerComponent()
            )

        fun getSecondary(instrumentation: Instrumentation): SplitScreenHelper =
            SplitScreenHelper(
                instrumentation,
                Components.SplitScreenSecondaryActivity.LABEL,
                Components.SplitScreenSecondaryActivity.COMPONENT.toFlickerComponent()
            )

        fun getNonResizeable(instrumentation: Instrumentation): SplitScreenHelper =
            SplitScreenHelper(
                instrumentation,
                Components.NonResizeableActivity.LABEL,
                Components.NonResizeableActivity.COMPONENT.toFlickerComponent()
            )

        fun getSendNotification(instrumentation: Instrumentation): SplitScreenHelper =
            SplitScreenHelper(
                instrumentation,
                Components.SendNotificationActivity.LABEL,
                Components.SendNotificationActivity.COMPONENT.toFlickerComponent()
            )

        fun dragFromNotificationToSplit(
            instrumentation: Instrumentation,
            device: UiDevice,
            wmHelper: WindowManagerStateHelper
        ) {
            val displayBounds = wmHelper.currentState.layerState
                .displays.firstOrNull { !it.isVirtual }
                ?.layerStackSpace
                ?: error("Display not found")

            // Pull down the notifications
            device.swipe(
                displayBounds.centerX(), 5,
                displayBounds.centerX(), displayBounds.bottom, 20 /* steps */
            )
            SystemClock.sleep(TIMEOUT_MS)

            // Find the target notification
            val notificationScroller = device.wait(
                Until.findObject(notificationScrollerSelector), TIMEOUT_MS
            )
            var notificationContent = notificationScroller.findObject(notificationContentSelector)

            while (notificationContent == null) {
                device.swipe(
                    displayBounds.centerX(), displayBounds.centerY(),
                    displayBounds.centerX(), displayBounds.centerY() - 150, 20 /* steps */
                )
                notificationContent = notificationScroller.findObject(notificationContentSelector)
            }

            // Drag to split
            var dragStart = notificationContent.visibleCenter
            var dragMiddle = Point(dragStart.x + 50, dragStart.y)
            var dragEnd = Point(displayBounds.width / 4, displayBounds.width / 4)
            val downTime = SystemClock.uptimeMillis()

            touch(
                instrumentation, MotionEvent.ACTION_DOWN, downTime, downTime,
                TIMEOUT_MS, dragStart
            )
            // It needs a horizontal movement to trigger the drag
            touchMove(
                instrumentation, downTime, SystemClock.uptimeMillis(),
                DRAG_DURATION_MS, dragStart, dragMiddle
            )
            touchMove(
                instrumentation, downTime, SystemClock.uptimeMillis(),
                DRAG_DURATION_MS, dragMiddle, dragEnd
            )
            // Wait for a while to start splitting
            SystemClock.sleep(TIMEOUT_MS)
            touch(
                instrumentation, MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(),
                GESTURE_STEP_MS, dragEnd
            )
            SystemClock.sleep(TIMEOUT_MS)
        }

        fun touch(
            instrumentation: Instrumentation,
            action: Int,
            downTime: Long,
            eventTime: Long,
            duration: Long,
            point: Point
        ) {
            val motionEvent = MotionEvent.obtain(
                downTime, eventTime, action, point.x.toFloat(), point.y.toFloat(), 0
            )
            motionEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(motionEvent, true)
            motionEvent.recycle()
            SystemClock.sleep(duration)
        }

        fun touchMove(
            instrumentation: Instrumentation,
            downTime: Long,
            eventTime: Long,
            duration: Long,
            from: Point,
            to: Point
        ) {
            val steps: Long = duration / GESTURE_STEP_MS
            var currentTime = eventTime
            var currentX = from.x.toFloat()
            var currentY = from.y.toFloat()
            val stepX = (to.x.toFloat() - from.x.toFloat()) / steps.toFloat()
            val stepY = (to.y.toFloat() - from.y.toFloat()) / steps.toFloat()

            for (i in 1..steps) {
                val motionMove = MotionEvent.obtain(
                    downTime, currentTime, MotionEvent.ACTION_MOVE, currentX, currentY, 0
                )
                motionMove.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(motionMove, true)
                motionMove.recycle()

                currentTime += GESTURE_STEP_MS
                if (i == steps - 1) {
                    currentX = to.x.toFloat()
                    currentY = to.y.toFloat()
                } else {
                    currentX += stepX
                    currentY += stepY
                }
                SystemClock.sleep(GESTURE_STEP_MS)
            }
        }

        fun createShortcutOnHotseatIfNotExist(
            taplInstrumentation: LauncherInstrumentation,
            appName: String
        ) {
            taplInstrumentation.workspace
                .deleteAppIcon(taplInstrumentation.workspace.getHotseatAppIcon(0))
            val allApps = taplInstrumentation.workspace.switchToAllApps()
            allApps.freeze()
            try {
                val appIconSrc = allApps.getAppIcon(appName)
                Assert.assertNotNull("Unable to find app icon", appIconSrc)
                val appIconDest = appIconSrc.dragToHotseat(0)
                Assert.assertNotNull("Unable to drag app icon on hotseat", appIconDest)
            } finally {
                allApps.unfreeze()
            }
        }
    }
}
