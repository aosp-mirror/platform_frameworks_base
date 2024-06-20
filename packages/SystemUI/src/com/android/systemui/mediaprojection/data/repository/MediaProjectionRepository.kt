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

package com.android.systemui.mediaprojection.data.repository

import android.app.ActivityManager.RunningTaskInfo
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import kotlinx.coroutines.flow.Flow

/** Represents a repository to retrieve and change data related to media projection. */
interface MediaProjectionRepository {

    /** Switches the task that should be projected. */
    suspend fun switchProjectedTask(task: RunningTaskInfo)

    /** Stops the currently active projection. */
    suspend fun stopProjecting()

    /** Represents the current [MediaProjectionState]. */
    val mediaProjectionState: Flow<MediaProjectionState>
}
