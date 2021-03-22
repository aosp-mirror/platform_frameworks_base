/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.Task.ActivityState.STOPPED;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.WindowConfiguration;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager;

import androidx.test.filters.MediumTest;

import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

/**
 * Tests for Size Compatibility mode.
 *
 * Build/Install/Run:
 *  atest WmTests:SizeCompatTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class SizeCompatTests extends WindowTestsBase {
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private Task mTask;
    private ActivityRecord mActivity;

    private void setUpApp(DisplayContent display) {
        mTask = new TaskBuilder(mSupervisor).setDisplay(display).setCreateActivity(true).build();
        mActivity = mTask.getTopNonFinishingActivity();
    }

    private void setUpDisplaySizeWithApp(int dw, int dh) {
        final TestDisplayContent.Builder builder = new TestDisplayContent.Builder(mAtm, dw, dh);
        setUpApp(builder.build());
    }

    @Test
    public void testRestartProcessIfVisible() {
        setUpDisplaySizeWithApp(1000, 2500);
        doNothing().when(mSupervisor).scheduleRestartTimeout(mActivity);
        mActivity.mVisibleRequested = true;
        mActivity.setSavedState(null /* savedState */);
        mActivity.setState(Task.ActivityState.RESUMED, "testRestart");
        prepareUnresizable(mActivity, 1.5f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);

        final Rect originalOverrideBounds = new Rect(mActivity.getBounds());
        resizeDisplay(mTask.mDisplayContent, 600, 1200);
        // The visible activity should recompute configuration according to the last parent bounds.
        mAtm.mActivityClientController.restartActivityProcessIfVisible(mActivity.appToken);

        assertEquals(Task.ActivityState.RESTARTING_PROCESS, mActivity.getState());
        assertNotEquals(originalOverrideBounds, mActivity.getBounds());
    }

    @Test
    public void testKeepBoundsWhenChangingFromFreeformToFullscreen() {
        removeGlobalMinSizeRestriction();
        // Create landscape freeform display and a freeform app.
        DisplayContent display = new TestDisplayContent.Builder(mAtm, 2000, 1000)
                .setCanRotate(false)
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM).build();
        setUpApp(display);

        // Put app window into portrait freeform and then make it a compat app.
        final Rect bounds = new Rect(100, 100, 400, 600);
        mTask.setBounds(bounds);
        prepareUnresizable(mActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(bounds, mActivity.getBounds());
        // Activity is not yet in size compat mode; it is filling the freeform task window.
        assertActivityMaxBoundsSandboxed();

        // The activity should be able to accept negative x position [-150, 100 - 150, 600].
        final int dx = bounds.left + bounds.width() / 2;
        mTask.setBounds(bounds.left - dx, bounds.top, bounds.right - dx, bounds.bottom);
        assertEquals(mTask.getBounds(), mActivity.getBounds());

        final int density = mActivity.getConfiguration().densityDpi;

        // Change display configuration to fullscreen.
        Configuration c = new Configuration(display.getRequestedOverrideConfiguration());
        c.windowConfiguration.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        display.onRequestedOverrideConfigurationChanged(c);

        // Check if dimensions on screen stay the same by scaling.
        assertScaled();
        assertEquals(bounds.width(), mActivity.getBounds().width());
        assertEquals(bounds.height(), mActivity.getBounds().height());
        assertEquals(density, mActivity.getConfiguration().densityDpi);
        // Size compat mode is sandboxed at the activity level.
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testFixedAspectRatioBoundsWithDecorInSquareDisplay() {
        final int notchHeight = 100;
        setUpApp(new TestDisplayContent.Builder(mAtm, 600, 800).setNotch(notchHeight).build());

        final Rect displayBounds = mActivity.mDisplayContent.getWindowConfiguration().getBounds();
        final float aspectRatio = 1.2f;
        mActivity.info.setMaxAspectRatio(aspectRatio);
        mActivity.info.setMinAspectRatio(aspectRatio);
        prepareUnresizable(mActivity, -1f, SCREEN_ORIENTATION_UNSPECIFIED);
        final Rect appBounds = mActivity.getWindowConfiguration().getAppBounds();

        // The parent configuration doesn't change since the first resolved configuration, so the
        // activity should fit in the parent naturally (size=583x700, appBounds=[9, 100 - 592, 800],
        // horizontal offset = round((600 - 583) / 2) = 9)).
        assertFitted();
        final int offsetX = (int) ((1f + displayBounds.width() - appBounds.width()) / 2);
        // The bounds must be horizontal centered.
        assertEquals(offsetX, appBounds.left);
        assertEquals(appBounds.height(), displayBounds.height() - notchHeight);
        // Ensure the app bounds keep the declared aspect ratio.
        assertEquals(appBounds.height(), appBounds.width() * aspectRatio, 0.5f /* delta */);
        // The decor height should be a part of the effective bounds.
        assertEquals(mActivity.getBounds().height(), appBounds.height() + notchHeight);
        // Activity max bounds should be sandboxed; activity is letterboxed due to aspect ratio.
        assertActivityMaxBoundsSandboxed();
        // Activity max bounds ignore notch, since an app can be shown past the notch (although app
        // is currently limited by the notch).
        assertThat(mActivity.getWindowConfiguration().getMaxBounds().height())
                .isEqualTo(displayBounds.height());

        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        assertFitted();

        // After the orientation of activity is changed, the display is rotated, the aspect
        // ratio should be the same (bounds=[100, 0 - 800, 583], appBounds=[100, 0 - 800, 583]).
        assertEquals(appBounds.width(), appBounds.height() * aspectRatio, 0.5f /* delta */);
        // The notch is no longer on top.
        assertEquals(appBounds, mActivity.getBounds());
        // Activity max bounds are sandboxed.
        assertActivityMaxBoundsSandboxed();

        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();
        // Activity max bounds should be sandboxed; activity is letterboxed due to aspect ratio.
        assertActivityMaxBoundsSandboxed();
        // Activity max bounds ignore notch, since an app can be shown past the notch (although app
        // is currently limited by the notch).
        assertThat(mActivity.getWindowConfiguration().getMaxBounds().height())
                .isEqualTo(displayBounds.height());
    }

    @Test
    public void testFixedScreenConfigurationWhenMovingToDisplay() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make a new less-tall display with lower density
        final DisplayContent newDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 2000)
                        .setDensityDpi(200).build();

        prepareUnresizable(mActivity, 1.5f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);

        final Rect originalBounds = new Rect(mActivity.getBounds());
        final int originalDpi = mActivity.getConfiguration().densityDpi;

        // Move the non-resizable activity to the new display.
        mTask.reparent(newDisplay.getDefaultTaskDisplayArea(), true /* onTop */);

        assertEquals(originalBounds.width(), mActivity.getBounds().width());
        assertEquals(originalBounds.height(), mActivity.getBounds().height());
        assertEquals(originalDpi, mActivity.getConfiguration().densityDpi);
        // Activity is sandboxed; it is in size compat mode since it is not resizable and has a
        // max aspect ratio.
        assertActivityMaxBoundsSandboxed();
        assertScaled();
    }

    @Test
    public void testFixedScreenBoundsWhenDisplaySizeChanged() {
        setUpDisplaySizeWithApp(1000, 2500);
        prepareUnresizable(mActivity, -1f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        final DisplayContent display = mActivity.mDisplayContent;
        assertFitted();
        // Activity inherits bounds from TaskDisplayArea, since not sandboxed.
        assertMaxBoundsInheritDisplayAreaBounds();

        final Rect origBounds = new Rect(mActivity.getBounds());
        final Rect currentBounds = mActivity.getWindowConfiguration().getBounds();

        // Change the size of current display.
        resizeDisplay(display, 1000, 2000);
        // The bounds should be [100, 0 - 1100, 2500].
        assertEquals(origBounds.width(), currentBounds.width());
        assertEquals(origBounds.height(), currentBounds.height());
        assertScaled();

        // The scale is 2000/2500=0.8. The horizontal centered offset is (1000-(1000*0.8))/2=100.
        final float scale = (float) display.mBaseDisplayHeight / currentBounds.height();
        final int offsetX = (int) (display.mBaseDisplayWidth - (origBounds.width() * scale)) / 2;
        assertEquals(offsetX, currentBounds.left);

        // The position of configuration bounds should be the same as compat bounds.
        assertEquals(mActivity.getBounds().left, currentBounds.left);
        assertEquals(mActivity.getBounds().top, currentBounds.top);
        // Activity is sandboxed to the offset size compat bounds.
        assertActivityMaxBoundsSandboxed();

        // Change display size to a different orientation
        resizeDisplay(display, 2000, 1000);
        // The bounds should be [800, 0 - 1800, 2500].
        assertEquals(origBounds.width(), currentBounds.width());
        assertEquals(origBounds.height(), currentBounds.height());
        assertEquals(ORIENTATION_LANDSCAPE, display.getConfiguration().orientation);
        assertEquals(Configuration.ORIENTATION_PORTRAIT, mActivity.getConfiguration().orientation);
        // Activity is sandboxed to the offset size compat bounds.
        assertActivityMaxBoundsSandboxed();

        // The previous resize operation doesn't consider the rotation change after size changed.
        // These setups apply the requested orientation to rotation as real case that the top fixed
        // portrait activity will determine the display rotation.
        final DisplayRotation displayRotation = display.getDisplayRotation();
        doCallRealMethod().when(displayRotation).updateRotationUnchecked(anyBoolean());
        // Skip unrelated layout procedures.
        mAtm.deferWindowLayout();
        display.reconfigureDisplayLocked();
        displayRotation.updateOrientation(display.getOrientation(), true /* forceUpdate */);
        display.sendNewConfiguration();

        assertEquals(Configuration.ORIENTATION_PORTRAIT, display.getConfiguration().orientation);
        assertEquals(Configuration.ORIENTATION_PORTRAIT, mActivity.getConfiguration().orientation);
        // The size should still be in portrait [100, 0 - 1100, 2500] = 1000x2500.
        assertEquals(origBounds.width(), currentBounds.width());
        assertEquals(origBounds.height(), currentBounds.height());
        assertEquals(offsetX, currentBounds.left);
        assertScaled();
        // Activity is sandboxed due to size compat mode.
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testLetterboxFullscreenBoundsAndNotImeAttachable() {
        final int displayWidth = 2500;
        setUpDisplaySizeWithApp(displayWidth, 1000);

        final float maxAspect = 1.5f;
        prepareUnresizable(mActivity, maxAspect, SCREEN_ORIENTATION_LANDSCAPE);
        assertFitted();

        final Rect bounds = mActivity.getBounds();
        assertEquals(bounds.width(), bounds.height() * maxAspect, 0.0001f /* delta */);
        // The position should be horizontal centered.
        assertEquals((displayWidth - bounds.width()) / 2, bounds.left);
        // Activity max bounds should be sandboxed since it is letterboxed.
        assertActivityMaxBoundsSandboxed();

        mActivity.mDisplayContent.setImeLayeringTarget(addWindowToActivity(mActivity));
        // Make sure IME cannot attach to the app, otherwise IME window will also be shifted.
        assertFalse(mActivity.mDisplayContent.isImeAttachedToApp());

        // Recompute the natural configuration without resolving size compat configuration.
        mActivity.clearSizeCompatMode();
        mActivity.onConfigurationChanged(mTask.getConfiguration());
        // It should keep non-attachable because the resolved bounds will be computed according to
        // the aspect ratio that won't match its parent bounds.
        assertFalse(mActivity.mDisplayContent.isImeAttachedToApp());
        // Activity max bounds should be sandboxed since it is letterboxed.
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testIsLetterboxed_activityShowsWallpaper_returnsFalse() {
        setUpDisplaySizeWithApp(1000, 2500);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);
        final WindowState window = createWindow(null, TYPE_BASE_APPLICATION, mActivity, "window");

        assertEquals(window, mActivity.findMainWindow());
        assertTrue(mActivity.isLetterboxed(mActivity.findMainWindow()));

        window.mAttrs.flags |= FLAG_SHOW_WALLPAPER;

        assertFalse(mActivity.isLetterboxed(mActivity.findMainWindow()));
    }

    @Test
    public void testAspectRatioMatchParentBoundsAndImeAttachable() {
        setUpApp(new TestDisplayContent.Builder(mAtm, 1000, 2000)
                .setSystemDecorations(true).build());
        prepareUnresizable(mActivity, 2f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);
        assertFitted();

        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);
        mActivity.mDisplayContent.setImeLayeringTarget(addWindowToActivity(mActivity));
        mActivity.mDisplayContent.setImeInputTarget(
                mActivity.mDisplayContent.getImeTarget(IME_TARGET_LAYERING).getWindow());
        // Because the aspect ratio of display doesn't exceed the max aspect ratio of activity.
        // The activity should still fill its parent container and IME can attach to the activity.
        assertTrue(mActivity.matchParentBounds());
        assertTrue(mActivity.mDisplayContent.isImeAttachedToApp());

        final Rect letterboxInnerBounds = new Rect();
        mActivity.getLetterboxInnerBounds(letterboxInnerBounds);
        // The activity should not have letterbox.
        assertTrue(letterboxInnerBounds.isEmpty());
    }

    @Test
    public void testMoveToDifferentOrientationDisplay() {
        setUpDisplaySizeWithApp(1000, 2500);
        prepareUnresizable(mActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        final Rect currentBounds = mActivity.getWindowConfiguration().getBounds();
        final Rect originalBounds = new Rect(mActivity.getWindowConfiguration().getBounds());

        final int notchHeight = 100;
        final DisplayContent newDisplay = new TestDisplayContent.Builder(mAtm, 2000, 1000)
                .setCanRotate(false).setNotch(notchHeight).build();

        // Move the non-resizable activity to the new display.
        mTask.reparent(newDisplay.getDefaultTaskDisplayArea(), true /* onTop */);
        // The configuration bounds [820, 0 - 1820, 2500] should keep the same.
        assertEquals(originalBounds.width(), currentBounds.width());
        assertEquals(originalBounds.height(), currentBounds.height());
        assertScaled();
        // Activity max bounds are sandboxed due to size compat mode on the new display.
        assertActivityMaxBoundsSandboxed();

        final Rect newDisplayBounds = newDisplay.getWindowConfiguration().getBounds();
        // The scaled bounds should exclude notch area (1000 - 100 == 360 * 2500 / 1000 = 900).
        assertEquals(newDisplayBounds.height() - notchHeight,
                (int) ((float) mActivity.getBounds().width() * originalBounds.height()
                        / originalBounds.width()));

        // Recompute the natural configuration in the new display.
        mActivity.clearSizeCompatMode();
        mActivity.ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
        // Because the display cannot rotate, the portrait activity will fit the short side of
        // display with keeping portrait bounds [200, 0 - 700, 1000] in center.
        assertEquals(newDisplayBounds.height(), currentBounds.height());
        assertEquals(currentBounds.height() * newDisplayBounds.height() / newDisplayBounds.width(),
                currentBounds.width());
        assertFitted();
        // The appBounds should be [200, 100 - 700, 1000].
        final Rect appBounds = mActivity.getWindowConfiguration().getAppBounds();
        assertEquals(currentBounds.width(), appBounds.width());
        assertEquals(currentBounds.height() - notchHeight, appBounds.height());
        // Activity max bounds are sandboxed due to letterboxing from orientation mismatch with
        // display.
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testFixedOrientationRotateCutoutDisplay() {
        // Create a display with a notch/cutout
        final int notchHeight = 60;
        final int width = 1000;
        setUpApp(new TestDisplayContent.Builder(mAtm, width, 2500)
                .setNotch(notchHeight).build());
        // Bounds=[0, 0 - 1000, 1400], AppBounds=[0, 60 - 1000, 1460].
        final float maxAspect = 1.4f;
        prepareUnresizable(mActivity, 1.4f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);

        final Rect currentBounds = mActivity.getWindowConfiguration().getBounds();
        final Rect appBounds = mActivity.getWindowConfiguration().getAppBounds();
        final Rect origBounds = new Rect(currentBounds);
        final Rect origAppBounds = new Rect(appBounds);

        // Activity is sandboxed, and bounds include the area consumed by the notch.
        assertActivityMaxBoundsSandboxed();
        assertThat(mActivity.getConfiguration().windowConfiguration.getMaxBounds().height())
                .isEqualTo(Math.round(width * maxAspect) + notchHeight);

        // Although the activity is fixed orientation, force rotate the display.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_270);
        assertEquals(ROTATION_270, mTask.getWindowConfiguration().getRotation());

        assertEquals(origBounds.width(), currentBounds.width());
        // The notch is on horizontal side, so current height changes from 1460 to 1400.
        assertEquals(origBounds.height() - notchHeight, currentBounds.height());
        // Make sure the app size is the same
        assertEquals(origAppBounds.width(), appBounds.width());
        assertEquals(origAppBounds.height(), appBounds.height());
        // The activity is 1000x1400 and the display is 2500x1000.
        assertScaled();
        // The position in configuration should be global coordinates.
        assertEquals(mActivity.getBounds().left, currentBounds.left);
        assertEquals(mActivity.getBounds().top, currentBounds.top);

        // Activity max bounds are sandboxed due to size compat mode.
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testFixedAspectRatioOrientationChangeOrientation() {
        setUpDisplaySizeWithApp(1000, 2500);

        final float maxAspect = 1.4f;
        prepareUnresizable(mActivity, maxAspect, SCREEN_ORIENTATION_PORTRAIT);
        // The display aspect ratio 2.5 > 1.4 (max of activity), so the size is fitted.
        assertFitted();

        final Rect originalBounds = new Rect(mActivity.getBounds());
        final Rect originalAppBounds = new Rect(mActivity.getWindowConfiguration().getAppBounds());

        assertEquals((int) (originalBounds.width() * maxAspect), originalBounds.height());
        // Activity is sandboxed due to fixed aspect ratio.
        assertActivityMaxBoundsSandboxed();

        // Change the fixed orientation.
        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertFitted();
        assertEquals(originalBounds.width(), mActivity.getBounds().height());
        assertEquals(originalBounds.height(), mActivity.getBounds().width());
        assertEquals(originalAppBounds.width(),
                mActivity.getWindowConfiguration().getAppBounds().height());
        assertEquals(originalAppBounds.height(),
                mActivity.getWindowConfiguration().getAppBounds().width());
        // Activity is sandboxed due to fixed aspect ratio.
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testFixedScreenLayoutSizeBits() {
        setUpDisplaySizeWithApp(1000, 2500);
        final int fixedScreenLayout = Configuration.SCREENLAYOUT_LONG_NO
                | Configuration.SCREENLAYOUT_SIZE_NORMAL
                | Configuration.SCREENLAYOUT_COMPAT_NEEDED;
        final int layoutMask = Configuration.SCREENLAYOUT_LONG_MASK
                | Configuration.SCREENLAYOUT_SIZE_MASK
                | Configuration.SCREENLAYOUT_LAYOUTDIR_MASK
                | Configuration.SCREENLAYOUT_COMPAT_NEEDED;
        Configuration c = new Configuration(mTask.getRequestedOverrideConfiguration());
        c.screenLayout = fixedScreenLayout | Configuration.SCREENLAYOUT_LAYOUTDIR_LTR;
        mTask.onRequestedOverrideConfigurationChanged(c);
        prepareUnresizable(mActivity, 1.5f, SCREEN_ORIENTATION_UNSPECIFIED);

        // The initial configuration should inherit from parent.
        assertEquals(fixedScreenLayout | Configuration.SCREENLAYOUT_LAYOUTDIR_LTR,
                mActivity.getConfiguration().screenLayout & layoutMask);

        mTask.getConfiguration().screenLayout = Configuration.SCREENLAYOUT_LAYOUTDIR_RTL
                | Configuration.SCREENLAYOUT_LONG_YES | Configuration.SCREENLAYOUT_SIZE_LARGE;
        mActivity.onConfigurationChanged(mTask.getConfiguration());

        // The size and aspect ratio bits don't change, but the layout direction should be updated.
        assertEquals(fixedScreenLayout | Configuration.SCREENLAYOUT_LAYOUTDIR_RTL,
                mActivity.getConfiguration().screenLayout & layoutMask);
    }

    @Test
    public void testResetNonVisibleActivity() {
        setUpDisplaySizeWithApp(1000, 2500);
        prepareUnresizable(mActivity, 1.5f, SCREEN_ORIENTATION_UNSPECIFIED);
        final DisplayContent display = mTask.mDisplayContent;
        // Resize the display so the activity is in size compatibility mode.
        resizeDisplay(display, 900, 1800);

        mActivity.setState(STOPPED, "testSizeCompatMode");
        mActivity.mVisibleRequested = false;
        mActivity.app.setReportedProcState(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);

        // Simulate the display changes orientation.
        final Configuration rotatedConfig = rotateDisplay(display, ROTATION_90);
        // Size compatibility mode is able to handle orientation change so the process shouldn't be
        // restarted and the override configuration won't be cleared.
        verify(mActivity, never()).restartProcessIfVisible();
        assertScaled();
        // Activity max bounds are sandboxed due to size compat mode, even if is not visible.
        assertActivityMaxBoundsSandboxed();

        // Change display density
        display.mBaseDisplayDensity = (int) (0.7f * display.mBaseDisplayDensity);
        display.computeScreenConfiguration(rotatedConfig);
        mAtm.mAmInternal = mock(ActivityManagerInternal.class);
        display.onRequestedOverrideConfigurationChanged(rotatedConfig);

        // The override configuration should be reset and the activity's process will be killed.
        assertFitted();
        verify(mActivity).restartProcessIfVisible();
        waitHandlerIdle(mAtm.mH);
        verify(mAtm.mAmInternal).killProcess(
                eq(mActivity.app.mName), eq(mActivity.app.mUid), anyString());
    }

    /**
     * Ensures that {@link TaskOrganizerController} can receive callback about the activity in size
     * compatibility mode.
     */
    @Test
    public void testHandleActivitySizeCompatModeChanged() {
        setUpDisplaySizeWithApp(1000, 2000);
        doReturn(true).when(mTask).isOrganized();
        mActivity.setState(Task.ActivityState.RESUMED, "testHandleActivitySizeCompatModeChanged");
        prepareUnresizable(mActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        // Resize the display so that the activity exercises size-compat mode.
        resizeDisplay(mTask.mDisplayContent, 1000, 2500);

        // Expect the exact token when the activity is in size compatibility mode.
        verify(mTask).onSizeCompatActivityChanged();
        ActivityManager.RunningTaskInfo taskInfo = mTask.getTaskInfo();

        assertEquals(mActivity.appToken, taskInfo.topActivityToken);
        assertTrue(taskInfo.topActivityInSizeCompat);

        // Make the activity resizable again by restarting it
        clearInvocations(mTask);
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;
        mActivity.mVisibleRequested = true;
        mActivity.restartProcessIfVisible();
        // The full lifecycle isn't hooked up so manually set state to resumed
        mActivity.setState(Task.ActivityState.RESUMED, "testHandleActivitySizeCompatModeChanged");
        mTask.mDisplayContent.handleActivitySizeCompatModeIfNeeded(mActivity);

        // Expect null token when switching to non-size-compat mode activity.
        verify(mTask).onSizeCompatActivityChanged();
        taskInfo = mTask.getTaskInfo();

        assertEquals(mActivity.appToken, taskInfo.topActivityToken);
        assertFalse(taskInfo.topActivityInSizeCompat);
    }

    @Test
    public void testHandleActivitySizeCompatModeChangedOnDifferentTask() {
        setUpDisplaySizeWithApp(1000, 2000);
        doReturn(true).when(mTask).isOrganized();
        mActivity.setState(Task.ActivityState.RESUMED, "testHandleActivitySizeCompatModeChanged");
        prepareUnresizable(mActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        // Resize the display so that the activity exercises size-compat mode.
        resizeDisplay(mTask.mDisplayContent, 1000, 2500);

        // Expect the exact token when the activity is in size compatibility mode.
        verify(mTask).onSizeCompatActivityChanged();
        ActivityManager.RunningTaskInfo taskInfo = mTask.getTaskInfo();

        assertEquals(mActivity.appToken, taskInfo.topActivityToken);
        assertTrue(taskInfo.topActivityInSizeCompat);

        // Create another Task to hold another size compat activity.
        clearInvocations(mTask);
        final Task secondTask = new TaskBuilder(mSupervisor).setDisplay(mTask.getDisplayContent())
                .setCreateActivity(true).build();
        final ActivityRecord secondActivity = secondTask.getTopNonFinishingActivity();
        doReturn(true).when(secondTask).isOrganized();
        secondActivity.setState(Task.ActivityState.RESUMED,
                "testHandleActivitySizeCompatModeChanged");
        prepareUnresizable(secondActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);

        // Resize the display so that the activity exercises size-compat mode.
        resizeDisplay(mTask.mDisplayContent, 1000, 3000);

        // Expect the exact token when the activity is in size compatibility mode.
        verify(secondTask).onSizeCompatActivityChanged();
        verify(mTask, never()).onSizeCompatActivityChanged();
        taskInfo = secondTask.getTaskInfo();

        assertEquals(secondActivity.appToken, taskInfo.topActivityToken);
        assertTrue(taskInfo.topActivityInSizeCompat);
    }

    @Test
    public void testShouldCreateCompatDisplayInsetsOnResizeableTask() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .build();
        assertTrue(activity.shouldCreateCompatDisplayInsets());

        // The non-resizable activity should not be size compat because it is on a resizable task
        // in multi-window mode.
        mTask.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        assertFalse(activity.shouldCreateCompatDisplayInsets());
        // Activity should not be sandboxed.
        assertMaxBoundsInheritDisplayAreaBounds();

        // The non-resizable activity should not be size compat because the display support
        // changing windowing mode from fullscreen to freeform.
        mTask.mDisplayContent.setDisplayWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        mTask.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        assertFalse(activity.shouldCreateCompatDisplayInsets());
        // Activity should not be sandboxed.
        assertMaxBoundsInheritDisplayAreaBounds();
    }

    @Test
    public void testShouldCreateCompatDisplayInsetsWhenUnresizeableAndSupportsSizeChangesTrue() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setSupportsSizeChanges(true)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    public void testShouldCreateCompatDisplayInsetsWhenUnresizeableAndSupportsSizeChangesFalse() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setSupportsSizeChanges(false)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
        assertTrue(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    public void testShouldCreateCompatDisplayInsetsWhenResizeableAndSupportsSizeChangesFalse() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setResizeMode(ActivityInfo.RESIZE_MODE_RESIZEABLE)
                .setSupportsSizeChanges(false)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    public void
            testShouldCreateCompatDisplayInsetsWhenUnfixedOrientationSupportsSizeChangesFalse() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setSupportsSizeChanges(false)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.FORCE_RESIZE_APP})
    public void testShouldCreateCompatDisplayInsetsWhenForceResizeAppOverrideSet() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setSupportsSizeChanges(false)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.FORCE_NON_RESIZE_APP})
    public void testShouldCreateCompatDisplayInsetsWhenForceNonResizeOverrideSet() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setResizeMode(ActivityInfo.RESIZE_MODE_RESIZEABLE)
                .setSupportsSizeChanges(true)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
        assertTrue(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.FORCE_NON_RESIZE_APP})
    public void testShouldCreateCompatDisplayInsetsWhenForceNonResizeSetAndUnfixedOrientation() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setResizeMode(ActivityInfo.RESIZE_MODE_RESIZEABLE)
                .setSupportsSizeChanges(true)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
        assertTrue(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testOverrideMinAspectRatioMedium() {
        setUpDisplaySizeWithApp(1000, 1200);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        // The per-package override forces the activity into a 3:2 aspect ratio
        assertEquals(1200, activity.getBounds().height());
        assertEquals(1200 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE,
                activity.getBounds().width(), 0.5);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testOverrideMinAspectRatioLowerThanManifest() {
        setUpDisplaySizeWithApp(1400, 1600);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setMinAspectRatio(2f)
                .setUid(android.os.Process.myUid())
                .build();

        // The per-package override should have no effect, because the manifest aspect ratio is
        // larger (2:1)
        assertEquals(1600, activity.getBounds().height());
        assertEquals(800, activity.getBounds().width());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE})
    public void testOverrideMinAspectRatioLargerThanManifest() {
        setUpDisplaySizeWithApp(1400, 1600);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setMinAspectRatio(1.1f)
                .setUid(android.os.Process.myUid())
                .build();

        // The per-package override should have no effect, because the manifest aspect ratio is
        // larger (2:1)
        assertEquals(1600, activity.getBounds().height());
        assertEquals(1600 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                activity.getBounds().width(), 0.5);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE})
    public void testOverrideMinAspectRatioLarge() {
        setUpDisplaySizeWithApp(1500, 1600);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        // The per-package override forces the activity into a 16:9 aspect ratio
        assertEquals(1600, activity.getBounds().height());
        assertEquals(1600 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                activity.getBounds().width(), 0.5);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE})
    public void testOverrideMinAspectRatio_Both() {
        // If multiple override aspect ratios are set, we should use the largest one

        setUpDisplaySizeWithApp(1400, 1600);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        // The per-package override forces the activity into a 16:9 aspect ratio
        assertEquals(1600, activity.getBounds().height());
        assertEquals(1600 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                activity.getBounds().width(), 0.5);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testOverrideMinAspectRatioWithoutGlobalOverride() {
        // In this test, only OVERRIDE_MIN_ASPECT_RATIO_1_5 is set, which has no effect without
        // OVERRIDE_MIN_ASPECT_RATIO being also set.

        setUpDisplaySizeWithApp(1000, 1200);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        // The per-package override should have no effect
        assertEquals(1200, activity.getBounds().height());
        assertEquals(1000, activity.getBounds().width());
    }

    @Test
    public void testLaunchWithFixedRotationTransform() {
        final int dw = 1000;
        final int dh = 2500;
        final int notchHeight = 200;
        setUpApp(new TestDisplayContent.Builder(mAtm, dw, dh).setNotch(notchHeight).build());
        addStatusBar(mActivity.mDisplayContent);

        mActivity.setVisible(false);
        mActivity.mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_OPEN);
        mActivity.mDisplayContent.mOpeningApps.add(mActivity);
        final float maxAspect = 1.8f;
        prepareUnresizable(mActivity, maxAspect, SCREEN_ORIENTATION_LANDSCAPE);

        assertFitted();
        assertTrue(mActivity.isFixedRotationTransforming());
        // Display keeps in original orientation.
        assertEquals(Configuration.ORIENTATION_PORTRAIT,
                mActivity.mDisplayContent.getConfiguration().orientation);
        // The width should be restricted by the max aspect ratio = 1000 * 1.8 = 1800.
        assertEquals((int) (dw * maxAspect), mActivity.getBounds().width());
        // The notch is at the left side of the landscape activity. The bounds should be horizontal
        // centered in the remaining area [200, 0 - 2500, 1000], so its left should be
        // 200 + (2300 - 1800) / 2 = 450. The bounds should be [450, 0 - 2250, 1000].
        assertEquals(notchHeight + (dh - notchHeight - mActivity.getBounds().width()) / 2,
                mActivity.getBounds().left);

        // The letterbox needs a main window to layout.
        final WindowState w = addWindowToActivity(mActivity);
        // Compute the frames of the window and invoke {@link ActivityRecord#layoutLetterbox}.
        mActivity.mRootWindowContainer.performSurfacePlacement();
        // The letterbox insets should be [450, 0 - 250, 0].
        assertEquals(new Rect(mActivity.getBounds().left, 0, dh - mActivity.getBounds().right, 0),
                mActivity.getLetterboxInsets());

        final DisplayPolicy displayPolicy = mActivity.mDisplayContent.getDisplayPolicy();
        // The activity doesn't fill the display, so the letterbox of the rotated activity is
        // overlapped with the rotated content frame of status bar. Hence the status bar shouldn't
        // be transparent.
        assertFalse(displayPolicy.isFullyTransparentAllowed(w, TYPE_STATUS_BAR));

        // Activity is sandboxed.
        assertActivityMaxBoundsSandboxed();

        // Make the activity fill the display.
        prepareUnresizable(mActivity, 10 /* maxAspect */, SCREEN_ORIENTATION_LANDSCAPE);
        w.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        // Refresh the letterbox.
        mActivity.mRootWindowContainer.performSurfacePlacement();

        // The letterbox should only cover the notch area, so status bar can be transparent.
        assertEquals(new Rect(notchHeight, 0, 0, 0), mActivity.getLetterboxInsets());
        assertTrue(displayPolicy.isFullyTransparentAllowed(w, TYPE_STATUS_BAR));
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_fixedOrientationAppLaunchedLetterbox() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app without max aspect.
        prepareUnresizable(mActivity, /* maxAspect= */ 0, SCREEN_ORIENTATION_PORTRAIT);

        final Rect displayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect activityBounds = new Rect(mActivity.getBounds());

        // Display shouldn't be rotated.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED,
                mActivity.mDisplayContent.getLastOrientation());
        assertTrue(displayBounds.width() > displayBounds.height());

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());
        assertActivityMaxBoundsSandboxed();

        // Activity bounds should be 700x1400 with the ratio as the display.
        assertEquals(displayBounds.height(), activityBounds.height());
        assertEquals(displayBounds.height() * displayBounds.height() / displayBounds.width(),
                activityBounds.width());
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_fixedOrientationAppRespectMinAspectRatio() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app with min aspect ratio higher that aspect ratio override for fixed
        // orientation letterbox.
        mActivity.mWmService.setFixedOrientationLetterboxAspectRatio(1.1f);
        mActivity.info.setMinAspectRatio(3);
        prepareUnresizable(mActivity, /* maxAspect= */ 0, SCREEN_ORIENTATION_PORTRAIT);

        final Rect displayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect activityBounds = new Rect(mActivity.getBounds());

        // Display shouldn't be rotated.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED,
                mActivity.mDisplayContent.getLastOrientation());
        assertTrue(displayBounds.width() > displayBounds.height());

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());

        // Activity bounds should respect minimum aspect ratio for activity.
        assertEquals(displayBounds.height(), activityBounds.height());
        assertEquals((int) Math.rint(displayBounds.height()
                        / mActivity.info.getManifestMinAspectRatio()),
                activityBounds.width());
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_fixedOrientationAppRespectMaxAspectRatio() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app with max aspect ratio lower that aspect ratio override for fixed
        // orientation letterbox.
        mActivity.mWmService.setFixedOrientationLetterboxAspectRatio(3);
        prepareUnresizable(mActivity, /* maxAspect= */ 2, SCREEN_ORIENTATION_PORTRAIT);

        final Rect displayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect activityBounds = new Rect(mActivity.getBounds());

        // Display shouldn't be rotated.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED,
                mActivity.mDisplayContent.getLastOrientation());
        assertTrue(displayBounds.width() > displayBounds.height());

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());

        // Activity bounds should respect maximum aspect ratio for activity.
        assertEquals(displayBounds.height(), activityBounds.height());
        assertEquals((int) Math.rint(displayBounds.height()
                        / mActivity.info.getMaxAspectRatio()),
                activityBounds.width());
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_fixedOrientationAppWithAspectRatioOverride() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app with min aspect ratio higher that aspect ratio override for fixed
        // orientation letterbox.
        final float fixedOrientationLetterboxAspectRatio = 1.1f;
        mActivity.mWmService.setFixedOrientationLetterboxAspectRatio(
                fixedOrientationLetterboxAspectRatio);
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_PORTRAIT);

        final Rect displayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect activityBounds = new Rect(mActivity.getBounds());

        // Display shouldn't be rotated.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED,
                mActivity.mDisplayContent.getLastOrientation());
        assertTrue(displayBounds.width() > displayBounds.height());

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());

        // Activity bounds should respect aspect ratio override for fixed orientation letterbox.
        assertEquals(displayBounds.height(), activityBounds.height());
        assertEquals((int) Math.rint(displayBounds.height() / fixedOrientationLetterboxAspectRatio),
                activityBounds.width());
    }

    @Test
    public void
            testDisplayIgnoreOrientationRequest_orientationLetterboxBecameSizeCompatAfterRotate() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app without max aspect.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_PORTRAIT);

        final Rect activityBounds = new Rect(mActivity.getBounds());

        // Rotate display to portrait.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        final Rect displayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect newActivityBounds = new Rect(mActivity.getBounds());
        assertTrue(displayBounds.width() < displayBounds.height());

        // App should be in size compat.
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertScaled();
        assertEquals(activityBounds.width(), newActivityBounds.width());
        assertEquals(activityBounds.height(), newActivityBounds.height());
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_sizeCompatAfterRotate() {
        // Set up a display in portrait and ignoring orientation request.
        setUpDisplaySizeWithApp(1400, 2800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app without max aspect.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_PORTRAIT);

        // App should launch in fullscreen.
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());
        // Activity inherits max bounds from TaskDisplayArea.
        assertMaxBoundsInheritDisplayAreaBounds();

        // Rotate display to landscape.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        final Rect rotatedDisplayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect rotatedActivityBounds = new Rect(mActivity.getBounds());
        assertTrue(rotatedDisplayBounds.width() > rotatedDisplayBounds.height());

        // App should be in size compat.
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertScaled();
        assertThat(mActivity.inSizeCompatMode()).isTrue();
        assertActivityMaxBoundsSandboxed();

        // App bounds should be 700x1400 with the ratio as the display.
        assertEquals(rotatedDisplayBounds.height(), rotatedActivityBounds.height());
        assertEquals(rotatedDisplayBounds.height() * rotatedDisplayBounds.height()
                        / rotatedDisplayBounds.width(), rotatedActivityBounds.width());
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_newLaunchedOrientationAppInLetterbox() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        final DisplayContent display = mActivity.mDisplayContent;
        display.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app without max aspect.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_PORTRAIT);

        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());

        // Launch another portrait fixed app.
        spyOn(mTask);
        setBooted(display.mWmService.mAtmService);
        final ActivityRecord newActivity = new ActivityBuilder(display.mWmService.mAtmService)
                .setResizeMode(RESIZE_MODE_UNRESIZEABLE)
                .setScreenOrientation(SCREEN_ORIENTATION_PORTRAIT)
                .setTask(mTask)
                .build();

        // Update with new activity requested orientation and recompute bounds with no previous
        // size compat cache.
        verify(mTask).onDescendantOrientationChanged(same(newActivity));

        final Rect displayBounds = new Rect(display.getBounds());
        final Rect taskBounds = new Rect(mTask.getBounds());
        final Rect newActivityBounds = new Rect(newActivity.getBounds());

        // Task and display bounds should be equal while activity should be letterboxed and
        // has 700x1400 bounds with the ratio as the display.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(newActivity.inSizeCompatMode());
        assertActivityMaxBoundsSandboxed();
        assertEquals(taskBounds, displayBounds);
        assertEquals(displayBounds.height(), newActivityBounds.height());
        assertEquals(displayBounds.height() * displayBounds.height() / displayBounds.width(),
                newActivityBounds.width());
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_newLaunchedMaxAspectApp() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        final DisplayContent display = mActivity.mDisplayContent;
        display.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app without max aspect.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_PORTRAIT);

        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());

        // Launch another portrait fixed app with max aspect ratio as 1.3.
        spyOn(mTask);
        setBooted(display.mWmService.mAtmService);
        final ActivityRecord newActivity = new ActivityBuilder(display.mWmService.mAtmService)
                .setResizeMode(RESIZE_MODE_UNRESIZEABLE)
                .setMaxAspectRatio(1.3f)
                .setScreenOrientation(SCREEN_ORIENTATION_PORTRAIT)
                .setTask(mTask)
                .build();

        // Update with new activity requested orientation and recompute bounds with no previous
        // size compat cache.
        verify(mTask).onDescendantOrientationChanged(same(newActivity));

        final Rect displayBounds = new Rect(display.getBounds());
        final Rect taskBounds = new Rect(mTask.getBounds());
        final Rect newActivityBounds = new Rect(newActivity.getBounds());

        // Task bounds should fill parent bounds.
        assertEquals(displayBounds, taskBounds);

        // Prior and new activity max bounds are sandboxed due to letterbox.
        assertThat(newActivity.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(newActivityBounds);
        assertActivityMaxBoundsSandboxed();

        // Activity bounds should be (1400 / 1.3 = 1076)x1400 with the app requested ratio.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(newActivity.inSizeCompatMode());
        assertEquals(displayBounds.height(), newActivityBounds.height());
        assertEquals((long) Math.rint(newActivityBounds.height()
                        / newActivity.info.getMaxAspectRatio()),
                newActivityBounds.width());
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_pausedAppNotLostSizeCompat() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        final DisplayContent display = mActivity.mDisplayContent;
        display.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_PORTRAIT);
        clearInvocations(mActivity);

        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());

        // Rotate display to portrait.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // App should be in size compat.
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertScaled();
        assertThat(mActivity.inSizeCompatMode()).isTrue();
        // Activity max bounds are sandboxed due to size compat mode.
        assertActivityMaxBoundsSandboxed();

        final Rect activityBounds = new Rect(mActivity.getBounds());
        mTask.resumeTopActivityUncheckedLocked(null /* prev */, null /* options */);

        // App still in size compat, and the bounds don't change.
        verify(mActivity, never()).clearSizeCompatMode();
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertScaled();
        assertEquals(activityBounds, mActivity.getBounds());
        // Activity max bounds are sandboxed due to size compat.
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_rotated180_notInSizeCompat() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        final DisplayContent display = mActivity.mDisplayContent;
        display.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_PORTRAIT);

        // In fixed orientation letterbox
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());
        assertActivityMaxBoundsSandboxed();

        // Rotate display to portrait.
        rotateDisplay(display, ROTATION_90);

        // App should be in size compat.
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertScaled();
        assertActivityMaxBoundsSandboxed();

        // Rotate display to landscape.
        rotateDisplay(display, ROTATION_180);

        // In activity letterbox
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testDisplayIgnoreOrientationRequestWithInsets_rotated180_notInSizeCompat() {
        // Set up a display in portrait with display cutout and ignoring orientation request.
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1400, 2800)
                .setNotch(75)
                .build();
        setUpApp(display);
        display.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Landscape fixed app.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_LANDSCAPE);

        // In fixed orientation letterbox
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());
        assertActivityMaxBoundsSandboxed();

        // Rotate display to landscape.
        rotateDisplay(display, ROTATION_90);

        // App should be in size compat.
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertScaled();
        assertActivityMaxBoundsSandboxed();

        // Rotate display to portrait.
        rotateDisplay(display, ROTATION_180);

        // In fixed orientation letterbox
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testTaskDisplayAreaNotFillDisplay() {
        setUpDisplaySizeWithApp(1400, 2800);
        final DisplayContent display = mActivity.mDisplayContent;
        final TaskDisplayArea taskDisplayArea = mActivity.getDisplayArea();
        taskDisplayArea.setBounds(0, 0, 1000, 2400);

        // Portrait fixed app.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_LANDSCAPE);

        final Rect displayBounds = new Rect(display.getBounds());
        assertEquals(ORIENTATION_LANDSCAPE, display.getConfiguration().orientation);
        assertEquals(2800, displayBounds.width());
        assertEquals(1400, displayBounds.height());
        Rect displayAreaBounds = new Rect(0, 0, 2400, 1000);
        taskDisplayArea.setBounds(displayAreaBounds);

        final Rect activityBounds = new Rect(mActivity.getBounds());
        assertFalse(mActivity.inSizeCompatMode());
        assertEquals(2400, activityBounds.width());
        assertEquals(1000, activityBounds.height());
        // Task and activity maximum bounds inherit from TaskDisplayArea bounds.
        assertThat(mActivity.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(displayAreaBounds);
        assertThat(mTask.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(displayAreaBounds);
    }

    @Test
    public void testSupportsNonResizableInSplitScreen_letterboxForDifferentOrientation() {
        // Support non resizable in multi window
        mAtm.mSupportsNonResizableMultiWindow = true;
        setUpDisplaySizeWithApp(1000, 2800);
        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());

        // Non-resizable landscape activity
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);
        final Rect originalBounds = new Rect(mActivity.getBounds());

        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP,
                false /*moveParents*/, "test");
        organizer.mPrimary.setBounds(0, 0, 1000, 1400);
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, mActivity.getWindowingMode());

        // Non-resizable activity in size compat mode
        assertScaled();
        final Rect newBounds = new Rect(mActivity.getWindowConfiguration().getBounds());
        assertEquals(originalBounds.width(), newBounds.width());
        assertEquals(originalBounds.height(), newBounds.height());
        assertActivityMaxBoundsSandboxed();

        // Recompute the natural configuration of the non-resizable activity and the split screen.
        mActivity.clearSizeCompatMode();

        // Draw letterbox.
        mActivity.setVisible(false);
        mActivity.mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_OPEN);
        mActivity.mDisplayContent.mOpeningApps.add(mActivity);
        addWindowToActivity(mActivity);
        mActivity.mRootWindowContainer.performSurfacePlacement();

        // Split screen is also in portrait [1000,1400], so activity should be in fixed orientation
        // letterbox.
        assertEquals(ORIENTATION_PORTRAIT, mTask.getConfiguration().orientation);
        assertEquals(ORIENTATION_LANDSCAPE, mActivity.getConfiguration().orientation);
        assertFitted();
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertActivityMaxBoundsSandboxed();

        // Letterbox should fill the gap between the split screen and the letterboxed activity.
        final Rect primarySplitBounds = new Rect(organizer.mPrimary.getBounds());
        final Rect letterboxedBounds = new Rect(mActivity.getBounds());
        assertTrue(primarySplitBounds.contains(letterboxedBounds));
        assertEquals(new Rect(letterboxedBounds.left - primarySplitBounds.left,
                letterboxedBounds.top - primarySplitBounds.top,
                primarySplitBounds.right - letterboxedBounds.right,
                primarySplitBounds.bottom - letterboxedBounds.bottom),
                mActivity.getLetterboxInsets());
    }

    @Test
    public void testSupportsNonResizableInSplitScreen_fillTaskForSameOrientation() {
        // Support non resizable in multi window
        mAtm.mSupportsNonResizableMultiWindow = true;
        setUpDisplaySizeWithApp(1000, 2800);
        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());

        // Non-resizable portrait activity
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);
        final Rect originalBounds = new Rect(mActivity.getBounds());

        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP,
                false /*moveParents*/, "test");
        organizer.mPrimary.setBounds(0, 0, 1000, 1400);
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, mActivity.getWindowingMode());

        // Non-resizable activity in size compat mode
        assertScaled();
        final Rect newBounds = new Rect(mActivity.getWindowConfiguration().getBounds());
        assertEquals(originalBounds.width(), newBounds.width());
        assertEquals(originalBounds.height(), newBounds.height());
        assertActivityMaxBoundsSandboxed();

        // Recompute the natural configuration of the non-resizable activity and the split screen.
        mActivity.clearSizeCompatMode();
        mActivity.setVisible(false);
        mActivity.mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_OPEN);
        mActivity.mDisplayContent.mOpeningApps.add(mActivity);
        addWindowToActivity(mActivity);
        mActivity.mRootWindowContainer.performSurfacePlacement();

        // Split screen is also in portrait [1000,1400], which meets the activity request. It should
        // sandbox to the activity bounds for non-resizable.
        assertEquals(ORIENTATION_PORTRAIT, mTask.getConfiguration().orientation);
        assertEquals(ORIENTATION_PORTRAIT, mActivity.getConfiguration().orientation);
        assertFitted();
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertActivityMaxBoundsSandboxed();

        // Activity bounds fill split screen.
        final Rect primarySplitBounds = new Rect(organizer.mPrimary.getBounds());
        final Rect letterboxedBounds = new Rect(mActivity.getBounds());
        assertEquals(primarySplitBounds, letterboxedBounds);
    }

    private static WindowState addWindowToActivity(ActivityRecord activity) {
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        params.setFitInsetsSides(0);
        params.setFitInsetsTypes(0);
        final TestWindowState w = new TestWindowState(
                activity.mWmService, mock(Session.class), new TestIWindow(), params, activity);
        makeWindowVisible(w);
        w.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        activity.addWindow(w);
        return w;
    }

    private static void addStatusBar(DisplayContent displayContent) {
        final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
        doReturn(true).when(displayPolicy).hasStatusBar();
        displayPolicy.onConfigurationChanged();

        final TestWindowToken token = createTestWindowToken(
                TYPE_STATUS_BAR, displayContent);
        final WindowManager.LayoutParams attrs =
                new WindowManager.LayoutParams(TYPE_STATUS_BAR);
        attrs.gravity = android.view.Gravity.TOP;
        attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        attrs.setFitInsetsTypes(0 /* types */);
        final TestWindowState statusBar = new TestWindowState(
                displayContent.mWmService, mock(Session.class), new TestIWindow(), attrs, token);
        token.addWindow(statusBar);
        statusBar.setRequestedSize(displayContent.mBaseDisplayWidth,
                displayContent.getDisplayUiContext().getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height));

        displayPolicy.addWindowLw(statusBar, attrs);
        displayPolicy.layoutWindowLw(statusBar, null, displayContent.mDisplayFrames);
    }

    static void prepareUnresizable(ActivityRecord activity, int screenOrientation) {
        prepareUnresizable(activity, -1 /* maxAspect */, screenOrientation);
    }

    static void prepareUnresizable(ActivityRecord activity, float maxAspect,
            int screenOrientation) {
        prepareLimitedBounds(activity, maxAspect, screenOrientation, true /* isUnresizable */);
    }

    static void prepareLimitedBounds(ActivityRecord activity, int screenOrientation,
            boolean isUnresizable) {
        prepareLimitedBounds(activity, -1 /* maxAspect */, screenOrientation, isUnresizable);
    }

    /**
     * Setups {@link #mActivity} with restriction on its bounds, such as maxAspect, fixed
     * orientation, and/or whether it is resizable.
     */
    static void prepareLimitedBounds(ActivityRecord activity, float maxAspect,
            int screenOrientation, boolean isUnresizable) {
        activity.info.resizeMode = isUnresizable
                ? RESIZE_MODE_UNRESIZEABLE
                : RESIZE_MODE_RESIZEABLE;
        activity.mVisibleRequested = true;
        if (maxAspect >= 0) {
            activity.info.setMaxAspectRatio(maxAspect);
        }
        if (screenOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
            activity.info.screenOrientation = screenOrientation;
            activity.setRequestedOrientation(screenOrientation);
        }
        // Make sure to use the provided configuration to construct the size compat fields.
        activity.clearSizeCompatMode();
        activity.ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
        // Make sure the display configuration reflects the change of activity.
        if (activity.mDisplayContent.updateOrientation()) {
            activity.mDisplayContent.sendNewConfiguration();
        }
    }

    /** Asserts that the size of activity is larger than its parent so it is scaling. */
    private void assertScaled() {
        assertTrue(mActivity.inSizeCompatMode());
        assertNotEquals(1f, mActivity.getSizeCompatScale(), 0.0001f /* delta */);
    }

    /** Asserts that the activity is best fitted in the parent. */
    private void assertFitted() {
        final boolean inSizeCompatMode = mActivity.inSizeCompatMode();
        final String failedConfigInfo = inSizeCompatMode
                ? ("ParentConfig=" + mActivity.getParent().getConfiguration()
                        + " ActivityConfig=" + mActivity.getConfiguration())
                : "";
        assertFalse(failedConfigInfo, inSizeCompatMode);
        assertFalse(mActivity.hasSizeCompatBounds());
    }

    /** Asserts the activity max bounds inherit from the TaskDisplayArea. */
    private void assertMaxBoundsInheritDisplayAreaBounds() {
        assertThat(mActivity.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(mTask.getDisplayArea().getBounds());
    }

    /**
     * Asserts activity-level letterbox or size compat mode size compat mode, so activity max
     * bounds are sandboxed.
     */
    private void assertActivityMaxBoundsSandboxed() {
        // Activity max bounds are sandboxed due to size compat mode.
        assertThat(mActivity.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(mActivity.getWindowConfiguration().getBounds());
    }

    static Configuration rotateDisplay(DisplayContent display, int rotation) {
        final Configuration c = new Configuration();
        display.getDisplayRotation().setRotation(rotation);
        display.computeScreenConfiguration(c);
        display.onRequestedOverrideConfigurationChanged(c);
        return c;
    }

    private static void resizeDisplay(DisplayContent displayContent, int width, int height) {
        displayContent.mBaseDisplayWidth = width;
        displayContent.mBaseDisplayHeight = height;
        final Configuration c = new Configuration();
        displayContent.computeScreenConfiguration(c);
        displayContent.onRequestedOverrideConfigurationChanged(c);
    }
}
