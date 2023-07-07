/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.wallpapers;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.intThat;

import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ImageWallpaperTest extends SysuiTestCase {
    private static final int LOW_BMP_WIDTH = 128;
    private static final int LOW_BMP_HEIGHT = 128;
    private static final int DISPLAY_WIDTH = 1920;
    private static final int DISPLAY_HEIGHT = 1080;

    @Mock
    private WindowManager mWindowManager;
    @Mock
    private WindowMetrics mWindowMetrics;
    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private WallpaperManager mWallpaperManager;
    @Mock
    private SurfaceHolder mSurfaceHolder;
    @Mock
    private Surface mSurface;
    @Mock
    private Context mMockContext;
    @Mock
    private UserTracker mUserTracker;

    @Mock
    private Bitmap mWallpaperBitmap;
    FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    FakeExecutor mFakeExecutor = new FakeExecutor(mFakeSystemClock);

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        MockitoAnnotations.initMocks(this);
        //mEventCountdown = new CountDownLatch(1);

        // set up window manager
        when(mWindowMetrics.getBounds()).thenReturn(
                new Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT));
        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(mWindowMetrics);
        when(mMockContext.getSystemService(WindowManager.class)).thenReturn(mWindowManager);

        // set up display manager
        doNothing().when(mDisplayManager).registerDisplayListener(any(), any());
        when(mMockContext.getSystemService(DisplayManager.class)).thenReturn(mDisplayManager);

        // set up bitmap
        when(mWallpaperBitmap.getColorSpace()).thenReturn(ColorSpace.get(ColorSpace.Named.SRGB));
        when(mWallpaperBitmap.getConfig()).thenReturn(Bitmap.Config.ARGB_8888);

        // set up wallpaper manager
        when(mWallpaperManager.getBitmapAsUser(eq(ActivityManager.getCurrentUser()), anyBoolean()))
                .thenReturn(mWallpaperBitmap);
        when(mMockContext.getSystemService(WallpaperManager.class)).thenReturn(mWallpaperManager);

        // set up surface
        when(mSurfaceHolder.getSurface()).thenReturn(mSurface);
        doNothing().when(mSurface).hwuiDestroy();

        // set up UserTracker
        when(mUserTracker.getUserId()).thenReturn(ActivityManager.getCurrentUser());
    }

    @Test
    public void testBitmapWallpaper_normal() {
        // Will use an image wallpaper with dimensions DISPLAY_WIDTH x DISPLAY_WIDTH.
        // Then we expect the surface size will be also DISPLAY_WIDTH x DISPLAY_WIDTH.
        int bitmapSide = DISPLAY_WIDTH;
        testSurfaceHelper(
                bitmapSide /* bitmapWidth */,
                bitmapSide /* bitmapHeight */,
                bitmapSide /* expectedSurfaceWidth */,
                bitmapSide /* expectedSurfaceHeight */);
    }

    @Test
    public void testBitmapWallpaper_low_resolution() {
        // Will use an image wallpaper with dimensions BMP_WIDTH x BMP_HEIGHT.
        // Then we expect the surface size will be also BMP_WIDTH x BMP_HEIGHT.
        testSurfaceHelper(LOW_BMP_WIDTH /* bitmapWidth */,
                LOW_BMP_HEIGHT /* bitmapHeight */,
                LOW_BMP_WIDTH /* expectedSurfaceWidth */,
                LOW_BMP_HEIGHT /* expectedSurfaceHeight */);
    }

    @Test
    public void testBitmapWallpaper_too_small() {

        // test that the surface is always at least MIN_SURFACE_WIDTH x MIN_SURFACE_HEIGHT
        testMinSurfaceHelper(8, 8);
        testMinSurfaceHelper(100, 2000);
        testMinSurfaceHelper(200, 1);
    }

    @Test
    public void testLoadDrawAndUnloadBitmap() {
        setBitmapDimensions(LOW_BMP_WIDTH, LOW_BMP_HEIGHT);

        ImageWallpaper.CanvasEngine spyEngine = getSpyEngine();
        spyEngine.onCreate(mSurfaceHolder);
        spyEngine.onSurfaceRedrawNeeded(mSurfaceHolder);
        assertThat(mFakeExecutor.numPending()).isAtLeast(1);

        int n = 0;
        while (mFakeExecutor.numPending() >= 1) {
            n++;
            assertThat(n).isAtMost(10);
            mFakeExecutor.runNextReady();
            mFakeSystemClock.advanceTime(1000);
        }

        verify(spyEngine, times(1)).drawFrameOnCanvas(mWallpaperBitmap);
        assertThat(spyEngine.isBitmapLoaded()).isFalse();
    }

    private ImageWallpaper createImageWallpaper() {
        return new ImageWallpaper(mFakeExecutor, mUserTracker) {
            @Override
            public Engine onCreateEngine() {
                return new CanvasEngine() {
                    @Override
                    public Context getDisplayContext() {
                        return mMockContext;
                    }

                    @Override
                    public SurfaceHolder getSurfaceHolder() {
                        return mSurfaceHolder;
                    }

                    @Override
                    public void setFixedSizeAllowed(boolean allowed) {
                        super.setFixedSizeAllowed(allowed);
                        assertWithMessage("mFixedSizeAllowed should be true").that(
                                allowed).isTrue();
                    }
                };
            }
        };
    }

    private ImageWallpaper.CanvasEngine getSpyEngine() {
        ImageWallpaper imageWallpaper = createImageWallpaper();
        ImageWallpaper.CanvasEngine engine =
                (ImageWallpaper.CanvasEngine) imageWallpaper.onCreateEngine();
        ImageWallpaper.CanvasEngine spyEngine = spy(engine);
        doNothing().when(spyEngine).drawFrameOnCanvas(any(Bitmap.class));
        doNothing().when(spyEngine).reportEngineShown(anyBoolean());
        doAnswer(invocation -> {
            ((ImageWallpaper.CanvasEngine) invocation.getMock()).onMiniBitmapUpdated();
            return null;
        }).when(spyEngine).recomputeColorExtractorMiniBitmap();
        return spyEngine;
    }

    private void setBitmapDimensions(int bitmapWidth, int bitmapHeight) {
        when(mWallpaperManager.peekBitmapDimensions())
                .thenReturn(new Rect(0, 0, bitmapWidth, bitmapHeight));
        when(mWallpaperBitmap.getWidth()).thenReturn(bitmapWidth);
        when(mWallpaperBitmap.getHeight()).thenReturn(bitmapHeight);
    }

    private void testMinSurfaceHelper(int bitmapWidth, int bitmapHeight) {
        testSurfaceHelper(bitmapWidth, bitmapHeight,
                Math.max(ImageWallpaper.CanvasEngine.MIN_SURFACE_WIDTH, bitmapWidth),
                Math.max(ImageWallpaper.CanvasEngine.MIN_SURFACE_HEIGHT, bitmapHeight));
    }

    private void testSurfaceHelper(int bitmapWidth, int bitmapHeight,
            int expectedSurfaceWidth, int expectedSurfaceHeight) {

        clearInvocations(mSurfaceHolder);
        setBitmapDimensions(bitmapWidth, bitmapHeight);

        ImageWallpaper imageWallpaper = createImageWallpaper();
        ImageWallpaper.CanvasEngine engine =
                (ImageWallpaper.CanvasEngine) imageWallpaper.onCreateEngine();
        engine.onCreate(mSurfaceHolder);

        verify(mSurfaceHolder, times(1)).setFixedSize(
                intThat(equalTo(expectedSurfaceWidth)),
                intThat(equalTo(expectedSurfaceHeight)));
    }
}
