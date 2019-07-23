/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.policy;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

public class DecorViewTest {

    private Context mContext;
    private DecorView mDecorView;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        PhoneWindow phoneWindow = new PhoneWindow(mContext);
        mDecorView = (DecorView) phoneWindow.getDecorView();
    }

    @Test
    public void setBackgroundDrawableSameAsSetWindowBackground() {
        Drawable bitmapDrawable = mContext.getResources()
                .getDrawable(R.drawable.test16x12, mContext.getTheme());
        int w = 16;
        int h = 12;
        Bitmap expectedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        mDecorView.setWindowBackground(bitmapDrawable);
        Canvas testCanvas = new Canvas(expectedBitmap);
        mDecorView.draw(testCanvas);
        testCanvas.release();

        Drawable expectedBackground = mDecorView.getBackground();

        mDecorView.setBackgroundDrawable(bitmapDrawable);
        Bitmap resultBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas resCanvas = new Canvas(resultBitmap);
        mDecorView.draw(resCanvas);
        resCanvas.release();

        // Check that the drawable is the same.
        assertThat(mDecorView.getBackground()).isEqualTo(expectedBackground);
        assertThat(mDecorView.getBackground()).isEqualTo(bitmapDrawable);

        // Check that canvas is the same.
        int[] expPixels = new int[w * h];
        int[] resPixels = new int[w * h];
        resultBitmap.getPixels(resPixels, 0, w, 0, 0, w, h);
        expectedBitmap.getPixels(expPixels, 0, w, 0, 0, w, h);
        assertThat(Arrays.toString(expPixels)).isEqualTo(Arrays.toString(resPixels));
    }

    @Test
    public void setBackgroundWithNoWindow() {
        PhoneWindow phoneWindow = new PhoneWindow(mContext);
        // Set a theme that defines a non-null value for android:background
        mContext.setTheme(R.style.ViewDefaultBackground);
        DecorView decorView = (DecorView) phoneWindow.getDecorView();
        assertThat(decorView.getBackground()).isNotNull();
    }
}
