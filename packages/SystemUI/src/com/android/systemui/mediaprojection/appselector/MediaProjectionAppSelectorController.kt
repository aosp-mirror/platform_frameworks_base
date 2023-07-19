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
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.data.RecentTaskListProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@MediaProjectionAppSelectorScope
class MediaProjectionAppSelectorController @Inject constructor(
    private val recentTaskListProvider: RecentTaskListProvider,
    private val view: MediaProjectionAppSelectorView,
    @MediaProjectionAppSelector private val scope: CoroutineScope,
    @MediaProjectionAppSelector private val appSelectorComponentName: ComponentName
) {

    fun init() {
        scope.launch {
            val tasks = recentTaskListProvider.loadRecentTasks().sortTasks()
            view.bind(tasks)
        }
    }

    fun destroy() {
        scope.cancel()
    }

    private fun List<RecentTask>.sortTasks(): List<RecentTask> =
        sortedBy {
            // Show normal tasks first and only then tasks with opened app selector
            it.topActivityComponent == appSelectorComponentName
        }
}
