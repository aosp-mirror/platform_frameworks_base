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

import android.app.ActivityTaskManager.RootTaskInfo
import android.app.WindowConfiguration
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.ComponentName
import android.graphics.Rect
import android.os.UserHandle
import android.util.Log
import com.android.systemui.screenshot.data.model.DisplayContentModel
import com.android.systemui.screenshot.data.model.ProfileType.PRIVATE
import com.android.systemui.screenshot.data.model.ProfileType.WORK
import com.android.systemui.screenshot.data.repository.ProfileTypeRepository
import com.android.systemui.screenshot.policy.CaptureType.FullScreen
import com.android.systemui.screenshot.policy.CaptureType.IsolatedTask
import javax.inject.Inject

private const val TAG = "ScreenshotPolicy"

/** Determines what to capture and which user owns the output. */
class ScreenshotPolicy @Inject constructor(private val profileTypes: ProfileTypeRepository) {
    /**
     * Apply the policy to the content, resulting in [LegacyCaptureParameters].
     *
     * @param content the content of the display
     * @param defaultComponent the component associated with the screenshot by default
     * @param defaultOwner the user to own the screenshot by default
     */
    suspend fun apply(
        content: DisplayContentModel,
        defaultComponent: ComponentName,
        defaultOwner: UserHandle,
    ): CaptureParameters {
        val defaultFullScreen by lazy {
            CaptureParameters(
                type = FullScreen(displayId = content.displayId),
                contentTask = TaskReference(-1, defaultComponent, defaultOwner, Rect()),
                owner = defaultOwner,
            )
        }

        // When the systemUI notification shade is open, disregard tasks.
        if (content.systemUiState.shadeExpanded) {
            return defaultFullScreen
        }

        // find the first (top) RootTask which is visible and not Picture-in-Picture
        val topRootTask =
            content.rootTasks.firstOrNull {
                it.isVisible && it.windowingMode != WindowConfiguration.WINDOWING_MODE_PINNED
            } ?: return defaultFullScreen

        Log.d(TAG, "topRootTask: $topRootTask")

        // When:
        // * there is one or more child task
        // * all owned by the same user
        // * this user is a work profile
        // * the root task is fullscreen or freeform-maximized
        //
        // Then:
        // the result will be a task snapshot instead of a full screen capture. If there is more
        // than one child task, the root task will be snapshot to include any/all child tasks. This
        // is intended to cover split-screen mode.
        val rootTaskOwners = topRootTask.childTaskUserIds.distinct()
        if (
            rootTaskOwners.size == 1 &&
                profileTypes.getProfileType(rootTaskOwners.single()) == WORK &&
                (topRootTask.isFullScreen() || topRootTask.isMaximizedFreeform())
        ) {
            val topChildTask = topRootTask.childTasksTopDown().first()

            // If there is more than one task, capture the parent to include both.
            val type =
                if (topRootTask.childTaskCount() > 1) {
                    IsolatedTask(taskId = topRootTask.taskId, taskBounds = topRootTask.bounds)
                } else {
                    // Otherwise capture the single task, and use its bounds.
                    IsolatedTask(taskId = topChildTask.id, taskBounds = topChildTask.bounds)
                }

            // The content task (the focus of the screenshot) must represent a single task
            // containing an activity, so always reference the top child task here. The owner
            // of the screenshot here is always the same as well.
            return CaptureParameters(
                type = type,
                contentTask =
                    TaskReference(
                        taskId = topChildTask.id,
                        component = topRootTask.topActivity ?: defaultComponent,
                        owner = UserHandle.of(topChildTask.userId),
                        bounds = topChildTask.bounds,
                    ),
                owner = UserHandle.of(topChildTask.userId),
            )
        }

        // In every other case the output will be a full screen capture regardless of content.
        // For this reason, consider all owners of all visible content on the display (in all
        // root tasks). This includes all root tasks in free-form mode.
        val visibleChildTasks =
            content.rootTasks.filter { it.isVisible }.flatMap { it.childTasksTopDown() }

        // Don't target a PIP window as the screenshot "content", it should only be used
        // to determine ownership (above).
        val contentTask =
            content.rootTasks
                .filter {
                    it.isVisible && it.windowingMode != WindowConfiguration.WINDOWING_MODE_PINNED
                }
                .flatMap { it.childTasksTopDown() }
                .first()

        val allVisibleProfileTypes =
            visibleChildTasks
                .map { it.userId }
                .distinct()
                .associate { profileTypes.getProfileType(it) to UserHandle.of(it) }

        // If any task is visible and owned by a PRIVATE profile user, the screenshot is assigned
        // to that user. Work profile has been handled above so it is not considered here. Fallback
        // to the default user which is the primary "current" user ('aka' personal "profile").
        val ownerHandle = allVisibleProfileTypes[PRIVATE] ?: defaultOwner

        return CaptureParameters(
            type = FullScreen(content.displayId),
            contentTask =
                TaskReference(
                    taskId = contentTask.id,
                    component = contentTask.componentName,
                    owner = UserHandle.of(contentTask.userId),
                    bounds = contentTask.bounds,
                ),
            owner = ownerHandle,
        )
    }

    private fun RootTaskInfo.isFullScreen(): Boolean =
        configuration.windowConfiguration.windowingMode == WINDOWING_MODE_FULLSCREEN

    private fun RootTaskInfo.isMaximizedFreeform(): Boolean {
        val bounds = configuration.windowConfiguration.bounds
        val maxBounds = configuration.windowConfiguration.maxBounds

        if (
            windowingMode != WINDOWING_MODE_FREEFORM ||
                childTaskCount() != 1 ||
                childTaskBounds[0] != bounds
        ) {
            return false
        }

        // Maximized floating windows fill maxBounds width
        if (bounds.width() != maxBounds.width()) {
            return false
        }

        // Maximized floating windows fill nearly all the height
        return (bounds.height().toFloat() / maxBounds.height()) >= 0.89f
    }
}
