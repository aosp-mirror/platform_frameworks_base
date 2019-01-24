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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.DisplayInfo;
import android.view.SurfaceHolder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ImageWallpaperTest extends SysuiTestCase {

    private static final int BMP_WIDTH = 128;
    private static final int BMP_HEIGHT = 128;

    private static final int INVALID_BMP_WIDTH = 1;
    private static final int INVALID_BMP_HEIGHT = 1;

    private ImageWallpaper mImageWallpaper;

    @Mock private SurfaceHolder mSurfaceHolder;
    @Mock private DisplayInfo mDisplayInfo;

    private CountDownLatch mEventCountdown;
    private CountDownLatch mAmbientEventCountdown;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mEventCountdown = new CountDownLatch(1);
        mAmbientEventCountdown = new CountDownLatch(2);

        mImageWallpaper = new ImageWallpaper() {
            @Override
            public Engine onCreateEngine() {
                return new DrawableEngine() {
                    @Override
                    DisplayInfo getDisplayInfo() {
                        return mDisplayInfo;
                    }

                    @Override
                    public SurfaceHolder getSurfaceHolder() {
                        return mSurfaceHolder;
                    }

                    @Override
                    public void setFixedSizeAllowed(boolean allowed) {
                        super.setFixedSizeAllowed(allowed);
                        assertTrue("mFixedSizeAllowed should be true", allowed);
                        mEventCountdown.countDown();
                    }

                    @Override
                    public void onAmbientModeChanged(boolean inAmbientMode, long duration) {
                        mAmbientEventCountdown.countDown();
                    }
                };
            }
        };
    }

    @Test
    public void testSetValidBitmapWallpaper() {
        ImageWallpaper.DrawableEngine wallpaperEngine =
                (ImageWallpaper.DrawableEngine) mImageWallpaper.onCreateEngine();

        assertEquals("setFixedSizeAllowed should have been called.",
                0, mEventCountdown.getCount());

        Bitmap mockedBitmap = mock(Bitmap.class);
        when(mockedBitmap.getWidth()).thenReturn(BMP_WIDTH);
        when(mockedBitmap.getHeight()).thenReturn(BMP_HEIGHT);

        wallpaperEngine.updateBitmap(mockedBitmap);

        assertEquals(BMP_WIDTH, wallpaperEngine.mBackgroundWidth);
        assertEquals(BMP_HEIGHT, wallpaperEngine.mBackgroundHeight);

        verify(mSurfaceHolder, times(1)).setFixedSize(BMP_WIDTH, BMP_HEIGHT);

    }

    @Test
    public void testSetTooSmallBitmapWallpaper() {
        ImageWallpaper.DrawableEngine wallpaperEngine =
                (ImageWallpaper.DrawableEngine) mImageWallpaper.onCreateEngine();

        assertEquals("setFixedSizeAllowed should have been called.",
                0, mEventCountdown.getCount());

        Bitmap mockedBitmap = mock(Bitmap.class);
        when(mockedBitmap.getWidth()).thenReturn(INVALID_BMP_WIDTH);
        when(mockedBitmap.getHeight()).thenReturn(INVALID_BMP_HEIGHT);

        wallpaperEngine.updateBitmap(mockedBitmap);

        assertEquals(INVALID_BMP_WIDTH, wallpaperEngine.mBackgroundWidth);
        assertEquals(INVALID_BMP_HEIGHT, wallpaperEngine.mBackgroundHeight);

        verify(mSurfaceHolder, times(1)).setFixedSize(ImageWallpaper.DrawableEngine.MIN_BACKGROUND_WIDTH, ImageWallpaper.DrawableEngine.MIN_BACKGROUND_HEIGHT);
    }

    @Test
    public void testDeliversAmbientModeChanged() {
        ImageWallpaper.DrawableEngine wallpaperEngine =
                (ImageWallpaper.DrawableEngine) mImageWallpaper.onCreateEngine();

        assertEquals("setFixedSizeAllowed should have been called.",
                0, mEventCountdown.getCount());

        wallpaperEngine.setCreated(true);
        wallpaperEngine.doAmbientModeChanged(false, 1000);
        assertFalse("ambient mode should be false", wallpaperEngine.isInAmbientMode());
        assertEquals("onAmbientModeChanged should have been called.",
                1, mAmbientEventCountdown.getCount());

        wallpaperEngine.doAmbientModeChanged(true, 1000);
        assertTrue("ambient mode should be true", wallpaperEngine.isInAmbientMode());
        assertEquals("onAmbientModeChanged should have been called.",
                0, mAmbientEventCountdown.getCount());
    }
}
