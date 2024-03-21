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

package com.android.systemui.screenshot.policy

import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.os.UserHandle
import com.android.systemui.screenshot.data.model.DisplayContentModel
import com.android.systemui.screenshot.data.model.ProfileType
import com.android.systemui.screenshot.data.repository.ProfileTypeRepository
import com.android.systemui.screenshot.policy.CaptureType.IsolatedTask
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Condition: When the top visible task (excluding PIP mode) belongs to a work user.
 *
 * Parameters: Capture only the foreground task, owned by the work user.
 */
class WorkProfilePolicy
@Inject
constructor(
    private val profileTypes: ProfileTypeRepository,
) : CapturePolicy {
    override suspend fun apply(content: DisplayContentModel): CaptureParameters? {
        // Find the first non PiP rootTask with a top child task owned by a work user
        val (rootTask, childTask) =
            content.rootTasks
                .filter { it.isVisible && it.windowingMode != WINDOWING_MODE_PINNED }
                .map { it to it.childTasksTopDown().first() }
                .firstOrNull { (_, child) ->
                    profileTypes.getProfileType(child.userId) == ProfileType.WORK
                }
                ?: return null

        // If matched, return parameters needed to modify the request.
        return CaptureParameters(
            type = IsolatedTask(taskId = childTask.id, taskBounds = childTask.bounds),
            component = childTask.componentName ?: rootTask.topActivity,
            owner = UserHandle.of(childTask.userId),
        )
    }
}
