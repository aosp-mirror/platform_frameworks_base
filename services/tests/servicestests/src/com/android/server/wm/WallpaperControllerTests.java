package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static junit.framework.TestCase.assertNotNull;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link WallpaperController} class.
 *
 * Build/Install/Run:
 *  atest com.android.server.wm.WallpaperControllerTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WallpaperControllerTests extends WindowTestsBase {
    @Test
    public void testWallpaperScreenshot() {
        WindowSurfaceController windowSurfaceController = mock(WindowSurfaceController.class);

        synchronized (sWm.mWindowMap) {
            // No wallpaper
            final DisplayContent dc = createNewDisplay();
            Bitmap wallpaperBitmap = sWm.mRoot.mWallpaperController.screenshotWallpaperLocked();
            assertNull(wallpaperBitmap);

            // No wallpaper WSA Surface
            WindowToken wallpaperWindowToken = new WallpaperWindowToken(sWm, mock(IBinder.class),
                    true, dc, true /* ownerCanManageAppTokens */);
            WindowState wallpaperWindow = createWindow(null /* parent */, TYPE_WALLPAPER,
                    wallpaperWindowToken, "wallpaperWindow");
            wallpaperBitmap = mWallpaperController.screenshotWallpaperLocked();
            assertNull(wallpaperBitmap);

            // Wallpaper with not visible WSA surface.
            wallpaperWindow.mWinAnimator.mSurfaceController = windowSurfaceController;
            wallpaperWindow.mWinAnimator.mLastAlpha = 1;
            wallpaperBitmap = mWallpaperController.screenshotWallpaperLocked();
            assertNull(wallpaperBitmap);

            when(windowSurfaceController.getShown()).thenReturn(true);

            // Wallpaper with WSA alpha set to 0.
            wallpaperWindow.mWinAnimator.mLastAlpha = 0;
            wallpaperBitmap = mWallpaperController.screenshotWallpaperLocked();
            assertNull(wallpaperBitmap);

            // Wallpaper window with WSA Surface
            wallpaperWindow.mWinAnimator.mLastAlpha = 1;
            wallpaperBitmap = mWallpaperController.screenshotWallpaperLocked();
            assertNotNull(wallpaperBitmap);
        }
    }
}
