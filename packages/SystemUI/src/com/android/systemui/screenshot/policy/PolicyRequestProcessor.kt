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

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.screenshot.ImageCapture
import com.android.systemui.screenshot.ScreenshotData
import com.android.systemui.screenshot.ScreenshotRequestProcessor
import com.android.systemui.screenshot.data.repository.DisplayContentRepository
import com.android.systemui.screenshot.policy.CaptureType.FullScreen
import com.android.systemui.screenshot.policy.CaptureType.IsolatedTask
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "PolicyRequestProcessor"

/** A [ScreenshotRequestProcessor] which supports general policy rule matching. */
class PolicyRequestProcessor(
    @Background private val background: CoroutineDispatcher,
    private val capture: ImageCapture,
    private val displayTasks: DisplayContentRepository,
    private val policies: List<CapturePolicy>,
) : ScreenshotRequestProcessor {
    override suspend fun process(original: ScreenshotData): ScreenshotData {
        if (original.type == TAKE_SCREENSHOT_PROVIDED_IMAGE) {
            // The request contains an already captured screenshot, accept it as is.
            Log.i(TAG, "Screenshot bitmap provided. No modifications applied.")
            return original
        }

        val tasks = displayTasks.getDisplayContent(original.displayId)

        // If policies yield explicit modifications, apply them and return the result
        Log.i(TAG, "Applying policy checks....")
        policies
            .firstNotNullOfOrNull { policy -> policy.apply(tasks) }
            ?.let {
                Log.i(TAG, "Modifying screenshot: $it")
                return apply(it, original)
            }

        // Otherwise capture normally, filling in additional information as needed.
        return replaceWithScreenshot(
            original = original,
            componentName = original.topComponent ?: tasks.rootTasks.firstOrNull()?.topActivity,
            owner = original.userHandle,
            displayId = original.displayId
        )
    }

    /** Produce a new [ScreenshotData] using [CaptureParameters] */
    suspend fun apply(updates: CaptureParameters, original: ScreenshotData): ScreenshotData {
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

    suspend fun replaceWithTaskSnapshot(
        original: ScreenshotData,
        componentName: ComponentName?,
        owner: UserHandle,
        taskId: Int,
        taskBounds: Rect?,
    ): ScreenshotData {
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
        val screenshot = captureDisplay(displayId)
        return original.copy(
            type = TAKE_SCREENSHOT_FULLSCREEN,
            bitmap = screenshot,
            userHandle = owner,
            topComponent = componentName,
            screenBounds = Rect(0, 0, screenshot?.width ?: 0, screenshot?.height ?: 0)
        )
    }

    /** TODO: Move to ImageCapture (existing function is non-suspending) */
    private suspend fun captureDisplay(displayId: Int): Bitmap? {
        return withContext(background) { capture.captureDisplay(displayId) }
    }
}
