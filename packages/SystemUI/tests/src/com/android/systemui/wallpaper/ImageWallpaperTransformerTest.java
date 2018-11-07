/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.systemui.wallpaper;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.DisplayInfo;
import android.view.WindowManager;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ImageWallpaperTransformerTest extends SysuiTestCase {
    private DisplayInfo mDisplayInfo;
    private Bitmap mBitmap;
    private Canvas mCanvas;
    private RectF mDestination;

    @Before
    public void setUp() throws Exception {
        mDisplayInfo = new DisplayInfo();
        ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getDisplayInfo(mDisplayInfo);
        int dimension = Math.max(mDisplayInfo.logicalHeight, mDisplayInfo.logicalWidth);
        mBitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888);
        mCanvas = new Canvas(mBitmap);
        mCanvas.drawColor(Color.RED);
        mDestination = new RectF(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
    }

    @Test
    public void testVignetteFilter() {
        VignetteFilter vignette = new VignetteFilter();

        ImageWallpaperTransformer transformer = getTransformer(vignette);
        transformer.drawTransformedImage(mCanvas, mBitmap, null, mDestination);

        PointF center = vignette.getCenterPoint();
        int p1 = mBitmap.getPixel((int) center.x, (int) center.y);
        int p2 = mBitmap.getPixel(0, 0);
        int p3 = mBitmap.getPixel(mBitmap.getWidth() - 1, mBitmap.getHeight() - 1);

        assertThat(p1).isEqualTo(Color.RED);
        assertThat(p2 | p3).isEqualTo(Color.BLACK);
    }

    @Test
    public void testScrimFilter() {
        getTransformer(new ScrimFilter())
                .drawTransformedImage(mCanvas, mBitmap, null, mDestination);

        int pixel = mBitmap.getPixel(0, 0);

        // 0xff4d0000 is the result of 70% alpha pre-multiplied which is 0.7*(0,0,0)+0.3*(255,0,0).
        assertThat(pixel).isEqualTo(0xff4d0000);
    }

    private ImageWallpaperTransformer getTransformer(ImageWallpaperFilter filter) {
        ImageWallpaperTransformer transformer = new ImageWallpaperTransformer(null);
        transformer.addFilter(filter);
        transformer.updateDisplayInfo(mDisplayInfo);
        transformer.updateOffsets();
        transformer.updateAmbientModeState(true);
        return transformer;
    }
}
