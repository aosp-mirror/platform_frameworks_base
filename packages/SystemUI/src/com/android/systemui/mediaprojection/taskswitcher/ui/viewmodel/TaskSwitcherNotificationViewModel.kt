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

package com.android.systemui.mediaprojection.taskswitcher.ui.viewmodel

import android.app.ActivityManager.RunningTaskInfo
import android.util.Log
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.mediaprojection.taskswitcher.domain.interactor.TaskSwitchInteractor
import com.android.systemui.mediaprojection.taskswitcher.domain.model.TaskSwitchState
import com.android.systemui.mediaprojection.taskswitcher.ui.model.TaskSwitcherNotificationUiState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TaskSwitcherNotificationViewModel
@Inject
constructor(
    private val interactor: TaskSwitchInteractor,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {

    val uiState: Flow<TaskSwitcherNotificationUiState> =
        interactor.taskSwitchChanges.map { taskSwitchChange ->
            Log.d(TAG, "taskSwitchChange: $taskSwitchChange")
            when (taskSwitchChange) {
                is TaskSwitchState.TaskSwitched -> {
                    TaskSwitcherNotificationUiState.Showing(
                        projectedTask = taskSwitchChange.projectedTask,
                        foregroundTask = taskSwitchChange.foregroundTask,
                    )
                }
                is TaskSwitchState.NotProjectingTask,
                is TaskSwitchState.TaskUnchanged -> {
                    TaskSwitcherNotificationUiState.NotShowing
                }
            }
        }

    suspend fun onSwitchTaskClicked(task: RunningTaskInfo) {
        interactor.switchProjectedTask(task)
    }

    suspend fun onGoBackToTaskClicked(task: RunningTaskInfo) =
        withContext(backgroundDispatcher) { interactor.goBackToTask(task) }

    companion object {
        private const val TAG = "TaskSwitchNotifVM"
    }
}
