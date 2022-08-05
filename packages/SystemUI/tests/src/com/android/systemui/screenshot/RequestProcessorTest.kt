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

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Insets
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.net.Uri
import android.view.WindowManager
import android.view.WindowManager.ScreenshotSource
import com.android.internal.util.ScreenshotHelper.HardwareBitmapBundler
import com.android.internal.util.ScreenshotHelper.ScreenshotRequest
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import java.util.function.Consumer
import org.junit.Test
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.Mockito.isNull

class RequestProcessorTest {
    private val controller = mock<ScreenshotController>()
    private val bitmapCaptor = argumentCaptor<Bitmap>()

    @Test
    fun testFullScreenshot() {
        val request = ScreenshotRequest(ScreenshotSource.SCREENSHOT_KEY_CHORD)
        val onSavedListener = mock<Consumer<Uri>>()
        val callback = mock<RequestCallback>()
        val processor = RequestProcessor(controller)

        processor.processRequest(WindowManager.TAKE_SCREENSHOT_FULLSCREEN, onSavedListener,
            request, callback)

        verify(controller).takeScreenshotFullscreen(/* topComponent */ isNull(),
            eq(onSavedListener), eq(callback))
    }

    @Test
    fun testSelectedRegionScreenshot() {
        val request = ScreenshotRequest(ScreenshotSource.SCREENSHOT_KEY_CHORD)
        val onSavedListener = mock<Consumer<Uri>>()
        val callback = mock<RequestCallback>()
        val processor = RequestProcessor(controller)

        processor.processRequest(WindowManager.TAKE_SCREENSHOT_SELECTED_REGION, onSavedListener,
            request, callback)

        verify(controller).takeScreenshotPartial(/* topComponent */ isNull(),
            eq(onSavedListener), eq(callback))
    }

    @Test
    fun testProvidedImageScreenshot() {
        val taskId = 1111
        val userId = 2222
        val bounds = Rect(50, 50, 150, 150)
        val topComponent = ComponentName("test", "test")
        val processor = RequestProcessor(controller)

        val buffer = HardwareBuffer.create(100, 100, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val bitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
        val bitmapBundle = HardwareBitmapBundler.hardwareBitmapToBundle(bitmap)

        val request = ScreenshotRequest(ScreenshotSource.SCREENSHOT_OTHER, bitmapBundle,
            bounds, Insets.NONE, taskId, userId, topComponent)

        val onSavedListener = mock<Consumer<Uri>>()
        val callback = mock<RequestCallback>()

        processor.processRequest(WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE, onSavedListener,
            request, callback)

        verify(controller).handleImageAsScreenshot(
            bitmapCaptor.capture(), eq(bounds), eq(Insets.NONE), eq(taskId), eq(userId),
            eq(topComponent), eq(onSavedListener), eq(callback)
        )

        assertThat(bitmapCaptor.value.equalsHardwareBitmap(bitmap)).isTrue()
    }

    private fun Bitmap.equalsHardwareBitmap(bitmap: Bitmap): Boolean {
        return bitmap.hardwareBuffer == this.hardwareBuffer &&
                bitmap.colorSpace == this.colorSpace
    }
}
