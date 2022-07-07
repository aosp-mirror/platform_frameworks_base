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

import android.net.Uri
import android.util.Log
import android.view.WindowManager.ScreenshotType
import android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import android.view.WindowManager.TAKE_SCREENSHOT_SELECTED_REGION
import com.android.internal.util.ScreenshotHelper.HardwareBitmapBundler
import com.android.internal.util.ScreenshotHelper.ScreenshotRequest
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback
import java.util.function.Consumer
import javax.inject.Inject

/**
 * Processes a screenshot request sent from {@link ScreenshotHelper}.
 */
@SysUISingleton
internal class RequestProcessor @Inject constructor(
    private val controller: ScreenshotController,
) {
    fun processRequest(
        @ScreenshotType type: Int,
        onSavedListener: Consumer<Uri>,
        request: ScreenshotRequest,
        callback: RequestCallback
    ) {

        if (type == TAKE_SCREENSHOT_PROVIDED_IMAGE) {
            val image = HardwareBitmapBundler.bundleToHardwareBitmap(request.bitmapBundle)

            controller.handleImageAsScreenshot(
                image, request.boundsInScreen, request.insets,
                request.taskId, request.userId, request.topComponent, onSavedListener, callback
            )
            return
        }

        when (type) {
            TAKE_SCREENSHOT_FULLSCREEN ->
                controller.takeScreenshotFullscreen(null, onSavedListener, callback)
            TAKE_SCREENSHOT_SELECTED_REGION ->
                controller.takeScreenshotPartial(null, onSavedListener, callback)
            else -> Log.w(TAG, "Invalid screenshot option: $type")
        }
    }

    companion object {
        const val TAG: String = "RequestProcessor"
    }
}
