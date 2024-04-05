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

package com.android.systemui.screenshot.scroll

import android.app.ActivityManager
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import android.view.ScrollCaptureResponse
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.screenshot.scroll.ScrollCaptureController.LongScreenshot
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import javax.inject.Inject

class ScrollCaptureExecutor
@Inject
constructor(
    activityManager: ActivityManager,
    private val scrollCaptureClient: ScrollCaptureClient,
    private val scrollCaptureController: ScrollCaptureController,
    private val longScreenshotHolder: LongScreenshotData,
    @Main private val mainExecutor: Executor
) {
    private val isLowRamDevice = activityManager.isLowRamDevice
    private var lastScrollCaptureRequest: ListenableFuture<ScrollCaptureResponse>? = null
    private var lastScrollCaptureResponse: ScrollCaptureResponse? = null
    private var longScreenshotFuture: ListenableFuture<LongScreenshot>? = null

    fun requestScrollCapture(
        displayId: Int,
        token: IBinder,
        callback: (ScrollCaptureResponse) -> Unit
    ) {
        if (!allowLongScreenshots()) {
            Log.d(TAG, "Long screenshots not supported on this device")
            return
        }
        scrollCaptureClient.setHostWindowToken(token)
        lastScrollCaptureRequest?.cancel(true)
        val scrollRequest =
            scrollCaptureClient.request(displayId).apply {
                addListener(
                    { onScrollCaptureResponseReady(this)?.let { callback.invoke(it) } },
                    mainExecutor
                )
            }
        lastScrollCaptureRequest = scrollRequest
    }

    fun interface ScrollTransitionReady {
        fun onTransitionReady(
            destRect: Rect,
            onTransitionEnd: Runnable,
            longScreenshot: LongScreenshot
        )
    }

    fun executeBatchScrollCapture(
        response: ScrollCaptureResponse,
        onCaptureComplete: Runnable,
        onFailure: Runnable,
        transition: ScrollTransitionReady,
    ) {
        // Clear the reference to prevent close() on reset
        lastScrollCaptureResponse = null
        longScreenshotFuture?.cancel(true)
        longScreenshotFuture =
            scrollCaptureController.run(response).apply {
                addListener(
                    {
                        getLongScreenshotChecked(this, onFailure)?.let {
                            longScreenshotHolder.setLongScreenshot(it)
                            longScreenshotHolder.setTransitionDestinationCallback {
                                destinationRect: Rect,
                                onTransitionEnd: Runnable ->
                                transition.onTransitionReady(destinationRect, onTransitionEnd, it)
                            }
                            onCaptureComplete.run()
                        }
                    },
                    mainExecutor
                )
            }
    }

    fun close() {
        lastScrollCaptureRequest?.cancel(true)
        lastScrollCaptureRequest = null
        lastScrollCaptureResponse?.close()
        lastScrollCaptureResponse = null
        longScreenshotFuture?.cancel(true)
    }

    private fun getLongScreenshotChecked(
        future: ListenableFuture<LongScreenshot>,
        onFailure: Runnable
    ): LongScreenshot? {
        var longScreenshot: LongScreenshot? = null
        runCatching { longScreenshot = future.get() }
            .onFailure {
                Log.e(TAG, "Caught exception", it)
                onFailure.run()
                return null
            }
        if (longScreenshot?.height != 0) {
            return longScreenshot
        }
        onFailure.run()
        return null
    }

    private fun onScrollCaptureResponseReady(
        responseFuture: Future<ScrollCaptureResponse>
    ): ScrollCaptureResponse? {
        try {
            lastScrollCaptureResponse?.close()
            lastScrollCaptureResponse = null
            if (responseFuture.isCancelled) {
                return null
            }
            val captureResponse = responseFuture.get().apply { lastScrollCaptureResponse = this }
            if (!captureResponse.isConnected) {
                // No connection means that the target window wasn't found
                // or that it cannot support scroll capture.
                Log.d(
                    TAG,
                    "ScrollCapture: ${captureResponse.description} [${captureResponse.windowTitle}]"
                )
                return null
            }
            Log.d(TAG, "ScrollCapture: connected to window [${captureResponse.windowTitle}]")
            return captureResponse
        } catch (e: InterruptedException) {
            Log.e(TAG, "requestScrollCapture interrupted", e)
        } catch (e: ExecutionException) {
            Log.e(TAG, "requestScrollCapture failed", e)
        }
        return null
    }

    private fun allowLongScreenshots(): Boolean {
        return !isLowRamDevice
    }

    private companion object {
        private const val TAG = "ScrollCaptureExecutor"
    }
}
