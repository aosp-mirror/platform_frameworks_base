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

import android.app.ActivityManager
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.IBinder
import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS
import android.view.WindowManager.TRANSIT_NONE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_SLEEP
import android.view.WindowManager.TRANSIT_TO_BACK
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.view.WindowManager.TRANSIT_WAKE
import android.window.IWindowContainerToken
import android.window.TransitionInfo
import android.window.TransitionInfo.Change
import android.window.WindowContainerToken
import androidx.test.filters.SmallTest
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.EnterReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ExitReason
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.TaskUpdate
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.TRANSIT_ENTER_DESKTOP_FROM_APP_FROM_OVERVIEW
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.TRANSIT_ENTER_DESKTOP_FROM_APP_HANDLE_MENU_BUTTON
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.TRANSIT_ENTER_DESKTOP_FROM_KEYBOARD_SHORTCUT
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.TRANSIT_ENTER_DESKTOP_FROM_UNKNOWN
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.TRANSIT_EXIT_DESKTOP_MODE_HANDLE_MENU_BUTTON
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.TRANSIT_EXIT_DESKTOP_MODE_KEYBOARD_SHORTCUT
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.TRANSIT_EXIT_DESKTOP_MODE_TASK_DRAG
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.TRANSIT_EXIT_DESKTOP_MODE_UNKNOWN
import com.android.wm.shell.shared.DesktopModeStatus
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.TransitionInfoBuilder
import com.android.wm.shell.transition.Transitions
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyZeroInteractions
import org.mockito.kotlin.whenever

/**
 * Test class for {@link DesktopModeLoggerTransitionObserver}
 *
 * Usage: atest WMShellUnitTests:DesktopModeLoggerTransitionObserverTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopModeLoggerTransitionObserverTest : ShellTestCase() {

  @JvmField
  @Rule
  val extendedMockitoRule =
      ExtendedMockitoRule.Builder(this).mockStatic(DesktopModeStatus::class.java).build()!!

  private val testExecutor = mock<ShellExecutor>()
  private val mockShellInit = mock<ShellInit>()
  private val transitions = mock<Transitions>()
  private val context = mock<Context>()

  private lateinit var transitionObserver: DesktopModeLoggerTransitionObserver
  private lateinit var shellInit: ShellInit
  private lateinit var desktopModeEventLogger: DesktopModeEventLogger

  @Before
  fun setup() {
    whenever(DesktopModeStatus.canEnterDesktopMode(any())).thenReturn(true)
    shellInit = spy(ShellInit(testExecutor))
    desktopModeEventLogger = mock<DesktopModeEventLogger>()

    transitionObserver =
        DesktopModeLoggerTransitionObserver(
            context, mockShellInit, transitions, desktopModeEventLogger)
    if (Transitions.ENABLE_SHELL_TRANSITIONS) {
      val initRunnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
      verify(mockShellInit).addInitCallback(initRunnableCaptor.capture(), same(transitionObserver))
      initRunnableCaptor.value.run()
    } else {
      transitionObserver.onInit()
    }
  }

  @Test
  fun testRegistersObserverAtInit() {
    verify(transitions).registerObserver(same(transitionObserver))
  }

  @Test
  fun transitOpen_notFreeformWindow_doesNotLogTaskAddedOrSessionEnter() {
    val change = createChange(TRANSIT_OPEN, createTaskInfo(WINDOWING_MODE_FULLSCREEN))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change).build()

    callOnTransitionReady(transitionInfo)

    verify(desktopModeEventLogger, never()).logSessionEnter(any(), any())
    verify(desktopModeEventLogger, never()).logTaskAdded(any(), any())
  }

  @Test
  fun transitOpen_logTaskAddedAndEnterReasonAppFreeformIntent() {
    val change = createChange(TRANSIT_OPEN, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change).build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.APP_FREEFORM_INTENT, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitEndDragToDesktop_logTaskAddedAndEnterReasonAppHandleDrag() {
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    // task change is finalised when drag ends
    val transitionInfo =
        TransitionInfoBuilder(Transitions.TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP, 0)
            .addChange(change)
            .build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.APP_HANDLE_DRAG, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitEnterDesktopByButtonTap_logTaskAddedAndEnterReasonButtonTap() {
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_ENTER_DESKTOP_FROM_APP_HANDLE_MENU_BUTTON, 0)
            .addChange(change)
            .build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.APP_HANDLE_MENU_BUTTON, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitEnterDesktopFromAppFromOverview_logTaskAddedAndEnterReasonAppFromOverview() {
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_ENTER_DESKTOP_FROM_APP_FROM_OVERVIEW, 0)
            .addChange(change)
            .build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.APP_FROM_OVERVIEW, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitEnterDesktopFromKeyboardShortcut_logTaskAddedAndEnterReasonKeyboardShortcut() {
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_ENTER_DESKTOP_FROM_KEYBOARD_SHORTCUT, 0)
            .addChange(change)
            .build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.KEYBOARD_SHORTCUT_ENTER, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitToFront_logTaskAddedAndEnterReasonOverview() {
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_TO_FRONT, 0).addChange(change).build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.OVERVIEW, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitToFront_previousTransitionExitToOverview_logTaskAddedAndEnterReasonOverview() {
    // previous exit to overview transition
    val previousSessionId = 1
    // add a freeform task
    val previousTaskInfo = createTaskInfo(WINDOWING_MODE_FREEFORM)
    transitionObserver.addTaskInfosToCachedMap(previousTaskInfo)
    transitionObserver.setLoggerSessionId(previousSessionId)
    val previousTransitionInfo =
        TransitionInfoBuilder(TRANSIT_TO_FRONT, TRANSIT_FLAG_IS_RECENTS)
            .addChange(createChange(TRANSIT_TO_BACK, previousTaskInfo))
            .build()

    callOnTransitionReady(previousTransitionInfo)

    verifyTaskRemovedAndExitLogging(
        previousSessionId, ExitReason.RETURN_HOME_OR_OVERVIEW, DEFAULT_TASK_UPDATE)

    // Enter desktop mode from cancelled recents has no transition. Enter is detected on the
    // next transition involving freeform windows

    // TRANSIT_TO_FRONT
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_TO_FRONT, 0).addChange(change).build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.OVERVIEW, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitChange_previousTransitionExitToOverview_logTaskAddedAndEnterReasonOverview() {
    // previous exit to overview transition
    val previousSessionId = 1
    // add a freeform task
    val previousTaskInfo = createTaskInfo(WINDOWING_MODE_FREEFORM)
    transitionObserver.addTaskInfosToCachedMap(previousTaskInfo)
    transitionObserver.setLoggerSessionId(previousSessionId)
    val previousTransitionInfo =
        TransitionInfoBuilder(TRANSIT_TO_FRONT, TRANSIT_FLAG_IS_RECENTS)
            .addChange(createChange(TRANSIT_TO_BACK, previousTaskInfo))
            .build()

    callOnTransitionReady(previousTransitionInfo)

    verifyTaskRemovedAndExitLogging(
        previousSessionId, ExitReason.RETURN_HOME_OR_OVERVIEW, DEFAULT_TASK_UPDATE)

    // Enter desktop mode from cancelled recents has no transition. Enter is detected on the
    // next transition involving freeform windows

    // TRANSIT_CHANGE
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_CHANGE, 0).addChange(change).build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.OVERVIEW, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitOpen_previousTransitionExitToOverview_logTaskAddedAndEnterReasonOverview() {
    // previous exit to overview transition
    val previousSessionId = 1
    // add a freeform task
    val previousTaskInfo = createTaskInfo(WINDOWING_MODE_FREEFORM)
    transitionObserver.addTaskInfosToCachedMap(previousTaskInfo)
    transitionObserver.setLoggerSessionId(previousSessionId)
    val previousTransitionInfo =
        TransitionInfoBuilder(TRANSIT_TO_FRONT, TRANSIT_FLAG_IS_RECENTS)
            .addChange(createChange(TRANSIT_TO_BACK, previousTaskInfo))
            .build()

    callOnTransitionReady(previousTransitionInfo)

    verifyTaskRemovedAndExitLogging(
        previousSessionId, ExitReason.RETURN_HOME_OR_OVERVIEW, DEFAULT_TASK_UPDATE)

    // Enter desktop mode from cancelled recents has no transition. Enter is detected on the
    // next transition involving freeform windows

    // TRANSIT_OPEN
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change).build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.OVERVIEW, DEFAULT_TASK_UPDATE)
  }

  @Test
  @Suppress("ktlint:standard:max-line-length")
  fun transitEnterDesktopFromAppFromOverview_previousTransitionExitToOverview_logTaskAddedAndEnterReasonAppFromOverview() {
    // Tests for AppFromOverview precedence in compared to cancelled Overview

    // previous exit to overview transition
    val previousSessionId = 1
    // add a freeform task
    val previousTaskInfo = createTaskInfo(WINDOWING_MODE_FREEFORM)
    transitionObserver.addTaskInfosToCachedMap(previousTaskInfo)
    transitionObserver.setLoggerSessionId(previousSessionId)
    val previousTransitionInfo =
        TransitionInfoBuilder(TRANSIT_TO_FRONT, TRANSIT_FLAG_IS_RECENTS)
            .addChange(createChange(TRANSIT_TO_BACK, previousTaskInfo))
            .build()

    callOnTransitionReady(previousTransitionInfo)

    verifyTaskRemovedAndExitLogging(
        previousSessionId, ExitReason.RETURN_HOME_OR_OVERVIEW, DEFAULT_TASK_UPDATE)

    // TRANSIT_ENTER_DESKTOP_FROM_APP_FROM_OVERVIEW
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_ENTER_DESKTOP_FROM_APP_FROM_OVERVIEW, 0)
            .addChange(change)
            .build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.APP_FROM_OVERVIEW, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitEnterDesktopFromUnknown_logTaskAddedAndEnterReasonUnknown() {
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_ENTER_DESKTOP_FROM_UNKNOWN, 0).addChange(change).build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.UNKNOWN_ENTER, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitWake_logTaskAddedAndEnterReasonScreenOn() {
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_WAKE, 0).addChange(change).build()

    callOnTransitionReady(transitionInfo)

    verifyTaskAddedAndEnterLogging(EnterReason.SCREEN_ON, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitSleep_logTaskRemovedAndExitReasonScreenOff_sessionIdNull() {
    val sessionId = 1
    // add a freeform task
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM))
    transitionObserver.setLoggerSessionId(sessionId)

    val transitionInfo = TransitionInfoBuilder(TRANSIT_SLEEP).build()
    callOnTransitionReady(transitionInfo)

    verifyTaskRemovedAndExitLogging(sessionId, ExitReason.SCREEN_OFF, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitExitDesktopTaskDrag_logTaskRemovedAndExitReasonDragToExit_sessionIdNull() {
    val sessionId = 1
    // add a freeform task
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM))
    transitionObserver.setLoggerSessionId(sessionId)

    // window mode changing from FREEFORM to FULLSCREEN
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FULLSCREEN))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_EXIT_DESKTOP_MODE_TASK_DRAG).addChange(change).build()
    callOnTransitionReady(transitionInfo)

    verifyTaskRemovedAndExitLogging(sessionId, ExitReason.DRAG_TO_EXIT, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitExitDesktopAppHandleButton_logTaskRemovedAndExitReasonButton_sessionIdNull() {
    val sessionId = 1
    // add a freeform task
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM))
    transitionObserver.setLoggerSessionId(sessionId)

    // window mode changing from FREEFORM to FULLSCREEN
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FULLSCREEN))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_EXIT_DESKTOP_MODE_HANDLE_MENU_BUTTON)
            .addChange(change)
            .build()
    callOnTransitionReady(transitionInfo)

    verifyTaskRemovedAndExitLogging(
        sessionId, ExitReason.APP_HANDLE_MENU_BUTTON_EXIT, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitExitDesktopUsingKeyboard_logTaskRemovedAndExitReasonKeyboard_sessionIdNull() {
    val sessionId = 1
    // add a freeform task
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM))
    transitionObserver.setLoggerSessionId(sessionId)

    // window mode changing from FREEFORM to FULLSCREEN
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FULLSCREEN))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_EXIT_DESKTOP_MODE_KEYBOARD_SHORTCUT).addChange(change).build()
    callOnTransitionReady(transitionInfo)

    verifyTaskRemovedAndExitLogging(
        sessionId, ExitReason.KEYBOARD_SHORTCUT_EXIT, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitExitDesktopUnknown_logTaskRemovedAndExitReasonUnknown_sessionIdNull() {
    val sessionId = 1
    // add a freeform task
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM))
    transitionObserver.setLoggerSessionId(sessionId)

    // window mode changing from FREEFORM to FULLSCREEN
    val change = createChange(TRANSIT_TO_FRONT, createTaskInfo(WINDOWING_MODE_FULLSCREEN))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_EXIT_DESKTOP_MODE_UNKNOWN).addChange(change).build()
    callOnTransitionReady(transitionInfo)

    verifyTaskRemovedAndExitLogging(sessionId, ExitReason.UNKNOWN_EXIT, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitToFrontWithFlagRecents_logTaskRemovedAndExitReasonOverview_sessionIdNull() {
    val sessionId = 1
    // add a freeform task
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM))
    transitionObserver.setLoggerSessionId(sessionId)

    // recents transition
    val change = createChange(TRANSIT_TO_BACK, createTaskInfo(WINDOWING_MODE_FREEFORM))
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_TO_FRONT, TRANSIT_FLAG_IS_RECENTS).addChange(change).build()
    callOnTransitionReady(transitionInfo)

    verifyTaskRemovedAndExitLogging(
        sessionId, ExitReason.RETURN_HOME_OR_OVERVIEW, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun transitClose_logTaskRemovedAndExitReasonTaskFinished_sessionIdNull() {
    val sessionId = 1
    // add a freeform task
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM))
    transitionObserver.setLoggerSessionId(sessionId)

    // task closing
    val change = createChange(TRANSIT_CLOSE, createTaskInfo(WINDOWING_MODE_FULLSCREEN))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_CLOSE).addChange(change).build()
    callOnTransitionReady(transitionInfo)

    verifyTaskRemovedAndExitLogging(sessionId, ExitReason.TASK_FINISHED, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun sessionExitByRecents_cancelledAnimation_sessionRestored() {
    val sessionId = 1
    // add a freeform task to an existing session
    val taskInfo = createTaskInfo(WINDOWING_MODE_FREEFORM)
    transitionObserver.addTaskInfosToCachedMap(taskInfo)
    transitionObserver.setLoggerSessionId(sessionId)

    // recents transition sent freeform window to back
    val change = createChange(TRANSIT_TO_BACK, taskInfo)
    val transitionInfo1 =
        TransitionInfoBuilder(TRANSIT_TO_FRONT, TRANSIT_FLAG_IS_RECENTS).addChange(change).build()
    callOnTransitionReady(transitionInfo1)

    verifyTaskRemovedAndExitLogging(
        sessionId, ExitReason.RETURN_HOME_OR_OVERVIEW, DEFAULT_TASK_UPDATE)

    val transitionInfo2 = TransitionInfoBuilder(TRANSIT_NONE).build()
    callOnTransitionReady(transitionInfo2)

    verifyTaskAddedAndEnterLogging(EnterReason.OVERVIEW, DEFAULT_TASK_UPDATE)
  }

  @Test
  fun sessionAlreadyStarted_newFreeformTaskAdded_logsTaskAdded() {
    val sessionId = 1
    // add an existing freeform task
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM))
    transitionObserver.setLoggerSessionId(sessionId)

    // new freeform task added
    val change = createChange(TRANSIT_OPEN, createTaskInfo(WINDOWING_MODE_FREEFORM, id = 2))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change).build()
    callOnTransitionReady(transitionInfo)

    verify(desktopModeEventLogger, times(1))
        .logTaskAdded(eq(sessionId), eq(DEFAULT_TASK_UPDATE.copy(instanceId = 2)))
    verify(desktopModeEventLogger, never()).logSessionEnter(any(), any())
  }

  @Test
  fun sessionAlreadyStarted_taskPositionChanged_logsTaskUpdate() {
    val sessionId = 1
    // add an existing freeform task
    val taskInfo = createTaskInfo(WINDOWING_MODE_FREEFORM)
    transitionObserver.addTaskInfosToCachedMap(taskInfo)
    transitionObserver.setLoggerSessionId(sessionId)

    // task position changed
    val newTaskInfo = createTaskInfo(WINDOWING_MODE_FREEFORM, taskX = DEFAULT_TASK_X + 100)
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_CHANGE, 0)
            .addChange(createChange(TRANSIT_CHANGE, newTaskInfo))
            .build()
    callOnTransitionReady(transitionInfo)

    verify(desktopModeEventLogger, times(1))
        .logTaskInfoChanged(
            eq(sessionId), eq(DEFAULT_TASK_UPDATE.copy(taskX = DEFAULT_TASK_X + 100)))
    verifyZeroInteractions(desktopModeEventLogger)
  }

  @Test
  fun sessionAlreadyStarted_taskResized_logsTaskUpdate() {
    val sessionId = 1
    // add an existing freeform task
    val taskInfo = createTaskInfo(WINDOWING_MODE_FREEFORM)
    transitionObserver.addTaskInfosToCachedMap(taskInfo)
    transitionObserver.setLoggerSessionId(sessionId)

    // task resized
    val newTaskInfo =
        createTaskInfo(
            WINDOWING_MODE_FREEFORM,
            taskWidth = DEFAULT_TASK_WIDTH + 100,
            taskHeight = DEFAULT_TASK_HEIGHT - 100)
    val transitionInfo =
        TransitionInfoBuilder(TRANSIT_CHANGE, 0)
            .addChange(createChange(TRANSIT_CHANGE, newTaskInfo))
            .build()
    callOnTransitionReady(transitionInfo)

    verify(desktopModeEventLogger, times(1))
        .logTaskInfoChanged(
            eq(sessionId),
            eq(
                DEFAULT_TASK_UPDATE.copy(
                    taskWidth = DEFAULT_TASK_WIDTH + 100, taskHeight = DEFAULT_TASK_HEIGHT - 100)))
    verifyZeroInteractions(desktopModeEventLogger)
  }

  @Test
  fun sessionAlreadyStarted_multipleTasksUpdated_logsTaskUpdateForCorrectTask() {
    val sessionId = 1
    // add 2 existing freeform task
    val taskInfo1 = createTaskInfo(WINDOWING_MODE_FREEFORM)
    val taskInfo2 = createTaskInfo(WINDOWING_MODE_FREEFORM, id = 2)
    transitionObserver.addTaskInfosToCachedMap(taskInfo1)
    transitionObserver.addTaskInfosToCachedMap(taskInfo2)
    transitionObserver.setLoggerSessionId(sessionId)

    // task 1 position update
    val newTaskInfo1 = createTaskInfo(WINDOWING_MODE_FREEFORM, taskX = DEFAULT_TASK_X + 100)
    val transitionInfo1 =
        TransitionInfoBuilder(TRANSIT_CHANGE, 0)
            .addChange(createChange(TRANSIT_CHANGE, newTaskInfo1))
            .build()
    callOnTransitionReady(transitionInfo1)

    verify(desktopModeEventLogger, times(1))
        .logTaskInfoChanged(
            eq(sessionId), eq(DEFAULT_TASK_UPDATE.copy(taskX = DEFAULT_TASK_X + 100)))
    verifyZeroInteractions(desktopModeEventLogger)

    // task 2 resize
    val newTaskInfo2 =
        createTaskInfo(
            WINDOWING_MODE_FREEFORM,
            id = 2,
            taskWidth = DEFAULT_TASK_WIDTH + 100,
            taskHeight = DEFAULT_TASK_HEIGHT - 100)
    val transitionInfo2 =
        TransitionInfoBuilder(TRANSIT_CHANGE, 0)
            .addChange(createChange(TRANSIT_CHANGE, newTaskInfo2))
            .build()

    callOnTransitionReady(transitionInfo2)

    verify(desktopModeEventLogger, times(1))
        .logTaskInfoChanged(
            eq(sessionId),
            eq(
                DEFAULT_TASK_UPDATE.copy(
                    instanceId = 2,
                    taskWidth = DEFAULT_TASK_WIDTH + 100,
                    taskHeight = DEFAULT_TASK_HEIGHT - 100)))
    verifyZeroInteractions(desktopModeEventLogger)
  }

  @Test
  fun sessionAlreadyStarted_freeformTaskRemoved_logsTaskRemoved() {
    val sessionId = 1
    // add two existing freeform tasks
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM))
    transitionObserver.addTaskInfosToCachedMap(createTaskInfo(WINDOWING_MODE_FREEFORM, id = 2))
    transitionObserver.setLoggerSessionId(sessionId)

    // new freeform task closed
    val change = createChange(TRANSIT_CLOSE, createTaskInfo(WINDOWING_MODE_FREEFORM, id = 2))
    val transitionInfo = TransitionInfoBuilder(TRANSIT_CLOSE, 0).addChange(change).build()
    callOnTransitionReady(transitionInfo)

    verify(desktopModeEventLogger, times(1))
        .logTaskRemoved(eq(sessionId), eq(DEFAULT_TASK_UPDATE.copy(instanceId = 2)))
    verify(desktopModeEventLogger, never()).logSessionExit(any(), any())
  }

  /** Simulate calling the onTransitionReady() method */
  private fun callOnTransitionReady(transitionInfo: TransitionInfo) {
    val transition = mock<IBinder>()
    val startT = mock<SurfaceControl.Transaction>()
    val finishT = mock<SurfaceControl.Transaction>()

    transitionObserver.onTransitionReady(transition, transitionInfo, startT, finishT)
  }

  private fun verifyTaskAddedAndEnterLogging(enterReason: EnterReason, taskUpdate: TaskUpdate) {
    val sessionId = transitionObserver.getLoggerSessionId()
    assertNotNull(sessionId)
    verify(desktopModeEventLogger, times(1)).logSessionEnter(eq(sessionId!!), eq(enterReason))
    verify(desktopModeEventLogger, times(1)).logTaskAdded(eq(sessionId), eq(taskUpdate))
    verifyZeroInteractions(desktopModeEventLogger)
  }

  private fun verifyTaskRemovedAndExitLogging(
      sessionId: Int,
      exitReason: ExitReason,
      taskUpdate: TaskUpdate
  ) {
    verify(desktopModeEventLogger, times(1)).logTaskRemoved(eq(sessionId), eq(taskUpdate))
    verify(desktopModeEventLogger, times(1)).logSessionExit(eq(sessionId), eq(exitReason))
    verifyZeroInteractions(desktopModeEventLogger)
    assertNull(transitionObserver.getLoggerSessionId())
  }

  private companion object {
    const val DEFAULT_TASK_ID = 1
    const val DEFAULT_TASK_UID = 2
    const val DEFAULT_TASK_HEIGHT = 100
    const val DEFAULT_TASK_WIDTH = 200
    const val DEFAULT_TASK_X = 30
    const val DEFAULT_TASK_Y = 70
    val DEFAULT_TASK_UPDATE =
        TaskUpdate(
            DEFAULT_TASK_ID,
            DEFAULT_TASK_UID,
            DEFAULT_TASK_HEIGHT,
            DEFAULT_TASK_WIDTH,
            DEFAULT_TASK_X,
            DEFAULT_TASK_Y,
        )

    fun createTaskInfo(
        windowMode: Int,
        id: Int = DEFAULT_TASK_ID,
        uid: Int = DEFAULT_TASK_UID,
        taskHeight: Int = DEFAULT_TASK_HEIGHT,
        taskWidth: Int = DEFAULT_TASK_WIDTH,
        taskX: Int = DEFAULT_TASK_X,
        taskY: Int = DEFAULT_TASK_Y,
    ) =
        ActivityManager.RunningTaskInfo().apply {
          taskId = id
          userId = uid
          configuration.windowConfiguration.apply {
            windowingMode = windowMode
            positionInParent = Point(taskX, taskY)
            bounds.set(Rect(taskX, taskY, taskX + taskWidth, taskY + taskHeight))
          }
        }

    fun createChange(mode: Int, taskInfo: ActivityManager.RunningTaskInfo): Change {
      val change =
          Change(WindowContainerToken(mock<IWindowContainerToken>()), mock<SurfaceControl>())
      change.mode = mode
      change.taskInfo = taskInfo
      return change
    }
  }
}
