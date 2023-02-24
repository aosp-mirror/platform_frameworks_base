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
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import android.view.DisplayAddress
import android.view.SurfaceControl
import android.view.SurfaceControl.DisplayCaptureArgs
import android.view.SurfaceControl.ScreenshotHardwareBuffer
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "ImageCaptureImpl"

@SysUISingleton
open class ImageCaptureImpl @Inject constructor(
    private val displayManager: DisplayManager,
    private val atmService: IActivityTaskManager,
    @Background private val bgContext: CoroutineDispatcher
) : ImageCapture {

    override fun captureDisplay(displayId: Int, crop: Rect?): Bitmap? {
        val width = crop?.width() ?: 0
        val height = crop?.height() ?: 0
        val sourceCrop = crop ?: Rect()
        val displayToken = physicalDisplayToken(displayId) ?: return null
        val buffer = captureDisplay(displayToken, width, height, sourceCrop)

        return buffer?.asBitmap()
    }

    override suspend fun captureTask(taskId: Int): Bitmap? {
        val snapshot = withContext(bgContext) { atmService.takeTaskSnapshot(taskId) } ?: return null
        return Bitmap.wrapHardwareBuffer(snapshot.hardwareBuffer, snapshot.colorSpace)
    }

    @VisibleForTesting
    open fun physicalDisplayToken(displayId: Int): IBinder? {
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            Log.e(TAG, "No display with id: $displayId")
            return null
        }
        val address = display.address
        if (address !is DisplayAddress.Physical) {
            Log.e(TAG, "Display does not have a physical address: $display")
            return null
        }
        return SurfaceControl.getPhysicalDisplayToken(address.physicalDisplayId)
    }

    @VisibleForTesting
    open fun captureDisplay(
        displayToken: IBinder,
        width: Int,
        height: Int,
        crop: Rect
    ): ScreenshotHardwareBuffer? {
        val captureArgs =
            DisplayCaptureArgs.Builder(displayToken)
                .setSize(width, height)
                .setSourceCrop(crop)
                .build()
        return SurfaceControl.captureDisplay(captureArgs)
    }
}
