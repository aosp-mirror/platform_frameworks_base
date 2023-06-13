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

import android.app.IActivityTaskManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.IWindowManager
import android.window.ScreenCapture
import android.window.ScreenCapture.CaptureArgs
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "ImageCaptureImpl"

@SysUISingleton
open class ImageCaptureImpl @Inject constructor(
    private val windowManager: IWindowManager,
    private val atmService: IActivityTaskManager,
    @Background private val bgContext: CoroutineDispatcher
) : ImageCapture {

    override fun captureDisplay(displayId: Int, crop: Rect?): Bitmap? {
        val captureArgs = CaptureArgs.Builder()
            .setSourceCrop(crop)
            .build()
        val syncScreenCapture = ScreenCapture.createSyncCaptureListener()
        windowManager.captureDisplay(displayId, captureArgs, syncScreenCapture)
        val buffer = syncScreenCapture.getBuffer()
        return buffer?.asBitmap()
    }

    override suspend fun captureTask(taskId: Int): Bitmap? {
        val snapshot = withContext(bgContext) {
            atmService.takeTaskSnapshot(taskId, false /* updateCache */)
        } ?: return null
        return Bitmap.wrapHardwareBuffer(snapshot.hardwareBuffer, snapshot.colorSpace)
    }
}
