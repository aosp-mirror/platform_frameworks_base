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

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import android.hardware.input.InputManager
import android.hardware.input.InputManager.KeyGestureEventHandler
import android.hardware.input.KeyGestureEvent
import android.os.IBinder
import com.android.hardware.input.Flags.manageKeyGestures
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags.enableMoveToNextDisplayShortcut
import com.android.window.flags.Flags.enableTaskResizingKeyboardShortcuts
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.common.ToggleTaskSizeInteraction
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.transition.FocusTransitionObserver
import com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel
import java.util.Optional

/** Handles key gesture events (keyboard shortcuts) in Desktop Mode. */
class DesktopModeKeyGestureHandler(
    private val context: Context,
    private val desktopModeWindowDecorViewModel: Optional<DesktopModeWindowDecorViewModel>,
    private val desktopTasksController: Optional<DesktopTasksController>,
    inputManager: InputManager,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val focusTransitionObserver: FocusTransitionObserver,
    @ShellMainThread private val mainExecutor: ShellExecutor,
    private val displayController: DisplayController,
) : KeyGestureEventHandler {

    init {
        inputManager.registerKeyGestureEventHandler(this)
    }

    override fun handleKeyGestureEvent(event: KeyGestureEvent, focusedToken: IBinder?): Boolean {
        if (
            !isKeyGestureSupported(event.keyGestureType) ||
                !desktopTasksController.isPresent ||
                !desktopModeWindowDecorViewModel.isPresent
        ) {
            return false
        }
        when (event.keyGestureType) {
            KeyGestureEvent.KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY -> {
                logV("Key gesture MOVE_TO_NEXT_DISPLAY is handled")
                getGloballyFocusedFreeformTask()?.let {
                    mainExecutor.execute {
                        desktopTasksController.get().moveToNextDisplay(it.taskId)
                    }
                }
                return true
            }
            KeyGestureEvent.KEY_GESTURE_TYPE_SNAP_LEFT_FREEFORM_WINDOW -> {
                logV("Key gesture SNAP_LEFT_FREEFORM_WINDOW is handled")
                getGloballyFocusedFreeformTask()?.let {
                    mainExecutor.execute {
                        desktopModeWindowDecorViewModel
                            .get()
                            .onSnapResize(
                                it.taskId,
                                true,
                                DesktopModeEventLogger.Companion.InputMethod.KEYBOARD,
                                /* fromMenu= */ false,
                            )
                    }
                }
                return true
            }
            KeyGestureEvent.KEY_GESTURE_TYPE_SNAP_RIGHT_FREEFORM_WINDOW -> {
                logV("Key gesture SNAP_RIGHT_FREEFORM_WINDOW is handled")
                getGloballyFocusedFreeformTask()?.let {
                    mainExecutor.execute {
                        desktopModeWindowDecorViewModel
                            .get()
                            .onSnapResize(
                                it.taskId,
                                false,
                                DesktopModeEventLogger.Companion.InputMethod.KEYBOARD,
                                /* fromMenu= */ false,
                            )
                    }
                }
                return true
            }
            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MAXIMIZE_FREEFORM_WINDOW -> {
                logV("Key gesture TOGGLE_MAXIMIZE_FREEFORM_WINDOW is handled")
                getGloballyFocusedFreeformTask()?.let { taskInfo ->
                    mainExecutor.execute {
                        desktopTasksController
                            .get()
                            .toggleDesktopTaskSize(
                                taskInfo,
                                ToggleTaskSizeInteraction(
                                    isMaximized = isTaskMaximized(taskInfo, displayController),
                                    source = ToggleTaskSizeInteraction.Source.KEYBOARD_SHORTCUT,
                                    inputMethod =
                                        DesktopModeEventLogger.Companion.InputMethod.KEYBOARD,
                                ),
                            )
                    }
                }
                return true
            }
            KeyGestureEvent.KEY_GESTURE_TYPE_MINIMIZE_FREEFORM_WINDOW -> {
                logV("Key gesture MINIMIZE_FREEFORM_WINDOW is handled")
                getGloballyFocusedFreeformTask()?.let {
                    mainExecutor.execute { desktopTasksController.get().minimizeTask(it) }
                }
                return true
            }
            else -> return false
        }
    }

    override fun isKeyGestureSupported(gestureType: Int): Boolean =
        when (gestureType) {
            KeyGestureEvent.KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY ->
                enableMoveToNextDisplayShortcut()
            KeyGestureEvent.KEY_GESTURE_TYPE_SNAP_LEFT_FREEFORM_WINDOW,
            KeyGestureEvent.KEY_GESTURE_TYPE_SNAP_RIGHT_FREEFORM_WINDOW,
            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MAXIMIZE_FREEFORM_WINDOW,
            KeyGestureEvent.KEY_GESTURE_TYPE_MINIMIZE_FREEFORM_WINDOW ->
                enableTaskResizingKeyboardShortcuts() && manageKeyGestures()
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
