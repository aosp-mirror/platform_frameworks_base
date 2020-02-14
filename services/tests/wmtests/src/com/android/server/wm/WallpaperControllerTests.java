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

import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link WallpaperController} class.
 *
 * Build/Install/Run:
 *  atest WmTests:WallpaperControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WallpaperControllerTests extends WindowTestsBase {
    @Test
    public void testWallpaperScreenshot() {
        WindowSurfaceController windowSurfaceController = mock(WindowSurfaceController.class);

        // No wallpaper
        final DisplayContent dc = createNewDisplay();
        assertFalse(dc.mWallpaperController.canScreenshotWallpaper());

        // No wallpaper WSA Surface
        WindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm, mock(IBinder.class),
                true, dc, true /* ownerCanManageAppTokens */);
        WindowState wallpaperWindow = createWindow(null /* parent */, TYPE_WALLPAPER,
                wallpaperWindowToken, "wallpaperWindow");
        assertFalse(dc.mWallpaperController.canScreenshotWallpaper());

        // Wallpaper with not visible WSA surface.
        wallpaperWindow.mWinAnimator.mSurfaceController = windowSurfaceController;
        wallpaperWindow.mWinAnimator.mLastAlpha = 1;
        assertFalse(dc.mWallpaperController.canScreenshotWallpaper());

        when(windowSurfaceController.getShown()).thenReturn(true);

        // Wallpaper with WSA alpha set to 0.
        wallpaperWindow.mWinAnimator.mLastAlpha = 0;
        assertFalse(dc.mWallpaperController.canScreenshotWallpaper());

        // Wallpaper window with WSA Surface
        wallpaperWindow.mWinAnimator.mLastAlpha = 1;
        assertTrue(dc.mWallpaperController.canScreenshotWallpaper());
    }

    @Test
    public void testWallpaperSizeWithFixedTransform() {
        // No wallpaper
        final DisplayContent dc = createNewDisplay();
        dc.mWmService.mIsFixedRotationTransformEnabled = true;

        // No wallpaper WSA Surface
        WindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm, mock(IBinder.class),
                true, dc, true /* ownerCanManageAppTokens */);
        WindowState wallpaperWindow = createWindow(null /* parent */, TYPE_WALLPAPER,
                wallpaperWindowToken, "wallpaperWindow");

        WindowManager.LayoutParams attrs = wallpaperWindow.getAttrs();
        Rect bounds = dc.getBounds();
        int displayHeight = dc.getBounds().height();

        // Use a wallpaper with a different ratio than the display
        int wallpaperWidth = bounds.width() * 2;
        int wallpaperHeight = (int) (bounds.height() * 1.10);

        // Simulate what would be done on the client's side
        attrs.width = wallpaperWidth;
        attrs.height = wallpaperHeight;
        attrs.flags |= FLAG_LAYOUT_NO_LIMITS;
        attrs.gravity = Gravity.TOP | Gravity.LEFT;
        wallpaperWindow.getWindowFrames().mParentFrame.set(dc.getBounds());

        // Calling layoutWindowLw a first time, so adjustWindowParams gets the correct data
        dc.getDisplayPolicy().layoutWindowLw(wallpaperWindow, null, dc.mDisplayFrames);

        wallpaperWindowToken.adjustWindowParams(wallpaperWindow, attrs);
        dc.getDisplayPolicy().layoutWindowLw(wallpaperWindow, null, dc.mDisplayFrames);

        assertEquals(Configuration.ORIENTATION_PORTRAIT, dc.getConfiguration().orientation);
        int expectedWidth = (int) (wallpaperWidth * (displayHeight / (double) wallpaperHeight));

        // Check that the wallpaper is correctly scaled
        assertEquals(new Rect(0, 0, expectedWidth, displayHeight), wallpaperWindow.getFrameLw());
        Rect portraitFrame = wallpaperWindow.getFrameLw();

        // Rotate the display
        dc.getDisplayRotation().updateOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, true);
        dc.sendNewConfiguration();

        // Apply the fixed transform
        Configuration config = new Configuration();
        final DisplayInfo info = dc.computeScreenConfiguration(config, Surface.ROTATION_0);
        final WmDisplayCutout cutout = dc.calculateDisplayCutoutForRotation(Surface.ROTATION_0);
        final DisplayFrames displayFrames = new DisplayFrames(dc.getDisplayId(), info, cutout);
        wallpaperWindowToken.applyFixedRotationTransform(info, displayFrames, config);

        // Check that the wallpaper has the same frame in landscape than in portrait
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, dc.getConfiguration().orientation);
        assertEquals(portraitFrame, wallpaperWindow.getFrameLw());
    }
}
