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

package com.android.systemui;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceHolder;

import com.android.systemui.glwallpaper.ImageWallpaperRenderer;

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
@Ignore
public class ImageWallpaperTest extends SysuiTestCase {
    private static final int LOW_BMP_WIDTH = 128;
    private static final int LOW_BMP_HEIGHT = 128;
    private static final int INVALID_BMP_WIDTH = 1;
    private static final int INVALID_BMP_HEIGHT = 1;
    private static final int DISPLAY_WIDTH = 1920;
    private static final int DISPLAY_HEIGHT = 1080;

    @Mock
    private SurfaceHolder mSurfaceHolder;
    @Mock
    private Context mMockContext;
    @Mock
    private Bitmap mWallpaperBitmap;
    @Mock
    private Handler mHandler;

    private CountDownLatch mEventCountdown;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        MockitoAnnotations.initMocks(this);
        mEventCountdown = new CountDownLatch(1);

        WallpaperManager wallpaperManager = mock(WallpaperManager.class);
        Resources resources = mock(Resources.class);

        when(mMockContext.getSystemService(WallpaperManager.class)).thenReturn(wallpaperManager);
        when(mMockContext.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(mock(Configuration.class));

        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = DISPLAY_WIDTH;
        displayInfo.logicalHeight = DISPLAY_HEIGHT;
        when(mMockContext.getDisplay()).thenReturn(
                new Display(mock(DisplayManagerGlobal.class), 0, displayInfo, (Resources) null));

        when(wallpaperManager.getBitmap(false)).thenReturn(mWallpaperBitmap);
        when(mWallpaperBitmap.getColorSpace()).thenReturn(ColorSpace.get(ColorSpace.Named.SRGB));
        when(mWallpaperBitmap.getConfig()).thenReturn(Bitmap.Config.ARGB_8888);
    }

    private ImageWallpaper createImageWallpaper() {
        return new ImageWallpaper() {
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
    public void testBitmapWallpaper_normal() {
        // Will use a image wallpaper with dimensions DISPLAY_WIDTH x DISPLAY_WIDTH.
        // Then we expect the surface size will be also DISPLAY_WIDTH x DISPLAY_WIDTH.
        verifySurfaceSize(DISPLAY_WIDTH /* bmpWidth */,
                DISPLAY_WIDTH /* bmpHeight */,
                DISPLAY_WIDTH /* surfaceWidth */,
                DISPLAY_WIDTH /* surfaceHeight */);
    }

    @Test
    public void testBitmapWallpaper_low_resolution() {
        // Will use a image wallpaper with dimensions BMP_WIDTH x BMP_HEIGHT.
        // Then we expect the surface size will be also BMP_WIDTH x BMP_HEIGHT.
        verifySurfaceSize(LOW_BMP_WIDTH /* bmpWidth */,
                LOW_BMP_HEIGHT /* bmpHeight */,
                LOW_BMP_WIDTH /* surfaceWidth */,
                LOW_BMP_HEIGHT /* surfaceHeight */);
    }

    @Test
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

        when(mWallpaperBitmap.getWidth()).thenReturn(bmpWidth);
        when(mWallpaperBitmap.getHeight()).thenReturn(bmpHeight);

        ImageWallpaperRenderer renderer = new ImageWallpaperRenderer(mMockContext);
        doReturn(renderer).when(engineSpy).getRendererInstance();
        engineSpy.onCreate(engineSpy.getSurfaceHolder());

        verify(mSurfaceHolder, times(1)).setFixedSize(surfaceWidth, surfaceHeight);
        assertWithMessage("setFixedSizeAllowed should have been called.").that(
                mEventCountdown.getCount()).isEqualTo(0);
    }
}
