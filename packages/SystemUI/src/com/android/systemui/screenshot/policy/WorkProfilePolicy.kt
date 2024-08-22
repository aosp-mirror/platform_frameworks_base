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

import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.content.Context
import android.os.UserHandle
import com.android.systemui.screenshot.data.model.DisplayContentModel
import com.android.systemui.screenshot.data.model.ProfileType
import com.android.systemui.screenshot.data.repository.ProfileTypeRepository
import com.android.systemui.screenshot.policy.CapturePolicy.PolicyResult
import com.android.systemui.screenshot.policy.CapturePolicy.PolicyResult.NotMatched
import com.android.systemui.screenshot.policy.CaptureType.IsolatedTask
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
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
    private val context: Context,
) : CapturePolicy {

    override suspend fun check(content: DisplayContentModel): PolicyResult {
        // The systemUI notification shade isn't a work app, skip.
        if (content.systemUiState.shadeExpanded) {
            return NotMatched(policy = NAME, reason = SHADE_EXPANDED)
        }

        if (DesktopModeStatus.canEnterDesktopMode(context)) {
            content.rootTasks.firstOrNull()?.also {
                if (it.windowingMode == WINDOWING_MODE_FREEFORM) {
                    return NotMatched(policy = NAME, reason = DESKTOP_MODE_ENABLED)
                }
            }
        }

        // Find the first non PiP rootTask with a top child task owned by a work user
        val (rootTask, childTask) =
            content.rootTasks
                .filter {
                    it.isVisible && it.windowingMode != WINDOWING_MODE_PINNED && it.hasChildTasks()
                }
                .map { it to it.childTasksTopDown().first() }
                .firstOrNull { (_, child) ->
                    profileTypes.getProfileType(child.userId) == ProfileType.WORK
                }
                ?: return NotMatched(
                    policy = NAME,
                    reason = WORK_TASK_NOT_TOP,
                )

        // If matched, return parameters needed to modify the request.
        return PolicyResult.Matched(
            policy = NAME,
            reason = WORK_TASK_IS_TOP,
            CaptureParameters(
                type = IsolatedTask(taskId = childTask.id, taskBounds = childTask.bounds),
                component = childTask.componentName ?: rootTask.topActivity,
                owner = UserHandle.of(childTask.userId),
            )
        )
    }

    companion object {
        const val NAME = "WorkProfile"
        const val SHADE_EXPANDED = "Notification shade is expanded"
        const val WORK_TASK_NOT_TOP =
            "The top-most non-PINNED task does not belong to a work profile user"
        const val WORK_TASK_IS_TOP = "The top-most non-PINNED task belongs to a work profile user"
        const val DESKTOP_MODE_ENABLED =
            "enable_desktop_windowing_mode is enabled and top " +
                "RootTask has WINDOWING_MODE_FREEFORM"
    }
}
