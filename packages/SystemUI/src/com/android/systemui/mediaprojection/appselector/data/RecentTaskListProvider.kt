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

import com.android.systemui.dagger.qualifiers.Background
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

interface RecentTaskListProvider {
    suspend fun loadRecentTasks(): List<RecentTask>
}

class ShellRecentTaskListProvider
@Inject
constructor(@Background private val coroutineDispatcher: CoroutineDispatcher) :
    RecentTaskListProvider {

    override suspend fun loadRecentTasks(): List<RecentTask> =
        withContext(coroutineDispatcher) {
            // TODO(b/240924731): add blocking call to load the recents
            emptyList()
        }
}
