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
import com.android.systemui.shared.recents.model.ThumbnailData
import com.android.systemui.shared.system.ActivityManagerWrapper
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface RecentTaskThumbnailLoader {
    suspend fun loadThumbnail(taskId: Int): ThumbnailData?
}

class ActivityTaskManagerThumbnailLoader
@Inject
constructor(
    @Background private val coroutineDispatcher: CoroutineDispatcher,
    private val activityManager: ActivityManagerWrapper
) : RecentTaskThumbnailLoader {

    override suspend fun loadThumbnail(taskId: Int): ThumbnailData? =
        withContext(coroutineDispatcher) {
            val thumbnailData =
                activityManager.getTaskThumbnail(taskId, /* isLowResolution= */ false)
            if (thumbnailData.thumbnail == null) null else thumbnailData
        }
}
