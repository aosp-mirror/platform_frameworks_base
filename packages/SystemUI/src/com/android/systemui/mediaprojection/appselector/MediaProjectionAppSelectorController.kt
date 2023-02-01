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
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.data.RecentTaskListProvider
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@MediaProjectionAppSelectorScope
class MediaProjectionAppSelectorController
@Inject
constructor(
    private val recentTaskListProvider: RecentTaskListProvider,
    private val view: MediaProjectionAppSelectorView,
    private val flags: FeatureFlags,
    @HostUserHandle private val hostUserHandle: UserHandle,
    @MediaProjectionAppSelector private val scope: CoroutineScope,
    @MediaProjectionAppSelector private val appSelectorComponentName: ComponentName
) {

    fun init() {
        scope.launch {
            val recentTasks = recentTaskListProvider.loadRecentTasks()

            val tasks = recentTasks
                .filterDevicePolicyRestrictedTasks()
                .sortedTasks()

            view.bind(tasks)
        }
    }

    fun destroy() {
        scope.cancel()
    }

    /**
     * Removes all recent tasks that are different from the profile of the host app to avoid any
     * cross-profile sharing
     */
    private fun List<RecentTask>.filterDevicePolicyRestrictedTasks(): List<RecentTask> =
        if (flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES)) {
            // TODO(b/263950746): filter tasks based on the enterprise policies
            this
        } else {
            filter { UserHandle.of(it.userId) == hostUserHandle }
        }

    private fun List<RecentTask>.sortedTasks(): List<RecentTask> = sortedBy {
        // Show normal tasks first and only then tasks with opened app selector
        it.topActivityComponent == appSelectorComponentName
    }
}
