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

import android.util.Log
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.app.tracing.TraceUtils.Companion.launch
import kotlinx.coroutines.CoroutineScope
import java.util.function.Consumer
import javax.inject.Inject

/** Processes a screenshot request sent from [ScreenshotHelper]. */
interface ScreenshotRequestProcessor {
    /**
     * Inspects the incoming ScreenshotData, potentially modifying it based upon policy.
     *
     * @param screenshot the screenshot to process
     */
    suspend fun process(screenshot: ScreenshotData): ScreenshotData
}

/** Implementation of [ScreenshotRequestProcessor] */
@SysUISingleton
class RequestProcessor
@Inject
constructor(
    private val capture: ImageCapture,
    private val policy: ScreenshotPolicy,
    /** For the Java Async version, to invoke the callback. */
    @Application private val mainScope: CoroutineScope
) : ScreenshotRequestProcessor {

    override suspend fun process(screenshot: ScreenshotData): ScreenshotData {
        var result = screenshot

        // Apply work profile screenshots policy:
        //
        // If the focused app belongs to a work profile, transforms a full screen
        // (or partial) screenshot request to a task snapshot (provided image) screenshot.

        // Whenever displayContentInfo is fetched, the topComponent is also populated
        // regardless of the managed profile status.

        if (screenshot.type != TAKE_SCREENSHOT_PROVIDED_IMAGE) {
            val info = policy.findPrimaryContent(screenshot.displayId)
            Log.d(TAG, "findPrimaryContent: $info")
            result.taskId = info.taskId
            result.topComponent = info.component
            result.userHandle = info.user

            if (policy.isManagedProfile(info.user.identifier)) {
                val image =
                    capture.captureTask(info.taskId)
                        ?: throw RequestProcessorException("Task snapshot returned a null Bitmap!")

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
        mainScope.launch({ "$TAG#processAsync" }) {
            val result = process(screenshot)
            callback.accept(result)
        }
    }
}

private const val TAG = "RequestProcessor"

/** Exception thrown by [RequestProcessor] if something goes wrong. */
class RequestProcessorException(message: String) : IllegalStateException(message)
