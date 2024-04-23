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
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Process.myUserHandle
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.screenshot.ImageCapture
import com.android.systemui.screenshot.ScreenshotData
import com.android.systemui.screenshot.ScreenshotRequestProcessor
import com.android.systemui.screenshot.data.model.DisplayContentModel
import com.android.systemui.screenshot.data.repository.DisplayContentRepository
import com.android.systemui.screenshot.policy.CapturePolicy.PolicyResult.Matched
import com.android.systemui.screenshot.policy.CapturePolicy.PolicyResult.NotMatched
import com.android.systemui.screenshot.policy.CaptureType.FullScreen
import com.android.systemui.screenshot.policy.CaptureType.IsolatedTask
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "PolicyRequestProcessor"

/** A [ScreenshotRequestProcessor] which supports general policy rule matching. */
class PolicyRequestProcessor(
    @Background private val background: CoroutineDispatcher,
    private val capture: ImageCapture,
    /** Provides information about the tasks on a given display */
    private val displayTasks: DisplayContentRepository,
    /** The list of policies to apply, in order of priority */
    private val policies: List<CapturePolicy>,
    /** The owner to assign for screenshot when a focused task isn't visible */
    private val defaultOwner: UserHandle = myUserHandle(),
    /** The assigned component when no application has focus, or not visible */
    private val defaultComponent: ComponentName,
) : ScreenshotRequestProcessor {
    override suspend fun process(original: ScreenshotData): ScreenshotData {
        if (original.type == TAKE_SCREENSHOT_PROVIDED_IMAGE) {
            // The request contains an already captured screenshot, accept it as is.
            Log.i(TAG, "Screenshot bitmap provided. No modifications applied.")
            return original
        }
        val displayContent = displayTasks.getDisplayContent(original.displayId)

        // If policies yield explicit modifications, apply them and return the result
        Log.i(TAG, "Applying policy checks....")
        policies.map { policy ->
            when (val result = policy.check(displayContent)) {
                is Matched -> {
                    Log.i(TAG, "$result")
                    return modify(original, result.parameters)
                }
                is NotMatched -> Log.i(TAG, "$result")
            }
        }

        // Otherwise capture normally, filling in additional information as needed.
        return captureScreenshot(original, displayContent)
    }

    /** Produce a new [ScreenshotData] using [CaptureParameters] */
    suspend fun modify(original: ScreenshotData, updates: CaptureParameters): ScreenshotData {
        // Update and apply bitmap capture depending on the parameters.
        val updated =
            when (val type = updates.type) {
                is IsolatedTask ->
                    replaceWithTaskSnapshot(
                        original,
                        updates.component,
                        updates.owner,
                        type.taskId,
                        type.taskBounds
                    )
                is FullScreen ->
                    replaceWithScreenshot(
                        original,
                        updates.component,
                        updates.owner,
                        type.displayId
                    )
            }
        return updated
    }

    private suspend fun captureScreenshot(
        original: ScreenshotData,
        displayContent: DisplayContentModel,
    ): ScreenshotData {
        // The first root task on the display, excluding Picture-in-Picture
        val topMainRootTask =
            if (!displayContent.systemUiState.shadeExpanded) {
                displayContent.rootTasks.firstOrNull(::nonPipVisibleTask)
            } else {
                null // Otherwise attributed to SystemUI / current user
            }

        return replaceWithScreenshot(
            original = original,
            componentName = topMainRootTask?.topActivity ?: defaultComponent,
            owner = topMainRootTask?.userId?.let { UserHandle.of(it) } ?: defaultOwner,
            displayId = original.displayId
        )
    }

    suspend fun replaceWithTaskSnapshot(
        original: ScreenshotData,
        componentName: ComponentName?,
        owner: UserHandle,
        taskId: Int,
        taskBounds: Rect?,
    ): ScreenshotData {
        Log.i(TAG, "Capturing task snapshot: $componentName / $owner")
        val taskSnapshot = capture.captureTask(taskId)
        return original.copy(
            type = TAKE_SCREENSHOT_PROVIDED_IMAGE,
            bitmap = taskSnapshot,
            userHandle = owner,
            taskId = taskId,
            topComponent = componentName,
            screenBounds = taskBounds
        )
    }

    suspend fun replaceWithScreenshot(
        original: ScreenshotData,
        componentName: ComponentName?,
        owner: UserHandle?,
        displayId: Int,
    ): ScreenshotData {
        Log.i(TAG, "Capturing screenshot: $componentName / $owner")
        val screenshot = captureDisplay(displayId)
        return original.copy(
            type = TAKE_SCREENSHOT_FULLSCREEN,
            bitmap = screenshot,
            userHandle = owner,
            topComponent = componentName,
            screenBounds = Rect(0, 0, screenshot?.width ?: 0, screenshot?.height ?: 0)
        )
    }

    /** Filter for the task used to attribute a full screen capture to an owner */
    private fun nonPipVisibleTask(info: RootTaskInfo): Boolean {
        return info.windowingMode != WindowConfiguration.WINDOWING_MODE_PINNED &&
            info.isVisible &&
            info.isRunning &&
            info.numActivities > 0 &&
            info.topActivity != null &&
            info.childTaskIds.isNotEmpty()
    }

    /** TODO: Move to ImageCapture (existing function is non-suspending) */
    private suspend fun captureDisplay(displayId: Int): Bitmap? {
        return withContext(background) { capture.captureDisplay(displayId) }
    }
}
