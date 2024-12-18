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
import android.os.Binder
import android.os.Handler
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_MINIMIZE_WINDOW
import com.android.internal.jank.InteractionJankMonitor
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFreeformTask
import com.android.wm.shell.desktopmode.persistence.DesktopPersistentRepository
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.TransitionInfoBuilder
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.util.StubTransaction
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.quality.Strictness


/**
 * Test class for {@link DesktopTasksLimiter}
 *
 * Usage: atest WMShellUnitTests:DesktopTasksLimiterTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@ExperimentalCoroutinesApi
class DesktopTasksLimiterTest : ShellTestCase() {

    @JvmField
    @Rule
    val setFlagsRule = SetFlagsRule()

    @Mock lateinit var shellTaskOrganizer: ShellTaskOrganizer
    @Mock lateinit var transitions: Transitions
    @Mock lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock lateinit var handler: Handler
    @Mock lateinit var testExecutor: ShellExecutor
    @Mock lateinit var persistentRepository: DesktopPersistentRepository

    private lateinit var mockitoSession: StaticMockitoSession
    private lateinit var desktopTasksLimiter: DesktopTasksLimiter
    private lateinit var desktopTaskRepo: DesktopModeTaskRepository
    private lateinit var shellInit: ShellInit
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        mockitoSession = ExtendedMockito.mockitoSession().strictness(Strictness.LENIENT)
                .spyStatic(DesktopModeStatus::class.java).startMocking()
        doReturn(true).`when`{ DesktopModeStatus.canEnterDesktopMode(any()) }
        shellInit = spy(ShellInit(testExecutor))
        Dispatchers.setMain(StandardTestDispatcher())
        testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        desktopTaskRepo =
            DesktopModeTaskRepository(context, shellInit, persistentRepository, testScope)
        desktopTasksLimiter =
            DesktopTasksLimiter(transitions, desktopTaskRepo, shellTaskOrganizer, MAX_TASK_LIMIT,
                interactionJankMonitor, mContext, handler)
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
        testScope.cancel()
    }

    @Test
    fun createDesktopTasksLimiter_withZeroLimit_shouldThrow() {
        assertFailsWith<IllegalArgumentException> {
            DesktopTasksLimiter(transitions, desktopTaskRepo, shellTaskOrganizer, 0,
                interactionJankMonitor, mContext, handler)
        }
    }

    @Test
    fun createDesktopTasksLimiter_withNegativeLimit_shouldThrow() {
        assertFailsWith<IllegalArgumentException> {
            DesktopTasksLimiter(transitions, desktopTaskRepo, shellTaskOrganizer, -5,
                interactionJankMonitor, mContext, handler)
        }
    }

    @Test
    fun addPendingMinimizeTransition_taskIsNotMinimized() {
        val task = setUpFreeformTask()
        markTaskHidden(task)

        desktopTasksLimiter.addPendingMinimizeChange(Binder(), displayId = 1, taskId = task.taskId)

        assertThat(desktopTaskRepo.isMinimizedTask(taskId = task.taskId)).isFalse()
    }

    @Test
    fun onTransitionReady_noPendingTransition_taskIsNotMinimized() {
        val task = setUpFreeformTask()
        markTaskHidden(task)

        desktopTasksLimiter.getTransitionObserver().onTransitionReady(
                Binder() /* transition */,
                TransitionInfoBuilder(TRANSIT_OPEN).addChange(TRANSIT_TO_BACK, task).build(),
                StubTransaction() /* startTransaction */,
                StubTransaction() /* finishTransaction */)

        assertThat(desktopTaskRepo.isMinimizedTask(taskId = task.taskId)).isFalse()
    }

    @Test
    fun onTransitionReady_differentPendingTransition_taskIsNotMinimized() {
        val pendingTransition = Binder()
        val taskTransition = Binder()
        val task = setUpFreeformTask()
        markTaskHidden(task)
        desktopTasksLimiter.addPendingMinimizeChange(
            pendingTransition, displayId = DEFAULT_DISPLAY, taskId = task.taskId)

        desktopTasksLimiter.getTransitionObserver().onTransitionReady(
            taskTransition /* transition */,
            TransitionInfoBuilder(TRANSIT_OPEN).addChange(TRANSIT_TO_BACK, task).build(),
            StubTransaction() /* startTransaction */,
            StubTransaction() /* finishTransaction */)

        assertThat(desktopTaskRepo.isMinimizedTask(taskId = task.taskId)).isFalse()
    }

    @Test
    fun onTransitionReady_pendingTransition_noTaskChange_taskVisible_taskIsNotMinimized() {
        val transition = Binder()
        val task = setUpFreeformTask()
        markTaskVisible(task)
        desktopTasksLimiter.addPendingMinimizeChange(
                transition, displayId = DEFAULT_DISPLAY, taskId = task.taskId)

        desktopTasksLimiter.getTransitionObserver().onTransitionReady(
                transition,
                TransitionInfoBuilder(TRANSIT_OPEN).build(),
                StubTransaction() /* startTransaction */,
                StubTransaction() /* finishTransaction */)

        assertThat(desktopTaskRepo.isMinimizedTask(taskId = task.taskId)).isFalse()
    }

    @Test
    fun onTransitionReady_pendingTransition_noTaskChange_taskInvisible_taskIsMinimized() {
        val transition = Binder()
        val task = setUpFreeformTask()
        markTaskHidden(task)
        desktopTasksLimiter.addPendingMinimizeChange(
                transition, displayId = DEFAULT_DISPLAY, taskId = task.taskId)

        desktopTasksLimiter.getTransitionObserver().onTransitionReady(
                transition,
                TransitionInfoBuilder(TRANSIT_OPEN).build(),
                StubTransaction() /* startTransaction */,
                StubTransaction() /* finishTransaction */)

        assertThat(desktopTaskRepo.isMinimizedTask(taskId = task.taskId)).isTrue()
    }

    @Test
    fun onTransitionReady_pendingTransition_changeTaskToBack_taskIsMinimized() {
        val transition = Binder()
        val task = setUpFreeformTask()
        desktopTasksLimiter.addPendingMinimizeChange(
                transition, displayId = DEFAULT_DISPLAY, taskId = task.taskId)

        desktopTasksLimiter.getTransitionObserver().onTransitionReady(
                transition,
                TransitionInfoBuilder(TRANSIT_OPEN).addChange(TRANSIT_TO_BACK, task).build(),
                StubTransaction() /* startTransaction */,
                StubTransaction() /* finishTransaction */)

        assertThat(desktopTaskRepo.isMinimizedTask(taskId = task.taskId)).isTrue()
    }

    @Test
    fun onTransitionReady_transitionMergedFromPending_taskIsMinimized() {
        val mergedTransition = Binder()
        val newTransition = Binder()
        val task = setUpFreeformTask()
        desktopTasksLimiter.addPendingMinimizeChange(
            mergedTransition, displayId = DEFAULT_DISPLAY, taskId = task.taskId)
        desktopTasksLimiter.getTransitionObserver().onTransitionMerged(
            mergedTransition, newTransition)

        desktopTasksLimiter.getTransitionObserver().onTransitionReady(
            newTransition,
            TransitionInfoBuilder(TRANSIT_OPEN).addChange(TRANSIT_TO_BACK, task).build(),
            StubTransaction() /* startTransaction */,
            StubTransaction() /* finishTransaction */)

        assertThat(desktopTaskRepo.isMinimizedTask(taskId = task.taskId)).isTrue()
    }

    @Test
    fun removeLeftoverMinimizedTasks_activeNonMinimizedTasksStillAround_doesNothing() {
        desktopTaskRepo.addActiveTask(displayId = DEFAULT_DISPLAY, taskId = 1)
        desktopTaskRepo.addActiveTask(displayId = DEFAULT_DISPLAY, taskId = 2)
        desktopTaskRepo.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = 2)

        val wct = WindowContainerTransaction()
        desktopTasksLimiter.leftoverMinimizedTasksRemover.removeLeftoverMinimizedTasks(
            DEFAULT_DISPLAY, wct)

        assertThat(wct.isEmpty).isTrue()
    }

    @Test
    fun removeLeftoverMinimizedTasks_noMinimizedTasks_doesNothing() {
        val wct = WindowContainerTransaction()
        desktopTasksLimiter.leftoverMinimizedTasksRemover.removeLeftoverMinimizedTasks(
            DEFAULT_DISPLAY, wct)

        assertThat(wct.isEmpty).isTrue()
    }

    @Test
    fun removeLeftoverMinimizedTasks_onlyMinimizedTasksLeft_removesAllMinimizedTasks() {
        val task1 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
        val task2 = setUpFreeformTask(displayId = DEFAULT_DISPLAY)
        desktopTaskRepo.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = task1.taskId)
        desktopTaskRepo.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = task2.taskId)

        val wct = WindowContainerTransaction()
        desktopTasksLimiter.leftoverMinimizedTasksRemover.removeLeftoverMinimizedTasks(
            DEFAULT_DISPLAY, wct)

        assertThat(wct.hierarchyOps).hasSize(2)
        assertThat(wct.hierarchyOps[0].type).isEqualTo(HIERARCHY_OP_TYPE_REMOVE_TASK)
        assertThat(wct.hierarchyOps[0].container).isEqualTo(task1.token.asBinder())
        assertThat(wct.hierarchyOps[1].type).isEqualTo(HIERARCHY_OP_TYPE_REMOVE_TASK)
        assertThat(wct.hierarchyOps[1].container).isEqualTo(task2.token.asBinder())
    }

    @Test
    fun addAndGetMinimizeTaskChangesIfNeeded_tasksWithinLimit_noTaskMinimized() {
        (1..<MAX_TASK_LIMIT).forEach { _ -> setUpFreeformTask() }

        val wct = WindowContainerTransaction()
        val minimizedTaskId =
                desktopTasksLimiter.addAndGetMinimizeTaskChangesIfNeeded(
                        displayId = DEFAULT_DISPLAY,
                        wct = wct,
                        newFrontTaskInfo = setUpFreeformTask())

        assertThat(minimizedTaskId).isNull()
        assertThat(wct.hierarchyOps).isEmpty() // No reordering operations added
    }

    @Test
    fun addAndGetMinimizeTaskChangesIfNeeded_tasksAboveLimit_backTaskMinimized() {
        // The following list will be ordered bottom -> top, as the last task is moved to top last.
        val tasks = (1..MAX_TASK_LIMIT).map { setUpFreeformTask() }

        val wct = WindowContainerTransaction()
        val minimizedTaskId =
                desktopTasksLimiter.addAndGetMinimizeTaskChangesIfNeeded(
                        displayId = DEFAULT_DISPLAY,
                        wct = wct,
                        newFrontTaskInfo = setUpFreeformTask())

        assertThat(minimizedTaskId).isEqualTo(tasks.first())
        assertThat(wct.hierarchyOps.size).isEqualTo(1)
        assertThat(wct.hierarchyOps[0].type).isEqualTo(HIERARCHY_OP_TYPE_REORDER)
        assertThat(wct.hierarchyOps[0].toTop).isFalse() // Reorder to bottom
    }

    @Test
    fun addAndGetMinimizeTaskChangesIfNeeded_nonMinimizedTasksWithinLimit_noTaskMinimized() {
        val tasks = (1..MAX_TASK_LIMIT).map { setUpFreeformTask() }
        desktopTaskRepo.minimizeTask(displayId = DEFAULT_DISPLAY, taskId = tasks[0].taskId)

        val wct = WindowContainerTransaction()
        val minimizedTaskId =
                desktopTasksLimiter.addAndGetMinimizeTaskChangesIfNeeded(
                        displayId = 0,
                        wct = wct,
                        newFrontTaskInfo = setUpFreeformTask())

        assertThat(minimizedTaskId).isNull()
        assertThat(wct.hierarchyOps).isEmpty() // No reordering operations added
    }

    @Test
    fun getTaskToMinimizeIfNeeded_tasksWithinLimit_returnsNull() {
        val tasks = (1..MAX_TASK_LIMIT).map { setUpFreeformTask() }

        val minimizedTask = desktopTasksLimiter.getTaskToMinimizeIfNeeded(
                visibleFreeformTaskIdsOrderedFrontToBack = tasks.map { it.taskId })

        assertThat(minimizedTask).isNull()
    }

    @Test
    fun getTaskToMinimizeIfNeeded_tasksAboveLimit_returnsBackTask() {
        val tasks = (1..MAX_TASK_LIMIT + 1).map { setUpFreeformTask() }

        val minimizedTask = desktopTasksLimiter.getTaskToMinimizeIfNeeded(
                visibleFreeformTaskIdsOrderedFrontToBack = tasks.map { it.taskId })

        // first == front, last == back
        assertThat(minimizedTask).isEqualTo(tasks.last())
    }

    @Test
    fun getTaskToMinimizeIfNeeded_tasksAboveLimit_otherLimit_returnsBackTask() {
        desktopTasksLimiter =
            DesktopTasksLimiter(transitions, desktopTaskRepo, shellTaskOrganizer, MAX_TASK_LIMIT2,
                interactionJankMonitor, mContext, handler)
        val tasks = (1..MAX_TASK_LIMIT2 + 1).map { setUpFreeformTask() }

        val minimizedTask = desktopTasksLimiter.getTaskToMinimizeIfNeeded(
            visibleFreeformTaskIdsOrderedFrontToBack = tasks.map { it.taskId })

        // first == front, last == back
        assertThat(minimizedTask).isEqualTo(tasks.last())
    }

    @Test
    fun getTaskToMinimizeIfNeeded_withNewTask_tasksAboveLimit_returnsBackTask() {
        val tasks = (1..MAX_TASK_LIMIT).map { setUpFreeformTask() }

        val minimizedTask = desktopTasksLimiter.getTaskToMinimizeIfNeeded(
                visibleFreeformTaskIdsOrderedFrontToBack = tasks.map { it.taskId },
                newTaskIdInFront = setUpFreeformTask().taskId)

        // first == front, last == back
        assertThat(minimizedTask).isEqualTo(tasks.last())
    }

    @Test
    fun minimizeTransitionReadyAndFinished_logsJankInstrumentationBeginAndEnd() {
        (1..<MAX_TASK_LIMIT).forEach { _ -> setUpFreeformTask() }
        val transition = Binder()
        val task = setUpFreeformTask()
        desktopTasksLimiter.addPendingMinimizeChange(
            transition, displayId = DEFAULT_DISPLAY, taskId = task.taskId)

        desktopTasksLimiter.getTransitionObserver().onTransitionReady(
            transition,
            TransitionInfoBuilder(TRANSIT_OPEN).build(),
            StubTransaction() /* startTransaction */,
            StubTransaction() /* finishTransaction */)

        desktopTasksLimiter.getTransitionObserver().onTransitionStarting(transition)

        verify(interactionJankMonitor).begin(
            any(),
            eq(mContext),
            eq(handler),
            eq(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW))

        desktopTasksLimiter.getTransitionObserver().onTransitionFinished(
            transition,
            /* aborted = */ false)

        verify(interactionJankMonitor).end(eq(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW))
    }

    @Test
    fun minimizeTransitionReadyAndAborted_logsJankInstrumentationBeginAndCancel() {
        (1..<MAX_TASK_LIMIT).forEach { _ -> setUpFreeformTask() }
        val transition = Binder()
        val task = setUpFreeformTask()
        desktopTasksLimiter.addPendingMinimizeChange(
            transition, displayId = DEFAULT_DISPLAY, taskId = task.taskId)

        desktopTasksLimiter.getTransitionObserver().onTransitionReady(
            transition,
            TransitionInfoBuilder(TRANSIT_OPEN).build(),
            StubTransaction() /* startTransaction */,
            StubTransaction() /* finishTransaction */)

        desktopTasksLimiter.getTransitionObserver().onTransitionStarting(transition)

        verify(interactionJankMonitor).begin(
            any(),
            eq(mContext),
            eq(handler),
            eq(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW),
        )

        desktopTasksLimiter.getTransitionObserver().onTransitionFinished(
            transition,
            /* aborted = */ true)

        verify(interactionJankMonitor).cancel(eq(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW))
    }

    @Test
    fun minimizeTransitionReadyAndMerged_logsJankInstrumentationBeginAndEnd() {
        (1..<MAX_TASK_LIMIT).forEach { _ -> setUpFreeformTask() }
        val mergedTransition = Binder()
        val newTransition = Binder()
        val task = setUpFreeformTask()
        desktopTasksLimiter.addPendingMinimizeChange(
            mergedTransition, displayId = DEFAULT_DISPLAY, taskId = task.taskId)

        desktopTasksLimiter.getTransitionObserver().onTransitionReady(
            mergedTransition,
            TransitionInfoBuilder(TRANSIT_OPEN).build(),
            StubTransaction() /* startTransaction */,
            StubTransaction() /* finishTransaction */)

        desktopTasksLimiter.getTransitionObserver().onTransitionStarting(mergedTransition)

        verify(interactionJankMonitor).begin(
            any(),
            eq(mContext),
            eq(handler),
            eq(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW))

        desktopTasksLimiter.getTransitionObserver().onTransitionMerged(
            mergedTransition,
            newTransition)

        verify(interactionJankMonitor).end(eq(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW))
    }

    private fun setUpFreeformTask(
            displayId: Int = DEFAULT_DISPLAY,
    ): RunningTaskInfo {
        val task = createFreeformTask(displayId)
        `when`(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        desktopTaskRepo.addActiveTask(displayId, task.taskId)
        desktopTaskRepo.addOrMoveFreeformTaskToTop(displayId, task.taskId)
        return task
    }

    private fun markTaskVisible(task: RunningTaskInfo) {
        desktopTaskRepo.updateTaskVisibility(
                task.displayId,
                task.taskId,
                visible = true
        )
    }

    private fun markTaskHidden(task: RunningTaskInfo) {
        desktopTaskRepo.updateTaskVisibility(
                task.displayId,
                task.taskId,
                visible = false
        )
    }

    private companion object {
        const val MAX_TASK_LIMIT = 6
        const val MAX_TASK_LIMIT2 = 9
    }
}
