/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.view.PointerIcon;
import android.view.SurfaceControl;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 *  atest WmTests:ScreenshotTests
 */
@SmallTest
@Presubmit
public class ScreenshotTests {
    private static final int BUFFER_WIDTH = 100;
    private static final int BUFFER_HEIGHT = 100;

    private final Instrumentation mInstrumentation = getInstrumentation();

    @Rule
    public ActivityTestRule<ScreenshotActivity> mActivityRule =
            new ActivityTestRule<>(ScreenshotActivity.class);

    private ScreenshotActivity mActivity;

    @Before
    public void setup() {
        mActivity = mActivityRule.getActivity();
        mInstrumentation.waitForIdleSync();
    }

    @Test
    public void testScreenshotSecureLayers() {
        SurfaceControl secureSC = new SurfaceControl.Builder()
                .setName("SecureChildSurfaceControl")
                .setBLASTLayer()
                .setCallsite("makeSecureSurfaceControl")
                .setSecure(true)
                .build();

        SurfaceControl.Transaction t = mActivity.addChildSc(secureSC);
        mInstrumentation.waitForIdleSync();

        GraphicBuffer buffer = GraphicBuffer.create(BUFFER_WIDTH, BUFFER_HEIGHT,
                PixelFormat.RGBA_8888,
                GraphicBuffer.USAGE_HW_TEXTURE | GraphicBuffer.USAGE_HW_COMPOSER
                        | GraphicBuffer.USAGE_SW_WRITE_RARELY);

        Canvas canvas = buffer.lockCanvas();
        canvas.drawColor(Color.RED);
        buffer.unlockCanvasAndPost(canvas);

        t.show(secureSC)
                .setBuffer(secureSC, buffer)
                .setColorSpace(secureSC, ColorSpace.get(ColorSpace.Named.SRGB))
                .apply(true);

        SurfaceControl.LayerCaptureArgs args = new SurfaceControl.LayerCaptureArgs.Builder(secureSC)
                .setCaptureSecureLayers(true)
                .setChildrenOnly(false)
                .build();
        SurfaceControl.ScreenshotHardwareBuffer hardwareBuffer = SurfaceControl.captureLayers(args);
        assertNotNull(hardwareBuffer);

        Bitmap screenshot = hardwareBuffer.asBitmap();
        assertNotNull(screenshot);

        Bitmap swBitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false);
        screenshot.recycle();

        int numMatchingPixels = PixelChecker.getNumMatchingPixels(swBitmap,
                new PixelColor(PixelColor.RED));
        long sizeOfBitmap = swBitmap.getWidth() * swBitmap.getHeight();
        boolean success = numMatchingPixels == sizeOfBitmap;
        swBitmap.recycle();

        assertTrue(success);
    }

    public static class ScreenshotActivity extends Activity {
        private static final long WAIT_TIMEOUT_S = 5;
        private final Handler mHandler = new Handler(Looper.getMainLooper());

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().getDecorView().setPointerIcon(
                    PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        SurfaceControl.Transaction addChildSc(SurfaceControl surfaceControl) {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            CountDownLatch countDownLatch = new CountDownLatch(1);
            mHandler.post(() -> {
                t.merge(getWindow().getRootSurfaceControl().buildReparentTransaction(
                        surfaceControl));
                countDownLatch.countDown();
            });

            try {
                countDownLatch.await(WAIT_TIMEOUT_S, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
            }
            return t;
        }
    }

    public abstract static class PixelChecker {
        static int getNumMatchingPixels(Bitmap bitmap, PixelColor pixelColor) {
            int numMatchingPixels = 0;
            for (int x = 0; x < bitmap.getWidth(); x++) {
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    int color = bitmap.getPixel(x, y);
                    if (matchesColor(pixelColor, color)) {
                        numMatchingPixels++;
                    }
                }
            }
            return numMatchingPixels;
        }

        static boolean matchesColor(PixelColor expectedColor, int color) {
            final float red = Color.red(color);
            final float green = Color.green(color);
            final float blue = Color.blue(color);
            final float alpha = Color.alpha(color);

            return alpha <= expectedColor.mMaxAlpha
                    && alpha >= expectedColor.mMinAlpha
                    && red <= expectedColor.mMaxRed
                    && red >= expectedColor.mMinRed
                    && green <= expectedColor.mMaxGreen
                    && green >= expectedColor.mMinGreen
                    && blue <= expectedColor.mMaxBlue
                    && blue >= expectedColor.mMinBlue;
        }
    }

    public static class PixelColor {
        public static final int BLACK = 0xFF000000;
        public static final int RED = 0xFF0000FF;
        public static final int GREEN = 0xFF00FF00;
        public static final int BLUE = 0xFFFF0000;
        public static final int YELLOW = 0xFF00FFFF;
        public static final int MAGENTA = 0xFFFF00FF;
        public static final int WHITE = 0xFFFFFFFF;

        public static final int TRANSPARENT_RED = 0x7F0000FF;
        public static final int TRANSPARENT_BLUE = 0x7FFF0000;
        public static final int TRANSPARENT = 0x00000000;

        // Default to black
        public short mMinAlpha;
        public short mMaxAlpha;
        public short mMinRed;
        public short mMaxRed;
        public short mMinBlue;
        public short mMaxBlue;
        public short mMinGreen;
        public short mMaxGreen;

        public PixelColor(int color) {
            short alpha = (short) ((color >> 24) & 0xFF);
            short blue = (short) ((color >> 16) & 0xFF);
            short green = (short) ((color >> 8) & 0xFF);
            short red = (short) (color & 0xFF);

            mMinAlpha = (short) getMinValue(alpha);
            mMaxAlpha = (short) getMaxValue(alpha);
            mMinRed = (short) getMinValue(red);
            mMaxRed = (short) getMaxValue(red);
            mMinBlue = (short) getMinValue(blue);
            mMaxBlue = (short) getMaxValue(blue);
            mMinGreen = (short) getMinValue(green);
            mMaxGreen = (short) getMaxValue(green);
        }

        public PixelColor() {
            this(BLACK);
        }

        private int getMinValue(short color) {
            return Math.max(color - 4, 0);
        }

        private int getMaxValue(short color) {
            return Math.min(color + 4, 0xFF);
        }
    }
}
