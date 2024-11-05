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
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.SurfaceControl
import android.view.WindowManager
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TransitionType
import android.window.TransitionInfo
import android.window.WindowContainerTransaction
import androidx.test.filters.SmallTest
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE
import com.android.internal.jank.InteractionJankMonitor
import com.android.window.flags.Flags
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.desktopmode.DesktopMixedTransitionHandler.PendingMixedTransition
import com.android.wm.shell.freeform.FreeformTaskTransitionHandler
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
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

    @JvmField @Rule val setFlagsRule = SetFlagsRule()

    @Mock lateinit var transitions: Transitions
    @Mock lateinit var desktopRepository: DesktopRepository
    @Mock lateinit var freeformTaskTransitionHandler: FreeformTaskTransitionHandler
    @Mock lateinit var closeDesktopTaskTransitionHandler: CloseDesktopTaskTransitionHandler
    @Mock lateinit var desktopImmersiveController: DesktopImmersiveController
    @Mock lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock lateinit var mockHandler: Handler
    @Mock lateinit var closingTaskLeash: SurfaceControl
    @Mock lateinit var shellInit: ShellInit
    @Mock lateinit var rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer

    private lateinit var mixedHandler: DesktopMixedTransitionHandler

    @Before
    fun setUp() {
        mixedHandler =
            DesktopMixedTransitionHandler(
                context,
                transitions,
                desktopRepository,
                freeformTaskTransitionHandler,
                closeDesktopTaskTransitionHandler,
                desktopImmersiveController,
                interactionJankMonitor,
                mockHandler,
                shellInit,
                rootTaskDisplayAreaOrganizer,
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
    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_EXIT_TRANSITIONS)
    fun startRemoveTransition_callsFreeformTaskTransitionHandler() {
        val wct = WindowContainerTransaction()
        whenever(freeformTaskTransitionHandler.startRemoveTransition(wct))
            .thenReturn(mock())

        mixedHandler.startRemoveTransition(wct)

        verify(freeformTaskTransitionHandler).startRemoveTransition(wct)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_EXIT_TRANSITIONS)
    fun startRemoveTransition_startsCloseTransition() {
        val wct = WindowContainerTransaction()
        whenever(transitions.startTransition(WindowManager.TRANSIT_CLOSE, wct, mixedHandler))
            .thenReturn(Binder())

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
                changeMode = TRANSIT_OPEN,
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
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_EXIT_TRANSITIONS)
    fun startAnimation_withClosingDesktopTask_callsCloseTaskHandler() {
        val wct = WindowContainerTransaction()
        val transition = mock<IBinder>()
        val transitionInfo = createTransitionInfo(task = createTask(WINDOWING_MODE_FREEFORM))
        whenever(desktopRepository.getExpandedTaskCount(any())).thenReturn(2)
        whenever(
                closeDesktopTaskTransitionHandler.startAnimation(any(), any(), any(), any(), any())
            )
            .thenReturn(true)
        whenever(transitions.startTransition(WindowManager.TRANSIT_CLOSE, wct, mixedHandler))
            .thenReturn(transition)
        mixedHandler.startRemoveTransition(wct)

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
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_EXIT_TRANSITIONS)
    fun startAnimation_withClosingLastDesktopTask_dispatchesTransition() {
        val wct = WindowContainerTransaction()
        val transition = mock<IBinder>()
        val transitionInfo = createTransitionInfo(task = createTask(WINDOWING_MODE_FREEFORM))
        whenever(desktopRepository.getExpandedTaskCount(any())).thenReturn(1)
        whenever(transitions.dispatchTransition(any(), any(), any(), any(), any(), any()))
            .thenReturn(mock())
        whenever(transitions.startTransition(WindowManager.TRANSIT_CLOSE, wct, mixedHandler))
            .thenReturn(transition)
        mixedHandler.startRemoveTransition(wct)

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

    @Test
    @DisableFlags(
        Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP,
        Flags.FLAG_ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS)
    fun startLaunchTransition_immersiveAndAppLaunchFlagsDisabled_doesNotUseMixedHandler() {
        val wct = WindowContainerTransaction()
        val task = createTask(WINDOWING_MODE_FREEFORM)
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(Binder())

        mixedHandler.startLaunchTransition(
            transitionType = TRANSIT_OPEN,
            wct = wct,
            taskId = task.taskId,
            exitingImmersiveTask = null
        )

        verify(transitions).startTransition(TRANSIT_OPEN, wct, /* handler= */ null)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun startLaunchTransition_immersiveMixEnabled_usesMixedHandler() {
        val wct = WindowContainerTransaction()
        val task = createTask(WINDOWING_MODE_FREEFORM)
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(Binder())

        mixedHandler.startLaunchTransition(
            transitionType = TRANSIT_OPEN,
            wct = wct,
            taskId = task.taskId,
            exitingImmersiveTask = null
        )

        verify(transitions).startTransition(TRANSIT_OPEN, wct, mixedHandler)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS)
    fun startLaunchTransition_desktopAppLaunchEnabled_usesMixedHandler() {
        val wct = WindowContainerTransaction()
        val task = createTask(WINDOWING_MODE_FREEFORM)
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(Binder())

        mixedHandler.startLaunchTransition(
            transitionType = TRANSIT_OPEN,
            wct = wct,
            taskId = task.taskId,
            exitingImmersiveTask = null
        )

        verify(transitions).startTransition(TRANSIT_OPEN, wct, mixedHandler)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun startAndAnimateLaunchTransition_withoutImmersiveChange_dispatchesAllChangesToLeftOver() {
        val wct = WindowContainerTransaction()
        val launchingTask = createTask(WINDOWING_MODE_FREEFORM)
        val transition = Binder()
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(transition)

        mixedHandler.startLaunchTransition(
            transitionType = TRANSIT_OPEN,
            wct = wct,
            taskId = launchingTask.taskId,
            exitingImmersiveTask = null,
        )
        val launchTaskChange = createChange(launchingTask)
        val otherChange = createChange(createTask(WINDOWING_MODE_FREEFORM))
        mixedHandler.startAnimation(
            transition,
            createTransitionInfo(
                TRANSIT_OPEN,
                listOf(launchTaskChange, otherChange)
            ),
            SurfaceControl.Transaction(),
            SurfaceControl.Transaction(),
        ) { }

        verify(transitions).dispatchTransition(
            eq(transition),
            argThat { info ->
                info.changes.contains(launchTaskChange) && info.changes.contains(otherChange)
            },
            any(),
            any(),
            any(),
            eq(mixedHandler),
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun startAndAnimateLaunchTransition_withImmersiveChange_mixesAnimations() {
        val wct = WindowContainerTransaction()
        val launchingTask = createTask(WINDOWING_MODE_FREEFORM)
        val immersiveTask = createTask(WINDOWING_MODE_FREEFORM)
        val transition = Binder()
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(transition)

        mixedHandler.startLaunchTransition(
            transitionType = TRANSIT_OPEN,
            wct = wct,
            taskId = launchingTask.taskId,
            exitingImmersiveTask = immersiveTask.taskId,
        )
        val launchTaskChange = createChange(launchingTask)
        val immersiveChange = createChange(immersiveTask)
        mixedHandler.startAnimation(
            transition,
            createTransitionInfo(
                TRANSIT_OPEN,
                listOf(launchTaskChange, immersiveChange)
            ),
            SurfaceControl.Transaction(),
            SurfaceControl.Transaction(),
        ) { }

        verify(desktopImmersiveController)
            .animateResizeChange(eq(immersiveChange), any(), any(), any())
        verify(transitions).dispatchTransition(
            eq(transition),
            argThat { info ->
                info.changes.contains(launchTaskChange) && !info.changes.contains(immersiveChange)
            },
            any(),
            any(),
            any(),
            eq(mixedHandler),
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS)
    fun startAndAnimateLaunchTransition_noMinimizeChange_doesNotReparentMinimizeChange() {
        val wct = WindowContainerTransaction()
        val launchingTask = createTask(WINDOWING_MODE_FREEFORM)
        val launchTaskChange = createChange(launchingTask)
        val transition = Binder()
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(transition)

        mixedHandler.startLaunchTransition(
            transitionType = TRANSIT_OPEN,
            wct = wct,
            taskId = launchingTask.taskId,
            minimizingTaskId = null,
        )
        mixedHandler.startAnimation(
            transition,
            createTransitionInfo(
                TRANSIT_OPEN,
                listOf(launchTaskChange)
            ),
            SurfaceControl.Transaction(),
            SurfaceControl.Transaction(),
        ) { }

        verify(rootTaskDisplayAreaOrganizer, times(0))
            .reparentToDisplayArea(anyInt(), any(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS)
    fun startAndAnimateLaunchTransition_withMinimizeChange_reparentsMinimizeChange() {
        val wct = WindowContainerTransaction()
        val launchingTask = createTask(WINDOWING_MODE_FREEFORM)
        val minimizingTask = createTask(WINDOWING_MODE_FREEFORM)
        val launchTaskChange = createChange(launchingTask)
        val minimizeChange = createChange(minimizingTask)
        val transition = Binder()
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(transition)

        mixedHandler.startLaunchTransition(
            transitionType = TRANSIT_OPEN,
            wct = wct,
            taskId = launchingTask.taskId,
            minimizingTaskId = minimizingTask.taskId,
        )
        mixedHandler.startAnimation(
            transition,
            createTransitionInfo(
                TRANSIT_OPEN,
                listOf(launchTaskChange, minimizeChange)
            ),
            SurfaceControl.Transaction(),
            SurfaceControl.Transaction(),
        ) { }

        verify(rootTaskDisplayAreaOrganizer).reparentToDisplayArea(
            anyInt(), eq(minimizeChange.leash), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS)
    fun startAnimation_pendingTransition_noLaunchChange_returnsFalse() {
        val wct = WindowContainerTransaction()
        val launchingTask = createTask(WINDOWING_MODE_FREEFORM)
        val nonLaunchTaskChange = createChange(createTask(WINDOWING_MODE_FREEFORM))
        val transition = Binder()
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(transition)
        mixedHandler.addPendingMixedTransition(
            PendingMixedTransition.Launch(
                transition = transition,
                launchingTask = launchingTask.taskId,
                minimizingTask = null,
                exitingImmersiveTask = null,
            )
        )

        val started = mixedHandler.startAnimation(
            transition,
            createTransitionInfo(
                TRANSIT_OPEN,
                listOf(nonLaunchTaskChange)
            ),
            SurfaceControl.Transaction(),
            SurfaceControl.Transaction(),
        ) { }

        assertFalse("Should not start animation without launching desktop task", started)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS)
    fun addPendingAndAnimateLaunchTransition_noMinimizeChange_doesNotReparentMinimizeChange() {
        val wct = WindowContainerTransaction()
        val launchingTask = createTask(WINDOWING_MODE_FREEFORM)
        val launchTaskChange = createChange(launchingTask)
        val transition = Binder()
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(transition)

        mixedHandler.addPendingMixedTransition(
            PendingMixedTransition.Launch(
                transition = transition,
                launchingTask = launchingTask.taskId,
                minimizingTask = null,
                exitingImmersiveTask = null,
            )
        )
        mixedHandler.startAnimation(
            transition,
            createTransitionInfo(
                TRANSIT_OPEN,
                listOf(launchTaskChange)
            ),
            SurfaceControl.Transaction(),
            SurfaceControl.Transaction(),
        ) { }

        verify(rootTaskDisplayAreaOrganizer, times(0))
            .reparentToDisplayArea(anyInt(), any(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS)
    fun addPendingAndAnimateLaunchTransition_withMinimizeChange_reparentsMinimizeChange() {
        val wct = WindowContainerTransaction()
        val launchingTask = createTask(WINDOWING_MODE_FREEFORM)
        val minimizingTask = createTask(WINDOWING_MODE_FREEFORM)
        val launchTaskChange = createChange(launchingTask)
        val minimizeChange = createChange(minimizingTask)
        val transition = Binder()
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(transition)

        mixedHandler.addPendingMixedTransition(
            PendingMixedTransition.Launch(
                transition = transition,
                launchingTask = launchingTask.taskId,
                minimizingTask = minimizingTask.taskId,
                exitingImmersiveTask = null,
            )
        )
        mixedHandler.startAnimation(
            transition,
            createTransitionInfo(
                TRANSIT_OPEN,
                listOf(launchTaskChange, minimizeChange)
            ),
            SurfaceControl.Transaction(),
            SurfaceControl.Transaction(),
        ) { }

        verify(rootTaskDisplayAreaOrganizer).reparentToDisplayArea(
            anyInt(), eq(minimizeChange.leash), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun startAndAnimateLaunchTransition_removesPendingMixedTransition() {
        val wct = WindowContainerTransaction()
        val launchingTask = createTask(WINDOWING_MODE_FREEFORM)
        val transition = Binder()
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(transition)

        mixedHandler.startLaunchTransition(
            transitionType = TRANSIT_OPEN,
            wct = wct,
            taskId = launchingTask.taskId,
            exitingImmersiveTask = null,
        )
        val launchTaskChange = createChange(launchingTask)
        mixedHandler.startAnimation(
            transition,
            createTransitionInfo(
                TRANSIT_OPEN,
                listOf(launchTaskChange)
            ),
            SurfaceControl.Transaction(),
            SurfaceControl.Transaction(),
        ) { }

        assertThat(mixedHandler.pendingMixedTransitions).isEmpty()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    fun startAndAnimateLaunchTransition_aborted_removesPendingMixedTransition() {
        val wct = WindowContainerTransaction()
        val launchingTask = createTask(WINDOWING_MODE_FREEFORM)
        val transition = Binder()
        whenever(transitions.startTransition(eq(TRANSIT_OPEN), eq(wct), anyOrNull()))
            .thenReturn(transition)

        mixedHandler.startLaunchTransition(
            transitionType = TRANSIT_OPEN,
            wct = wct,
            taskId = launchingTask.taskId,
            exitingImmersiveTask = null,
        )
        mixedHandler.onTransitionConsumed(
            transition = transition,
            aborted = true,
            finishTransaction = SurfaceControl.Transaction()
        )

        assertThat(mixedHandler.pendingMixedTransitions).isEmpty()
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

    private fun createTransitionInfo(
        @TransitionType type: Int,
        changes: List<TransitionInfo.Change> = emptyList()
    ): TransitionInfo = TransitionInfo(type, /* flags= */ 0).apply {
        changes.forEach { change -> addChange(change) }
    }

    private fun createChange(task: RunningTaskInfo): TransitionInfo.Change =
        TransitionInfo.Change(task.token, SurfaceControl()).apply {
            taskInfo = task
        }

    private fun createTask(@WindowingMode windowingMode: Int): RunningTaskInfo =
        TestRunningTaskInfoBuilder()
            .setActivityType(ACTIVITY_TYPE_STANDARD)
            .setWindowingMode(windowingMode)
            .build()
}
