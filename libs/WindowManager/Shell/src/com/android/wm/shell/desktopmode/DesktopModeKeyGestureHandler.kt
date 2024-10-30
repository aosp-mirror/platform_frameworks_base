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

package com.android.wm.shell.desktopmode

import android.hardware.input.KeyGestureEvent
import android.view.KeyEvent

import android.hardware.input.InputManager
import android.hardware.input.InputManager.KeyGestureEventHandler
import android.os.IBinder
import com.android.window.flags.Flags.enableMoveToNextDisplayShortcut
import com.android.wm.shell.ShellTaskOrganizer
import android.app.ActivityManager.RunningTaskInfo
import com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel
import com.android.internal.protolog.ProtoLog
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import com.android.wm.shell.transition.FocusTransitionObserver
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import java.util.Optional

/**
 * Handles key gesture events (keyboard shortcuts) in Desktop Mode.
 */
class DesktopModeKeyGestureHandler(
    private val context: Context,
    private val desktopModeWindowDecorViewModel: DesktopModeWindowDecorViewModel,
    private val desktopTasksController: Optional<DesktopTasksController>,
    inputManager: InputManager,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val focusTransitionObserver: FocusTransitionObserver,
    ) : KeyGestureEventHandler {

    init {
        inputManager.registerKeyGestureEventHandler(this)
    }

    override fun handleKeyGestureEvent(event: KeyGestureEvent, focusedToken: IBinder?): Boolean {
        if (!isKeyGestureSupported(event.keyGestureType) || !desktopTasksController.isPresent) {
            return false
        }
        when (event.keyGestureType) {
            KeyGestureEvent.KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY -> {
                if (event.keycodes.contains(KeyEvent.KEYCODE_D) &&
                    event.hasModifiers(KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON)
                ) {
                    logV("Key gesture MOVE_TO_NEXT_DISPLAY is handled")
                    getGloballyFocusedFreeformTask()?.let {
                        desktopTasksController.get().moveToNextDisplay(
                            it.taskId
                        )
                    }
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    override fun isKeyGestureSupported(gestureType: Int): Boolean = when (gestureType) {
        KeyGestureEvent.KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY
            -> enableMoveToNextDisplayShortcut()
        else -> false
    }

    //  TODO: b/364154795 - wait for the completion of moveToNextDisplay transition, otherwise it
    //  will pick a wrong task when a user quickly perform other actions with keyboard shortcuts
    //  after moveToNextDisplay, and move this to FocusTransitionObserver class.
    private fun getGloballyFocusedFreeformTask(): RunningTaskInfo? =
        shellTaskOrganizer.getRunningTasks().find { taskInfo ->
            taskInfo.windowingMode == WINDOWING_MODE_FREEFORM &&
                    focusTransitionObserver.hasGlobalFocus(taskInfo)
        }

    private fun logV(msg: String, vararg arguments: Any?) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    companion object {
        private const val TAG = "DesktopModeKeyGestureHandler"
    }
}
