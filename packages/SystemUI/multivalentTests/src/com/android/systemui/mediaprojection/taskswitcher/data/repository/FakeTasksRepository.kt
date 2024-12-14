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

package com.android.systemui.mediaprojection.taskswitcher.data.repository

import android.app.ActivityManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Fake tasks repository that gives us fine-grained control over when the result of
 * [findRunningTaskFromWindowContainerToken] gets emitted.
 */
class FakeTasksRepository : TasksRepository {
    override suspend fun launchRecentTask(taskInfo: ActivityManager.RunningTaskInfo) {}

    private val findRunningTaskResult: CompletableDeferred<ActivityManager.RunningTaskInfo?> =
        CompletableDeferred()

    override suspend fun findRunningTaskFromWindowContainerToken(
        windowContainerToken: IBinder
    ): ActivityManager.RunningTaskInfo? {
        return findRunningTaskResult.await()
    }

    fun setRunningTaskResult(task: ActivityManager.RunningTaskInfo?) {
        findRunningTaskResult.complete(task)
    }

    override val foregroundTask: Flow<ActivityManager.RunningTaskInfo> = emptyFlow()
}
