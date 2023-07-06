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
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.os.IBinder
import android.util.Log
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

/** Implementation of [TasksRepository] that uses [ActivityTaskManager] as the data source. */
@SysUISingleton
class ActivityTaskManagerTasksRepository
@Inject
constructor(
    private val activityTaskManager: ActivityTaskManager,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : TasksRepository {

    override suspend fun findRunningTaskFromWindowContainerToken(
        windowContainerToken: IBinder
    ): RunningTaskInfo? =
        getRunningTasks().firstOrNull { taskInfo ->
            taskInfo.token.asBinder() == windowContainerToken
        }

    private suspend fun getRunningTasks(): List<RunningTaskInfo> =
        withContext(backgroundDispatcher) { activityTaskManager.getTasks(Integer.MAX_VALUE) }

    override val foregroundTask: Flow<RunningTaskInfo> =
        conflatedCallbackFlow {
                val listener =
                    object : TaskStackListener() {
                        override fun onTaskMovedToFront(taskInfo: RunningTaskInfo) {
                            Log.d(TAG, "onTaskMovedToFront: $taskInfo")
                            trySendWithFailureLogging(taskInfo, TAG)
                        }
                    }
                activityTaskManager.registerTaskStackListener(listener)
                awaitClose { activityTaskManager.unregisterTaskStackListener(listener) }
            }
            .shareIn(applicationScope, SharingStarted.Lazily, replay = 1)

    companion object {
        private const val TAG = "TasksRepository"
    }
}
