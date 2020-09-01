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

package com.android.server.wm.utils;

import static android.graphics.Bitmap.Config.ARGB_8888;

import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.view.Surface;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;

@Presubmit
public class RotationAnimationUtilsTest {

    private static final int BITMAP_HEIGHT = 100;
    private static final int BITMAP_WIDTH = 100;
    private static final int POINT_WIDTH = 1000;
    private static final int POINT_HEIGHT = 2000;

    private ColorSpace mColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
    private Matrix mMatrix;

    @Before
    public void setup() {
        mMatrix = new Matrix();
    }

    @Test
    public void blackLuma() {
        Bitmap swBitmap = createBitmap(0);
        GraphicBuffer gb = swBitmapToGraphicsBuffer(swBitmap);
        float borderLuma = RotationAnimationUtils.getMedianBorderLuma(gb, mColorSpace);
        assertEquals(0, borderLuma, 0);
    }

    @Test
    public void whiteLuma() {
        Bitmap swBitmap = createBitmap(1);
        GraphicBuffer gb = swBitmapToGraphicsBuffer(swBitmap);
        float borderLuma = RotationAnimationUtils.getMedianBorderLuma(gb, mColorSpace);
        assertEquals(1, borderLuma, 0);
    }

    @Test
    public void unevenBitmapDimens() {
        Bitmap swBitmap = createBitmap(1, BITMAP_WIDTH + 1, BITMAP_HEIGHT + 1);
        GraphicBuffer gb = swBitmapToGraphicsBuffer(swBitmap);
        float borderLuma = RotationAnimationUtils.getMedianBorderLuma(gb, mColorSpace);
        assertEquals(1, borderLuma, 0);
    }

    @Test
    public void whiteImageBlackBorderLuma() {
        Bitmap swBitmap = createBitmap(1);
        setBorderLuma(swBitmap, 0);
        GraphicBuffer gb = swBitmapToGraphicsBuffer(swBitmap);
        float borderLuma = RotationAnimationUtils.getMedianBorderLuma(gb, mColorSpace);
        assertEquals(0, borderLuma, 0);
    }

    @Test
    public void blackImageWhiteBorderLuma() {
        Bitmap swBitmap = createBitmap(0);
        setBorderLuma(swBitmap, 1);
        GraphicBuffer gb = swBitmapToGraphicsBuffer(swBitmap);
        float borderLuma = RotationAnimationUtils.getMedianBorderLuma(gb, mColorSpace);
        assertEquals(1, borderLuma, 0);
    }

    @Test
    public void rotate_0_bottomRight() {
        RotationAnimationUtils.createRotationMatrix(Surface.ROTATION_0,
                POINT_WIDTH, POINT_HEIGHT, mMatrix);
        PointF newPoints = checkMappedPoints(POINT_WIDTH, POINT_HEIGHT);
        assertEquals(POINT_WIDTH, newPoints.x, 0);
        assertEquals(POINT_HEIGHT, newPoints.y, 0);
    }

    @Test
    public void rotate_90_bottomRight() {
        RotationAnimationUtils.createRotationMatrix(Surface.ROTATION_90,
                POINT_WIDTH, POINT_HEIGHT, mMatrix);
        PointF newPoints = checkMappedPoints(POINT_WIDTH, POINT_HEIGHT);
        assertEquals(0, newPoints.x, 0);
        assertEquals(POINT_WIDTH, newPoints.y, 0);
    }

    @Test
    public void rotate_180_bottomRight() {
        RotationAnimationUtils.createRotationMatrix(Surface.ROTATION_180,
                POINT_WIDTH, POINT_HEIGHT, mMatrix);
        PointF newPoints = checkMappedPoints(POINT_WIDTH, POINT_HEIGHT);
        assertEquals(0, newPoints.x, 0);
        assertEquals(0, newPoints.y, 0);
    }

    @Test
    public void rotate_270_bottomRight() {
        RotationAnimationUtils.createRotationMatrix(Surface.ROTATION_270,
                POINT_WIDTH, POINT_HEIGHT, mMatrix);
        PointF newPoints = checkMappedPoints(POINT_WIDTH, POINT_HEIGHT);
        assertEquals(POINT_HEIGHT, newPoints.x, 0);
        assertEquals(0, newPoints.y, 0);
    }

    private PointF checkMappedPoints(int x, int y) {
        final float[] fs = new float[] {x, y};
        mMatrix.mapPoints(fs);
        return new PointF(fs[0], fs[1]);
    }

    private Bitmap createBitmap(float luma) {
        return createBitmap(luma, BITMAP_WIDTH, BITMAP_HEIGHT);
    }

    private Bitmap createBitmap(float luma, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, ARGB_8888);
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                bitmap.setPixel(i, j, Color.argb(1, luma, luma, luma));
            }
        }
        return bitmap;
    }

    private GraphicBuffer swBitmapToGraphicsBuffer(Bitmap swBitmap) {
        Bitmap hwBitmap = swBitmap.copy(Bitmap.Config.HARDWARE, false);
        return hwBitmap.createGraphicBufferHandle();
    }

    private void setBorderLuma(Bitmap swBitmap, float luma) {
        int i;
        int width = swBitmap.getWidth();
        int height = swBitmap.getHeight();
        for (i = 0; i < width; i++) {
            swBitmap.setPixel(i, 0, Color.argb(1, luma, luma, luma));
            swBitmap.setPixel(i, height - 1, Color.argb(1, luma, luma, luma));
        }
        for (i = 0; i < height; i++) {
            swBitmap.setPixel(0, i, Color.argb(1, luma, luma, luma));
            swBitmap.setPixel(width - 1, i, Color.argb(1, luma, luma, luma));
        }
    }
}
