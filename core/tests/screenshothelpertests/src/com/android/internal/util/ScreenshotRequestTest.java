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

package com.android.internal.util;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.os.UserHandle.USER_NULL;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_OTHER;
import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;
import static android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.testng.Assert.assertThrows;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Parcel;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ScreenshotRequestTest {
    private final ComponentName mComponentName =
            new ComponentName("android.test", "android.test.Component");

    @Test
    public void testSimpleScreenshot() {
        ScreenshotRequest in =
                new ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_OTHER).build();

        Parcel parcel = Parcel.obtain();
        in.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ScreenshotRequest out = ScreenshotRequest.CREATOR.createFromParcel(parcel);

        assertEquals(TAKE_SCREENSHOT_FULLSCREEN, out.getType());
        assertEquals(SCREENSHOT_OTHER, out.getSource());
        assertNull("Top component was expected to be null", out.getTopComponent());
        assertEquals(INVALID_TASK_ID, out.getTaskId());
        assertEquals(USER_NULL, out.getUserId());
        assertNull("Bitmap was expected to be null", out.getBitmap());
        assertNull("Bounds were expected to be null", out.getBoundsInScreen());
        assertEquals(Insets.NONE, out.getInsets());
    }

    @Test
    public void testProvidedScreenshot() {
        Bitmap bitmap = makeHardwareBitmap(50, 50);
        ScreenshotRequest in =
                new ScreenshotRequest.Builder(TAKE_SCREENSHOT_PROVIDED_IMAGE, SCREENSHOT_OTHER)
                        .setTopComponent(mComponentName)
                        .setTaskId(2)
                        .setUserId(3)
                        .setBitmap(bitmap)
                        .setBoundsOnScreen(new Rect(10, 10, 60, 60))
                        .setInsets(Insets.of(2, 3, 4, 5))
                        .build();

        Parcel parcel = Parcel.obtain();
        in.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ScreenshotRequest out = ScreenshotRequest.CREATOR.createFromParcel(parcel);

        assertEquals(TAKE_SCREENSHOT_PROVIDED_IMAGE, out.getType());
        assertEquals(SCREENSHOT_OTHER, out.getSource());
        assertEquals(mComponentName, out.getTopComponent());
        assertEquals(2, out.getTaskId());
        assertEquals(3, out.getUserId());
        assertTrue("Bitmaps should be equal", out.getBitmap().sameAs(bitmap));
        assertEquals(new Rect(10, 10, 60, 60), out.getBoundsInScreen());
        assertEquals(Insets.of(2, 3, 4, 5), out.getInsets());
    }

    @Test
    public void testProvidedScreenshot_nullBitmap() {
        ScreenshotRequest.Builder inBuilder =
                new ScreenshotRequest.Builder(TAKE_SCREENSHOT_PROVIDED_IMAGE, SCREENSHOT_OTHER)
                        .setTopComponent(mComponentName)
                        .setTaskId(2)
                        .setUserId(3)
                        .setBoundsOnScreen(new Rect(10, 10, 60, 60))
                        .setInsets(Insets.of(2, 3, 4, 5));

        assertThrows(IllegalStateException.class, inBuilder::build);
    }

    @Test
    public void testFullScreenshot_withBitmap() {
        // A bitmap added to a FULLSCREEN request will be ignored, but it's technically valid
        Bitmap bitmap = makeHardwareBitmap(50, 50);
        ScreenshotRequest in =
                new ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_OTHER)
                        .setBitmap(bitmap)
                        .build();

        Parcel parcel = Parcel.obtain();
        in.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ScreenshotRequest out = ScreenshotRequest.CREATOR.createFromParcel(parcel);

        assertEquals(TAKE_SCREENSHOT_FULLSCREEN, out.getType());
        assertEquals(SCREENSHOT_OTHER, out.getSource());
        assertNull(out.getTopComponent());
        assertEquals(INVALID_TASK_ID, out.getTaskId());
        assertEquals(USER_NULL, out.getUserId());
        assertTrue("Bitmaps should be equal", out.getBitmap().sameAs(bitmap));
        assertNull("Bounds expected to be null", out.getBoundsInScreen());
        assertEquals(Insets.NONE, out.getInsets());
    }

    @Test
    public void testInvalidType() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenshotRequest.Builder(5, 2).build());
    }

    private Bitmap makeHardwareBitmap(int width, int height) {
        HardwareBuffer buffer = HardwareBuffer.create(
                width, height, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        return Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
    }
}
