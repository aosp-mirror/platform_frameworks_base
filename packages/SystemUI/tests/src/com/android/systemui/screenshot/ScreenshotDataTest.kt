/*
 * Copyright (C) 2023 The Android Open Source Project
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Insets
import android.graphics.Rect
import android.os.UserHandle
import android.view.Display
import android.view.WindowManager
import com.android.internal.util.ScreenshotRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotDataTest {
    private val type = WindowManager.TAKE_SCREENSHOT_FULLSCREEN
    private val source = WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER
    private val bounds = Rect(1, 2, 3, 4)
    private val taskId = 123
    private val userId = 1
    private val insets = Insets.of(1, 2, 3, 4)
    private val component = ComponentName("android.test", "android.test.Component")

    @Test
    fun testConstruction() {
        val request =
            ScreenshotRequest.Builder(type, source)
                .setBoundsOnScreen(bounds)
                .setInsets(insets)
                .setTaskId(taskId)
                .setUserId(userId)
                .setTopComponent(component)
                .build()

        val data = ScreenshotData.fromRequest(request)

        assertThat(data.source).isEqualTo(source)
        assertThat(data.type).isEqualTo(type)
        assertThat(data.screenBounds).isEqualTo(bounds)
        assertThat(data.insets).isEqualTo(insets)
        assertThat(data.taskId).isEqualTo(taskId)
        assertThat(data.userHandle).isEqualTo(UserHandle.of(userId))
        assertThat(data.topComponent).isEqualTo(component)
        assertThat(data.displayId).isEqualTo(Display.DEFAULT_DISPLAY)
    }

    @Test
    fun testConstruction_notDefaultDisplayId() {
        val request = ScreenshotRequest.Builder(type, source).build()

        val data = ScreenshotData.fromRequest(request, displayId = 42)

        assertThat(data.displayId).isEqualTo(42)
    }

    @Test
    fun testNegativeUserId() {
        val request = ScreenshotRequest.Builder(type, source).setUserId(-1).build()

        val data = ScreenshotData.fromRequest(request)

        assertThat(data.userHandle).isNull()
    }

    @Test
    fun testPackageNameAsString() {
        val request = ScreenshotRequest.Builder(type, source).setTopComponent(component).build()

        val data = ScreenshotData.fromRequest(request)

        assertThat(data.packageNameString).isEqualTo("android.test")
    }

    @Test
    fun testPackageNameAsString_null() {
        val request = ScreenshotRequest.Builder(type, source).build()

        val data = ScreenshotData.fromRequest(request)

        assertThat(data.packageNameString).isEqualTo("")
    }
}
