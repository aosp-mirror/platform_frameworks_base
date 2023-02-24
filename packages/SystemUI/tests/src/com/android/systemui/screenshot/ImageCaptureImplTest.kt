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
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.SurfaceControl.ScreenshotHardwareBuffer
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test the logic within ImageCaptureImpl
 */
@RunWith(AndroidTestingRunner::class)
class ImageCaptureImplTest : SysuiTestCase() {
    private val displayManager = mock<DisplayManager>()
    private val atmService = mock<IActivityTaskManager>()
    private val capture = TestableImageCaptureImpl(
        displayManager,
        atmService,
        Dispatchers.Unconfined)

    @Test
    fun captureDisplayWithCrop() {
        capture.captureDisplay(Display.DEFAULT_DISPLAY, Rect(1, 2, 3, 4))
        assertThat(capture.token).isNotNull()
        assertThat(capture.width!!).isEqualTo(2)
        assertThat(capture.height!!).isEqualTo(2)
        assertThat(capture.crop!!).isEqualTo(Rect(1, 2, 3, 4))
    }

    @Test
    fun captureDisplayWithNullCrop() {
        capture.captureDisplay(Display.DEFAULT_DISPLAY, null)
        assertThat(capture.token).isNotNull()
        assertThat(capture.width!!).isEqualTo(0)
        assertThat(capture.height!!).isEqualTo(0)
        assertThat(capture.crop!!).isEqualTo(Rect())
    }

    class TestableImageCaptureImpl(
        displayManager: DisplayManager,
        atmService: IActivityTaskManager,
        bgDispatcher: CoroutineDispatcher
    ) :
        ImageCaptureImpl(displayManager, atmService, bgDispatcher) {

        var token: IBinder? = null
        var width: Int? = null
        var height: Int? = null
        var crop: Rect? = null

        override fun physicalDisplayToken(displayId: Int): IBinder {
            return Binder()
        }

        override fun captureDisplay(displayToken: IBinder, width: Int, height: Int, crop: Rect):
                ScreenshotHardwareBuffer {
            this.token = displayToken
            this.width = width
            this.height = height
            this.crop = crop
            return ScreenshotHardwareBuffer(null, null, false, false)
        }
    }
}
