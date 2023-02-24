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

package com.android.systemui.mediaprojection.appselector.data

import android.app.ActivityManager
import android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.kotlin.getOrNull
import com.android.wm.shell.recents.RecentTasks
import com.android.wm.shell.util.GroupedRecentTaskInfo
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

interface RecentTaskListProvider {
    /** Loads recent tasks, the returned task list is from the most-recent to least-recent order */
    suspend fun loadRecentTasks(): List<RecentTask>
}

class ShellRecentTaskListProvider
@Inject
constructor(
    @Background private val coroutineDispatcher: CoroutineDispatcher,
    @Background private val backgroundExecutor: Executor,
    private val recentTasks: Optional<RecentTasks>
) : RecentTaskListProvider {

    private val recents by lazy { recentTasks.getOrNull() }

    override suspend fun loadRecentTasks(): List<RecentTask> =
        withContext(coroutineDispatcher) {
            val rawRecentTasks: List<GroupedRecentTaskInfo> = recents?.getTasks() ?: emptyList()

            rawRecentTasks
                .flatMap { listOfNotNull(it.taskInfo1, it.taskInfo2) }
                .map {
                    RecentTask(
                        it.taskId,
                        it.userId,
                        it.topActivity,
                        it.baseIntent?.component,
                        it.taskDescription?.backgroundColor
                    )
                }
        }

    private suspend fun RecentTasks.getTasks(): List<GroupedRecentTaskInfo> =
        suspendCoroutine { continuation ->
            getRecentTasks(
                Integer.MAX_VALUE,
                RECENT_IGNORE_UNAVAILABLE,
                ActivityManager.getCurrentUser(),
                backgroundExecutor
            ) { tasks ->
                continuation.resume(tasks)
            }
        }
}
