/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.testing.AndroidTestingRunner
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.ExtendedMockito.never
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFreeformTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFullscreenTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createHomeTask
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopTasksControllerTest : ShellTestCase() {

    @Mock lateinit var testExecutor: ShellExecutor
    @Mock lateinit var shellController: ShellController
    @Mock lateinit var shellTaskOrganizer: ShellTaskOrganizer
    @Mock lateinit var transitions: Transitions

    lateinit var mockitoSession: StaticMockitoSession
    lateinit var controller: DesktopTasksController
    lateinit var shellInit: ShellInit
    lateinit var desktopModeTaskRepository: DesktopModeTaskRepository

    // Mock running tasks are registered here so we can get the list from mock shell task organizer
    private val runningTasks = mutableListOf<RunningTaskInfo>()

    @Before
    fun setUp() {
        mockitoSession = mockitoSession().mockStatic(DesktopModeStatus::class.java).startMocking()
        whenever(DesktopModeStatus.isProto2Enabled()).thenReturn(true)

        shellInit = Mockito.spy(ShellInit(testExecutor))
        desktopModeTaskRepository = DesktopModeTaskRepository()

        whenever(shellTaskOrganizer.getRunningTasks(anyInt())).thenAnswer { runningTasks }

        controller = createController()

        shellInit.init()
    }

    private fun createController(): DesktopTasksController {
        return DesktopTasksController(
            context,
            shellInit,
            shellController,
            shellTaskOrganizer,
            transitions,
            desktopModeTaskRepository,
            TestShellExecutor()
        )
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()

        runningTasks.clear()
    }

    @Test
    fun instantiate_addInitCallback() {
        verify(shellInit).addInitCallback(any(), any<DesktopTasksController>())
    }

    @Test
    fun instantiate_flagOff_doNotAddInitCallback() {
        whenever(DesktopModeStatus.isProto2Enabled()).thenReturn(false)
        clearInvocations(shellInit)

        createController()

        verify(shellInit, never()).addInitCallback(any(), any<DesktopTasksController>())
    }

    @Test
    fun showDesktopApps_allAppsInvisible_bringsToFront() {
        val homeTask = setUpHomeTask()
        val task1 = setUpFreeformTask()
        val task2 = setUpFreeformTask()
        markTaskHidden(task1)
        markTaskHidden(task2)

        controller.showDesktopApps()

        val wct = getLatestWct()
        assertThat(wct.hierarchyOps).hasSize(3)
        // Expect order to be from bottom: home, task1, task2
        wct.assertReorderAt(index = 0, homeTask)
        wct.assertReorderAt(index = 1, task1)
        wct.assertReorderAt(index = 2, task2)
    }

    @Test
    fun showDesktopApps_appsAlreadyVisible_doesNothing() {
        setUpHomeTask()
        val task1 = setUpFreeformTask()
        val task2 = setUpFreeformTask()
        markTaskVisible(task1)
        markTaskVisible(task2)

        controller.showDesktopApps()

        verifyWCTNotExecuted()
    }

    @Test
    fun showDesktopApps_someAppsInvisible_reordersAll() {
        val homeTask = setUpHomeTask()
        val task1 = setUpFreeformTask()
        val task2 = setUpFreeformTask()
        markTaskHidden(task1)
        markTaskVisible(task2)

        controller.showDesktopApps()

        val wct = getLatestWct()
        assertThat(wct.hierarchyOps).hasSize(3)
        // Expect order to be from bottom: home, task1, task2
        wct.assertReorderAt(index = 0, homeTask)
        wct.assertReorderAt(index = 1, task1)
        wct.assertReorderAt(index = 2, task2)
    }

    @Test
    fun showDesktopApps_noActiveTasks_reorderHomeToTop() {
        val homeTask = setUpHomeTask()

        controller.showDesktopApps()

        val wct = getLatestWct()
        assertThat(wct.hierarchyOps).hasSize(1)
        wct.assertReorderAt(index = 0, homeTask)
    }

    @Test
    fun moveToDesktop() {
        val task = setUpFullscreenTask()
        controller.moveToDesktop(task)
        val wct = getLatestWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FREEFORM)
    }

    @Test
    fun moveToDesktop_nonExistentTask_doesNothing() {
        controller.moveToDesktop(999)
        verifyWCTNotExecuted()
    }

    @Test
    fun moveToFullscreen() {
        val task = setUpFreeformTask()
        controller.moveToFullscreen(task)
        val wct = getLatestWct()
        assertThat(wct.changes[task.token.asBinder()]?.windowingMode)
            .isEqualTo(WINDOWING_MODE_FULLSCREEN)
    }

    @Test
    fun moveToFullscreen_nonExistentTask_doesNothing() {
        controller.moveToFullscreen(999)
        verifyWCTNotExecuted()
    }

    @Test
    fun getTaskWindowingMode() {
        val fullscreenTask = setUpFullscreenTask()
        val freeformTask = setUpFreeformTask()

        assertThat(controller.getTaskWindowingMode(fullscreenTask.taskId))
            .isEqualTo(WINDOWING_MODE_FULLSCREEN)
        assertThat(controller.getTaskWindowingMode(freeformTask.taskId))
            .isEqualTo(WINDOWING_MODE_FREEFORM)
        assertThat(controller.getTaskWindowingMode(999)).isEqualTo(WINDOWING_MODE_UNDEFINED)
    }

    private fun setUpFreeformTask(): RunningTaskInfo {
        val task = createFreeformTask()
        whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        desktopModeTaskRepository.addActiveTask(task.taskId)
        desktopModeTaskRepository.addOrMoveFreeformTaskToTop(task.taskId)
        runningTasks.add(task)
        return task
    }

    private fun setUpHomeTask(): RunningTaskInfo {
        val task = createHomeTask()
        whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        runningTasks.add(task)
        return task
    }

    private fun setUpFullscreenTask(): RunningTaskInfo {
        val task = createFullscreenTask()
        whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        runningTasks.add(task)
        return task
    }

    private fun markTaskVisible(task: RunningTaskInfo) {
        desktopModeTaskRepository.updateVisibleFreeformTasks(task.taskId, visible = true)
    }

    private fun markTaskHidden(task: RunningTaskInfo) {
        desktopModeTaskRepository.updateVisibleFreeformTasks(task.taskId, visible = false)
    }

    private fun getLatestWct(): WindowContainerTransaction {
        val arg = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            verify(transitions).startTransition(anyInt(), arg.capture(), isNull())
        } else {
            verify(shellTaskOrganizer).applyTransaction(arg.capture())
        }
        return arg.value
    }

    private fun verifyWCTNotExecuted() {
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            verify(transitions, never()).startTransition(anyInt(), any(), isNull())
        } else {
            verify(shellTaskOrganizer, never()).applyTransaction(any())
        }
    }
}

private fun WindowContainerTransaction.assertReorderAt(index: Int, task: RunningTaskInfo) {
    assertWithMessage("WCT does not have a hierarchy operation at index $index")
        .that(hierarchyOps.size)
        .isGreaterThan(index)
    val op = hierarchyOps[index]
    assertThat(op.type).isEqualTo(HIERARCHY_OP_TYPE_REORDER)
    assertThat(op.container).isEqualTo(task.token.asBinder())
}
