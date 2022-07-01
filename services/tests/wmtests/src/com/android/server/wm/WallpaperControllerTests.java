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
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
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
import static org.junit.Assert.assertNotEquals;
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
        final WindowState wallpaperWindow = createWallpaperWindow(dc);
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
        final DisplayContent dc = mDisplayContent;

        // No wallpaper WSA Surface
        final WindowState wallpaperWindow = createWallpaperWindow(dc);

        WindowManager.LayoutParams attrs = wallpaperWindow.getAttrs();
        Rect bounds = dc.getBounds();
        int displayWidth = dc.getBounds().width();
        int displayHeight = dc.getBounds().height();

        // Use a wallpaper with a different ratio than the display
        int wallpaperWidth = bounds.width() * 2;
        int wallpaperHeight = (int) (bounds.height() * 1.10);

        // Simulate what would be done on the client's side
        final float layoutScale = Math.max(
                displayWidth / (float) wallpaperWidth, displayHeight / (float) wallpaperHeight);
        attrs.width = (int) (wallpaperWidth * layoutScale + .5f);
        attrs.height = (int) (wallpaperHeight * layoutScale + .5f);
        attrs.flags |= FLAG_LAYOUT_NO_LIMITS | FLAG_SCALED;
        attrs.gravity = Gravity.TOP | Gravity.LEFT;
        wallpaperWindow.getWindowFrames().mParentFrame.set(dc.getBounds());

        dc.getDisplayPolicy().layoutWindowLw(wallpaperWindow, null, dc.mDisplayFrames);

        assertEquals(Configuration.ORIENTATION_PORTRAIT, dc.getConfiguration().orientation);
        int expectedWidth = (int) (wallpaperWidth * layoutScale + .5f);

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
        wallpaperWindow.mToken.applyFixedRotationTransform(info, displayFrames, config);

        // Check that the wallpaper has the same frame in landscape than in portrait
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, dc.getConfiguration().orientation);
        assertEquals(portraitFrame, wallpaperWindow.getFrame());
    }

    @Test
    public void testWallpaperZoom() throws RemoteException {
        final DisplayContent dc = mWm.mRoot.getDefaultDisplay();
        final WindowState wallpaperWindow = createWallpaperWindow(dc);
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
        final WindowState wallpaperWindow = createWallpaperWindow(dc);
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
        assertEquals(1f, wallpaperWindow.mWallpaperScale, .01f);
        verify(wallpaperWindow.mClient).dispatchWallpaperOffsets(anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), eq(newZoom), anyBoolean());
    }

    @Test
    public void testWallpaperZoom_multipleCallers() {
        final DisplayContent dc = mWm.mRoot.getDefaultDisplay();
        final WindowState wallpaperWindow = createWallpaperWindow(dc);
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

    @Test
    public void testUpdateWallpaperTarget() {
        final DisplayContent dc = mDisplayContent;
        final WindowState homeWin = createWallpaperTargetWindow(dc);
        final WindowState appWin = createWindow(null, TYPE_BASE_APPLICATION, "app");
        final RecentsAnimationController recentsController = mock(RecentsAnimationController.class);
        doReturn(true).when(recentsController).isWallpaperVisible(eq(appWin));
        mWm.setRecentsAnimationController(recentsController);

        dc.mWallpaperController.adjustWallpaperWindows();
        assertEquals(appWin, dc.mWallpaperController.getWallpaperTarget());
        // The wallpaper target is gone, so it should adjust to the next target.
        appWin.removeImmediately();
        assertEquals(homeWin, dc.mWallpaperController.getWallpaperTarget());
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

    @Test
    public void testFixedRotationRecentsAnimatingTask() {
        final WindowState wallpaperWindow = createWallpaperWindow(mDisplayContent);
        final WallpaperWindowToken wallpaperToken = wallpaperWindow.mToken.asWallpaperToken();
        final WindowState appWin = createWindow(null, TYPE_BASE_APPLICATION, "app");
        makeWindowVisible(appWin);
        final ActivityRecord r = appWin.mActivityRecord;
        final RecentsAnimationController recentsController = mock(RecentsAnimationController.class);
        doReturn(true).when(recentsController).isWallpaperVisible(eq(appWin));
        mWm.setRecentsAnimationController(recentsController);

        r.applyFixedRotationTransform(mDisplayContent.getDisplayInfo(),
                mDisplayContent.mDisplayFrames, mDisplayContent.getConfiguration());
        // Invisible requested activity should not share its rotation transform.
        r.mVisibleRequested = false;
        mDisplayContent.mWallpaperController.adjustWallpaperWindows();
        assertFalse(wallpaperToken.hasFixedRotationTransform());

        // Wallpaper should link the transform of its target.
        r.mVisibleRequested = true;
        mDisplayContent.mWallpaperController.adjustWallpaperWindows();
        assertEquals(appWin, mDisplayContent.mWallpaperController.getWallpaperTarget());
        assertTrue(r.hasFixedRotationTransform());
        assertTrue(wallpaperToken.hasFixedRotationTransform());

        // The case with shell transition.
        registerTestTransitionPlayer();
        final Transition t = r.mTransitionController.createTransition(TRANSIT_OPEN);
        final ActivityRecord recents = mock(ActivityRecord.class);
        t.collect(r.getTask());
        r.mTransitionController.setTransientLaunch(recents, r.getTask());
        // The activity in restore-below task should not be the target if keyguard is not locked.
        mDisplayContent.mWallpaperController.adjustWallpaperWindows();
        assertNotEquals(appWin, mDisplayContent.mWallpaperController.getWallpaperTarget());
        // The activity in restore-below task should be the target if keyguard is occluded.
        doReturn(true).when(mDisplayContent).isKeyguardLocked();
        mDisplayContent.mWallpaperController.adjustWallpaperWindows();
        assertEquals(appWin, mDisplayContent.mWallpaperController.getWallpaperTarget());
    }

    @Test
    public void testWallpaperTokenVisibility() {
        final DisplayContent dc = mWm.mRoot.getDefaultDisplay();
        final WindowState wallpaperWindow = createWallpaperWindow(dc);
        final WallpaperWindowToken token = wallpaperWindow.mToken.asWallpaperToken();
        wallpaperWindow.setHasSurface(true);

        // Set-up mock shell transitions
        registerTestTransitionPlayer();

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

        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        token.finishSync(t, false /* cancel */);
        transit.onTransactionReady(transit.getSyncId(), t);
        dc.mTransitionController.finishTransition(transit);
        assertFalse(wallpaperWindow.isVisible());
        assertFalse(token.isVisible());

        // Assume wallpaper was visible. When transaction is ready without wallpaper target,
        // wallpaper should be requested to be invisible.
        token.setVisibility(true);
        transit = dc.mTransitionController.createTransition(TRANSIT_CLOSE);
        dc.mTransitionController.collect(token);
        transit.onTransactionReady(transit.getSyncId(), t);
        assertFalse(token.isVisibleRequested());
        assertTrue(token.isVisible());
    }

    private WindowState createWallpaperWindow(DisplayContent dc) {
        final WindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm, mock(IBinder.class),
                true /* explicit */, dc, true /* ownerCanManageAppTokens */);
        return createWindow(null /* parent */, TYPE_WALLPAPER, wallpaperWindowToken,
                "wallpaperWindow");
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
