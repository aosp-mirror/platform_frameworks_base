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
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.platform.test.annotations.EnableFlags
import android.view.Display.DEFAULT_DISPLAY
import android.view.WindowManager
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.IWindowContainerToken
import android.window.TransitionInfo
import android.window.TransitionInfo.Change
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.window.flags.Flags
import com.android.wm.shell.MockToken
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.back.BackAnimationController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.TRANSIT_EXIT_DESKTOP_MODE_TASK_DRAG
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isA
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DesktopTasksTransitionObserverTest {

    @JvmField
    @Rule
    val extendedMockitoRule =
        ExtendedMockitoRule.Builder(this).mockStatic(DesktopModeStatus::class.java).build()!!

    private val testExecutor = mock<ShellExecutor>()
    private val mockShellInit = mock<ShellInit>()
    private val transitions = mock<Transitions>()
    private val context = mock<Context>()
    private val shellTaskOrganizer = mock<ShellTaskOrganizer>()
    private val userRepositories = mock<DesktopUserRepositories>()
    private val taskRepository = mock<DesktopRepository>()
    private val mixedHandler = mock<DesktopMixedTransitionHandler>()
    private val backAnimationController = mock<BackAnimationController>()

    private lateinit var transitionObserver: DesktopTasksTransitionObserver
    private lateinit var shellInit: ShellInit

    @Before
    fun setup() {
        whenever(DesktopModeStatus.canEnterDesktopMode(any())).thenReturn(true)
        shellInit = spy(ShellInit(testExecutor))

        whenever(userRepositories.current).thenReturn(taskRepository)
        whenever(userRepositories.getProfile(anyInt())).thenReturn(taskRepository)

        transitionObserver =
            DesktopTasksTransitionObserver(
                context,
                userRepositories,
                transitions,
                shellTaskOrganizer,
                mixedHandler,
                backAnimationController,
                shellInit
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun backNavigation_taskMinimized() {
        val task = createTaskInfo(1)
        whenever(taskRepository.getVisibleTaskCount(any())).thenReturn(1)

        transitionObserver.onTransitionReady(
            transition = mock(),
            info = createBackNavigationTransition(task),
            startTransaction = mock(),
            finishTransaction = mock(),
        )

        verify(taskRepository).minimizeTask(task.displayId, task.taskId)
        verify(mixedHandler).addPendingMixedTransition(any())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun backNavigation_withCloseTransitionNotLastTask_taskMinimized() {
        val task = createTaskInfo(1)
        val transition = mock<IBinder>()
        whenever(taskRepository.getVisibleTaskCount(any())).thenReturn(2)
        whenever(taskRepository.isClosingTask(task.taskId)).thenReturn(false)
        whenever(backAnimationController.latestTriggerBackTask).thenReturn(task.taskId)

        transitionObserver.onTransitionReady(
            transition = transition,
            info = createBackNavigationTransition(task, TRANSIT_CLOSE),
            startTransaction = mock(),
            finishTransaction = mock(),
        )

        verify(taskRepository).minimizeTask(task.displayId, task.taskId)
        val pendingTransition =
            DesktopMixedTransitionHandler.PendingMixedTransition.Minimize(
                transition, task.taskId, isLastTask = false)
        verify(mixedHandler).addPendingMixedTransition(pendingTransition)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun backNavigation_withCloseTransitionLastTask_taskMinimized() {
        val task = createTaskInfo(1)
        val transition = mock<IBinder>()
        whenever(taskRepository.getVisibleTaskCount(any())).thenReturn(1)
        whenever(taskRepository.isClosingTask(task.taskId)).thenReturn(false)
        whenever(backAnimationController.latestTriggerBackTask).thenReturn(task.taskId)

        transitionObserver.onTransitionReady(
            transition = transition,
            info = createBackNavigationTransition(task, TRANSIT_CLOSE, true),
            startTransaction = mock(),
            finishTransaction = mock(),
        )

        verify(taskRepository).minimizeTask(task.displayId, task.taskId)
        val pendingTransition =
            DesktopMixedTransitionHandler.PendingMixedTransition.Minimize(
                transition, task.taskId, isLastTask = true)
        verify(mixedHandler).addPendingMixedTransition(pendingTransition)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun backNavigation_nullTaskInfo_taskNotMinimized() {
        val task = createTaskInfo(1)
        whenever(taskRepository.getVisibleTaskCount(any())).thenReturn(1)

        transitionObserver.onTransitionReady(
            transition = mock(),
            info = createBackNavigationTransition(null),
            startTransaction = mock(),
            finishTransaction = mock(),
        )

        verify(taskRepository, never()).minimizeTask(task.displayId, task.taskId)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun removeTasks_onTaskFullscreenLaunchWithOpenTransition_taskRemovedFromRepo() {
        val task = createTaskInfo(1, WINDOWING_MODE_FULLSCREEN)
        whenever(taskRepository.getVisibleTaskCount(any())).thenReturn(1)
        whenever(taskRepository.isActiveTask(task.taskId)).thenReturn(true)

        transitionObserver.onTransitionReady(
            transition = mock(),
            info = createOpenChangeTransition(task),
            startTransaction = mock(),
            finishTransaction = mock(),
        )

        verify(taskRepository, never()).minimizeTask(task.displayId, task.taskId)
        verify(taskRepository).removeFreeformTask(task.displayId, task.taskId)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun removeTasks_onTaskFullscreenLaunchExitDesktopTransition_taskRemovedFromRepo() {
        val task = createTaskInfo(1, WINDOWING_MODE_FULLSCREEN)
        whenever(taskRepository.getVisibleTaskCount(any())).thenReturn(1)
        whenever(taskRepository.isActiveTask(task.taskId)).thenReturn(true)

        transitionObserver.onTransitionReady(
            transition = mock(),
            info = createOpenChangeTransition(task, TRANSIT_EXIT_DESKTOP_MODE_TASK_DRAG),
            startTransaction = mock(),
            finishTransaction = mock(),
        )

        verify(taskRepository, never()).minimizeTask(task.displayId, task.taskId)
        verify(taskRepository).removeFreeformTask(task.displayId, task.taskId)
    }

    @Test
    fun closeLastTask_wallpaperTokenExists_wallpaperIsRemoved() {
        val mockTransition = Mockito.mock(IBinder::class.java)
        val task = createTaskInfo(1, WINDOWING_MODE_FREEFORM)
        val wallpaperToken = MockToken().token()
        whenever(taskRepository.getVisibleTaskCount(task.displayId)).thenReturn(0)
        whenever(taskRepository.wallpaperActivityToken).thenReturn(wallpaperToken)

        transitionObserver.onTransitionReady(
            transition = mockTransition,
            info = createCloseTransition(task),
            startTransaction = mock(),
            finishTransaction = mock(),
        )
        transitionObserver.onTransitionFinished(mockTransition, false)

        val wct = getLatestWct(type = TRANSIT_CLOSE)
        assertThat(wct.hierarchyOps).hasSize(1)
        wct.assertRemoveAt(index = 0, wallpaperToken)
    }

    private fun createBackNavigationTransition(
        task: RunningTaskInfo?,
        type: Int = TRANSIT_TO_BACK,
        withWallpaper: Boolean = false,
    ): TransitionInfo {
        return TransitionInfo(type, 0 /* flags */).apply {
            addChange(
                Change(mock(), mock()).apply {
                    mode = type
                    parent = null
                    taskInfo = task
                    flags = flags
                })
            if (withWallpaper) {
                addChange(
                    Change(mock(), mock()).apply {
                        mode = TRANSIT_CLOSE
                        parent = null
                        taskInfo = createWallpaperTaskInfo()
                        flags = flags
                    })
            }
        }
    }

    private fun createOpenChangeTransition(
        task: RunningTaskInfo?,
        type: Int = TRANSIT_OPEN
    ): TransitionInfo {
        return TransitionInfo(TRANSIT_OPEN, 0 /* flags */).apply {
            addChange(
                Change(mock(), mock()).apply {
                    mode = TRANSIT_OPEN
                    parent = null
                    taskInfo = task
                    flags = flags
                })
        }
    }

    private fun createCloseTransition(task: RunningTaskInfo?): TransitionInfo {
        return TransitionInfo(TRANSIT_CLOSE, 0 /* flags */).apply {
            addChange(
                Change(mock(), mock()).apply {
                    mode = TRANSIT_CLOSE
                    parent = null
                    taskInfo = task
                    flags = flags
                })
        }
    }

    private fun getLatestWct(
        @WindowManager.TransitionType type: Int = TRANSIT_OPEN,
        handlerClass: Class<out Transitions.TransitionHandler>? = null
    ): WindowContainerTransaction {
        val arg = ArgumentCaptor.forClass(WindowContainerTransaction::class.java)
        if (handlerClass == null) {
            Mockito.verify(transitions).startTransition(eq(type), arg.capture(), isNull())
        } else {
            Mockito.verify(transitions).startTransition(eq(type), arg.capture(), isA(handlerClass))
        }
        return arg.value
    }

    private fun WindowContainerTransaction.assertRemoveAt(index: Int, token: WindowContainerToken) {
        assertIndexInBounds(index)
        val op = hierarchyOps[index]
        assertThat(op.type).isEqualTo(HIERARCHY_OP_TYPE_REMOVE_TASK)
        assertThat(op.container).isEqualTo(token.asBinder())
    }

    private fun WindowContainerTransaction.assertIndexInBounds(index: Int) {
        assertWithMessage("WCT does not have a hierarchy operation at index $index")
            .that(hierarchyOps.size)
            .isGreaterThan(index)
    }

    private fun createTaskInfo(id: Int, windowingMode: Int = WINDOWING_MODE_FREEFORM) =
        RunningTaskInfo().apply {
            taskId = id
            displayId = DEFAULT_DISPLAY
            configuration.windowConfiguration.windowingMode = windowingMode
            token = WindowContainerToken(Mockito.mock(IWindowContainerToken::class.java))
            baseIntent = Intent().apply { component = ComponentName("package", "component.name") }
        }

    private fun createWallpaperTaskInfo() =
        RunningTaskInfo().apply {
            token = mock<WindowContainerToken>()
            baseIntent =
                Intent().apply {
                    component = DesktopWallpaperActivity.wallpaperActivityComponent
                }
        }
}
