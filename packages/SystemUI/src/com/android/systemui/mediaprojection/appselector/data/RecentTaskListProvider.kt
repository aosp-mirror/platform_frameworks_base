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

import android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE
import android.content.pm.UserInfo
import android.os.UserManager
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.kotlin.getOrNull
import com.android.wm.shell.recents.RecentTasks
import com.android.wm.shell.shared.GroupedRecentTaskInfo
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface RecentTaskListProvider {
    /** Loads recent tasks, the returned task list is from the most-recent to least-recent order */
    suspend fun loadRecentTasks(): List<RecentTask>
}

class ShellRecentTaskListProvider
@Inject
constructor(
    @Background private val coroutineDispatcher: CoroutineDispatcher,
    @Background private val backgroundExecutor: Executor,
    private val recentTasks: Optional<RecentTasks>,
    private val userTracker: UserTracker,
    private val userManager: UserManager,
) : RecentTaskListProvider {

    private val recents by lazy { recentTasks.getOrNull() }

    override suspend fun loadRecentTasks(): List<RecentTask> =
        withContext(coroutineDispatcher) {
            val groupedTasks: List<GroupedRecentTaskInfo> = recents?.getTasks() ?: emptyList()
            // Note: the returned task list is from the most-recent to least-recent order.
            // When opening the app selector in full screen, index 0 will be just the app selector
            // activity and a null second task, so the foreground task will be index 1, but when
            // opening the app selector in split screen mode, the foreground task will be the second
            // task in index 0.
            val foregroundGroup =
                if (groupedTasks.firstOrNull()?.splitBounds != null) groupedTasks.first()
                else groupedTasks.elementAtOrNull(1)
            val foregroundTaskId1 = foregroundGroup?.taskInfo1?.taskId
            val foregroundTaskId2 = foregroundGroup?.taskInfo2?.taskId
            val foregroundTaskIds = listOfNotNull(foregroundTaskId1, foregroundTaskId2)
            groupedTasks.flatMap {
                val task1 =
                    RecentTask(
                        it.taskInfo1,
                        it.taskInfo1.taskId in foregroundTaskIds && it.taskInfo1.isVisible,
                        userManager.getUserInfo(it.taskInfo1.userId).toUserType(),
                        it.splitBounds
                    )

                val task2 =
                    if (it.taskInfo2 != null) {
                        RecentTask(
                            it.taskInfo2!!,
                            it.taskInfo2!!.taskId in foregroundTaskIds && it.taskInfo2!!.isVisible,
                            userManager.getUserInfo(it.taskInfo2!!.userId).toUserType(),
                            it.splitBounds
                        )
                    } else null

                listOfNotNull(task1, task2)
            }
        }

    private suspend fun RecentTasks.getTasks(): List<GroupedRecentTaskInfo> =
        suspendCoroutine { continuation ->
            getRecentTasks(
                Integer.MAX_VALUE,
                RECENT_IGNORE_UNAVAILABLE,
                userTracker.userId,
                backgroundExecutor
            ) { tasks ->
                continuation.resume(tasks)
            }
        }

    private fun UserInfo.toUserType(): RecentTask.UserType =
        if (isCloneProfile) {
            RecentTask.UserType.CLONED
        } else if (isManagedProfile) {
            RecentTask.UserType.WORK
        } else if (isPrivateProfile) {
            RecentTask.UserType.PRIVATE
        } else {
            RecentTask.UserType.STANDARD
        }
}
