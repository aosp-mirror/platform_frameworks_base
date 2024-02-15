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

import static android.server.wm.CtsWindowInfoUtils.waitForStableWindowGeometry;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.statusBars;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.DataSpace;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ServiceManager;
import android.platform.test.annotations.Presubmit;
import android.server.wm.BuildUtils;
import android.view.IWindowManager;
import android.view.PointerIcon;
import android.view.SurfaceControl;
import android.view.cts.surfacevalidator.BitmapPixelChecker;
import android.view.cts.surfacevalidator.SaveBitmapHelper;
import android.window.ScreenCapture;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;

import com.android.server.wm.utils.CommonUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 *  atest WmTests:ScreenshotTests
 */
@SmallTest
@Presubmit
public class ScreenshotTests {
    private static final long WAIT_TIME_S = 5L * BuildUtils.HW_TIMEOUT_MULTIPLIER;
    private static final int BUFFER_WIDTH = 100;
    private static final int BUFFER_HEIGHT = 100;

    private final Instrumentation mInstrumentation = getInstrumentation();
    @Rule
    public TestName mTestName = new TestName();

    @Rule
    public ActivityTestRule<ScreenshotActivity> mActivityRule =
            new ActivityTestRule<>(ScreenshotActivity.class);

    private ScreenshotActivity mActivity;

    @Before
    public void setup() {
        mActivity = mActivityRule.getActivity();
        mInstrumentation.waitForIdleSync();
    }

    @After
    public void tearDown() {
        CommonUtils.waitUntilActivityRemoved(mActivity);
    }

    @Test
    public void testScreenshotSecureLayers() throws InterruptedException {
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

        CountDownLatch countDownLatch = new CountDownLatch(1);
        t.show(secureSC)
                .setBuffer(secureSC, HardwareBuffer.createFromGraphicBuffer(buffer))
                .setDataSpace(secureSC, DataSpace.DATASPACE_SRGB)
                .addTransactionCommittedListener(Runnable::run, countDownLatch::countDown)
                .apply();
        assertTrue("Failed to wait for transaction to get committed",
                countDownLatch.await(WAIT_TIME_S, TimeUnit.SECONDS));
        assertTrue("Failed to wait for stable geometry",
                waitForStableWindowGeometry(WAIT_TIME_S, TimeUnit.SECONDS));

        ScreenCapture.LayerCaptureArgs args = new ScreenCapture.LayerCaptureArgs.Builder(secureSC)
                .setCaptureSecureLayers(true)
                .setChildrenOnly(false)
                .build();

        ScreenshotHardwareBuffer[] screenCapture = new ScreenshotHardwareBuffer[1];
        Bitmap screenshot = null;
        Bitmap swBitmap = null;
        try {
            CountDownLatch screenshotComplete = new CountDownLatch(1);
            ScreenCapture.captureLayers(args, new ScreenCapture.ScreenCaptureListener(
                    (screenshotHardwareBuffer, result) -> {
                        if (result == 0) {
                            screenCapture[0] = screenshotHardwareBuffer;
                        }
                        screenshotComplete.countDown();
                    }));
            assertTrue("Failed to wait for screen capture",
                    screenshotComplete.await(WAIT_TIME_S, TimeUnit.SECONDS));
            assertNotNull("Screen capture buffer is null", screenCapture[0]);

            screenshot = screenCapture[0].asBitmap();
            assertNotNull("Screenshot from bitmap is null", screenshot);

            swBitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false);

            BitmapPixelChecker bitmapPixelChecker = new BitmapPixelChecker(Color.RED);
            Rect bounds = new Rect(0, 0, swBitmap.getWidth(), swBitmap.getHeight());
            int numMatchingPixels = bitmapPixelChecker.getNumMatchingPixels(swBitmap, bounds);
            int sizeOfBitmap = bounds.width() * bounds.height();

            assertEquals("numMatchingPixels=" + numMatchingPixels + " sizeOfBitmap=" + sizeOfBitmap,
                    sizeOfBitmap, numMatchingPixels);
        } finally {
            if (screenshot != null) {
                screenshot.recycle();
            }
            if (swBitmap != null) {
                swBitmap.recycle();
            }
            if (screenCapture[0].getHardwareBuffer() != null) {
                screenCapture[0].getHardwareBuffer().close();
            }
        }
    }

    @Test
    public void testCaptureDisplay() throws Exception {
        IWindowManager windowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        SurfaceControl sc = new SurfaceControl.Builder()
                .setName("Layer")
                .setCallsite("testCaptureDisplay")
                .build();

        SurfaceControl.Transaction t = mActivity.addChildSc(sc);
        mInstrumentation.waitForIdleSync();

        GraphicBuffer buffer = GraphicBuffer.create(BUFFER_WIDTH, BUFFER_HEIGHT,
                PixelFormat.RGBA_8888,
                GraphicBuffer.USAGE_HW_TEXTURE | GraphicBuffer.USAGE_HW_COMPOSER
                        | GraphicBuffer.USAGE_SW_WRITE_RARELY);

        Canvas canvas = buffer.lockCanvas();
        canvas.drawColor(Color.RED);
        buffer.unlockCanvasAndPost(canvas);

        Point point = mActivity.getPositionBelowStatusBar();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        t.show(sc)
                .setBuffer(sc, HardwareBuffer.createFromGraphicBuffer(buffer))
                .setDataSpace(sc, DataSpace.DATASPACE_SRGB)
                .setPosition(sc, point.x, point.y)
                .addTransactionCommittedListener(Runnable::run, countDownLatch::countDown)
                .apply();

        assertTrue("Failed to wait for transaction to get committed",
                countDownLatch.await(WAIT_TIME_S, TimeUnit.SECONDS));
        assertTrue("Failed to wait for stable geometry",
                waitForStableWindowGeometry(WAIT_TIME_S, TimeUnit.SECONDS));

        ScreenshotHardwareBuffer[] screenCapture = new ScreenshotHardwareBuffer[1];
        Bitmap screenshot = null;
        Bitmap swBitmap = null;
        try {
            CountDownLatch screenshotComplete = new CountDownLatch(1);
            windowManager.captureDisplay(DEFAULT_DISPLAY, null,
                    new ScreenCapture.ScreenCaptureListener(
                            (screenshotHardwareBuffer, result) -> {
                                if (result == 0) {
                                    screenCapture[0] = screenshotHardwareBuffer;
                                }
                                screenshotComplete.countDown();
                            }));
            assertTrue("Failed to wait for screen capture",
                    screenshotComplete.await(WAIT_TIME_S, TimeUnit.SECONDS));
            assertNotNull("Screen capture buffer is null", screenCapture[0]);

            screenshot = screenCapture[0].asBitmap();
            assertNotNull("Screenshot from bitmap is null", screenshot);

            swBitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false);

            BitmapPixelChecker bitmapPixelChecker = new BitmapPixelChecker(Color.RED);
            Rect bounds = new Rect(point.x, point.y, BUFFER_WIDTH + point.x,
                    BUFFER_HEIGHT + point.y);
            int numMatchingPixels = bitmapPixelChecker.getNumMatchingPixels(swBitmap, bounds);
            int pixelMatchSize = bounds.width() * bounds.height();
            boolean success = numMatchingPixels == pixelMatchSize;

            if (!success) {
                SaveBitmapHelper.saveBitmap(swBitmap, getClass(), mTestName, "failedImage");
            }
            assertTrue(
                    "numMatchingPixels=" + numMatchingPixels + " pixelMatchSize=" + pixelMatchSize,
                    success);
        } finally {
            if (screenshot != null) {
                screenshot.recycle();
            }
            if (swBitmap != null) {
                swBitmap.recycle();
            }
            if (screenCapture[0].getHardwareBuffer() != null) {
                screenCapture[0].getHardwareBuffer().close();
            }
        }
    }

    public static class ScreenshotActivity extends Activity {
        private static final long WAIT_TIMEOUT_S = 5;
        private final Handler mHandler = new Handler(Looper.getMainLooper());

        private final CountDownLatch mAttachedLatch = new CountDownLatch(1);

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().getDecorView().setPointerIcon(
                    PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
        }

        @Override
        public void onAttachedToWindow() {
            super.onAttachedToWindow();
            mAttachedLatch.countDown();
        }

        SurfaceControl.Transaction addChildSc(SurfaceControl surfaceControl)
                throws InterruptedException {
            assertTrue("Failed to wait for onAttachedToWindow",
                    mAttachedLatch.await(WAIT_TIMEOUT_S, TimeUnit.SECONDS));
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

        public Point getPositionBelowStatusBar() {
            Insets statusBarInsets = getWindow()
                    .getDecorView()
                    .getRootWindowInsets()
                    .getInsets(statusBars() | displayCutout());

            return new Point(statusBarInsets.left, statusBarInsets.top);
        }
    }
}
