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
import android.graphics.Rect
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import com.android.dx.mockito.inline.extended.ExtendedMockito.clearInvocations
import com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn
import com.android.dx.mockito.inline.extended.ExtendedMockito.staticMockMarker
import com.android.dx.mockito.inline.extended.ExtendedMockito.verify
import com.android.dx.mockito.inline.extended.ExtendedMockito.verifyZeroInteractions
import com.android.internal.util.FrameworkStatsLog
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.window.flags.Flags
import com.android.wm.shell.EventLogTags
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.EnterReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ExitReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.InputMethod
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.MinimizeReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.NO_SESSION_ID
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ResizeTrigger
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.TaskSizeUpdate
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.TaskUpdate
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.UNSET_MINIMIZE_REASON
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.UNSET_UNMINIMIZE_REASON
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.UnminimizeReason
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [DesktopModeEventLogger].
 */
class DesktopModeEventLoggerTest : ShellTestCase() {

    private val desktopModeEventLogger = DesktopModeEventLogger()
    val displayController = mock<DisplayController>()
    val displayLayout = mock<DisplayLayout>()

    @JvmField
    @Rule(order = 0)
    val extendedMockitoRule = ExtendedMockitoRule.Builder(this)
        .mockStatic(FrameworkStatsLog::class.java)
        .mockStatic(EventLogTags::class.java).build()!!

    @JvmField
    @Rule(order = 1)
    val setFlagsRule = SetFlagsRule()

    @Before
    fun setUp() {
        doReturn(displayLayout).whenever(displayController).getDisplayLayout(anyInt())
        doReturn(DISPLAY_WIDTH).whenever(displayLayout).width()
        doReturn(DISPLAY_HEIGHT).whenever(displayLayout).height()
    }

    @Test
    fun logSessionEnter_logsEnterReasonWithNewSessionId() {
        desktopModeEventLogger.logSessionEnter(EnterReason.KEYBOARD_SHORTCUT_ENTER)

        val sessionId = desktopModeEventLogger.currentSessionId.get()
        assertThat(sessionId).isNotEqualTo(NO_SESSION_ID)
        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_UI_CHANGED),
                /* event */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EVENT__ENTER),
                /* enter_reason */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__KEYBOARD_SHORTCUT_ENTER),
                /* exit_reason */
                eq(0),
                /* sessionId */
                eq(sessionId)
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verify {
            EventLogTags.writeWmShellEnterDesktopMode(
                eq(EnterReason.KEYBOARD_SHORTCUT_ENTER.reason),
                eq(sessionId)
            )
        }
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logSessionEnter_ongoingSession_logsEnterReasonWithNewSessionId() {
        val previousSessionId = startDesktopModeSession()

        desktopModeEventLogger.logSessionEnter(EnterReason.KEYBOARD_SHORTCUT_ENTER)

        val sessionId = desktopModeEventLogger.currentSessionId.get()
        assertThat(sessionId).isNotEqualTo(NO_SESSION_ID)
        assertThat(sessionId).isNotEqualTo(previousSessionId)
        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_UI_CHANGED),
                /* event */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EVENT__ENTER),
                /* enter_reason */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__ENTER_REASON__KEYBOARD_SHORTCUT_ENTER),
                /* exit_reason */
                eq(0),
                /* sessionId */
                eq(sessionId)
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verify {
            EventLogTags.writeWmShellEnterDesktopMode(
                eq(EnterReason.KEYBOARD_SHORTCUT_ENTER.reason),
                eq(sessionId)
            )
        }
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logSessionExit_noOngoingSession_doesNotLog() {
        desktopModeEventLogger.logSessionExit(ExitReason.DRAG_TO_EXIT)

        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logSessionExit_logsExitReasonAndClearsSessionId() {
        val sessionId = startDesktopModeSession()

        desktopModeEventLogger.logSessionExit(ExitReason.DRAG_TO_EXIT)

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_UI_CHANGED),
                /* event */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EVENT__EXIT),
                /* enter_reason */
                eq(0),
                /* exit_reason */
                eq(FrameworkStatsLog.DESKTOP_MODE_UICHANGED__EXIT_REASON__DRAG_TO_EXIT),
                /* sessionId */
                eq(sessionId)
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verify {
            EventLogTags.writeWmShellExitDesktopMode(
                eq(ExitReason.DRAG_TO_EXIT.reason),
                eq(sessionId)
            )
        }
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
        assertThat(desktopModeEventLogger.currentSessionId.get()).isEqualTo(NO_SESSION_ID)
    }

    @Test
    fun logTaskAdded_noOngoingSession_doesNotLog() {
        desktopModeEventLogger.logTaskAdded(TASK_UPDATE)

        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logTaskAdded_logsTaskUpdate() {
        val sessionId = startDesktopModeSession()

        desktopModeEventLogger.logTaskAdded(TASK_UPDATE)

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
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
                eq(sessionId),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                /* visible_task_count */
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(
                    FrameworkStatsLog
                        .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_ADDED
                ),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(sessionId),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logTaskRemoved_noOngoingSession_doesNotLog() {
        desktopModeEventLogger.logTaskRemoved(TASK_UPDATE)

        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logTaskRemoved_taskUpdate() {
        val sessionId = startDesktopModeSession()

        desktopModeEventLogger.logTaskRemoved(TASK_UPDATE)

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
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
                eq(sessionId),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                /* visible_task_count */
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(
                    FrameworkStatsLog
                        .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_REMOVED
                ),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(sessionId),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logTaskInfoChanged_noOngoingSession_doesNotLog() {
        desktopModeEventLogger.logTaskInfoChanged(TASK_UPDATE)

        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logTaskInfoChanged_taskUpdate() {
        val sessionId = startDesktopModeSession()

        desktopModeEventLogger.logTaskInfoChanged(TASK_UPDATE)

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
                /* task_event */
                eq(
                    FrameworkStatsLog
                        .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED
                ),
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
                eq(sessionId),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                /* visible_task_count */
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(
                    FrameworkStatsLog
                        .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED
                ),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(sessionId),
                eq(UNSET_MINIMIZE_REASON),
                eq(UNSET_UNMINIMIZE_REASON),
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logTaskInfoChanged_logsTaskUpdateWithMinimizeReason() {
        val sessionId = startDesktopModeSession()

        desktopModeEventLogger.logTaskInfoChanged(
            createTaskUpdate(minimizeReason = MinimizeReason.TASK_LIMIT)
        )

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
                /* task_event */
                eq(
                    FrameworkStatsLog
                        .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED
                ),
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
                eq(sessionId),
                /* minimize_reason */
                eq(MinimizeReason.TASK_LIMIT.reason),
                /* unminimize_reason */
                eq(UNSET_UNMINIMIZE_REASON),
                /* visible_task_count */
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(
                    FrameworkStatsLog
                        .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED
                ),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(sessionId),
                eq(MinimizeReason.TASK_LIMIT.reason),
                eq(UNSET_UNMINIMIZE_REASON),
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logTaskInfoChanged_logsTaskUpdateWithUnminimizeReason() {
        val sessionId = startDesktopModeSession()

        desktopModeEventLogger.logTaskInfoChanged(
            createTaskUpdate(unminimizeReason = UnminimizeReason.TASKBAR_TAP)
        )

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_SESSION_TASK_UPDATE),
                /* task_event */
                eq(
                    FrameworkStatsLog
                        .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED
                ),
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
                eq(sessionId),
                /* minimize_reason */
                eq(UNSET_MINIMIZE_REASON),
                /* unminimize_reason */
                eq(UnminimizeReason.TASKBAR_TAP.reason),
                /* visible_task_count */
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verify {
            EventLogTags.writeWmShellDesktopModeTaskUpdate(
                eq(
                    FrameworkStatsLog
                        .DESKTOP_MODE_SESSION_TASK_UPDATE__TASK_EVENT__TASK_INFO_CHANGED
                ),
                eq(TASK_UPDATE.instanceId),
                eq(TASK_UPDATE.uid),
                eq(TASK_UPDATE.taskHeight),
                eq(TASK_UPDATE.taskWidth),
                eq(TASK_UPDATE.taskX),
                eq(TASK_UPDATE.taskY),
                eq(sessionId),
                eq(UNSET_MINIMIZE_REASON),
                eq(UnminimizeReason.TASKBAR_TAP.reason),
                eq(TASK_COUNT)
            )
        }
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    fun logTaskResizingStarted_noOngoingSession_doesNotLog() {
        desktopModeEventLogger.logTaskResizingStarted(ResizeTrigger.CORNER,
            InputMethod.UNKNOWN_INPUT_METHOD, createTaskInfo())

        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESIZING_METRICS)
    fun logTaskResizingStarted_logsTaskSizeUpdatedWithStartResizingStage() {
        val sessionId = startDesktopModeSession()

        desktopModeEventLogger.logTaskResizingStarted(ResizeTrigger.CORNER,
            InputMethod.UNKNOWN_INPUT_METHOD, createTaskInfo(), TASK_SIZE_UPDATE.taskWidth,
            TASK_SIZE_UPDATE.taskHeight, displayController)

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED),
                /* resize_trigger */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__CORNER_RESIZE_TRIGGER),
                /* resizing_stage */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZING_STAGE__START_RESIZING_STAGE),
                /* input_method */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__UNKNOWN_INPUT_METHOD),
                /* desktop_mode_session_id */
                eq(sessionId),
                /* instance_id */
                eq(TASK_SIZE_UPDATE.instanceId),
                /* uid */
                eq(TASK_SIZE_UPDATE.uid),
                /* task_width */
                eq(TASK_SIZE_UPDATE.taskWidth),
                /* task_height */
                eq(TASK_SIZE_UPDATE.taskHeight),
                /* display_area */
                eq(DISPLAY_AREA),
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
    }

    @Test
    fun logTaskResizingEnded_noOngoingSession_doesNotLog() {
        desktopModeEventLogger.logTaskResizingEnded(ResizeTrigger.CORNER,
            InputMethod.UNKNOWN_INPUT_METHOD, createTaskInfo())

        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
        verifyZeroInteractions(staticMockMarker(EventLogTags::class.java))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESIZING_METRICS)
    fun logTaskResizingEnded_logsTaskSizeUpdatedWithEndResizingStage() {
        val sessionId = startDesktopModeSession()

        desktopModeEventLogger.logTaskResizingEnded(ResizeTrigger.CORNER,
            InputMethod.UNKNOWN_INPUT_METHOD, createTaskInfo(), displayController = displayController)

        verify {
            FrameworkStatsLog.write(
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED),
                /* resize_trigger */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZE_TRIGGER__CORNER_RESIZE_TRIGGER),
                /* resizing_stage */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__RESIZING_STAGE__END_RESIZING_STAGE),
                /* input_method */
                eq(FrameworkStatsLog.DESKTOP_MODE_TASK_SIZE_UPDATED__INPUT_METHOD__UNKNOWN_INPUT_METHOD),
                /* desktop_mode_session_id */
                eq(sessionId),
                /* instance_id */
                eq(TASK_SIZE_UPDATE.instanceId),
                /* uid */
                eq(TASK_SIZE_UPDATE.uid),
                /* task_width */
                eq(TASK_SIZE_UPDATE.taskWidth),
                /* task_height */
                eq(TASK_SIZE_UPDATE.taskHeight),
                /* display_area */
                eq(DISPLAY_AREA),
            )
        }
        verifyZeroInteractions(staticMockMarker(FrameworkStatsLog::class.java))
    }

    private fun startDesktopModeSession(): Int {
        desktopModeEventLogger.logSessionEnter(EnterReason.KEYBOARD_SHORTCUT_ENTER)
        clearInvocations(staticMockMarker(FrameworkStatsLog::class.java))
        clearInvocations(staticMockMarker(EventLogTags::class.java))
        return desktopModeEventLogger.currentSessionId.get()
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

    private fun createTaskInfo(): RunningTaskInfo {
        return TestRunningTaskInfoBuilder().setTaskId(TASK_ID)
            .setUid(TASK_UID)
            .setBounds(Rect(TASK_X, TASK_Y, TASK_WIDTH, TASK_HEIGHT))
            .build()
    }

    private companion object {
        private const val TASK_ID = 1
        private const val TASK_UID = 1
        private const val TASK_X = 0
        private const val TASK_Y = 0
        private const val TASK_HEIGHT = 100
        private const val TASK_WIDTH = 100
        private const val TASK_COUNT = 1
        private const val DISPLAY_WIDTH = 500
        private const val DISPLAY_HEIGHT = 500
        private const val DISPLAY_AREA = DISPLAY_HEIGHT * DISPLAY_WIDTH

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

        private fun createTaskUpdate(
            minimizeReason: MinimizeReason? = null,
            unminimizeReason: UnminimizeReason? = null,
        ) = TaskUpdate(
            TASK_ID, TASK_UID, TASK_HEIGHT, TASK_WIDTH, TASK_X, TASK_Y, minimizeReason,
            unminimizeReason, TASK_COUNT
        )
    }
}
