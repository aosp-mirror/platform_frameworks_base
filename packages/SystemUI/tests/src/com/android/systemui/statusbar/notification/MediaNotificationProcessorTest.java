/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.palette.graphics.Palette;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MediaNotificationProcessorTest extends SysuiTestCase {

    private static final int BITMAP_WIDTH = 10;
    private static final int BITMAP_HEIGHT = 10;

    /**
     * Color tolerance is borrowed from the AndroidX test utilities for Palette.
     */
    private static final int COLOR_TOLERANCE = 8;

    @Nullable private Bitmap mArtwork;

    @After
    public void tearDown() {
        if (mArtwork != null) {
            mArtwork.recycle();
            mArtwork = null;
        }
    }

    @Test
    public void findBackgroundSwatch_white() {
        // Given artwork that is completely white.
        mArtwork = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mArtwork);
        canvas.drawColor(Color.WHITE);
        // WHEN the background swatch is computed
        Palette.Swatch swatch = MediaNotificationProcessor.findBackgroundSwatch(mArtwork);
        // THEN the swatch color is white
        assertCloseColors(swatch.getRgb(), Color.WHITE);
    }

    @Test
    public void findBackgroundSwatch_red() {
        // Given artwork that is completely red.
        mArtwork = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mArtwork);
        canvas.drawColor(Color.RED);
        // WHEN the background swatch is computed
        Palette.Swatch swatch = MediaNotificationProcessor.findBackgroundSwatch(mArtwork);
        // THEN the swatch color is red
        assertCloseColors(swatch.getRgb(), Color.RED);
    }

    static void assertCloseColors(int expected, int actual) {
        assertThat((float) Color.red(expected)).isWithin(COLOR_TOLERANCE).of(Color.red(actual));
        assertThat((float) Color.green(expected)).isWithin(COLOR_TOLERANCE).of(Color.green(actual));
        assertThat((float) Color.blue(expected)).isWithin(COLOR_TOLERANCE).of(Color.blue(actual));
    }
}
