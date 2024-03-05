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

package com.android.systemui.mediaprojection.taskswitcher.domain.model

import android.app.ActivityManager.RunningTaskInfo

/** Represents tha state of task switching in the context of single task media projection. */
sealed interface TaskSwitchState {
    /** Currently no task is being projected. */
    object NotProjectingTask : TaskSwitchState
    /** The foreground task is the same as the task that is currently being projected. */
    object TaskUnchanged : TaskSwitchState
    /** The foreground task is a different one to the task it currently being projected. */
    data class TaskSwitched(
        val projectedTask: RunningTaskInfo,
        val foregroundTask: RunningTaskInfo
    ) : TaskSwitchState
}
