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

package com.android.systemui.screenshot

import android.net.Uri
import android.os.UserManager
import android.util.Log
import android.view.WindowManager
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.function.Consumer
import javax.inject.Inject

/**
 * A ScreenshotHandler that just saves the screenshot and calls back as appropriate, with no UI.
 *
 * Basically, ScreenshotController with all the UI bits ripped out.
 */
class HeadlessScreenshotHandler
@Inject
constructor(
    private val imageExporter: ImageExporter,
    @Main private val mainExecutor: Executor,
    private val imageCapture: ImageCapture,
    private val userManager: UserManager,
    private val uiEventLogger: UiEventLogger,
    private val notificationsControllerFactory: ScreenshotNotificationsController.Factory,
) : ScreenshotHandler {

    override fun handleScreenshot(
        screenshot: ScreenshotData,
        finisher: Consumer<Uri?>,
        requestCallback: TakeScreenshotService.RequestCallback
    ) {
        if (screenshot.type == WindowManager.TAKE_SCREENSHOT_FULLSCREEN) {
            screenshot.bitmap = imageCapture.captureDisplay(screenshot.displayId, crop = null)
        }

        if (screenshot.bitmap == null) {
            Log.e(TAG, "handleScreenshot: Screenshot bitmap was null")
            notificationsControllerFactory
                .create(screenshot.displayId)
                .notifyScreenshotError(R.string.screenshot_failed_to_capture_text)
            requestCallback.reportError()
            return
        }

        val future: ListenableFuture<ImageExporter.Result> =
            imageExporter.export(
                Executors.newSingleThreadExecutor(),
                UUID.randomUUID(),
                screenshot.bitmap,
                screenshot.getUserOrDefault(),
                screenshot.displayId
            )
        future.addListener(
            {
                try {
                    val result = future.get()
                    Log.d(TAG, "Saved screenshot: $result")
                    logScreenshotResultStatus(result.uri, screenshot)
                    finisher.accept(result.uri)
                    requestCallback.onFinish()
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to store screenshot", e)
                    finisher.accept(null)
                    requestCallback.reportError()
                }
            },
            mainExecutor
        )
    }

    private fun logScreenshotResultStatus(uri: Uri?, screenshot: ScreenshotData) {
        if (uri == null) {
            uiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED, 0, screenshot.packageNameString)
            notificationsControllerFactory
                .create(screenshot.displayId)
                .notifyScreenshotError(R.string.screenshot_failed_to_save_text)
        } else {
            uiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED, 0, screenshot.packageNameString)
            if (userManager.isManagedProfile(screenshot.getUserOrDefault().identifier)) {
                uiEventLogger.log(
                    ScreenshotEvent.SCREENSHOT_SAVED_TO_WORK_PROFILE,
                    0,
                    screenshot.packageNameString
                )
            }
        }
    }

    companion object {
        const val TAG = "HeadlessScreenshotHandler"
    }
}
