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
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.content.ComponentName
import android.os.UserHandle
import android.util.Log
import com.android.systemui.screenshot.data.model.DisplayContentModel
import com.android.systemui.screenshot.data.model.ProfileType
import com.android.systemui.screenshot.data.model.ProfileType.PRIVATE
import com.android.systemui.screenshot.data.model.ProfileType.WORK
import com.android.systemui.screenshot.data.repository.ProfileTypeRepository
import com.android.systemui.screenshot.policy.CaptureType.FullScreen
import com.android.systemui.screenshot.policy.CaptureType.IsolatedTask
import com.android.systemui.screenshot.policy.CaptureType.RootTask
import javax.inject.Inject

private const val TAG = "ScreenshotPolicy"

/** Determines what to capture and which user owns the output. */
class ScreenshotPolicy @Inject constructor(private val profileTypes: ProfileTypeRepository) {
    /**
     * Apply the policy to the content, resulting in [CaptureParameters].
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
                component = defaultComponent,
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
        val rootTaskOwners = topRootTask.childTaskUserIds.distinct()

        // Special case: Only WORK in top root task which is full-screen or maximized freeform
        if (
            rootTaskOwners.size == 1 &&
                profileTypes.getProfileType(rootTaskOwners.single()) == WORK &&
                (topRootTask.isFullScreen() || topRootTask.isMaximizedFreeform())
        ) {
            val type =
                if (topRootTask.childTaskCount() > 1) {
                    RootTask(
                        parentTaskId = topRootTask.taskId,
                        taskBounds = topRootTask.bounds,
                        childTaskIds = topRootTask.childTasksTopDown().map { it.id }.toList(),
                    )
                } else {
                    IsolatedTask(
                        taskId = topRootTask.childTasksTopDown().first().id,
                        taskBounds = topRootTask.bounds,
                    )
                }
            // Capture the RootTask (and all children)
            return CaptureParameters(
                type = type,
                component = topRootTask.topActivity,
                owner = UserHandle.of(rootTaskOwners.single()),
            )
        }

        // In every other case the output will be a full screen capture regardless of content.
        // For this reason, consider all owners of all visible content on the display (in all
        // root tasks). This includes all root tasks in free-form mode.
        val visibleChildTasks =
            content.rootTasks.filter { it.isVisible }.flatMap { it.childTasksTopDown() }

        val allVisibleProfileTypes =
            visibleChildTasks
                .map { it.userId }
                .distinct()
                .associate { profileTypes.getProfileType(it) to UserHandle.of(it) }

        // If any visible content belongs to the private profile user -> private profile
        // otherwise the personal user (including partial screen work content).
        val ownerHandle =
            allVisibleProfileTypes[PRIVATE]
                ?: allVisibleProfileTypes[ProfileType.NONE]
                ?: defaultOwner

        // Attribute to the component of top-most task owned by this user (or fallback to default)
        val topComponent =
            visibleChildTasks.firstOrNull { it.userId == ownerHandle.identifier }?.componentName

        return CaptureParameters(
            type = FullScreen(content.displayId),
            component = topComponent ?: topRootTask.topActivity ?: defaultComponent,
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
