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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.intThat;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.wallpapers.gl.ImageWallpaperRenderer;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ImageWallpaperTest extends SysuiTestCase {
    private static final int LOW_BMP_WIDTH = 128;
    private static final int LOW_BMP_HEIGHT = 128;
    private static final int INVALID_BMP_WIDTH = 1;
    private static final int INVALID_BMP_HEIGHT = 1;
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
    private Bitmap mWallpaperBitmap;
    private int mBitmapWidth = 1;
    private int mBitmapHeight = 1;

    @Mock
    private Handler mHandler;
    @Mock
    private FeatureFlags mFeatureFlags;

    FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    FakeExecutor mFakeMainExecutor = new FakeExecutor(mFakeSystemClock);
    FakeExecutor mFakeBackgroundExecutor = new FakeExecutor(mFakeSystemClock);

    private CountDownLatch mEventCountdown;

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
        when(mWallpaperBitmap.getWidth()).thenReturn(mBitmapWidth);
        when(mWallpaperBitmap.getHeight()).thenReturn(mBitmapHeight);

        // set up wallpaper manager
        when(mWallpaperManager.peekBitmapDimensions())
                .thenReturn(new Rect(0, 0, mBitmapWidth, mBitmapHeight));
        when(mWallpaperManager.getBitmapAsUser(eq(UserHandle.USER_CURRENT), anyBoolean()))
                .thenReturn(mWallpaperBitmap);
        when(mMockContext.getSystemService(WallpaperManager.class)).thenReturn(mWallpaperManager);

        // set up surface
        when(mSurfaceHolder.getSurface()).thenReturn(mSurface);
        doNothing().when(mSurface).hwuiDestroy();

        // TODO remove code below. Outdated, used in only in old GL tests (that are ignored)
        Resources resources = mock(Resources.class);
        when(resources.getConfiguration()).thenReturn(mock(Configuration.class));
        when(mMockContext.getResources()).thenReturn(resources);
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = DISPLAY_WIDTH;
        displayInfo.logicalHeight = DISPLAY_HEIGHT;
        when(mMockContext.getDisplay()).thenReturn(
                new Display(mock(DisplayManagerGlobal.class), 0, displayInfo, (Resources) null));
    }

    private void setBitmapDimensions(int bitmapWidth, int bitmapHeight) {
        mBitmapWidth = bitmapWidth;
        mBitmapHeight = bitmapHeight;
    }

    private ImageWallpaper createImageWallpaper() {
        return new ImageWallpaper(mFeatureFlags, mFakeBackgroundExecutor, mFakeMainExecutor) {
            @Override
            public Engine onCreateEngine() {
                return new GLEngine(mHandler) {
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
                        mEventCountdown.countDown();
                    }
                };
            }
        };
    }

    @Test
    @Ignore
    public void testBitmapWallpaper_normal() {
        // Will use a image wallpaper with dimensions DISPLAY_WIDTH x DISPLAY_WIDTH.
        // Then we expect the surface size will be also DISPLAY_WIDTH x DISPLAY_WIDTH.
        verifySurfaceSize(DISPLAY_WIDTH /* bmpWidth */,
                DISPLAY_WIDTH /* bmpHeight */,
                DISPLAY_WIDTH /* surfaceWidth */,
                DISPLAY_WIDTH /* surfaceHeight */);
    }

    @Test
    @Ignore
    public void testBitmapWallpaper_low_resolution() {
        // Will use a image wallpaper with dimensions BMP_WIDTH x BMP_HEIGHT.
        // Then we expect the surface size will be also BMP_WIDTH x BMP_HEIGHT.
        verifySurfaceSize(LOW_BMP_WIDTH /* bmpWidth */,
                LOW_BMP_HEIGHT /* bmpHeight */,
                LOW_BMP_WIDTH /* surfaceWidth */,
                LOW_BMP_HEIGHT /* surfaceHeight */);
    }

    @Test
    @Ignore
    public void testBitmapWallpaper_too_small() {
        // Will use a image wallpaper with dimensions INVALID_BMP_WIDTH x INVALID_BMP_HEIGHT.
        // Then we expect the surface size will be also MIN_SURFACE_WIDTH x MIN_SURFACE_HEIGHT.
        verifySurfaceSize(INVALID_BMP_WIDTH /* bmpWidth */,
                INVALID_BMP_HEIGHT /* bmpHeight */,
                ImageWallpaper.GLEngine.MIN_SURFACE_WIDTH /* surfaceWidth */,
                ImageWallpaper.GLEngine.MIN_SURFACE_HEIGHT /* surfaceHeight */);
    }

    private void verifySurfaceSize(int bmpWidth, int bmpHeight,
            int surfaceWidth, int surfaceHeight) {
        ImageWallpaper.GLEngine wallpaperEngine =
                (ImageWallpaper.GLEngine) createImageWallpaper().onCreateEngine();

        ImageWallpaper.GLEngine engineSpy = spy(wallpaperEngine);

        setBitmapDimensions(bmpWidth, bmpHeight);

        ImageWallpaperRenderer renderer = new ImageWallpaperRenderer(mMockContext);
        doReturn(renderer).when(engineSpy).getRendererInstance();
        engineSpy.onCreate(engineSpy.getSurfaceHolder());

        verify(mSurfaceHolder, times(1)).setFixedSize(surfaceWidth, surfaceHeight);
        assertWithMessage("setFixedSizeAllowed should have been called.").that(
                mEventCountdown.getCount()).isEqualTo(0);
    }


    private ImageWallpaper createImageWallpaperCanvas() {
        return new ImageWallpaper(mFeatureFlags, mFakeBackgroundExecutor, mFakeMainExecutor) {
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
        ImageWallpaper imageWallpaper = createImageWallpaperCanvas();
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

    @Test
    public void testMinSurface() {

        // test that the surface is always at least MIN_SURFACE_WIDTH x MIN_SURFACE_HEIGHT
        testMinSurfaceHelper(8, 8);
        testMinSurfaceHelper(100, 2000);
        testMinSurfaceHelper(200, 1);
    }

    private void testMinSurfaceHelper(int bitmapWidth, int bitmapHeight) {

        clearInvocations(mSurfaceHolder);
        setBitmapDimensions(bitmapWidth, bitmapHeight);

        ImageWallpaper imageWallpaper = createImageWallpaperCanvas();
        ImageWallpaper.CanvasEngine engine =
                (ImageWallpaper.CanvasEngine) imageWallpaper.onCreateEngine();
        engine.onCreate(mSurfaceHolder);

        verify(mSurfaceHolder, times(1)).setFixedSize(
                intThat(greaterThanOrEqualTo(ImageWallpaper.CanvasEngine.MIN_SURFACE_WIDTH)),
                intThat(greaterThanOrEqualTo(ImageWallpaper.CanvasEngine.MIN_SURFACE_HEIGHT)));
    }

    @Test
    public void testLoadDrawAndUnloadBitmap() {
        setBitmapDimensions(LOW_BMP_WIDTH, LOW_BMP_HEIGHT);

        ImageWallpaper.CanvasEngine spyEngine = getSpyEngine();
        spyEngine.onCreate(mSurfaceHolder);
        spyEngine.onSurfaceRedrawNeeded(mSurfaceHolder);
        assertThat(mFakeBackgroundExecutor.numPending()).isAtLeast(1);

        int n = 0;
        while (mFakeBackgroundExecutor.numPending() + mFakeMainExecutor.numPending() >= 1) {
            n++;
            assertThat(n).isAtMost(10);
            mFakeBackgroundExecutor.runNextReady();
            mFakeMainExecutor.runNextReady();
            mFakeSystemClock.advanceTime(1000);
        }

        verify(spyEngine, times(1)).drawFrameOnCanvas(mWallpaperBitmap);
        assertThat(spyEngine.isBitmapLoaded()).isFalse();
    }
}
