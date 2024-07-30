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

package com.android.systemui.mediaprojection.taskswitcher.domain.interactor

import android.app.ActivityManager.RunningTaskInfo
import android.app.TaskInfo
import android.content.Intent
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.MediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.data.repository.TasksRepository
import com.android.systemui.mediaprojection.taskswitcher.domain.model.TaskSwitchState
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Interactor with logic related to task switching in the context of media projection. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class TaskSwitchInteractor
@Inject
constructor(
    private val mediaProjectionRepository: MediaProjectionRepository,
    private val tasksRepository: TasksRepository,
) {

    suspend fun switchProjectedTask(task: RunningTaskInfo) {
        mediaProjectionRepository.switchProjectedTask(task)
    }

    suspend fun goBackToTask(task: RunningTaskInfo) {
        tasksRepository.launchRecentTask(task)
    }

    /**
     * Emits a stream of changes to the state of task switching, in the context of media projection.
     */
    val taskSwitchChanges: Flow<TaskSwitchState> =
        mediaProjectionRepository.mediaProjectionState.flatMapLatest { projectionState ->
            Log.d(TAG, "MediaProjectionState -> $projectionState")
            when (projectionState) {
                is MediaProjectionState.Projecting.SingleTask -> {
                    val projectedTask = projectionState.task
                    tasksRepository.foregroundTask.map { foregroundTask ->
                        if (hasForegroundTaskSwitched(projectedTask, foregroundTask)) {
                            TaskSwitchState.TaskSwitched(projectedTask, foregroundTask)
                        } else {
                            TaskSwitchState.TaskUnchanged
                        }
                    }
                }
                is MediaProjectionState.Projecting.EntireScreen,
                is MediaProjectionState.NotProjecting -> {
                    flowOf(TaskSwitchState.NotProjectingTask)
                }
            }
        }

    /**
     * Returns whether tasks have been switched.
     *
     * Always returns `false` when launcher is in the foreground. The reason is that when going to
     * recents to switch apps, launcher becomes the new foreground task, and we don't want to show
     * the notification then.
     */
    private fun hasForegroundTaskSwitched(projectedTask: TaskInfo, foregroundTask: TaskInfo) =
        projectedTask.taskId != foregroundTask.taskId && !foregroundTask.isLauncher

    private val TaskInfo.isLauncher
        get() =
            baseIntent.hasCategory(Intent.CATEGORY_HOME) && baseIntent.action == Intent.ACTION_MAIN

    companion object {
        private const val TAG = "TaskSwitchInteractor"
    }
}
