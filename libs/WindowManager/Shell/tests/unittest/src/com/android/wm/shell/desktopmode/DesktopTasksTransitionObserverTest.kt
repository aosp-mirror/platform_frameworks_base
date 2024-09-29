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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.platform.test.annotations.EnableFlags
import android.view.Display.DEFAULT_DISPLAY
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.IWindowContainerToken
import android.window.TransitionInfo
import android.window.TransitionInfo.Change
import android.window.WindowContainerToken
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DesktopTasksTransitionObserverTest {

    @JvmField
    @Rule
    val extendedMockitoRule =
        ExtendedMockitoRule.Builder(this)
            .mockStatic(DesktopModeStatus::class.java)
            .build()!!

    private val testExecutor = mock<ShellExecutor>()
    private val mockShellInit = mock<ShellInit>()
    private val transitions = mock<Transitions>()
    private val context = mock<Context>()
    private val shellTaskOrganizer = mock<ShellTaskOrganizer>()
    private val taskRepository = mock<DesktopModeTaskRepository>()

    private lateinit var transitionObserver: DesktopTasksTransitionObserver
    private lateinit var shellInit: ShellInit

    @Before
    fun setup() {
        whenever(DesktopModeStatus.canEnterDesktopMode(any())).thenReturn(true)
        shellInit = spy(ShellInit(testExecutor))

        transitionObserver =
            DesktopTasksTransitionObserver(
                context, taskRepository, transitions, shellTaskOrganizer, shellInit
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun backNavigation_taskMinimized() {
        val task = createTaskInfo(1)
        whenever(taskRepository.getVisibleTaskCount(any())).thenReturn(1)

        transitionObserver.onTransitionReady(
            transition = mock(),
            info =
            createBackNavigationTransition(task),
            startTransaction = mock(),
            finishTransaction = mock(),
        )

        verify(taskRepository).minimizeTask(task.displayId, task.taskId)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun backNavigation_nullTaskInfo_taskNotMinimized() {
        val task = createTaskInfo(1)
        whenever(taskRepository.getVisibleTaskCount(any())).thenReturn(1)

        transitionObserver.onTransitionReady(
            transition = mock(),
            info =
            createBackNavigationTransition(null),
            startTransaction = mock(),
            finishTransaction = mock(),
        )

        verify(taskRepository, never()).minimizeTask(task.displayId, task.taskId)
    }

    private fun createBackNavigationTransition(
        task: RunningTaskInfo?
    ): TransitionInfo {
        return TransitionInfo(TRANSIT_TO_BACK, 0 /* flags */).apply {
            addChange(
                Change(mock(), mock()).apply {
                    mode = TRANSIT_TO_BACK
                    parent = null
                    taskInfo = task
                    flags = flags
                }
            )
        }
    }

    private fun createTaskInfo(id: Int) =
        RunningTaskInfo().apply {
            taskId = id
            displayId = DEFAULT_DISPLAY
            configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM
            token = WindowContainerToken(Mockito.mock(IWindowContainerToken::class.java))
            baseIntent = Intent().apply {
                component = ComponentName("package", "component.name")
            }
        }
}
