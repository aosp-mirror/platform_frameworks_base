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
import android.content.Intent
import android.os.IBinder
import android.window.IWindowContainerToken
import android.window.WindowContainerToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeTasksRepository : TasksRepository {

    private val _foregroundTask = MutableStateFlow(DEFAULT_TASK)

    override val foregroundTask: Flow<RunningTaskInfo> = _foregroundTask.asStateFlow()

    private val runningTasks = mutableListOf(DEFAULT_TASK)

    override suspend fun findRunningTaskFromWindowContainerToken(
        windowContainerToken: IBinder
    ): RunningTaskInfo? = runningTasks.firstOrNull { it.token.asBinder() == windowContainerToken }

    fun addRunningTask(task: RunningTaskInfo) {
        runningTasks.add(task)
    }

    fun moveTaskToForeground(task: RunningTaskInfo) {
        _foregroundTask.value = task
    }

    companion object {
        val DEFAULT_TASK = createTask(taskId = -1)
        val LAUNCHER_INTENT: Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

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
