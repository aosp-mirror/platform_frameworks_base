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

import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import com.android.dx.mockito.inline.extended.ExtendedMockito.verify
import com.android.internal.util.FrameworkStatsLog
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.window.flags.Flags
import com.android.wm.shell.EventLogTags
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.EnterReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ExitReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.MinimizeReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ResizeTrigger
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.InputMethod
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.TaskUpdate
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.TaskSizeUpdate
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.UnminimizeReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.UNSET_MINIMIZE_REASON
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.UNSET_UNMINIMIZE_REASON
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.eq

/**
 * Tests for [DesktopModeEventLogger].
 */
class DesktopModeEventLoggerTest : ShellTestCase() {

    private val desktopModeEventLogger = DesktopModeEventLogger()

    @JvmField
    @Rule(order = 0)
    val extendedMockitoRule = ExtendedMockitoRule.Builder(this)
            .mockStatic(FrameworkStatsLog::class.java)
            .mockStatic(EventLogTags::class.java).build()!!

    @JvmField
    @Rule(order = 1)
    val setFlagsRule = SetFlagsRule()

    @Test
    fun logSessionEnter_enterReason() = runBlocking {
        desktopModeEventLogger.logSessionEnter(sessionId = SESSION_ID, EnterReason.UNKNOWN_ENTER)

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_UI_CHANGED),
                /* event */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EVENT__ENTER),
                /* enter_reason */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__UNKNOWN_ENTER),
                /* exit_reason */
                eq(0),
                /* sessionId */
                eq(SESSION_ID)
            )
        }
        verify {
            EventLogTags.writeWmShellEnterDesktopMode(
                eq(EnterReason.UNKNOWN_ENTER.reason),
                eq(SESSION_ID))
        }
    }

    @Test
    fun logSessionExit_exitReason() = runBlocking {
        desktopModeEventLogger.logSessionExit(sessionId = SESSION_ID, ExitReason.UNKNOWN_EXIT)

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_UI_CHANGED),
                /* event */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EVENT__EXIT),
                /* enter_reason */
                eq(0),
                /* exit_reason */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EXIT_REASON__UNKNOWN_EXIT),
                /* sessionId */
                eq(SESSION_ID)
            )
        }
        verify {
            EventLogTags.writeWmShellExitDesktopMode(
                eq(ExitReason.UNKNOWN_EXIT.reason),
                eq(SESSION_ID))
        }
    }

    @Test
    fun logTaskAdded_taskUpdate() = runBlocking {
        desktopModeEventLogger.logTaskAdded(sessionId = SESSION_ID, TASK_UPDATE)

        verify {
            FrameworkStatsLog.write(eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
                /* task_event */
                eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_ADDED),
                /* instance_id */
                eq(TASK_UPDATE.instanceId),
                /* uid */
                eq(TASK_UPDATE.uid),
                /* task_height */
                eq(TASK_UPDATE.taskHeight),
                /* task_width */
                eq(TASK_UPDATE.taskWidth),
                /* task_x */
                eq(TASK_UPDATE.taskX),
                /* task_y */
                eq(TASK_UPDATE.taskY),
                /* session_id */
                eq(SESSION_ID),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                /* visible_task_count */
                eq(TASK_COUNT))
        }

        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_ADDED),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(SESSION_ID),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                eq(TASK_COUNT))
        }
    }

    @Test
    fun logTaskRemoved_taskUpdate() = runBlocking {
        desktopModeEventLogger.logTaskRemoved(sessionId = SESSION_ID, TASK_UPDATE)

        verify {
            FrameworkStatsLog.write(eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
                /* task_event */
                eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_REMOVED),
                /* instance_id */
                eq(TASK_UPDATE.instanceId),
                /* uid */
                eq(TASK_UPDATE.uid),
                /* task_height */
                eq(TASK_UPDATE.taskHeight),
                /* task_width */
                eq(TASK_UPDATE.taskWidth),
                /* task_x */
                eq(TASK_UPDATE.taskX),
                /* task_y */
                eq(TASK_UPDATE.taskY),
                /* session_id */
                eq(SESSION_ID),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                /* visible_task_count */
                eq(TASK_COUNT))
        }

        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_REMOVED),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(SESSION_ID),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                eq(TASK_COUNT))
        }
    }

    @Test
    fun logTaskInfoChanged_taskUpdate() = runBlocking {
        desktopModeEventLogger.logTaskInfoChanged(sessionId = SESSION_ID, TASK_UPDATE)

        verify {
            FrameworkStatsLog.write(eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
                /* task_event */
                eq(FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED),
                /* instance_id */
                eq(TASK_UPDATE.instanceId),
                /* uid */
                eq(TASK_UPDATE.uid),
                /* task_height */
                eq(TASK_UPDATE.taskHeight),
                /* task_width */
                eq(TASK_UPDATE.taskWidth),
                /* task_x */
                eq(TASK_UPDATE.taskX),
                /* task_y */
                eq(TASK_UPDATE.taskY),
                /* session_id */
                eq(SESSION_ID),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                /* visible_task_count */
                eq(TASK_COUNT))
        }

        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(SESSION_ID),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                eq(TASK_COUNT))
        }
    }

    @Test
    fun logTaskInfoChanged_logsTaskUpdateWithMinimizeReason() = runBlocking {
        desktopModeEventLogger.logTaskInfoChanged(sessionId = SESSION_ID,
            createTaskUpdate(minimizeReason = MinimizeReason.TASK_LIMIT))

        verify {
            FrameworkStatsLog.write(eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
                /* task_event */
                eq(FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED),
                /* instance_id */
                eq(TASK_UPDATE.instanceId),
                /* uid */
                eq(TASK_UPDATE.uid),
                /* task_height */
                eq(TASK_UPDATE.taskHeight),
                /* task_width */
                eq(TASK_UPDATE.taskWidth),
                /* task_x */
                eq(TASK_UPDATE.taskX),
                /* task_y */
                eq(TASK_UPDATE.taskY),
                /* session_id */
                eq(SESSION_ID),
                /* minimize_reason */
                eq(MinimizeReason.TASK_LIMIT.reason),
                /* unminimize_reason */
                eq(UNSET_UNMINIMIZE_REASON),
                /* visible_task_count */
                eq(TASK_COUNT))
        }

        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(SESSION_ID),
                eq(MinimizeReason.TASK_LIMIT.reason),
                eq(UNSET_UNMINIMIZE_REASON),
                eq(TASK_COUNT))
        }
    }

    @Test
    fun logTaskInfoChanged_logsTaskUpdateWithUnminimizeReason() = runBlocking {
        desktopModeEventLogger.logTaskInfoChanged(sessionId = SESSION_ID,
            createTaskUpdate(unminimizeReason = UnminimizeReason.TASKBAR_TAP))

        verify {
            FrameworkStatsLog.write(eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
                /* task_event */
                eq(FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED),
                /* instance_id */
                eq(TASK_UPDATE.instanceId),
                /* uid */
                eq(TASK_UPDATE.uid),
                /* task_height */
                eq(TASK_UPDATE.taskHeight),
                /* task_width */
                eq(TASK_UPDATE.taskWidth),
                /* task_x */
                eq(TASK_UPDATE.taskX),
                /* task_y */
                eq(TASK_UPDATE.taskY),
                /* session_id */
                eq(SESSION_ID),
                /* minimize_reason */
                eq(UNSET_MINIMIZE_REASON),
                /* unminimize_reason */
                eq(UnminimizeReason.TASKBAR_TAP.reason),
                /* visible_task_count */
                eq(TASK_COUNT))
        }

        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(FrameworkStatsLog
                    .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(SESSION_ID),
                eq(UNSET_MINIMIZE_REASON),
                eq(UnminimizeReason.TASKBAR_TAP.reason),
                eq(TASK_COUNT))
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESIZING_METRICS)
    fun logTaskResizingStarted_logsTaskSizeUpdatedWithStartResizingStage() = runBlocking {
        desktopModeEventLogger.logTaskResizingStarted(sessionId = SESSION_ID, createTaskSizeUpdate())

        verify {
            FrameworkStatsLog.write(eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED),
                /* resize_trigger */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__UNKNOWN_RESIZE_TRIGGER),
                /* resizing_stage */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZING_STAGE__START_RESIZING_STAGE),
                /* input_method */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__UNKNOWN_INPUT_METHOD),
                /* desktop_mode_session_id */
                eq(SESSION_ID),
                /* instance_id */
                eq(TASK_SIZE_UPDATE.instanceId),
                /* uid */
                eq(TASK_SIZE_UPDATE.uid),
                /* task_height */
                eq(TASK_SIZE_UPDATE.taskHeight),
                /* task_width */
                eq(TASK_SIZE_UPDATE.taskWidth),
                /* display_area */
                eq(TASK_SIZE_UPDATE.displayArea),
            )
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESIZING_METRICS)
    fun logTaskResizingEnded_logsTaskSizeUpdatedWithEndResizingStage() = runBlocking {
        desktopModeEventLogger.logTaskResizingEnded(sessionId = SESSION_ID, createTaskSizeUpdate())

        verify {
            FrameworkStatsLog.write(eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED),
                /* resize_trigger */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__UNKNOWN_RESIZE_TRIGGER),
                /* resizing_stage */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZING_STAGE__END_RESIZING_STAGE),
                /* input_method */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__UNKNOWN_INPUT_METHOD),
                /* desktop_mode_session_id */
                eq(SESSION_ID),
                /* instance_id */
                eq(TASK_SIZE_UPDATE.instanceId),
                /* uid */
                eq(TASK_SIZE_UPDATE.uid),
                /* task_height */
                eq(TASK_SIZE_UPDATE.taskHeight),
                /* task_width */
                eq(TASK_SIZE_UPDATE.taskWidth),
                /* display_area */
                eq(TASK_SIZE_UPDATE.displayArea),
            )
        }
    }

    @Test
    fun logTaskInfoStateInit_logsTaskInfoChangedStateInit() {
        desktopModeEventLogger.logTaskInfoStateInit()
        verify {
            FrameworkStatsLog.write(eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
                /* task_event */
                eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INIT_STATSD),
                /* instance_id */
                eq(0),
                /* uid */
                eq(0),
                /* task_height */
                eq(0),
                /* task_width */
                eq(0),
                /* task_x */
                eq(0),
                /* task_y */
                eq(0),
                /* session_id */
                eq(0),
                /* minimize_reason */
                eq(UNSET_MINIMIZE_REASON),
                /* unminimize_reason */
                eq(UNSET_UNMINIMIZE_REASON),
                /* visible_task_count */
                eq(0)
            )
        }
    }

    private companion object {
        private const val SESSION_ID = 1
        private const val TASK_ID = 1
        private const val TASK_UID = 1
        private const val TASK_X = 0
        private const val TASK_Y = 0
        private const val TASK_HEIGHT = 100
        private const val TASK_WIDTH = 100
        private const val TASK_COUNT = 1
        private const val DISPLAY_AREA = 1000

        private val TASK_UPDATE = TaskUpdate(
            TASK_ID, TASK_UID, TASK_HEIGHT, TASK_WIDTH, TASK_X, TASK_Y,
            visibleTaskCount = TASK_COUNT,
        )

        private val TASK_SIZE_UPDATE = TaskSizeUpdate(
            resizeTrigger = ResizeTrigger.UNKNOWN_RESIZE_TRIGGER,
            inputMethod = InputMethod.UNKNOWN_INPUT_METHOD,
            TASK_ID,
            TASK_UID,
            TASK_HEIGHT,
            TASK_WIDTH,
            DISPLAY_AREA,
        )

        private fun createTaskSizeUpdate(
            resizeTrigger: ResizeTrigger = ResizeTrigger.UNKNOWN_RESIZE_TRIGGER,
            inputMethod: InputMethod = InputMethod.UNKNOWN_INPUT_METHOD,
        ) = TaskSizeUpdate(
            resizeTrigger,
            inputMethod,
            TASK_ID,
            TASK_UID,
            TASK_HEIGHT,
            TASK_WIDTH,
            DISPLAY_AREA,
        )

        private fun createTaskUpdate(
            minimizeReason: MinimizeReason? = null,
            unminimizeReason: UnminimizeReason? = null,
        ) = TaskUpdate(TASK_ID, TASK_UID, TASK_HEIGHT, TASK_WIDTH, TASK_X, TASK_Y, minimizeReason,
            unminimizeReason, TASK_COUNT)
    }
}
