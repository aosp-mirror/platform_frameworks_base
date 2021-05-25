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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.InsetsState;
import android.view.PrivacyIndicatorBounds;
import android.view.RoundedCorners;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ITransitionPlayer;

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
        assertEquals(expectedWidth, wallpaperWindow.getFrame().width());
        assertEquals(displayHeight, wallpaperWindow.getFrame().height());
        Rect portraitFrame = wallpaperWindow.getFrame();

        // Rotate the display
        dc.getDisplayRotation().updateOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, true);
        dc.sendNewConfiguration();

        // Apply the fixed transform
        Configuration config = new Configuration();
        final DisplayInfo info = dc.computeScreenConfiguration(config, Surface.ROTATION_0);
        final WmDisplayCutout cutout = dc.calculateDisplayCutoutForRotation(Surface.ROTATION_0);
        final DisplayFrames displayFrames = new DisplayFrames(dc.getDisplayId(), new InsetsState(),
                info, cutout, RoundedCorners.NO_ROUNDED_CORNERS, new PrivacyIndicatorBounds());
        wallpaperWindowToken.applyFixedRotationTransform(info, displayFrames, config);

        // Check that the wallpaper has the same frame in landscape than in portrait
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, dc.getConfiguration().orientation);
        assertEquals(portraitFrame, wallpaperWindow.getFrame());
    }

    @Test
    public void testWallpaperZoom() throws RemoteException {
        final DisplayContent dc = mWm.mRoot.getDefaultDisplay();
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true,  dc, true /* ownerCanManageAppTokens */);
        final WindowState wallpaperWindow = createWindow(null, TYPE_WALLPAPER, wallpaperWindowToken,
                "wallpaperWindow");
        wallpaperWindow.getAttrs().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS;

        final WindowState homeWindow = createWallpaperTargetWindow(dc);

        spyOn(dc.mWallpaperController);
        doReturn(true).when(dc.mWallpaperController).isWallpaperVisible();

        dc.mWallpaperController.adjustWallpaperWindows();

        spyOn(wallpaperWindow.mClient);

        float zoom = .5f;
        dc.mWallpaperController.setWallpaperZoomOut(homeWindow, zoom);
        assertEquals(zoom, wallpaperWindow.mWallpaperZoomOut, .01f);
        verify(wallpaperWindow.mClient).dispatchWallpaperOffsets(anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), eq(zoom), anyBoolean());
    }

    @Test
    public void testWallpaperZoom_shouldNotScaleWallpaper() throws RemoteException {
        final DisplayContent dc = mWm.mRoot.getDefaultDisplay();
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true,  dc, true /* ownerCanManageAppTokens */);
        final WindowState wallpaperWindow = createWindow(null, TYPE_WALLPAPER, wallpaperWindowToken,
                "wallpaperWindow");
        wallpaperWindow.getAttrs().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS;

        final WindowState homeWindow = createWallpaperTargetWindow(dc);

        spyOn(dc.mWallpaperController);
        doReturn(true).when(dc.mWallpaperController).isWallpaperVisible();

        dc.mWallpaperController.adjustWallpaperWindows();

        spyOn(wallpaperWindow.mClient);

        float newZoom = .5f;
        wallpaperWindow.mShouldScaleWallpaper = false;
        // Set zoom, and make sure the window animator scale didn't actually change, but the zoom
        // value did, and we do dispatch the zoom to the wallpaper service
        dc.mWallpaperController.setWallpaperZoomOut(homeWindow, newZoom);
        assertEquals(newZoom, wallpaperWindow.mWallpaperZoomOut, .01f);
        assertEquals(1f, wallpaperWindow.mWinAnimator.mWallpaperScale, .01f);
        verify(wallpaperWindow.mClient).dispatchWallpaperOffsets(anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), eq(newZoom), anyBoolean());
    }

    @Test
    public void testWallpaperZoom_multipleCallers() {
        final DisplayContent dc = mWm.mRoot.getDefaultDisplay();
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true,  dc,
                true /* ownerCanManageAppTokens */);
        final WindowState wallpaperWindow = createWindow(null, TYPE_WALLPAPER, wallpaperWindowToken,
                "wallpaperWindow");
        wallpaperWindow.getAttrs().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS;


        spyOn(dc.mWallpaperController);
        doReturn(true).when(dc.mWallpaperController).isWallpaperVisible();

        final WindowState homeWindow = createWallpaperTargetWindow(dc);

        WindowState otherWindow = createWindow(null /* parent */, TYPE_APPLICATION, dc,
                "otherWindow");

        dc.mWallpaperController.adjustWallpaperWindows();

        spyOn(wallpaperWindow.mClient);

        // Set zoom from 2 windows
        float homeWindowInitialZoom = .5f;
        float otherWindowInitialZoom = .7f;
        dc.mWallpaperController.setWallpaperZoomOut(homeWindow, homeWindowInitialZoom);
        dc.mWallpaperController.setWallpaperZoomOut(otherWindow, otherWindowInitialZoom);
        // Make sure the largest one wins
        assertEquals(otherWindowInitialZoom, wallpaperWindow.mWallpaperZoomOut, .01f);

        // Change zoom to a larger zoom from homeWindow
        float homeWindowZoom2 = .8f;
        dc.mWallpaperController.setWallpaperZoomOut(homeWindow, homeWindowZoom2);
        // New zoom should be current
        assertEquals(homeWindowZoom2, wallpaperWindow.mWallpaperZoomOut, .01f);

        // Set homeWindow zoom to a lower zoom, but keep the one from otherWindow
        dc.mWallpaperController.setWallpaperZoomOut(homeWindow, homeWindowInitialZoom);

        // Zoom from otherWindow should be the current.
        assertEquals(otherWindowInitialZoom, wallpaperWindow.mWallpaperZoomOut, .01f);
    }

    /**
     * Tests that the windowing mode of the wallpaper window must always be fullscreen.
     */
    @Test
    public void testWallpaperTokenWindowingMode() {
        final DisplayContent dc = mWm.mRoot.getDefaultDisplay();
        final WallpaperWindowToken token = new WallpaperWindowToken(mWm, mock(IBinder.class),
                true, dc, true /* ownerCanManageAppTokens */);

        // The wallpaper should have requested override fullscreen windowing mode, so the
        // configuration (windowing mode) propagation from display won't change it.
        dc.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(WINDOWING_MODE_FULLSCREEN, token.getWindowingMode());
        dc.setWindowingMode(WINDOWING_MODE_UNDEFINED);
        assertEquals(WINDOWING_MODE_FULLSCREEN, token.getWindowingMode());
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testFixedRotationRecentsAnimatingTask() {
        final RecentsAnimationController recentsController = mock(RecentsAnimationController.class);
        doReturn(true).when(recentsController).isWallpaperVisible(eq(mAppWindow));
        mWm.setRecentsAnimationController(recentsController);

        mAppWindow.mActivityRecord.applyFixedRotationTransform(mDisplayContent.getDisplayInfo(),
                mDisplayContent.mDisplayFrames, mDisplayContent.getConfiguration());
        mAppWindow.mActivityRecord.mVisibleRequested = true;
        mDisplayContent.mWallpaperController.adjustWallpaperWindows();

        assertEquals(mAppWindow, mDisplayContent.mWallpaperController.getWallpaperTarget());
        // Wallpaper should link the transform of its target.
        assertTrue(mAppWindow.mActivityRecord.hasFixedRotationTransform());

        mAppWindow.mActivityRecord.finishFixedRotationTransform();
        // Invisible requested activity should not share its rotation transform.
        mAppWindow.mActivityRecord.mVisibleRequested = false;
        mDisplayContent.mWallpaperController.adjustWallpaperWindows();

        assertFalse(mAppWindow.mActivityRecord.hasFixedRotationTransform());
    }

    @Test
    public void testWallpaperTokenVisibility() {
        final DisplayContent dc = mWm.mRoot.getDefaultDisplay();
        final WallpaperWindowToken token = new WallpaperWindowToken(mWm, mock(IBinder.class),
                true, dc, true /* ownerCanManageAppTokens */);
        final WindowState wallpaperWindow = createWindow(null, TYPE_WALLPAPER, token,
                "wallpaperWindow");
        wallpaperWindow.setHasSurface(true);

        // Set-up mock shell transitions
        final IBinder mockBinder = mock(IBinder.class);
        final ITransitionPlayer mockPlayer = mock(ITransitionPlayer.class);
        doReturn(mockBinder).when(mockPlayer).asBinder();
        mWm.mAtmService.getTransitionController().registerTransitionPlayer(mockPlayer);

        Transition transit =
                mWm.mAtmService.getTransitionController().createTransition(TRANSIT_OPEN);

        // wallpaper windows are immediately visible when set to visible even during a transition
        token.setVisibility(true);
        assertTrue(wallpaperWindow.isVisible());
        assertTrue(token.isVisibleRequested());
        assertTrue(token.isVisible());
        mWm.mAtmService.getTransitionController().abort(transit);

        // In a transition, setting invisible should ONLY set requestedVisible false; otherwise
        // wallpaper should remain "visible" until transition is over.
        transit = mWm.mAtmService.getTransitionController().createTransition(TRANSIT_CLOSE);
        transit.start();
        token.setVisibility(false);
        assertTrue(wallpaperWindow.isVisible());
        assertFalse(token.isVisibleRequested());
        assertTrue(token.isVisible());

        transit.onTransactionReady(transit.getSyncId(), mock(SurfaceControl.Transaction.class));
        transit.finishTransition();
        assertFalse(wallpaperWindow.isVisible());
        assertFalse(token.isVisible());
    }

    private WindowState createWallpaperTargetWindow(DisplayContent dc) {
        final ActivityRecord homeActivity = new ActivityBuilder(mWm.mAtmService)
                .setTask(dc.getDefaultTaskDisplayArea().getRootHomeTask())
                .build();
        homeActivity.setVisibility(true);

        WindowState appWindow = createWindow(null /* parent */, TYPE_BASE_APPLICATION,
                homeActivity, "wallpaperTargetWindow");
        appWindow.getAttrs().flags |= FLAG_SHOW_WALLPAPER;
        appWindow.mHasSurface = true;
        spyOn(appWindow);
        doReturn(true).when(appWindow).isDrawFinishedLw();

        homeActivity.addWindow(appWindow);
        return appWindow;
    }
}
