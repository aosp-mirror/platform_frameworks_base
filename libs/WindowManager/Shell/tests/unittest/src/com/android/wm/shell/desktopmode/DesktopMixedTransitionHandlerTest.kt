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
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WindowingMode
import android.os.Handler
import android.os.IBinder
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.SurfaceControl
import android.view.WindowManager
import android.window.TransitionInfo
import android.window.WindowContainerTransaction
import androidx.test.filters.SmallTest
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE
import com.android.internal.jank.InteractionJankMonitor
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.freeform.FreeformTaskTransitionHandler
import com.android.wm.shell.transition.Transitions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Test class for [DesktopMixedTransitionHandler]
 *
 * Usage: atest WMShellUnitTests:DesktopMixedTransitionHandlerTest
 */
@SmallTest
@RunWithLooper
@RunWith(AndroidTestingRunner::class)
class DesktopMixedTransitionHandlerTest : ShellTestCase() {

    @Mock lateinit var transitions: Transitions
    @Mock lateinit var desktopTaskRepository: DesktopModeTaskRepository
    @Mock lateinit var freeformTaskTransitionHandler: FreeformTaskTransitionHandler
    @Mock lateinit var closeDesktopTaskTransitionHandler: CloseDesktopTaskTransitionHandler
    @Mock lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock lateinit var mockHandler: Handler
    @Mock lateinit var closingTaskLeash: SurfaceControl

    private lateinit var mixedHandler: DesktopMixedTransitionHandler

    @Before
    fun setUp() {
        mixedHandler =
            DesktopMixedTransitionHandler(
                context,
                transitions,
                desktopTaskRepository,
                freeformTaskTransitionHandler,
                closeDesktopTaskTransitionHandler,
                interactionJankMonitor,
                mockHandler
            )
    }

    @Test
    fun startWindowingModeTransition_callsFreeformTaskTransitionHandler() {
        val windowingMode = WINDOWING_MODE_FULLSCREEN
        val wct = WindowContainerTransaction()

        mixedHandler.startWindowingModeTransition(windowingMode, wct)

        verify(freeformTaskTransitionHandler).startWindowingModeTransition(windowingMode, wct)
    }

    @Test
    fun startMinimizedModeTransition_callsFreeformTaskTransitionHandler() {
        val wct = WindowContainerTransaction()
        whenever(freeformTaskTransitionHandler.startMinimizedModeTransition(any()))
            .thenReturn(mock())

        mixedHandler.startMinimizedModeTransition(wct)

        verify(freeformTaskTransitionHandler).startMinimizedModeTransition(wct)
    }

    @Test
    fun startRemoveTransition_startsCloseTransition() {
        val wct = WindowContainerTransaction()

        mixedHandler.startRemoveTransition(wct)

        verify(transitions).startTransition(WindowManager.TRANSIT_CLOSE, wct, mixedHandler)
    }

    @Test
    fun handleRequest_returnsNull() {
        assertNull(mixedHandler.handleRequest(mock(), mock()))
    }

    @Test
    fun startAnimation_withoutClosingDesktopTask_returnsFalse() {
        val transition = mock<IBinder>()
        val transitionInfo =
            createTransitionInfo(
                changeMode = WindowManager.TRANSIT_OPEN,
                task = createTask(WINDOWING_MODE_FREEFORM)
            )
        whenever(freeformTaskTransitionHandler.startAnimation(any(), any(), any(), any(), any()))
            .thenReturn(true)

        val started = mixedHandler.startAnimation(
            transition = transition,
            info = transitionInfo,
            startTransaction = mock(),
            finishTransaction = mock(),
            finishCallback = {}
        )

        assertFalse("Should not start animation without closing desktop task", started)
    }

    @Test
    fun startAnimation_withClosingDesktopTask_callsCloseTaskHandler() {
        val transition = mock<IBinder>()
        val transitionInfo = createTransitionInfo(task = createTask(WINDOWING_MODE_FREEFORM))
        whenever(desktopTaskRepository.getActiveNonMinimizedTaskCount(any())).thenReturn(2)
        whenever(
                closeDesktopTaskTransitionHandler.startAnimation(any(), any(), any(), any(), any())
            )
            .thenReturn(true)

        val started = mixedHandler.startAnimation(
            transition = transition,
            info = transitionInfo,
            startTransaction = mock(),
            finishTransaction = mock(),
            finishCallback = {}
        )

        assertTrue("Should delegate animation to close transition handler", started)
        verify(closeDesktopTaskTransitionHandler)
            .startAnimation(eq(transition), eq(transitionInfo), any(), any(), any())
    }

    @Test
    fun startAnimation_withClosingLastDesktopTask_dispatchesTransition() {
        val transition = mock<IBinder>()
        val transitionInfo = createTransitionInfo(task = createTask(WINDOWING_MODE_FREEFORM))
        whenever(desktopTaskRepository.getActiveNonMinimizedTaskCount(any())).thenReturn(1)
        whenever(transitions.dispatchTransition(any(), any(), any(), any(), any(), any()))
            .thenReturn(mock())

        mixedHandler.startAnimation(
            transition = transition,
            info = transitionInfo,
            startTransaction = mock(),
            finishTransaction = mock(),
            finishCallback = {}
        )

        verify(transitions)
            .dispatchTransition(
                eq(transition),
                eq(transitionInfo),
                any(),
                any(),
                any(),
                eq(mixedHandler)
            )
        verify(interactionJankMonitor)
            .begin(
                closingTaskLeash,
                context,
                mockHandler,
                CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE
            )
    }

    private fun createTransitionInfo(
        type: Int = WindowManager.TRANSIT_CLOSE,
        changeMode: Int = WindowManager.TRANSIT_CLOSE,
        task: RunningTaskInfo
    ): TransitionInfo =
        TransitionInfo(type, 0 /* flags */).apply {
            addChange(
                TransitionInfo.Change(mock(), closingTaskLeash).apply {
                    mode = changeMode
                    parent = null
                    taskInfo = task
                }
            )
        }

    private fun createTask(@WindowingMode windowingMode: Int): RunningTaskInfo =
        TestRunningTaskInfoBuilder()
            .setActivityType(ACTIVITY_TYPE_STANDARD)
            .setWindowingMode(windowingMode)
            .build()
}
