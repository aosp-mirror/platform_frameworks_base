/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.wallpapers.gl;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.app.WallpaperManager;
import android.app.WallpaperManager.ColorManagementProxy;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@Ignore
public class ImageWallpaperRendererTest extends SysuiTestCase {

    private WallpaperManager mWpmSpy;

    @Before
    public void setUp() throws Exception {
        final WallpaperManager wpm = mContext.getSystemService(WallpaperManager.class);
        mWpmSpy = spy(wpm);
        mContext.addMockSystemService(WallpaperManager.class, mWpmSpy);
    }

    @Test
    public void testWcgContent() throws IOException {
        final Bitmap srgbBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        final Bitmap p3Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888,
                false /* hasAlpha */, ColorSpace.get(ColorSpace.Named.DISPLAY_P3));

        final ColorManagementProxy proxy = new ColorManagementProxy(mContext);
        final ColorManagementProxy cmProxySpy = spy(proxy);
        final Set<ColorSpace> supportedWideGamuts = new HashSet<>();
        supportedWideGamuts.add(ColorSpace.get(ColorSpace.Named.DISPLAY_P3));

        try {
            doReturn(true).when(mWpmSpy).shouldEnableWideColorGamut();
            doReturn(cmProxySpy).when(mWpmSpy).getColorManagementProxy();
            doReturn(supportedWideGamuts).when(cmProxySpy).getSupportedColorSpaces();

            mWpmSpy.setBitmap(p3Bitmap);
            ImageWallpaperRenderer rendererP3 = new ImageWallpaperRenderer(mContext);
            rendererP3.reportSurfaceSize();
            assertThat(rendererP3.isWcgContent()).isTrue();

            mWpmSpy.setBitmap(srgbBitmap);
            ImageWallpaperRenderer renderer = new ImageWallpaperRenderer(mContext);
            assertThat(renderer.isWcgContent()).isFalse();
        } finally {
            srgbBitmap.recycle();
            p3Bitmap.recycle();
        }
    }

}
