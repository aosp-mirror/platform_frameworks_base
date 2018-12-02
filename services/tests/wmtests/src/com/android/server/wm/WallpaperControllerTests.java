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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static junit.framework.TestCase.assertNotNull;

import static org.junit.Assert.assertNull;

import android.graphics.Bitmap;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Tests for the {@link WallpaperController} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:WallpaperControllerTests
 */
@SmallTest
@Presubmit
public class WallpaperControllerTests extends WindowTestsBase {
    @Test
    public void testWallpaperScreenshot() {
        WindowSurfaceController windowSurfaceController = mock(WindowSurfaceController.class);

        synchronized (mWm.mGlobalLock) {
            // No wallpaper
            final DisplayContent dc = createNewDisplay();
            Bitmap wallpaperBitmap = dc.mWallpaperController.screenshotWallpaperLocked();
            assertNull(wallpaperBitmap);

            // No wallpaper WSA Surface
            WindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm, mock(IBinder.class),
                    true, dc, true /* ownerCanManageAppTokens */);
            WindowState wallpaperWindow = createWindow(null /* parent */, TYPE_WALLPAPER,
                    wallpaperWindowToken, "wallpaperWindow");
            wallpaperBitmap = dc.mWallpaperController.screenshotWallpaperLocked();
            assertNull(wallpaperBitmap);

            // Wallpaper with not visible WSA surface.
            wallpaperWindow.mWinAnimator.mSurfaceController = windowSurfaceController;
            wallpaperWindow.mWinAnimator.mLastAlpha = 1;
            wallpaperBitmap = dc.mWallpaperController.screenshotWallpaperLocked();
            assertNull(wallpaperBitmap);

            when(windowSurfaceController.getShown()).thenReturn(true);

            // Wallpaper with WSA alpha set to 0.
            wallpaperWindow.mWinAnimator.mLastAlpha = 0;
            wallpaperBitmap = dc.mWallpaperController.screenshotWallpaperLocked();
            assertNull(wallpaperBitmap);

            // Wallpaper window with WSA Surface
            wallpaperWindow.mWinAnimator.mLastAlpha = 1;
            wallpaperBitmap = dc.mWallpaperController.screenshotWallpaperLocked();
            assertNotNull(wallpaperBitmap);
        }
    }
}
