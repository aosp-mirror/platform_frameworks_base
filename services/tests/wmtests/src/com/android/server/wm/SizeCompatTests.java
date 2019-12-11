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

import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.TaskStackListener;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Tests for Size Compatibility mode.
 *
 * Build/Install/Run:
 *  atest WmTests:SizeCompatTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class SizeCompatTests extends ActivityTestsBase {
    private ActivityStack mStack;
    private Task mTask;
    private ActivityRecord mActivity;

    private void setUpApp(ActivityDisplay display) {
        mStack = new StackBuilder(mRootActivityContainer).setDisplay(display).build();
        mTask = mStack.getChildAt(0);
        mActivity = mTask.getTopNonFinishingActivity();
    }

    private void ensureActivityConfiguration() {
        mActivity.ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
    }

    @Test
    public void testRestartProcessIfVisible() {
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2500).build());
        doNothing().when(mSupervisor).scheduleRestartTimeout(mActivity);
        mActivity.mVisibleRequested = true;
        mActivity.setSavedState(null /* savedState */);
        mActivity.setState(ActivityStack.ActivityState.RESUMED, "testRestart");
        prepareUnresizable(1.5f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);

        final Rect originalOverrideBounds = new Rect(mActivity.getBounds());
        resizeDisplay(mStack.getDisplay(), 600, 1200);
        // The visible activity should recompute configuration according to the last parent bounds.
        mService.restartActivityProcessIfVisible(mActivity.appToken);

        assertEquals(ActivityStack.ActivityState.RESTARTING_PROCESS, mActivity.getState());
        assertNotEquals(originalOverrideBounds, mActivity.getBounds());
    }

    @Test
    public void testKeepBoundsWhenChangingFromFreeformToFullscreen() {
        removeGlobalMinSizeRestriction();
        // create freeform display and a freeform app
        ActivityDisplay display = new TestActivityDisplay.Builder(mService, 2000, 1000)
                .setCanRotate(false)
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM).build();
        setUpApp(display);

        // Put app window into freeform and then make it a compat app.
        mTask.setBounds(100, 100, 400, 600);
        prepareUnresizable(-1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);

        final Rect bounds = new Rect(mActivity.getBounds());
        final int density = mActivity.getConfiguration().densityDpi;

        // change display configuration to fullscreen
        Configuration c = new Configuration(display.getRequestedOverrideConfiguration());
        c.windowConfiguration.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        display.onRequestedOverrideConfigurationChanged(c);

        // check if dimensions stay the same
        assertTrue(mActivity.inSizeCompatMode());
        assertEquals(bounds.width(), mActivity.getBounds().width());
        assertEquals(bounds.height(), mActivity.getBounds().height());
        assertEquals(density, mActivity.getConfiguration().densityDpi);
    }

    @Test
    public void testFixedAspectRatioBoundsWithDecor() {
        final int decorHeight = 200; // e.g. The device has cutout.
        setUpApp(new TestActivityDisplay.Builder(mService, 600, 800)
                .setNotch(decorHeight).build());

        mActivity.info.minAspectRatio = mActivity.info.maxAspectRatio = 1;
        prepareUnresizable(-1f, SCREEN_ORIENTATION_UNSPECIFIED);

        // The parent configuration doesn't change since the first resolved configuration, so the
        // activity shouldn't be in the size compatibility mode.
        assertFalse(mActivity.inSizeCompatMode());

        final Rect appBounds = mActivity.getWindowConfiguration().getAppBounds();
        // Ensure the app bounds keep the declared aspect ratio.
        assertEquals(appBounds.width(), appBounds.height());
        // The decor height should be a part of the effective bounds.
        assertEquals(mActivity.getBounds().height(), appBounds.height() + decorHeight);

        mTask.getConfiguration().windowConfiguration.setRotation(ROTATION_90);
        mActivity.onConfigurationChanged(mTask.getConfiguration());
        // After changing orientation, the aspect ratio should be the same.
        assertEquals(appBounds.width(), appBounds.height());
        // The decor height will be included in width.
        assertEquals(mActivity.getBounds().width(), appBounds.width() + decorHeight);
    }

    @Test
    public void testFixedScreenConfigurationWhenMovingToDisplay() {
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2500).build());

        // Make a new less-tall display with lower density
        final ActivityDisplay newDisplay =
                new TestActivityDisplay.Builder(mService, 1000, 2000)
                        .setDensityDpi(200).build();

        mActivity = new ActivityBuilder(mService)
                .setTask(mTask)
                .setResizeMode(RESIZE_MODE_UNRESIZEABLE)
                .setMaxAspectRatio(1.5f)
                .build();
        mActivity.mVisibleRequested = true;

        final Rect originalBounds = new Rect(mActivity.getBounds());
        final int originalDpi = mActivity.getConfiguration().densityDpi;

        // Move the non-resizable activity to the new display.
        mStack.reparent(newDisplay.mDisplayContent, true /* onTop */);

        assertEquals(originalBounds.width(), mActivity.getBounds().width());
        assertEquals(originalBounds.height(), mActivity.getBounds().height());
        assertEquals(originalDpi, mActivity.getConfiguration().densityDpi);
        assertTrue(mActivity.inSizeCompatMode());
    }

    @Test
    public void testFixedScreenBoundsWhenDisplaySizeChanged() {
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2500).build());
        prepareUnresizable(-1f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFalse(mActivity.inSizeCompatMode());

        final Rect origBounds = new Rect(mActivity.getBounds());

        // Change the size of current display.
        resizeDisplay(mStack.getDisplay(), 1000, 2000);
        ensureActivityConfiguration();

        assertEquals(origBounds.width(), mActivity.getWindowConfiguration().getBounds().width());
        assertEquals(origBounds.height(), mActivity.getWindowConfiguration().getBounds().height());
        assertTrue(mActivity.inSizeCompatMode());

        // Change display size to a different orientation
        resizeDisplay(mStack.getDisplay(), 2000, 1000);
        ensureActivityConfiguration();
        assertEquals(origBounds.width(), mActivity.getWindowConfiguration().getBounds().width());
        assertEquals(origBounds.height(), mActivity.getWindowConfiguration().getBounds().height());
    }

    @Test
    public void testLetterboxFullscreenBounds() {
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2500).build());

        // Fill out required fields on default display since WM-side is mocked out
        prepareUnresizable(-1.f /* maxAspect */, SCREEN_ORIENTATION_LANDSCAPE);
        assertFalse(mActivity.inSizeCompatMode());
        assertTrue(mActivity.getBounds().width() > mActivity.getBounds().height());
    }

    @Test
    public void testMoveToDifferentOrientDisplay() {
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2500).build());

        final ActivityDisplay newDisplay =
                new TestActivityDisplay.Builder(mService, 2000, 1000)
                        .setCanRotate(false).build();

        prepareUnresizable(-1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFalse(mActivity.inSizeCompatMode());

        final Rect origBounds = new Rect(mActivity.getBounds());

        // Move the non-resizable activity to the new display.
        mStack.reparent(newDisplay.mDisplayContent, true /* onTop */);
        ensureActivityConfiguration();
        assertEquals(origBounds.width(), mActivity.getWindowConfiguration().getBounds().width());
        assertEquals(origBounds.height(), mActivity.getWindowConfiguration().getBounds().height());
        assertTrue(mActivity.inSizeCompatMode());
    }

    @Test
    public void testFixedOrientRotateCutoutDisplay() {
        // Create a display with a notch/cutout
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2500).setNotch(60).build());
        prepareUnresizable(1.4f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);

        final Rect origBounds = new Rect(mActivity.getBounds());
        final Rect origAppBounds = new Rect(mActivity.getWindowConfiguration().getAppBounds());

        // Rotate the display
        Configuration c = new Configuration();
        mStack.getDisplay().mDisplayContent.getDisplayRotation().setRotation(ROTATION_270);
        mStack.getDisplay().mDisplayContent.computeScreenConfiguration(c);
        mStack.getDisplay().onRequestedOverrideConfigurationChanged(c);

        // Make sure the app size is the same
        assertEquals(ROTATION_270, mStack.getWindowConfiguration().getRotation());
        assertEquals(origBounds.width(), mActivity.getWindowConfiguration().getBounds().width());
        assertEquals(origBounds.height(), mActivity.getWindowConfiguration().getBounds().height());
        assertEquals(origAppBounds.width(),
                mActivity.getWindowConfiguration().getAppBounds().width());
        assertEquals(origAppBounds.height(),
                mActivity.getWindowConfiguration().getAppBounds().height());
    }

    @Test
    public void testFixedAspOrientChangeOrient() {
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2500).build());

        prepareUnresizable(1.4f /* maxAspect */, SCREEN_ORIENTATION_LANDSCAPE);
        // The display aspect ratio 2.5 > 1.4 (max of activity), so the size is fitted.
        assertFalse(mActivity.inSizeCompatMode());

        final Rect originalBounds = new Rect(mActivity.getBounds());
        final Rect originalAppBounds = new Rect(mActivity.getWindowConfiguration().getAppBounds());

        // Change the fixed orientation
        mActivity.mOrientation = SCREEN_ORIENTATION_PORTRAIT;
        mActivity.info.screenOrientation = SCREEN_ORIENTATION_PORTRAIT;
        // TaskRecord's configuration actually depends on the activity config right now for
        // pillarboxing.
        mActivity.getTask().onRequestedOverrideConfigurationChanged(
                mActivity.getTask().getRequestedOverrideConfiguration());

        assertEquals(originalBounds.width(), mActivity.getBounds().height());
        assertEquals(originalBounds.height(), mActivity.getBounds().width());
        assertEquals(originalAppBounds.width(),
                mActivity.getWindowConfiguration().getAppBounds().height());
        assertEquals(originalAppBounds.height(),
                mActivity.getWindowConfiguration().getAppBounds().width());
    }

    @Test
    public void testFixedScreenLayoutSizeBits() {
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2500).build());
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
        prepareUnresizable(1.5f, SCREEN_ORIENTATION_UNSPECIFIED);

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
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2500).build());
        prepareUnresizable(1.5f, SCREEN_ORIENTATION_UNSPECIFIED);
        final ActivityDisplay display = mStack.getDisplay();
        // Resize the display so the activity is in size compatibility mode.
        resizeDisplay(display, 900, 1800);

        mActivity.setState(STOPPED, "testSizeCompatMode");
        mActivity.mVisibleRequested = false;
        mActivity.app.setReportedProcState(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);

        // Simulate the display changes orientation.
        final Configuration c = new Configuration();
        display.getDisplayRotation().setRotation(ROTATION_90);
        display.computeScreenConfiguration(c);
        display.onRequestedOverrideConfigurationChanged(c);
        // Size compatibility mode is able to handle orientation change so the process shouldn't be
        // restarted and the override configuration won't be cleared.
        verify(mActivity, never()).restartProcessIfVisible();
        assertTrue(mActivity.inSizeCompatMode());

        // Change display density
        display.mBaseDisplayDensity = (int) (0.7f * display.mBaseDisplayDensity);
        display.computeScreenConfiguration(c);
        mService.mAmInternal = mock(ActivityManagerInternal.class);
        display.onRequestedOverrideConfigurationChanged(c);

        // The override configuration should be reset and the activity's process will be killed.
        assertFalse(mActivity.inSizeCompatMode());
        verify(mActivity).restartProcessIfVisible();
        waitHandlerIdle(mService.mH);
        verify(mService.mAmInternal).killProcess(
                eq(mActivity.app.mName), eq(mActivity.app.mUid), anyString());
    }

    /**
     * Ensures that {@link TaskStackListener} can receive callback about the activity in size
     * compatibility mode.
     */
    @Test
    public void testHandleActivitySizeCompatMode() {
        setUpApp(new TestActivityDisplay.Builder(mService, 1000, 2000).build());
        ActivityRecord activity = mActivity;
        activity.setState(ActivityStack.ActivityState.RESUMED, "testHandleActivitySizeCompatMode");
        prepareUnresizable(-1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFalse(mActivity.inSizeCompatMode());

        final ArrayList<IBinder> compatTokens = new ArrayList<>();
        mService.getTaskChangeNotificationController().registerTaskStackListener(
                new TaskStackListener() {
                    @Override
                    public void onSizeCompatModeActivityChanged(int displayId,
                            IBinder activityToken) {
                        compatTokens.add(activityToken);
                    }
                });

        // Resize the display so that the activity exercises size-compat mode.
        resizeDisplay(mStack.getDisplay(), 1000, 2500);

        // Expect the exact token when the activity is in size compatibility mode.
        assertEquals(1, compatTokens.size());
        assertEquals(activity.appToken, compatTokens.get(0));

        compatTokens.clear();
        // Make the activity resizable again by restarting it
        activity.info.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;
        activity.mVisibleRequested = true;
        activity.restartProcessIfVisible();
        // The full lifecycle isn't hooked up so manually set state to resumed
        activity.setState(ActivityStack.ActivityState.RESUMED, "testHandleActivitySizeCompatMode");
        mStack.getDisplay().handleActivitySizeCompatModeIfNeeded(activity);

        // Expect null token when switching to non-size-compat mode activity.
        assertEquals(1, compatTokens.size());
        assertEquals(null, compatTokens.get(0));
    }

    /**
     * Setup {@link #mActivity} as a size-compat-mode-able activity with fixed aspect and/or
     * orientation.
     */
    private void prepareUnresizable(float maxAspect, int screenOrientation) {
        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        mActivity.mVisibleRequested = true;
        if (maxAspect >= 0) {
            mActivity.info.maxAspectRatio = maxAspect;
        }
        if (screenOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
            mActivity.mOrientation = screenOrientation;
            mActivity.info.screenOrientation = screenOrientation;
            // TaskRecord's configuration actually depends on the activity config right now for
            // pillarboxing.
            mActivity.getTask().onRequestedOverrideConfigurationChanged(
                    mActivity.getTask().getRequestedOverrideConfiguration());
        }
        ensureActivityConfiguration();
    }

    private void resizeDisplay(ActivityDisplay display, int width, int height) {
        final DisplayContent displayContent = display.mDisplayContent;
        displayContent.mBaseDisplayWidth = width;
        displayContent.mBaseDisplayHeight = height;
        Configuration c = new Configuration();
        displayContent.computeScreenConfiguration(c);
        display.onRequestedOverrideConfigurationChanged(c);
    }
}
