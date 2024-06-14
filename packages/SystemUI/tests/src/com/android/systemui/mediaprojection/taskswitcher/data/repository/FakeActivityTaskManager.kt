/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.mediaprojection.taskswitcher.data.repository

import android.app.ActivityManager.RunningTaskInfo
import android.app.IActivityTaskManager
import android.app.TaskStackListener
import android.content.Intent
import android.window.IWindowContainerToken
import android.window.WindowContainerToken
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

class FakeActivityTaskManager {

    private val runningTasks = mutableListOf<RunningTaskInfo>()
    private val taskTaskListeners = mutableListOf<TaskStackListener>()

    val activityTaskManager = mock<IActivityTaskManager>()

    init {
        whenever(activityTaskManager.registerTaskStackListener(any())).thenAnswer {
            taskTaskListeners += it.arguments[0] as TaskStackListener
            return@thenAnswer Unit
        }
        whenever(activityTaskManager.unregisterTaskStackListener(any())).thenAnswer {
            taskTaskListeners -= it.arguments[0] as TaskStackListener
            return@thenAnswer Unit
        }
        whenever(activityTaskManager.getTasks(any(), any(), any(), any())).thenAnswer {
            val maxNumTasks = it.arguments[0] as Int
            return@thenAnswer runningTasks.take(maxNumTasks)
        }
        whenever(activityTaskManager.startActivityFromRecents(any(), any())).thenAnswer {
            val taskId = it.arguments[0] as Int
            val runningTask = runningTasks.find { runningTask -> runningTask.taskId == taskId }
            if (runningTask != null) {
                moveTaskToForeground(runningTask)
                return@thenAnswer 0
            } else {
                return@thenAnswer -1
            }
        }
    }

    fun moveTaskToForeground(task: RunningTaskInfo) {
        taskTaskListeners.forEach { it.onTaskMovedToFront(task) }
    }

    fun addRunningTasks(vararg tasks: RunningTaskInfo) {
        runningTasks += tasks
    }

    companion object {

        fun createTask(
            taskId: Int,
            token: WindowContainerToken = createToken(),
            baseIntent: Intent = Intent()
        ) =
            RunningTaskInfo().apply {
                this.taskId = taskId
                this.token = token
                this.baseIntent = baseIntent
            }

        fun createToken(): WindowContainerToken {
            val realToken = object : IWindowContainerToken.Stub() {}
            return WindowContainerToken(realToken)
        }
    }
}
