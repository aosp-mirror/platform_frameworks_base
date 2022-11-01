/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.wallpapers.canvas;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.intThat;

import android.graphics.Bitmap;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.DisplayInfo;
import android.view.SurfaceHolder;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ImageCanvasWallpaperRendererTest extends SysuiTestCase {

    private static final int MOBILE_DISPLAY_WIDTH = 720;
    private static final int MOBILE_DISPLAY_HEIGHT = 1600;

    @Mock
    private SurfaceHolder mMockSurfaceHolder;

    @Mock
    private DisplayInfo mMockDisplayInfo;

    @Mock
    private Bitmap mMockBitmap;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        MockitoAnnotations.initMocks(this);
    }

    private void setDimensions(
            int bitmapWidth, int bitmapHeight,
            int displayWidth, int displayHeight) {
        when(mMockBitmap.getWidth()).thenReturn(bitmapWidth);
        when(mMockBitmap.getHeight()).thenReturn(bitmapHeight);
        mMockDisplayInfo.logicalWidth = displayWidth;
        mMockDisplayInfo.logicalHeight = displayHeight;
    }

    private void testMinDimensions(
            int bitmapWidth, int bitmapHeight) {

        clearInvocations(mMockSurfaceHolder);
        setDimensions(bitmapWidth, bitmapHeight,
                ImageCanvasWallpaperRendererTest.MOBILE_DISPLAY_WIDTH,
                ImageCanvasWallpaperRendererTest.MOBILE_DISPLAY_HEIGHT);

        ImageCanvasWallpaperRenderer renderer =
                new ImageCanvasWallpaperRenderer(mMockSurfaceHolder);
        renderer.drawFrame(mMockBitmap, true);

        verify(mMockSurfaceHolder, times(1)).setFixedSize(
                intThat(greaterThanOrEqualTo(ImageCanvasWallpaperRenderer.MIN_SURFACE_WIDTH)),
                intThat(greaterThanOrEqualTo(ImageCanvasWallpaperRenderer.MIN_SURFACE_HEIGHT)));
    }

    @Test
    public void testMinSurface() {
        // test that the surface is always at least MIN_SURFACE_WIDTH x MIN_SURFACE_HEIGHT
        testMinDimensions(8, 8);

        testMinDimensions(100, 2000);

        testMinDimensions(200, 1);
    }

    private void testZeroDimensions(int bitmapWidth, int bitmapHeight) {

        clearInvocations(mMockSurfaceHolder);
        setDimensions(bitmapWidth, bitmapHeight,
                ImageCanvasWallpaperRendererTest.MOBILE_DISPLAY_WIDTH,
                ImageCanvasWallpaperRendererTest.MOBILE_DISPLAY_HEIGHT);

        ImageCanvasWallpaperRenderer renderer =
                new ImageCanvasWallpaperRenderer(mMockSurfaceHolder);
        ImageCanvasWallpaperRenderer spyRenderer = spy(renderer);
        spyRenderer.drawFrame(mMockBitmap, true);

        verify(mMockSurfaceHolder, never()).setFixedSize(anyInt(), anyInt());
        verify(spyRenderer, never()).drawWallpaperWithCanvas(any());
    }

    @Test
    public void testZeroBitmap() {
        // test that updateSurfaceSize is not called with a bitmap of width 0 or height 0
        testZeroDimensions(
                0, 1
        );

        testZeroDimensions(1, 0
        );

        testZeroDimensions(0, 0
        );
    }
}
