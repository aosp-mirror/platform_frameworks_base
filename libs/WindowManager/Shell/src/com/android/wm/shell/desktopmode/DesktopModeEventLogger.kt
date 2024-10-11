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

import com.android.internal.annotations.VisibleForTesting
import com.android.internal.protolog.ProtoLog
import com.android.internal.util.FrameworkStatsLog
import com.android.window.flags.Flags
import com.android.wm.shell.EventLogTags
import com.android.wm.shell.protolog.ShellProtoLogGroup

/** Event logger for logging desktop mode session events */
class DesktopModeEventLogger {
    /**
     * Logs the enter of desktop mode having session id [sessionId] and the reason [enterReason] for
     * entering desktop mode
     */
    fun logSessionEnter(sessionId: Int, enterReason: EnterReason) {
        ProtoLog.v(
            ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
            "DesktopModeLogger: Logging session enter, session: %s reason: %s",
            sessionId,
            enterReason.name
        )
        FrameworkStatsLog.write(
            DESKTOP_MODE_ATOM_ID,
            /* event */ FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EVENT__ENTER,
            /* enterReason */ enterReason.reason,
            /* exitReason */ 0,
            /* session_id */ sessionId
        )
        EventLogTags.writeWmShellEnterDesktopMode(enterReason.reason, sessionId)
    }

    /**
     * Logs the exit of desktop mode having session id [sessionId] and the reason [exitReason] for
     * exiting desktop mode
     */
    fun logSessionExit(sessionId: Int, exitReason: ExitReason) {
        ProtoLog.v(
            ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
            "DesktopModeLogger: Logging session exit, session: %s reason: %s",
            sessionId,
            exitReason.name
        )
        FrameworkStatsLog.write(
            DESKTOP_MODE_ATOM_ID,
            /* event */ FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EVENT__EXIT,
            /* enterReason */ 0,
            /* exitReason */ exitReason.reason,
            /* session_id */ sessionId
        )
        EventLogTags.writeWmShellExitDesktopMode(exitReason.reason, sessionId)
    }

    /**
     * Logs that the task with update [taskUpdate] was added in the desktop mode session having
     * session id [sessionId]
     */
    fun logTaskAdded(sessionId: Int, taskUpdate: TaskUpdate) {
        ProtoLog.v(
            ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
            "DesktopModeLogger: Logging task added, session: %s taskId: %s",
            sessionId,
            taskUpdate.instanceId
        )
        logTaskUpdate(
            FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_ADDED,
            sessionId, taskUpdate
        )
    }

    /**
     * Logs that the task with update [taskUpdate] was removed in the desktop mode session having
     * session id [sessionId]
     */
    fun logTaskRemoved(sessionId: Int, taskUpdate: TaskUpdate) {
        ProtoLog.v(
            ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
            "DesktopModeLogger: Logging task remove, session: %s taskId: %s",
            sessionId,
            taskUpdate.instanceId
        )
        logTaskUpdate(
            FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_REMOVED,
            sessionId, taskUpdate
        )
    }

    /**
     * Logs that the task with update [taskUpdate] had it's info changed in the desktop mode session
     * having session id [sessionId]
     */
    fun logTaskInfoChanged(sessionId: Int, taskUpdate: TaskUpdate) {
        ProtoLog.v(
            ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
            "DesktopModeLogger: Logging task info changed, session: %s taskId: %s",
            sessionId,
            taskUpdate.instanceId
        )
        logTaskUpdate(
            FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED,
            sessionId, taskUpdate
        )
    }

    /**
     * Logs that a task resize event is starting with [taskSizeUpdate] within a
     * Desktop mode [sessionId].
     */
    fun logTaskResizingStarted(sessionId: Int, taskSizeUpdate: TaskSizeUpdate) {
        if (!Flags.enableResizingMetrics()) return

        ProtoLog.v(
            ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
            "DesktopModeLogger: Logging task resize is starting, session: %s taskId: %s",
            sessionId,
            taskSizeUpdate.instanceId
        )
        logTaskSizeUpdated(
            FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZING_STAGE__START_RESIZING_STAGE,
            sessionId, taskSizeUpdate
        )
    }

    /**
     * Logs that a task resize event is ending with [taskSizeUpdate] within a
     * Desktop mode [sessionId].
     */
    fun logTaskResizingEnded(sessionId: Int, taskSizeUpdate: TaskSizeUpdate) {
        if (!Flags.enableResizingMetrics()) return

        ProtoLog.v(
            ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
            "DesktopModeLogger: Logging task resize is ending, session: %s taskId: %s",
            sessionId,
            taskSizeUpdate.instanceId
        )
        logTaskSizeUpdated(
            FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZING_STAGE__END_RESIZING_STAGE,
            sessionId, taskSizeUpdate
        )
    }

    fun logTaskInfoStateInit() {
        logTaskUpdate(
            FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INIT_STATSD,
            /* session_id */ 0,
            TaskUpdate(
                visibleTaskCount = 0,
                instanceId = 0,
                uid = 0,
                taskHeight = 0,
                taskWidth = 0,
                taskX = 0,
                taskY = 0)
        )
    }

    private fun logTaskUpdate(taskEvent: Int, sessionId: Int, taskUpdate: TaskUpdate) {
        FrameworkStatsLog.write(
            DESKTOP_MODE_TASK_UPDATE_ATOM_ID,
            /* task_event */
            taskEvent,
            /* instance_id */
            taskUpdate.instanceId,
            /* uid */
            taskUpdate.uid,
            /* task_height */
            taskUpdate.taskHeight,
            /* task_width */
            taskUpdate.taskWidth,
            /* task_x */
            taskUpdate.taskX,
            /* task_y */
            taskUpdate.taskY,
            /* session_id */
            sessionId,
            taskUpdate.minimizeReason?.reason ?: UNSET_MINIMIZE_REASON,
            taskUpdate.unminimizeReason?.reason ?: UNSET_UNMINIMIZE_REASON,
            /* visible_task_count */
            taskUpdate.visibleTaskCount
        )
        EventLogTags.writeWmShellDesktopModeTaskUpdate(
            /* task_event */
            taskEvent,
            /* instance_id */
            taskUpdate.instanceId,
            /* uid */
            taskUpdate.uid,
            /* task_height */
            taskUpdate.taskHeight,
            /* task_width */
            taskUpdate.taskWidth,
            /* task_x */
            taskUpdate.taskX,
            /* task_y */
            taskUpdate.taskY,
            /* session_id */
            sessionId,
            taskUpdate.minimizeReason?.reason ?: UNSET_MINIMIZE_REASON,
            taskUpdate.unminimizeReason?.reason ?: UNSET_UNMINIMIZE_REASON,
            /* visible_task_count */
            taskUpdate.visibleTaskCount
        )
    }

    private fun logTaskSizeUpdated(
        resizingStage: Int,
        sessionId: Int,
        taskSizeUpdate: TaskSizeUpdate
    ) {
        FrameworkStatsLog.write(
            DESKTOP_MODE_TASK_SIZE_UPDATED_ATOM_ID,
            /* resize_trigger */
            taskSizeUpdate.resizeTrigger?.trigger ?: ResizeTrigger.UNKNOWN_RESIZE_TRIGGER.trigger,
            /* resizing_stage */
            resizingStage,
            /* input_method */
            taskSizeUpdate.inputMethod?.method ?: InputMethod.UNKNOWN_INPUT_METHOD.method,
            /* desktop_mode_session_id */
            sessionId,
            /* instance_id */
            taskSizeUpdate.instanceId,
            /* uid */
            taskSizeUpdate.uid,
            /* task_height */
            taskSizeUpdate.taskHeight,
            /* task_width */
            taskSizeUpdate.taskWidth,
            /* display_area */
            taskSizeUpdate.displayArea
        )
    }

    companion object {
        /**
         * Describes a task position and dimensions.
         *
         * @property instanceId instance id of the task
         * @property uid uid of the app associated with the task
         * @property taskHeight height of the task in px
         * @property taskWidth width of the task in px
         * @property taskX x-coordinate of the top-left corner
         * @property taskY y-coordinate of the top-left corner
         * @property minimizeReason the reason the task was minimized
         * @property unminimizeEvent the reason the task was unminimized
         *
         */
        data class TaskUpdate(
            val instanceId: Int,
            val uid: Int,
            val taskHeight: Int,
            val taskWidth: Int,
            val taskX: Int,
            val taskY: Int,
            val minimizeReason: MinimizeReason? = null,
            val unminimizeReason: UnminimizeReason? = null,
            val visibleTaskCount: Int,
        )

        /**
         * Describes a task size update (resizing, snapping or maximizing to
         * stable bounds).
         *
         * @property resizeTrigger the trigger for task resize
         * @property inputMethod the input method for resizing this task
         * @property instanceId instance id of the task
         * @property uid uid of the app associated with the task
         * @property taskHeight height of the task in dp
         * @property taskWidth width of the task in dp
         * @property displayArea the display size of the screen in dp
         */
        data class TaskSizeUpdate(
            val resizeTrigger: ResizeTrigger? = null,
            val inputMethod: InputMethod? = null,
            val instanceId: Int,
            val uid: Int,
            val taskHeight: Int,
            val taskWidth: Int,
            val displayArea: Int,
        )

        // Default value used when the task was not minimized.
        @VisibleForTesting
        const val UNSET_MINIMIZE_REASON =
            FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE__MINIMIZE_REASON__UNSET_MINIMIZE

        /** The reason a task was minimized. */
        enum class MinimizeReason(val reason: Int) {
            TASK_LIMIT(
                FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__MINIMIZE_REASON__MINIMIZE_TASK_LIMIT
            ),
            MINIMIZE_BUTTON( // TODO(b/356843241): use this enum value
                FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__MINIMIZE_REASON__MINIMIZE_BUTTON
            ),
        }

        // Default value used when the task was not unminimized.
        @VisibleForTesting
        const val UNSET_UNMINIMIZE_REASON =
            FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE__UNMINIMIZE_REASON__UNSET_UNMINIMIZE

        /** The reason a task was unminimized. */
        enum class UnminimizeReason(val reason: Int) {
            UNKNOWN(
                FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__UNMINIMIZE_REASON__UNMINIMIZE_UNKNOWN
            ),
            TASKBAR_TAP(
                FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__UNMINIMIZE_REASON__UNMINIMIZE_TASKBAR_TAP
            ),
            ALT_TAB(
                FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__UNMINIMIZE_REASON__UNMINIMIZE_ALT_TAB
            ),
            TASK_LAUNCH(
                FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__UNMINIMIZE_REASON__UNMINIMIZE_TASK_LAUNCH
            ),
        }

        /**
         * Enum EnterReason mapped to the EnterReason definition in
         * stats/atoms/desktopmode/desktopmode_extensions_atoms.proto
         */
        enum class EnterReason(val reason: Int) {
            UNKNOWN_ENTER(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__UNKNOWN_ENTER),
            OVERVIEW(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__OVERVIEW),
            APP_HANDLE_DRAG(
                FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__APP_HANDLE_DRAG
            ),
            APP_HANDLE_MENU_BUTTON(
                FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__APP_HANDLE_MENU_BUTTON
            ),
            APP_FREEFORM_INTENT(
                FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__APP_FREEFORM_INTENT
            ),
            KEYBOARD_SHORTCUT_ENTER(
                FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__KEYBOARD_SHORTCUT_ENTER
            ),
            SCREEN_ON(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__SCREEN_ON),
            APP_FROM_OVERVIEW(
                FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__APP_FROM_OVERVIEW
            ),
        }

        /**
         * Enum ExitReason mapped to the ExitReason definition in
         * stats/atoms/desktopmode/desktopmode_extensions_atoms.proto
         */
        enum class ExitReason(val reason: Int) {
            UNKNOWN_EXIT(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EXIT_REASON__UNKNOWN_EXIT),
            DRAG_TO_EXIT(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EXIT_REASON__DRAG_TO_EXIT),
            APP_HANDLE_MENU_BUTTON_EXIT(
                FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EXIT_REASON__APP_HANDLE_MENU_BUTTON_EXIT
            ),
            KEYBOARD_SHORTCUT_EXIT(
                FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EXIT_REASON__KEYBOARD_SHORTCUT_EXIT
            ),
            RETURN_HOME_OR_OVERVIEW(
                FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EXIT_REASON__RETURN_HOME_OR_OVERVIEW
            ),
            TASK_FINISHED(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EXIT_REASON__TASK_FINISHED),
            SCREEN_OFF(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EXIT_REASON__SCREEN_OFF)
        }

        /**
         * Enum ResizeTrigger mapped to the ResizeTrigger definition in
         * stats/atoms/desktopmode/desktopmode_extensions_atoms.proto
         */
        enum class ResizeTrigger(val trigger: Int) {
            UNKNOWN_RESIZE_TRIGGER(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__UNKNOWN_RESIZE_TRIGGER
            ),
            CORNER(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__CORNER_RESIZE_TRIGGER
            ),
            EDGE(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__EDGE_RESIZE_TRIGGER
            ),
            TILING_DIVIDER(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__TILING_DIVIDER_RESIZE_TRIGGER
            ),
            MAXIMIZE_BUTTON(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__MAXIMIZE_BUTTON_RESIZE_TRIGGER
            ),
            DOUBLE_TAP_APP_HEADER(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__DOUBLE_TAP_APP_HEADER_RESIZE_TRIGGER
            ),
            DRAG_LEFT(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__DRAG_LEFT_RESIZE_TRIGGER
            ),
            DRAG_RIGHT(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__DRAG_RIGHT_RESIZE_TRIGGER
            ),
            SNAP_LEFT_MENU(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__SNAP_LEFT_MENU_RESIZE_TRIGGER
            ),
            SNAP_RIGHT_MENU(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__SNAP_RIGHT_MENU_RESIZE_TRIGGER
            ),
        }

        /**
         * Enum InputMethod mapped to the InputMethod definition in
         * stats/atoms/desktopmode/desktopmode_extensions_atoms.proto
         */
        enum class InputMethod(val method: Int) {
            UNKNOWN_INPUT_METHOD(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__UNKNOWN_INPUT_METHOD
            ),
            TOUCH(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__TOUCH_INPUT_METHOD
            ),
            STYLUS(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__STYLUS_INPUT_METHOD
            ),
            MOUSE(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__MOUSE_INPUT_METHOD
            ),
            TOUCHPAD(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__TOUCHPAD_INPUT_METHOD
            ),
            KEYBOARD(
                FrameworkStatsLog
                    .DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__KEYBOARD_INPUT_METHOD
            ),
        }

        private const val DESKTOP_MODE_ATOM_ID = FrameworkStatsLog.DESKTOP_MODE_UI_CHANGED
        private const val DESKTOP_MODE_TASK_UPDATE_ATOM_ID =
            FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE
        private const val DESKTOP_MODE_TASK_SIZE_UPDATED_ATOM_ID =
            FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED
    }
}
