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
import android.os.IBinder
import kotlinx.coroutines.flow.Flow

/** Repository responsible for retrieving data related to running tasks. */
interface TasksRepository {

    suspend fun launchRecentTask(taskInfo: RunningTaskInfo)

    /**
     * Tries to find a [RunningTaskInfo] with a matching window container token. Returns `null` when
     * no matching task was found.
     */
    suspend fun findRunningTaskFromWindowContainerToken(
        windowContainerToken: IBinder
    ): RunningTaskInfo?

    /**
     * Emits a stream of [RunningTaskInfo] that have been moved to the foreground.
     *
     * Note: when subscribing for the first time, it will not immediately emit the current
     * foreground task. Only after a change in foreground task has occurred.
     */
    val foregroundTask: Flow<RunningTaskInfo>
}
