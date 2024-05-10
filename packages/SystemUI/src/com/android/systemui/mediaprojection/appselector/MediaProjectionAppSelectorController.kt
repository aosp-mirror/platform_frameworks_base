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

package com.android.systemui.mediaprojection.appselector

import android.content.ComponentName
import android.os.UserHandle
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.data.RecentTaskListProvider
import com.android.systemui.mediaprojection.appselector.data.RecentTaskThumbnailLoader
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDevicePolicyResolver
import com.android.systemui.shared.recents.model.ThumbnailData
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@MediaProjectionAppSelectorScope
class MediaProjectionAppSelectorController
@Inject
constructor(
    private val recentTaskListProvider: RecentTaskListProvider,
    private val view: MediaProjectionAppSelectorView,
    private val devicePolicyResolver: ScreenCaptureDevicePolicyResolver,
    @HostUserHandle private val hostUserHandle: UserHandle,
    @MediaProjectionAppSelector private val scope: CoroutineScope,
    @MediaProjectionAppSelector private val appSelectorComponentName: ComponentName,
    @MediaProjectionAppSelector private val callerPackageName: String?,
    private val thumbnailLoader: RecentTaskThumbnailLoader,
    @MediaProjectionAppSelector private val isFirstStart: Boolean,
    private val logger: MediaProjectionMetricsLogger,
    @HostUid private val hostUid: Int,
) {

    fun init() {
        // Only log during the first start of the app selector.
        // Don't log when the app selector restarts due to a config change.
        if (isFirstStart) {
            logger.notifyAppSelectorDisplayed(hostUid)
        }

        scope.launch {
            val recentTasks = recentTaskListProvider.loadRecentTasks()

            val tasks =
                recentTasks.filterDevicePolicyRestrictedTasks().filterAppSelector().sortedTasks()

            // Thumbnails are not fresh for the foreground task(s). They are only refreshed at
            // launch, going to home, or going to overview.
            // For this reason, we need to refresh them here.
            refreshForegroundTaskThumbnails(tasks)

            view.bind(tasks)
        }
    }

    fun destroy() {
        scope.cancel()
    }

    fun onSelectorDismissed() {
        logger.notifyProjectionRequestCancelled(hostUid)
    }

    private suspend fun refreshForegroundTaskThumbnails(tasks: List<RecentTask>) {
        coroutineScope {
            val thumbnails: List<Deferred<ThumbnailData?>> =
                tasks
                    .filter { it.isForegroundTask }
                    .map { async { thumbnailLoader.captureThumbnail(it.taskId) } }
            thumbnails.forEach { thumbnail -> thumbnail.await() }
        }
    }

    /** Removes all recent tasks that should be blocked according to the policy */
    private fun List<RecentTask>.filterDevicePolicyRestrictedTasks(): List<RecentTask> = filter {
        devicePolicyResolver.isScreenCaptureAllowed(
            targetAppUserHandle = UserHandle.of(it.userId),
            hostAppUserHandle = hostUserHandle
        )
    }

    private fun List<RecentTask>.filterAppSelector(): List<RecentTask> = filter {
        // Only take tasks that is not the app selector
        it.topActivityComponent != appSelectorComponentName
    }

    private fun List<RecentTask>.sortedTasks(): List<RecentTask> = sortedBy {
        // Show normal tasks first and only then tasks with opened app selector
        it.topActivityComponent?.packageName == callerPackageName
    }
}
