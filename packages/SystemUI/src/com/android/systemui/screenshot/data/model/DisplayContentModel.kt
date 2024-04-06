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

package com.android.systemui.screenshot.data.model

import android.app.ActivityTaskManager.RootTaskInfo

/** Information about the tasks on a display. */
data class DisplayContentModel(
    /** The id of the display. */
    val displayId: Int,
    /** Information about the current System UI state which can affect capture. */
    val systemUiState: SystemUiState,
    /** A list of root tasks on the display, ordered from top to bottom along the z-axis */
    val rootTasks: List<RootTaskInfo>,
)
