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

package com.android.systemui.screenshot

import android.graphics.Insets
import android.util.Log
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import com.android.internal.util.ScreenshotRequest
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags.SCREENSHOT_WORK_PROFILE_POLICY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.function.Consumer
import javax.inject.Inject

/**
 * Processes a screenshot request sent from {@link ScreenshotHelper}.
 */
@SysUISingleton
class RequestProcessor @Inject constructor(
        private val capture: ImageCapture,
        private val policy: ScreenshotPolicy,
        private val flags: FeatureFlags,
        /** For the Java Async version, to invoke the callback. */
        @Application private val mainScope: CoroutineScope
) {
    /**
     * Inspects the incoming request, returning a potentially modified request depending on policy.
     *
     * @param request the request to process
     */
    // TODO: Delete once SCREENSHOT_METADATA flag is launched
    suspend fun process(request: ScreenshotRequest): ScreenshotRequest {
        var result = request

        // Apply work profile screenshots policy:
        //
        // If the focused app belongs to a work profile, transforms a full screen
        // (or partial) screenshot request to a task snapshot (provided image) screenshot.

        // Whenever displayContentInfo is fetched, the topComponent is also populated
        // regardless of the managed profile status.

        if (request.type != TAKE_SCREENSHOT_PROVIDED_IMAGE &&
                flags.isEnabled(SCREENSHOT_WORK_PROFILE_POLICY)
        ) {

            val info = policy.findPrimaryContent(policy.getDefaultDisplayId())
            Log.d(TAG, "findPrimaryContent: $info")

            result = if (policy.isManagedProfile(info.user.identifier)) {
                val image = capture.captureTask(info.taskId)
                        ?: error("Task snapshot returned a null Bitmap!")

                // Provide the task snapshot as the screenshot
                ScreenshotRequest.Builder(TAKE_SCREENSHOT_PROVIDED_IMAGE, request.source)
                        .setTopComponent(info.component)
                        .setTaskId(info.taskId)
                        .setUserId(info.user.identifier)
                        .setBitmap(image)
                        .setBoundsOnScreen(info.bounds)
                        .setInsets(Insets.NONE)
                        .build()
            } else {
                // Create a new request of the same type which includes the top component
                ScreenshotRequest.Builder(request.type, request.source)
                        .setTopComponent(info.component).build()
            }
        }

        return result
    }

    /**
     * Note: This is for compatibility with existing Java. Prefer the suspending function when
     * calling from a Coroutine context.
     *
     * @param request the request to process
     * @param callback the callback to provide the processed request, invoked from the main thread
     */
    // TODO: Delete once SCREENSHOT_METADATA flag is launched
    fun processAsync(request: ScreenshotRequest, callback: Consumer<ScreenshotRequest>) {
        mainScope.launch {
            val result = process(request)
            callback.accept(result)
        }
    }

    /**
     * Inspects the incoming ScreenshotData, potentially modifying it based upon policy.
     *
     * @param screenshot the screenshot to process
     */
    suspend fun process(screenshot: ScreenshotData): ScreenshotData {
        var result = screenshot

        // Apply work profile screenshots policy:
        //
        // If the focused app belongs to a work profile, transforms a full screen
        // (or partial) screenshot request to a task snapshot (provided image) screenshot.

        // Whenever displayContentInfo is fetched, the topComponent is also populated
        // regardless of the managed profile status.

        if (screenshot.type != TAKE_SCREENSHOT_PROVIDED_IMAGE &&
            flags.isEnabled(SCREENSHOT_WORK_PROFILE_POLICY)
        ) {
            val info = policy.findPrimaryContent(policy.getDefaultDisplayId())
            Log.d(TAG, "findPrimaryContent: $info")
            result.taskId = info.taskId
            result.topComponent = info.component
            result.userHandle = info.user

            if (policy.isManagedProfile(info.user.identifier)) {
                val image = capture.captureTask(info.taskId)
                    ?: error("Task snapshot returned a null Bitmap!")

                // Provide the task snapshot as the screenshot
                result.type = TAKE_SCREENSHOT_PROVIDED_IMAGE
                result.bitmap = image
                result.screenBounds = info.bounds
            }
        }

        return result
    }

    /**
     * Note: This is for compatibility with existing Java. Prefer the suspending function when
     * calling from a Coroutine context.
     *
     * @param screenshot the screenshot to process
     * @param callback the callback to provide the processed screenshot, invoked from the main
     *                 thread
     */
    fun processAsync(screenshot: ScreenshotData, callback: Consumer<ScreenshotData>) {
        mainScope.launch {
            val result = process(screenshot)
            callback.accept(result)
        }
    }
}

private const val TAG = "RequestProcessor"
