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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_16_9;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_3_2;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_4_3;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_DISPLAY_SIZE;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_FULLSCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_SPLIT_SCREEN;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.provider.DeviceConfig.NAMESPACE_CONSTRAIN_DISPLAY_APIS;
import static android.view.InsetsSource.FLAG_INSETS_ROUNDED_CORNER;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_ASPECT_RATIO;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_FIXED_ORIENTATION;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_SIZE_COMPAT_MODE;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__NOT_LETTERBOXED;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__NOT_VISIBLE;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.RESTARTING_PROCESS;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STOPPED;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_POSITION_MULTIPLIER_CENTER;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.WindowConfiguration;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;

import com.android.internal.policy.SystemBarUtils;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.DeviceStateController.DeviceState;
import com.android.window.flags.Flags;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

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
    private static final String CONFIG_NEVER_CONSTRAIN_DISPLAY_APIS =
            "never_constrain_display_apis";
    private static final String CONFIG_ALWAYS_CONSTRAIN_DISPLAY_APIS =
            "always_constrain_display_apis";
    private static final String CONFIG_NEVER_CONSTRAIN_DISPLAY_APIS_ALL_PACKAGES =
            "never_constrain_display_apis_all_packages";

    private static final float DELTA_ASPECT_RATIO_TOLERANCE = 0.005f;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private Task mTask;
    private ActivityRecord mActivity;
    private ActivityMetricsLogger mActivityMetricsLogger;
    private Properties mInitialConstrainDisplayApisFlags;

    @Before
    public void setUp() throws Exception {
        mActivityMetricsLogger = mock(ActivityMetricsLogger.class);
        clearInvocations(mActivityMetricsLogger);
        doReturn(mActivityMetricsLogger).when(mAtm.mTaskSupervisor).getActivityMetricsLogger();
        mInitialConstrainDisplayApisFlags = DeviceConfig.getProperties(
                NAMESPACE_CONSTRAIN_DISPLAY_APIS);
        // Provide empty default values for the configs.
        setNeverConstrainDisplayApisFlag("", true);
        setNeverConstrainDisplayApisAllPackagesFlag(false, true);
        setAlwaysConstrainDisplayApisFlag("", true);
    }

    @After
    public void tearDown() throws Exception {
        DeviceConfig.setProperties(mInitialConstrainDisplayApisFlags);
    }

    private void setUpApp(DisplayContent display) {
        mTask = new TaskBuilder(mSupervisor).setDisplay(display).setCreateActivity(true).build();
        mActivity = mTask.getTopNonFinishingActivity();
        doReturn(false).when(mActivity).isImmersiveMode(any());
    }

    private void setUpDisplaySizeWithApp(int dw, int dh) {
        final TestDisplayContent.Builder builder = new TestDisplayContent.Builder(mAtm, dw, dh);
        setUpApp(builder.build());
    }

    @Test
    public void testHorizontalReachabilityEnabledForTranslucentActivities() {
        testReachabilityEnabledForTranslucentActivity(/* dw */ 2500,  /* dh */1000,
                SCREEN_ORIENTATION_PORTRAIT, /* minAspectRatio */ 0f,
                /* horizontalReachability */ true);
    }

    @Test
    public void testHorizontalReachabilityEnabled_TranslucentPortraitActivities_portraitDisplay() {
        testReachabilityEnabledForTranslucentActivity(/* dw */ 1400,  /* dh */1600,
                SCREEN_ORIENTATION_PORTRAIT, OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                /* horizontalReachability */ true);
    }

    @Test
    public void testVerticalReachabilityEnabledForTranslucentActivities() {
        testReachabilityEnabledForTranslucentActivity(/* dw */ 1000,  /* dh */2500,
                SCREEN_ORIENTATION_LANDSCAPE, /* minAspectRatio */ 0f,
                /* horizontalReachability */ false);
    }

    @Test
    public void testVerticalReachabilityEnabled_TranslucentLandscapeActivities_landscapeDisplay() {
        testReachabilityEnabledForTranslucentActivity(/* dw */ 1600,  /* dh */1400,
                SCREEN_ORIENTATION_LANDSCAPE, OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                /* horizontalReachability */ false);
    }

    private void testReachabilityEnabledForTranslucentActivity(int displayWidth, int displayHeight,
            @ScreenOrientation int screenOrientation, float minAspectRatio,
            boolean horizontalReachability) {
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        final LetterboxConfiguration config = mWm.mLetterboxConfiguration;
        config.setTranslucentLetterboxingOverrideEnabled(true);
        config.setLetterboxVerticalPositionMultiplier(0.5f);
        config.setIsVerticalReachabilityEnabled(true);
        config.setLetterboxHorizontalPositionMultiplier(0.5f);
        config.setIsHorizontalReachabilityEnabled(true);

        // Opaque activity
        prepareMinAspectRatio(mActivity, minAspectRatio, screenOrientation);
        addWindowToActivity(mActivity);
        mActivity.mRootWindowContainer.performSurfacePlacement();

        // Translucent Activity
        final ActivityRecord translucentActivity = new ActivityBuilder(mAtm)
                .setActivityTheme(android.R.style.Theme_Translucent)
                .setLaunchedFromUid(mActivity.getUid())
                .setScreenOrientation(screenOrientation)
                .build();
        mTask.addChild(translucentActivity);

        spyOn(translucentActivity.mLetterboxUiController);
        doReturn(true).when(translucentActivity.mLetterboxUiController)
                .shouldShowLetterboxUi(any());

        addWindowToActivity(translucentActivity);
        translucentActivity.mRootWindowContainer.performSurfacePlacement();

        final Function<ActivityRecord, Rect> innerBoundsOf =
                (ActivityRecord a) -> {
                    final Rect bounds = new Rect();
                    a.mLetterboxUiController.getLetterboxInnerBounds(bounds);
                    return bounds;
                };
        final Runnable checkLetterboxPositions = () -> assertEquals(innerBoundsOf.apply(mActivity),
                innerBoundsOf.apply(translucentActivity));
        final Runnable checkIsTop = () -> assertThat(
                innerBoundsOf.apply(translucentActivity).top).isEqualTo(0);
        final Runnable checkIsBottom = () -> assertThat(
                innerBoundsOf.apply(translucentActivity).bottom).isEqualTo(displayHeight);
        final Runnable checkIsLeft = () -> assertThat(
                innerBoundsOf.apply(translucentActivity).left).isEqualTo(0);
        final Runnable checkIsRight = () -> assertThat(
                innerBoundsOf.apply(translucentActivity).right).isEqualTo(displayWidth);
        final Runnable checkIsHorizontallyCentered = () -> assertThat(
                innerBoundsOf.apply(translucentActivity).left > 0
                        && innerBoundsOf.apply(translucentActivity).right < displayWidth).isTrue();
        final Runnable checkIsVerticallyCentered = () -> assertThat(
                innerBoundsOf.apply(translucentActivity).top > 0
                        && innerBoundsOf.apply(translucentActivity).bottom < displayHeight)
                .isTrue();

        if (horizontalReachability) {
            final Consumer<Integer> doubleClick =
                    (Integer x) -> {
                        mActivity.mLetterboxUiController.handleHorizontalDoubleTap(x);
                        mActivity.mRootWindowContainer.performSurfacePlacement();
                    };

            // Initial state
            checkIsHorizontallyCentered.run();

            // Double-click left
            doubleClick.accept(/* x */ 10);
            checkLetterboxPositions.run();
            checkIsLeft.run();

            // Double-click right
            doubleClick.accept(/* x */ displayWidth - 100);
            checkLetterboxPositions.run();
            checkIsHorizontallyCentered.run();

            // Double-click right
            doubleClick.accept(/* x */ displayWidth - 100);
            checkLetterboxPositions.run();
            checkIsRight.run();

            // Double-click left
            doubleClick.accept(/* x */ 10);
            checkLetterboxPositions.run();
            checkIsHorizontallyCentered.run();
        } else {
            final Consumer<Integer> doubleClick =
                    (Integer y) -> {
                        mActivity.mLetterboxUiController.handleVerticalDoubleTap(y);
                        mActivity.mRootWindowContainer.performSurfacePlacement();
                    };

            // Initial state
            checkIsVerticallyCentered.run();

            // Double-click top
            doubleClick.accept(/* y */ 10);
            checkLetterboxPositions.run();
            checkIsTop.run();

            // Double-click bottom
            doubleClick.accept(/* y */ displayHeight - 100);
            checkLetterboxPositions.run();
            checkIsVerticallyCentered.run();

            // Double-click bottom
            doubleClick.accept(/* y */ displayHeight - 100);
            checkLetterboxPositions.run();
            checkIsBottom.run();

            // Double-click top
            doubleClick.accept(/* y */ 10);
            checkLetterboxPositions.run();
            checkIsVerticallyCentered.run();
        }
    }

    // TODO(b/333663877): Enable test after fix
    @Test
    @RequiresFlagsDisabled({Flags.FLAG_INSETS_DECOUPLED_CONFIGURATION})
    @EnableFlags(Flags.FLAG_IMMERSIVE_APP_REPOSITIONING)
    public void testRepositionLandscapeImmersiveAppWithDisplayCutout() {
        final int dw = 2100;
        final int dh = 2000;
        final int cutoutHeight = 150;
        final TestDisplayContent display = new TestDisplayContent.Builder(mAtm, dw, dh)
                .setCanRotate(false)
                .setNotch(cutoutHeight)
                .build();
        setUpApp(display);
        display.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(0.5f);
        mWm.mLetterboxConfiguration.setIsVerticalReachabilityEnabled(true);

        doReturn(true).when(mActivity).isImmersiveMode(any());
        prepareMinAspectRatio(mActivity, OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                SCREEN_ORIENTATION_LANDSCAPE);
        addWindowToActivity(mActivity);
        mActivity.mRootWindowContainer.performSurfacePlacement();

        final Function<ActivityRecord, Rect> innerBoundsOf =
                (ActivityRecord a) -> {
                    final Rect bounds = new Rect();
                    a.mLetterboxUiController.getLetterboxInnerBounds(bounds);
                    return bounds;
                };

        final Consumer<Integer> doubleClick =
                (Integer y) -> {
                    mActivity.mLetterboxUiController.handleVerticalDoubleTap(y);
                    mActivity.mRootWindowContainer.performSurfacePlacement();
                };

        final Rect bounds = mActivity.getBounds();
        assertTrue(bounds.top > cutoutHeight && bounds.bottom < dh);
        assertEquals(dw, bounds.width());

        // Double click bottom.
        doubleClick.accept(dh - 10);
        assertEquals(dh, innerBoundsOf.apply(mActivity).bottom);

        // Double click top.
        doubleClick.accept(10);
        doubleClick.accept(10);
        assertEquals(cutoutHeight, innerBoundsOf.apply(mActivity).top);
    }

    @Test
    public void testRestartProcessIfVisible() {
        setUpDisplaySizeWithApp(1000, 2500);
        doNothing().when(mSupervisor).scheduleRestartTimeout(mActivity);
        mActivity.setVisibleRequested(true);
        mActivity.setSavedState(null /* savedState */);
        mActivity.setState(RESUMED, "testRestart");
        prepareUnresizable(mActivity, 1.5f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);

        final Rect originalOverrideBounds = new Rect(mActivity.getBounds());
        resizeDisplay(mTask.mDisplayContent, 600, 1200);
        // The visible activity should recompute configuration according to the last parent bounds.
        mAtm.mActivityClientController.restartActivityProcessIfVisible(mActivity.token);

        assertEquals(RESTARTING_PROCESS, mActivity.getState());
        assertNotEquals(originalOverrideBounds, mActivity.getBounds());

        // Even if the state is changed (e.g. a floating activity on top is finished and make it
        // resume), the restart procedure should recover the state and continue to kill the process.
        mActivity.setState(RESUMED, "anyStateChange");
        doReturn(true).when(mSupervisor).hasScheduledRestartTimeouts(mActivity);
        mAtm.mActivityClientController.activityStopped(mActivity.token, null /* icicle */,
                null /* persistentState */, null /* description */);
        assertEquals(RESTARTING_PROCESS, mActivity.getState());
        verify(mSupervisor).removeRestartTimeouts(mActivity);
    }

    @Test
    public void testFixedAspectRatioBoundsWithDecorInSquareDisplay() {
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
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
        // ratio should be the same (bounds=[0, 0 - 800, 583], appBounds=[100, 0 - 800, 583]).
        assertEquals(appBounds.width(), appBounds.height() * aspectRatio, 0.5f /* delta */);
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
        final int screenX = mActivity.getBounds().left;
        assertEquals(offsetX, screenX);

        // The position of configuration bounds should be in app space.
        assertEquals(screenX, (int) (currentBounds.left * scale + 0.5f));
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
        assertEquals(offsetX, mActivity.getBounds().left);
        assertScaled();
        // Activity is sandboxed due to size compat mode.
        assertActivityMaxBoundsSandboxed();

        final WindowState appWindow = addWindowToActivity(mActivity);
        assertTrue(mActivity.hasSizeCompatBounds());
        assertEquals("App window must use size compat bounds for layout in screen space",
                mActivity.getBounds(), appWindow.getBounds());
    }

    @Test
    public void testLetterboxDisplayedForWindowBelow() {
        setUpDisplaySizeWithApp(1000, 2500);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);
        // Prepare two windows, one base app window below the splash screen
        final WindowState appWindow = addWindowToActivity(mActivity);
        final WindowState startWindow = addWindowToActivity(mActivity, TYPE_APPLICATION_STARTING);
        spyOn(appWindow);
        // Base app window is letterboxed for display cutout and splash screen is fullscreen
        doReturn(true).when(appWindow).isLetterboxedForDisplayCutout();

        mActivity.mRootWindowContainer.performSurfacePlacement();

        assertEquals(2, mActivity.mChildren.size());
        // Splash screen is still the activity's main window
        assertEquals(startWindow, mActivity.findMainWindow());
        assertFalse(startWindow.isLetterboxedForDisplayCutout());

        final Rect letterboxInnerBounds = new Rect();
        mActivity.getLetterboxInnerBounds(letterboxInnerBounds);
        // Letterboxed is still displayed for app window below splash screen
        assertFalse(letterboxInnerBounds.isEmpty());
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
        assertFalse(mActivity.mDisplayContent.shouldImeAttachedToApp());

        // Recompute the natural configuration without resolving size compat configuration.
        mActivity.clearSizeCompatMode();
        mActivity.onConfigurationChanged(mTask.getConfiguration());
        // It should keep non-attachable because the resolved bounds will be computed according to
        // the aspect ratio that won't match its parent bounds.
        assertFalse(mActivity.mDisplayContent.shouldImeAttachedToApp());
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

        spyOn(mActivity.mLetterboxUiController);
        doReturn(true).when(mActivity).isVisibleRequested();

        assertTrue(mActivity.mLetterboxUiController.shouldShowLetterboxUi(
                mActivity.findMainWindow()));

        window.mAttrs.flags |= FLAG_SHOW_WALLPAPER;

        assertFalse(mActivity.mLetterboxUiController.shouldShowLetterboxUi(
                mActivity.findMainWindow()));
    }

    @Test
    public void testIsLetterboxed_activityFromBubble_returnsFalse() {
        setUpDisplaySizeWithApp(1000, 2500);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        spyOn(mActivity);
        doReturn(true).when(mActivity).getLaunchedFromBubble();
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        assertFalse(mActivity.areBoundsLetterboxed());
    }

    @Test
    public void testAspectRatioMatchParentBoundsAndImeAttachable() {
        setUpApp(new TestDisplayContent.Builder(mAtm, 1000, 2000).build());
        prepareUnresizable(mActivity, 2f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);
        assertFitted();

        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);
        mActivity.mDisplayContent.setImeLayeringTarget(addWindowToActivity(mActivity));
        mActivity.mDisplayContent.setImeInputTarget(
                mActivity.mDisplayContent.getImeTarget(IME_TARGET_LAYERING).getWindow());
        // Because the aspect ratio of display doesn't exceed the max aspect ratio of activity.
        // The activity should still fill its parent container and IME can attach to the activity.
        assertTrue(mActivity.matchParentBounds());
        assertTrue(mActivity.mDisplayContent.shouldImeAttachedToApp());

        final Rect letterboxInnerBounds = new Rect();
        mActivity.getLetterboxInnerBounds(letterboxInnerBounds);
        // The activity should not have letterbox.
        assertTrue(letterboxInnerBounds.isEmpty());
    }

    @Test
    public void testMoveToDifferentOrientationDisplay() {
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
        setUpDisplaySizeWithApp(1000, 2500);
        prepareUnresizable(mActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        final Rect currentBounds = mActivity.getWindowConfiguration().getBounds();
        final Rect currentAppBounds = mActivity.getWindowConfiguration().getAppBounds();
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
        mActivity.ensureActivityConfiguration();
        // Because the display cannot rotate, the portrait activity will fit the short side of
        // display with keeping portrait bounds [200, 0 - 700, 1000] in center.
        assertEquals(newDisplayBounds.height(), currentBounds.height());
        assertEquals(currentAppBounds.height() * newDisplayBounds.height()
                / newDisplayBounds.width(), currentAppBounds.width());
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
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
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
        // Make sure the app size is the same
        assertEquals(origAppBounds.width(), appBounds.width());
        assertEquals(origAppBounds.height(), appBounds.height());
        // The activity is 1000x1400 and the display is 2500x1000.
        assertScaled();
        final float scale = mActivity.getCompatScale();
        // The position in configuration should be in app coordinates.
        final Rect screenBounds = mActivity.getBounds();
        assertEquals(screenBounds.left, (int) (currentBounds.left * scale + 0.5f));
        assertEquals(screenBounds.top, (int) (currentBounds.top * scale + 0.5f));

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

        // Prepare the states for verifying relaunching after changing orientation.
        mActivity.finishRelaunching();
        mActivity.setState(RESUMED, "testFixedAspectRatioOrientationChangeOrientation");
        mActivity.setLastReportedConfiguration(mAtm.getGlobalConfiguration(),
                mActivity.getConfiguration());

        // Change the fixed orientation.
        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        assertTrue(mActivity.isRelaunching());
        assertTrue(mActivity.mLetterboxUiController
                .getIsRelaunchingAfterRequestedOrientationChanged());

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
        mActivity.setVisibleRequested(false);
        mActivity.visibleIgnoringKeyguard = false;
        mActivity.app.setReportedProcState(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);
        mActivity.app.computeProcessActivityState();

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
        mActivity.setState(RESUMED, "testHandleActivitySizeCompatModeChanged");
        prepareUnresizable(mActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        // Resize the display so that the activity exercises size-compat mode.
        resizeDisplay(mTask.mDisplayContent, 1000, 2500);

        // Expect the exact token when the activity is in size compatibility mode.
        verify(mTask).onSizeCompatActivityChanged();
        ActivityManager.RunningTaskInfo taskInfo = mTask.getTaskInfo();

        assertTrue(taskInfo.appCompatTaskInfo.topActivityInSizeCompat);

        // Make the activity resizable again by restarting it
        clearInvocations(mTask);
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;
        mActivity.setVisibleRequested(true);
        mActivity.restartProcessIfVisible();
        // The full lifecycle isn't hooked up so manually set state to resumed
        mActivity.setState(RESUMED, "testHandleActivitySizeCompatModeChanged");
        mTask.mDisplayContent.handleActivitySizeCompatModeIfNeeded(mActivity);

        // Expect null token when switching to non-size-compat mode activity.
        verify(mTask).onSizeCompatActivityChanged();
        taskInfo = mTask.getTaskInfo();

        assertFalse(taskInfo.appCompatTaskInfo.topActivityInSizeCompat);
    }

    @Test
    public void testHandleActivitySizeCompatModeChangedOnDifferentTask() {
        setUpDisplaySizeWithApp(1000, 2000);
        doReturn(true).when(mTask).isOrganized();
        mActivity.setState(RESUMED, "testHandleActivitySizeCompatModeChanged");
        prepareUnresizable(mActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        // Resize the display so that the activity exercises size-compat mode.
        resizeDisplay(mTask.mDisplayContent, 1000, 2500);

        // Expect the exact token when the activity is in size compatibility mode.
        verify(mTask).onSizeCompatActivityChanged();
        ActivityManager.RunningTaskInfo taskInfo = mTask.getTaskInfo();

        assertTrue(taskInfo.appCompatTaskInfo.topActivityInSizeCompat);

        // Create another Task to hold another size compat activity.
        clearInvocations(mTask);
        final Task secondTask = new TaskBuilder(mSupervisor).setDisplay(mTask.getDisplayContent())
                .setCreateActivity(true).build();
        final ActivityRecord secondActivity = secondTask.getTopNonFinishingActivity();
        doReturn(true).when(secondTask).isOrganized();
        secondActivity.setState(RESUMED,
                "testHandleActivitySizeCompatModeChanged");
        prepareUnresizable(secondActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);

        // Resize the display so that the activity exercises size-compat mode.
        resizeDisplay(mTask.mDisplayContent, 1000, 3000);

        // Expect the exact token when the activity is in size compatibility mode.
        verify(secondTask).onSizeCompatActivityChanged();
        verify(mTask, never()).onSizeCompatActivityChanged();
        taskInfo = secondTask.getTaskInfo();

        assertTrue(taskInfo.appCompatTaskInfo.topActivityInSizeCompat);
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
        mTask.mDisplayContent.getDefaultTaskDisplayArea()
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
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
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */true,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    public void testShouldCreateCompatDisplayInsetsWhenUnresizeableAndSupportsSizeChangesFalse() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        assertTrue(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    public void testShouldCreateCompatDisplayInsetsWhenResizeableAndSupportsSizeChangesFalse() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_RESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    public void
            testShouldCreateCompatDisplayInsetsWhenUnfixedOrientationSupportsSizeChangesFalse() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.FORCE_RESIZE_APP})
    public void testShouldCreateCompatDisplayInsetsWhenForceResizeAppOverrideSet() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.FORCE_NON_RESIZE_APP})
    public void testShouldCreateCompatDisplayInsetsWhenForceNonResizeOverrideSet() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */true,
                RESIZE_MODE_RESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        assertTrue(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.FORCE_NON_RESIZE_APP})
    public void testShouldCreateCompatDisplayInsetsWhenForceNonResizeSetAndUnfixedOrientation() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */true,
                RESIZE_MODE_RESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        assertTrue(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    public void testShouldCreateCompatDisplayUserAspectRatioFullscreenOverride() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Simulate the user selecting the fullscreen user aspect ratio override
        spyOn(activity.mWmService.mLetterboxConfiguration);
        spyOn(activity.mLetterboxUiController);
        doReturn(true).when(activity.mWmService.mLetterboxConfiguration)
                .isUserAppAspectRatioFullscreenEnabled();
        doReturn(USER_MIN_ASPECT_RATIO_FULLSCREEN).when(activity.mLetterboxUiController)
                .getUserMinAspectRatioOverrideCode();
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testShouldNotCreateCompatDisplays_systemFullscreenOverride() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Simulate the user selecting the fullscreen user aspect ratio override
        spyOn(activity.mLetterboxUiController);
        doReturn(true).when(activity.mLetterboxUiController)
                .isSystemOverrideToFullscreenEnabled();
        assertFalse(activity.shouldCreateCompatDisplayInsets());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.NEVER_SANDBOX_DISPLAY_APIS})
    public void testNeverSandboxDisplayApis_configEnabled_sandboxingNotApplied() {
        setUpDisplaySizeWithApp(1000, 1200);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity with a max aspect ratio on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(activity, /* maxAspect=*/ 1.5f, SCREEN_ORIENTATION_LANDSCAPE);

        // Activity max bounds should not be sandboxed, even though it is letterboxed.
        assertTrue(activity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertThat(activity.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(activity.getDisplayArea().getBounds());
    }

    @Test
    @DisableCompatChanges({ActivityInfo.NEVER_SANDBOX_DISPLAY_APIS})
    public void testNeverSandboxDisplayApis_configDisabled_sandboxingApplied() {
        setUpDisplaySizeWithApp(1000, 1200);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity with a max aspect ratio on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(activity, /* maxAspect=*/ 1.5f, SCREEN_ORIENTATION_LANDSCAPE);

        // Activity max bounds should be sandboxed due to letterboxed and the config being disabled.
        assertActivityMaxBoundsSandboxed(activity);
    }

    @Test
    public void testNeverConstrainDisplayApisDeviceConfig_allPackagesFlagTrue_sandboxNotApplied() {
        setUpDisplaySizeWithApp(1000, 1200);

        setNeverConstrainDisplayApisAllPackagesFlag(true, false);
        // Setting 'never_constrain_display_apis' as well to make sure it is ignored.
        setNeverConstrainDisplayApisFlag("com.android.other::,com.android.other2::", false);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity with a max aspect ratio on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(activity, /* maxAspect=*/ 1.5f, SCREEN_ORIENTATION_LANDSCAPE);

        // Activity max bounds should not be sandboxed, even though it is letterboxed.
        assertTrue(activity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertThat(activity.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(activity.getDisplayArea().getBounds());
    }

    @Test
    public void testNeverConstrainDisplayApisDeviceConfig_packageInRange_sandboxingNotApplied() {
        setUpDisplaySizeWithApp(1000, 1200);

        setNeverConstrainDisplayApisFlag(
                "com.android.frameworks.wmtests:20:,com.android.other::,"
                        + "com.android.frameworks.wmtests:0:10", false);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity with a max aspect ratio on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(activity, /* maxAspect=*/ 1.5f, SCREEN_ORIENTATION_LANDSCAPE);

        // Activity max bounds should not be sandboxed, even though it is letterboxed.
        assertTrue(activity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertThat(activity.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(activity.getDisplayArea().getBounds());
    }

    @Test
    public void testNeverConstrainDisplayApisDeviceConfig_packageOutsideRange_sandboxingApplied() {
        setUpDisplaySizeWithApp(1000, 1200);

        setNeverConstrainDisplayApisFlag("com.android.other::,com.android.frameworks.wmtests:1:5",
                false);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity with a max aspect ratio on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(activity, /* maxAspect=*/ 1.5f, SCREEN_ORIENTATION_LANDSCAPE);

        // Activity max bounds should be sandboxed due to letterboxed and the mismatch with flag.
        assertActivityMaxBoundsSandboxed(activity);
    }

    @Test
    public void testNeverConstrainDisplayApisDeviceConfig_packageNotInFlag_sandboxingApplied() {
        setUpDisplaySizeWithApp(1000, 1200);

        setNeverConstrainDisplayApisFlag("com.android.other::,com.android.other2::", false);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity with a max aspect ratio on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(activity, /* maxAspect=*/ 1.5f, SCREEN_ORIENTATION_LANDSCAPE);

        // Activity max bounds should be sandboxed due to letterboxed and the mismatch with flag.
        assertActivityMaxBoundsSandboxed(activity);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.ALWAYS_SANDBOX_DISPLAY_APIS})
    public void testAlwaysSandboxDisplayApis_configEnabled_sandboxingApplied_unresizable() {
        setUpDisplaySizeWithApp(1000, 1200);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity with a max aspect ratio on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(activity, /* maxAspect=*/ 1.5f, SCREEN_ORIENTATION_LANDSCAPE);

        // Activity max bounds should be sandboxed due to letterboxed and the config being enabled.
        assertActivityMaxBoundsSandboxed(activity);
    }

    @Test
    @DisableCompatChanges({ActivityInfo.ALWAYS_SANDBOX_DISPLAY_APIS})
    public void testAlwaysSandboxDisplayApis_configDisabled_sandboxingApplied() {
        setUpDisplaySizeWithApp(1000, 1200);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity with a max aspect ratio on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(activity, /* maxAspect=*/ 1.5f, SCREEN_ORIENTATION_LANDSCAPE);

        // Activity max bounds should be sandboxed due to letterbox and the config being disabled.
        assertActivityMaxBoundsSandboxed(activity);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.ALWAYS_SANDBOX_DISPLAY_APIS})
    public void testAlwaysSandboxDisplayApis_configEnabled_sandboxingApplied_resizableSplit() {
        setUpDisplaySizeWithApp(1000, 2800);
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_RESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, activity.getDisplayContent());

        // Activity max bounds should be sandboxed due the config being enabled.
        assertFalse(activity.inSizeCompatMode());
        assertActivityMaxBoundsSandboxed(activity);

        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP,
                false /*moveParents*/, "test");
        organizer.mPrimary.setBounds(0, 0, 1000, 1400);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, activity.getWindowingMode());

        // Resizable activity is sandboxed due to config being enabled.
        assertActivityMaxBoundsSandboxed(activity);
    }

    @Test
    public void testAlwaysConstrainDisplayApisDeviceConfig_packageInRange_sandboxingApplied() {
        setUpDisplaySizeWithApp(1000, 1200);

        setAlwaysConstrainDisplayApisFlag(
                "com.android.frameworks.wmtests:20:,com.android.other::,"
                        + "com.android.frameworks.wmtests:0:10", false);

        // Make the task root resizable.
        mActivity.info.resizeMode = RESIZE_MODE_RESIZEABLE;

        // Create an activity with a max aspect ratio on the same task.
        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */false,
                RESIZE_MODE_UNRESIZEABLE, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(activity, /* maxAspect=*/ 1.5f, SCREEN_ORIENTATION_LANDSCAPE);

        // Resizable activity is sandboxed due to match with flag.
        assertActivityMaxBoundsSandboxed(activity);
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
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1400, 1800)
                .setNotch(200).setSystemDecorations(true).build();
        mTask = new TaskBuilder(mSupervisor).setDisplay(display).build();

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
        final Rect appBounds = activity.getWindowConfiguration().getAppBounds();
        assertEquals("App bounds must have min aspect ratio", 2f,
                (float) appBounds.height() / appBounds.width(), 0.0001f /* delta */);
        assertEquals("Long side must fit task",
                mTask.getWindowConfiguration().getAppBounds().height(), appBounds.height());
        assertEquals("Bounds can include insets", mTask.getBounds().height(),
                activity.getBounds().height());
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
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testOverrideMinAspectRatioScreenOrientationNotSetThenChangedToPortrait() {
        // In this test, the activity's orientation isn't fixed to portrait, therefore the override
        // isn't applied.

        setUpDisplaySizeWithApp(1000, 1200);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        // The per-package override should have no effect
        assertEquals(1200, activity.getBounds().height());
        assertEquals(1000, activity.getBounds().width());

        // After changing the orientation to portrait the override should be applied.
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        activity.clearSizeCompatMode();

        // The per-package override forces the activity into a 3:2 aspect ratio
        assertEquals(1200, activity.getBounds().height());
        assertEquals(1200 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE,
                activity.getBounds().width(), 0.5);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testOverrideMinAspectRatioScreenOrientationLandscapeThenChangedToPortrait() {
        // In this test, the activity's orientation isn't fixed to portrait, therefore the override
        // isn't applied.

        setUpDisplaySizeWithApp(1000, 1200);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        // The per-package override should have no effect
        assertEquals(1200, activity.getBounds().height());
        assertEquals(1000, activity.getBounds().width());

        // After changing the orientation to portrait the override should be applied.
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        activity.clearSizeCompatMode();

        // The per-package override forces the activity into a 3:2 aspect ratio
        assertEquals(1200, activity.getBounds().height());
        assertEquals(1200 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE,
                activity.getBounds().width(), 0.5);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testOverrideMinAspectRatioScreenOrientationPortraitThenChangedToUnspecified() {
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

        // After changing the orientation to landscape the override shouldn't be applied.
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.clearSizeCompatMode();

        // The per-package override should have no effect
        assertEquals(1200, activity.getBounds().height());
        assertEquals(1000, activity.getBounds().width());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    @DisableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY})
    public void testOverrideMinAspectRatioPortraitOnlyDisabledScreenOrientationNotSet() {
        setUpDisplaySizeWithApp(1000, 1200);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
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
    @DisableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY})
    public void testOverrideMinAspectRatioPortraitOnlyDisabledScreenOrientationLandscape() {
        // In this test, the activity's orientation isn't fixed to portrait, therefore the override
        // isn't applied.

        setUpDisplaySizeWithApp(1000, 1200);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        // The per-package override forces the activity into a 3:2 aspect ratio
        assertEquals(1000 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE,
                activity.getBounds().height(), 0.5);
        assertEquals(1000, activity.getBounds().width());
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
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE})
    public void testOverrideMinAspectRatioLargeForResizableAppInSplitScreen() {
        setUpDisplaySizeWithApp(/* dw= */ 1000, /* dh= */ 2800);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, activity.getDisplayContent());

        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, 1000, 1400);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, activity.getWindowingMode());

        // The per-package override forces the activity into a 16:9 aspect ratio
        assertEquals(1400, activity.getBounds().height());
        assertEquals(1400 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                activity.getBounds().width(), 0.5);
    }

    @Test
    public void testGetLetterboxInnerBounds_noScalingApplied() {
        // Set up a display in portrait and ignoring orientation request.
        final int dw = 1400;
        final int dh = 2800;
        setUpDisplaySizeWithApp(dw, dh);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Rotate display to landscape.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // Portrait fixed app without max aspect.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_LANDSCAPE);

        // Need a window to call adjustBoundsForTaskbar with.
        addWindowToActivity(mActivity);

        // App should launch in fullscreen.
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());

        // Activity inherits max bounds from TaskDisplayArea.
        assertMaxBoundsInheritDisplayAreaBounds();

        // Rotate display to portrait.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_0);

        final Rect rotatedDisplayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect rotatedActivityBounds = new Rect(mActivity.getBounds());
        assertTrue(rotatedDisplayBounds.width() < rotatedDisplayBounds.height());

        // App should be in size compat.
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertScaled();
        assertThat(mActivity.inSizeCompatMode()).isTrue();
        assertActivityMaxBoundsSandboxed();

        final int scale = dh / dw;

        // App bounds should be dh / scale x dw / scale
        assertEquals(dw, rotatedDisplayBounds.width());
        assertEquals(dh, rotatedDisplayBounds.height());

        assertEquals(dh / scale, rotatedActivityBounds.width());
        assertEquals(dw / scale, rotatedActivityBounds.height());

        // Compute the frames of the window and invoke {@link ActivityRecord#layoutLetterbox}.
        mActivity.mRootWindowContainer.performSurfacePlacement();

        LetterboxDetails letterboxDetails = mActivity.mLetterboxUiController.getLetterboxDetails();

        assertEquals(dh / scale, letterboxDetails.getLetterboxInnerBounds().width());
        assertEquals(dw / scale, letterboxDetails.getLetterboxInnerBounds().height());

        assertEquals(dw, letterboxDetails.getLetterboxFullBounds().width());
        assertEquals(dh, letterboxDetails.getLetterboxFullBounds().height());
    }

    @Test
    public void testLaunchWithFixedRotationTransform() {
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
        final int dw = 1000;
        final int dh = 2500;
        final int notchHeight = 200;
        setUpApp(new TestDisplayContent.Builder(mAtm, dw, dh).setNotch(notchHeight).build());
        // The test assumes the notch will be at left side when the orientation is landscape.
        if (mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_reverseDefaultRotation)) {
            setReverseDefaultRotation(mActivity.mDisplayContent, false);
        }
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
        assertFalse(displayPolicy.isFullyTransparentAllowed(w, statusBars()));

        // Activity is sandboxed.
        assertActivityMaxBoundsSandboxed();

        // Make the activity fill the display.
        prepareUnresizable(mActivity, 10 /* maxAspect */, SCREEN_ORIENTATION_LANDSCAPE);
        w.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        // Refresh the letterbox.
        mActivity.mRootWindowContainer.performSurfacePlacement();

        // The letterbox should only cover the notch area, so status bar can be transparent.
        assertEquals(new Rect(notchHeight, 0, 0, 0), mActivity.getLetterboxInsets());
        assertTrue(displayPolicy.isFullyTransparentAllowed(w, statusBars()));
        assertActivityMaxBoundsSandboxed();

        // The insets state for metrics should be rotated (landscape).
        final InsetsState insetsState = new InsetsState();
        mActivity.mDisplayContent.getInsetsPolicy().getInsetsForWindowMetrics(
                mActivity, insetsState);
        assertEquals(dh, insetsState.getDisplayFrame().width());
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
        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.1f);
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
        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(3);
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

        final float fixedOrientationLetterboxAspectRatio = 1.1f;
        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(
                fixedOrientationLetterboxAspectRatio);
        prepareLimitedBounds(mActivity, SCREEN_ORIENTATION_PORTRAIT, /* isUnresizable= */ false);

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
    public void testDefaultLetterboxAspectRatioForMultiWindowMode_fixedOrientationApp() {
        // Set-up display in portrait.
        mAtm.mDevEnableNonResizableMultiWindow = true;
        final int screenWidth = 1100;
        final int screenHeight = 2100;
        setUpDisplaySizeWithApp(screenWidth, screenHeight);

        mActivity.mDisplayContent.getWindowConfiguration()
                .setAppBounds(/* left */ 0, /* top */ 0, screenWidth, screenHeight);

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());
        // Move activity to multi-window which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, screenWidth, getExpectedSplitSize(screenHeight));
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());

        // Unresizable portrait-only activity.
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        // Activity should be letterboxed with an aspect ratio of 1.01.
        final Rect afterBounds = mActivity.getBounds();
        final float actualAspectRatio = 1f * afterBounds.height() / afterBounds.width();
        assertEquals(LetterboxConfiguration.DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW,
                actualAspectRatio, DELTA_ASPECT_RATIO_TOLERANCE);
        assertTrue(mActivity.areBoundsLetterboxed());
    }

    @Test
    public void
            testDefaultLetterboxAspectRatioForMultiWindowMode_fixedOrientationAppWithMinRatio() {
        // Set-up display in portrait.
        mAtm.mDevEnableNonResizableMultiWindow = true;
        final int screenWidth = 1100;
        final int screenHeight = 2100;
        setUpDisplaySizeWithApp(screenWidth, screenHeight);

        mActivity.mDisplayContent.getWindowConfiguration()
                .setAppBounds(/* left */ 0, /* top */ 0, screenWidth, screenHeight);

        // Set min aspect ratio to value greater than the default letterbox aspect ratio for
        // multi-window mode.
        final float minAspectRatio = 1.2f;
        mActivity.info.setMinAspectRatio(minAspectRatio);

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());
        // Move activity to multi-window which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, screenWidth, getExpectedSplitSize(screenHeight));
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());

        // Unresizable portrait-only activity.
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        // Activity should be letterboxed with the min aspect ratio requested by the app NOT the
        // default letterbox aspect ratio for multi-window.
        final Rect afterBounds = mActivity.getBounds();
        final float actualAspectRatio = 1f * afterBounds.height() / afterBounds.width();
        assertEquals(minAspectRatio, actualAspectRatio, DELTA_ASPECT_RATIO_TOLERANCE);
        assertTrue(mActivity.areBoundsLetterboxed());
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_unresizableWithCorrespondingMinAspectRatio() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        final float fixedOrientationLetterboxAspectRatio = 1.1f;
        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(
                fixedOrientationLetterboxAspectRatio);
        mActivity.mWmService.mLetterboxConfiguration.setDefaultMinAspectRatioForUnresizableApps(
                1.5f);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        final Rect displayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect activityBounds = new Rect(mActivity.getBounds());

        // Display shouldn't be rotated.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED,
                mActivity.mDisplayContent.getLastOrientation());
        assertTrue(displayBounds.width() > displayBounds.height());

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());

        // Letterbox logic should use config_letterboxDefaultMinAspectRatioForUnresizableApps over
        // config_fixedOrientationLetterboxAspectRatio.
        assertEquals(displayBounds.height(), activityBounds.height());
        final float defaultAspectRatio = mActivity.mWmService.mLetterboxConfiguration
                .getDefaultMinAspectRatioForUnresizableApps();
        assertEquals(displayBounds.height() / defaultAspectRatio, activityBounds.width(), 0.5);
    }

    @Test
    public void testComputeConfigResourceOverrides_unresizableApp() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        final Rect activityBounds = new Rect(mActivity.getBounds());

        int originalScreenWidthDp = mActivity.getConfiguration().screenWidthDp;
        int originalScreenHeighthDp = mActivity.getConfiguration().screenHeightDp;

        // App should launch in fixed orientation letterbox.
        // Activity bounds should be 700x1400 with the ratio as the display.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFitted();
        assertEquals(originalScreenWidthDp, mActivity.getConfiguration().smallestScreenWidthDp);
        assertTrue(originalScreenWidthDp < originalScreenHeighthDp);

        // Rotate display to portrait.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // After we rotate, the activity should go in the size-compat mode and report the same
        // configuration values.
        assertScaled();
        assertEquals(originalScreenWidthDp, mActivity.getConfiguration().smallestScreenWidthDp);
        assertEquals(originalScreenWidthDp, mActivity.getConfiguration().screenWidthDp);
        assertEquals(originalScreenHeighthDp, mActivity.getConfiguration().screenHeightDp);

        // Restart activity
        mActivity.restartProcessIfVisible();

        // Now configuration should be updated
        assertFitted();
        assertNotEquals(originalScreenWidthDp, mActivity.getConfiguration().screenWidthDp);
        assertNotEquals(originalScreenHeighthDp, mActivity.getConfiguration().screenHeightDp);
        assertEquals(mActivity.getConfiguration().screenWidthDp,
                mActivity.getConfiguration().smallestScreenWidthDp);
    }

    @Test
    public void testComputeConfigResourceOverrides_resizableFixedOrientationActivity() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app without max aspect.
        prepareLimitedBounds(mActivity, SCREEN_ORIENTATION_PORTRAIT, false /* isUnresizable */);

        final Rect activityBounds = new Rect(mActivity.getBounds());

        int originalScreenWidthDp = mActivity.getConfiguration().screenWidthDp;
        int originalScreenHeighthDp = mActivity.getConfiguration().screenHeightDp;

        // App should launch in fixed orientation letterbox.
        // Activity bounds should be 700x1400 with the ratio as the display.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFitted();
        assertEquals(originalScreenWidthDp, mActivity.getConfiguration().smallestScreenWidthDp);
        assertTrue(originalScreenWidthDp < originalScreenHeighthDp);

        // Rotate display to portrait.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // Now configuration should be updated
        assertFitted();
        assertNotEquals(originalScreenWidthDp, mActivity.getConfiguration().screenWidthDp);
        assertNotEquals(originalScreenHeighthDp, mActivity.getConfiguration().screenHeightDp);
        assertEquals(mActivity.getConfiguration().screenWidthDp,
                mActivity.getConfiguration().smallestScreenWidthDp);
    }

    @Test
    public void testSplitAspectRatioForUnresizablePortraitApps() {
        // Set up a display in landscape and ignoring orientation request.
        int screenWidth = 1600;
        int screenHeight = 1400;
        setUpDisplaySizeWithApp(screenWidth, screenHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration
                        .setIsSplitScreenAspectRatioForUnresizableAppsEnabled(true);

        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.1f);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        final Rect displayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect activityBounds = new Rect(mActivity.getBounds());

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        // Checking that there is no size compat mode.
        assertFitted();

        assertEquals(displayBounds.height(), activityBounds.height());
        assertTrue(activityBounds.width() < displayBounds.width() / 2);

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());
        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, getExpectedSplitSize(screenWidth), screenHeight);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());
        // Checking that there is no size compat mode.
        assertFitted();
    }

    @Test
    public void testUserOverrideFullscreenForLandscapeDisplay() {
        final int displayWidth = 1600;
        final int displayHeight = 1400;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        spyOn(mActivity.mWmService.mLetterboxConfiguration);
        doReturn(true).when(mActivity.mWmService.mLetterboxConfiguration)
                .isUserAppAspectRatioFullscreenEnabled();

        // Set user aspect ratio override
        spyOn(mActivity.mLetterboxUiController);
        doReturn(USER_MIN_ASPECT_RATIO_FULLSCREEN).when(mActivity.mLetterboxUiController)
                .getUserMinAspectRatioOverrideCode();

        prepareMinAspectRatio(mActivity, 16 / 9f, SCREEN_ORIENTATION_PORTRAIT);

        final Rect bounds = mActivity.getBounds();

        // bounds should be fullscreen
        assertEquals(displayHeight, bounds.height());
        assertEquals(displayWidth, bounds.width());
    }

    @Test
    public void testUserOverrideFullscreenForPortraitDisplay() {
        final int displayWidth = 1400;
        final int displayHeight = 1600;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        spyOn(mActivity.mWmService.mLetterboxConfiguration);
        doReturn(true).when(mActivity.mWmService.mLetterboxConfiguration)
                .isUserAppAspectRatioFullscreenEnabled();

        // Set user aspect ratio override
        spyOn(mActivity.mLetterboxUiController);
        doReturn(USER_MIN_ASPECT_RATIO_FULLSCREEN).when(mActivity.mLetterboxUiController)
                .getUserMinAspectRatioOverrideCode();

        prepareMinAspectRatio(mActivity, 16 / 9f, SCREEN_ORIENTATION_LANDSCAPE);

        final Rect bounds = mActivity.getBounds();

        // bounds should be fullscreen
        assertEquals(displayHeight, bounds.height());
        assertEquals(displayWidth, bounds.width());
    }

    @Test
    public void testSystemFullscreenOverrideForLandscapeDisplay() {
        final int displayWidth = 1600;
        final int displayHeight = 1400;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        spyOn(mActivity.mLetterboxUiController);
        doReturn(true).when(mActivity.mLetterboxUiController)
                .isSystemOverrideToFullscreenEnabled();

        prepareMinAspectRatio(mActivity, 16 / 9f, SCREEN_ORIENTATION_PORTRAIT);

        final Rect bounds = mActivity.getBounds();

        // bounds should be fullscreen
        assertEquals(displayHeight, bounds.height());
        assertEquals(displayWidth, bounds.width());
    }

    @Test
    public void testSystemFullscreenOverrideForPortraitDisplay() {
        final int displayWidth = 1400;
        final int displayHeight = 1600;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        spyOn(mActivity.mLetterboxUiController);
        doReturn(true).when(mActivity.mLetterboxUiController)
                .isSystemOverrideToFullscreenEnabled();

        prepareMinAspectRatio(mActivity, 16 / 9f, SCREEN_ORIENTATION_LANDSCAPE);

        final Rect bounds = mActivity.getBounds();

        // bounds should be fullscreen
        assertEquals(displayHeight, bounds.height());
        assertEquals(displayWidth, bounds.width());
    }

    @Test
    public void testUserOverrideSplitScreenAspectRatioForLandscapeDisplay() {
        final int displayWidth = 1600;
        final int displayHeight = 1400;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);

        float expectedAspectRatio = 1f * displayHeight / getExpectedSplitSize(displayWidth);

        testUserOverrideAspectRatio(expectedAspectRatio, USER_MIN_ASPECT_RATIO_SPLIT_SCREEN);
    }

    @Test
    public void testUserOverrideSplitScreenAspectRatioForPortraitDisplay() {
        final int displayWidth = 1400;
        final int displayHeight = 1600;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);

        float expectedAspectRatio = 1f * displayWidth / getExpectedSplitSize(displayHeight);

        testUserOverrideAspectRatio(expectedAspectRatio, USER_MIN_ASPECT_RATIO_SPLIT_SCREEN);
    }

    @Test
    public void testUserOverrideDisplaySizeAspectRatioForLandscapeDisplay() {
        final int displayWidth = 1600;
        final int displayHeight = 1400;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);

        float expectedAspectRatio = 1f * displayWidth / displayHeight;

        testUserOverrideAspectRatio(expectedAspectRatio, USER_MIN_ASPECT_RATIO_DISPLAY_SIZE);
    }

    @Test
    public void testUserOverrideDisplaySizeAspectRatioForPortraitDisplay() {
        final int displayWidth = 1400;
        final int displayHeight = 1600;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);

        float expectedAspectRatio = 1f * displayHeight / displayWidth;

        testUserOverrideAspectRatio(expectedAspectRatio, USER_MIN_ASPECT_RATIO_DISPLAY_SIZE);
    }

    @Test
    public void testUserOverride32AspectRatioForPortraitDisplay() {
        setUpDisplaySizeWithApp(/* dw */ 1400, /* dh */ 1600);
        testUserOverrideAspectRatio(3 / 2f, USER_MIN_ASPECT_RATIO_3_2);
    }

    @Test
    public void testUserOverride32AspectRatioForLandscapeDisplay() {
        setUpDisplaySizeWithApp(/* dw */ 1600, /* dh */ 1400);
        testUserOverrideAspectRatio(3 / 2f, USER_MIN_ASPECT_RATIO_3_2);
    }

    @Test
    public void testUserOverride43AspectRatioForPortraitDisplay() {
        setUpDisplaySizeWithApp(/* dw */ 1400, /* dh */ 1600);
        testUserOverrideAspectRatio(4 / 3f, USER_MIN_ASPECT_RATIO_4_3);
    }

    @Test
    public void testUserOverride43AspectRatioForLandscapeDisplay() {
        setUpDisplaySizeWithApp(/* dw */ 1600, /* dh */ 1400);
        testUserOverrideAspectRatio(4 / 3f, USER_MIN_ASPECT_RATIO_4_3);
    }

    @Test
    public void testUserOverride169AspectRatioForPortraitDisplay() {
        setUpDisplaySizeWithApp(/* dw */ 1800, /* dh */ 1500);
        testUserOverrideAspectRatio(16 / 9f, USER_MIN_ASPECT_RATIO_16_9);
    }

    @Test
    public void testUserOverride169AspectRatioForLandscapeDisplay() {
        setUpDisplaySizeWithApp(/* dw */ 1500, /* dh */ 1800);
        testUserOverrideAspectRatio(16 / 9f, USER_MIN_ASPECT_RATIO_16_9);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE})
    public void testUserOverrideAspectRatioOverSystemOverride() {
        setUpDisplaySizeWithApp(/* dw */ 1600, /* dh */ 1400);

        testUserOverrideAspectRatio(false,
                SCREEN_ORIENTATION_PORTRAIT,
                3 / 2f,
                USER_MIN_ASPECT_RATIO_3_2,
                true);
    }

    @Test
    @FlakyTest(bugId = 299220009)
    public void testUserOverrideAspectRatioNotEnabled() {
        setUpDisplaySizeWithApp(/* dw */ 1600, /* dh */ 1400);

        // App aspect ratio doesn't change
        testUserOverrideAspectRatio(false,
                SCREEN_ORIENTATION_PORTRAIT,
                1f * 1600 / 1400,
                USER_MIN_ASPECT_RATIO_3_2,
                false);
    }

    private void testUserOverrideAspectRatio(float expectedAspectRatio,
            @PackageManager.UserMinAspectRatio int aspectRatio) {
        testUserOverrideAspectRatio(true,
                SCREEN_ORIENTATION_PORTRAIT,
                expectedAspectRatio,
                aspectRatio,
                true);

        testUserOverrideAspectRatio(false,
                SCREEN_ORIENTATION_PORTRAIT,
                expectedAspectRatio,
                aspectRatio,
                true);

        testUserOverrideAspectRatio(true,
                SCREEN_ORIENTATION_LANDSCAPE,
                expectedAspectRatio,
                aspectRatio,
                true);

        testUserOverrideAspectRatio(false,
                SCREEN_ORIENTATION_LANDSCAPE,
                expectedAspectRatio,
                aspectRatio,
                true);
    }

    private void testUserOverrideAspectRatio(boolean isUnresizable, int screenOrientation,
            float expectedAspectRatio, @PackageManager.UserMinAspectRatio int aspectRatio,
            boolean enabled) {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        spyOn(activity.mWmService.mLetterboxConfiguration);
        doReturn(enabled).when(activity.mWmService.mLetterboxConfiguration)
                .isUserAppAspectRatioSettingsEnabled();
        // Set user aspect ratio override
        final IPackageManager pm = mAtm.getPackageManager();
        try {
            doReturn(aspectRatio).when(pm)
                    .getUserMinAspectRatio(activity.packageName, activity.mUserId);
        } catch (RemoteException ignored) {
        }

        prepareLimitedBounds(activity, screenOrientation, isUnresizable);

        final Rect afterBounds = activity.getBounds();
        final int width = afterBounds.width();
        final int height = afterBounds.height();
        final float afterAspectRatio =
                (float) Math.max(width, height) / (float) Math.min(width, height);

        assertEquals(expectedAspectRatio, afterAspectRatio, DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    public void testOverrideSplitScreenAspectRatioForUnresizablePortraitApps() {
        final int displayWidth = 1400;
        final int displayHeight = 1600;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setMinAspectRatio(1.1f)
                .setUid(android.os.Process.myUid())
                .build();
        // Setup Letterbox Configuration
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        activity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.5f);
        // Non-resizable portrait activity
        prepareUnresizable(activity, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        float expectedAspectRatio = 1f * displayWidth / getExpectedSplitSize(displayHeight);
        final Rect afterBounds = activity.getBounds();
        final float afterAspectRatio = (float) (afterBounds.height()) / afterBounds.width();
        assertEquals(expectedAspectRatio, afterAspectRatio, DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    public void testOverrideSplitScreenAspectRatioForUnresizablePortraitAppsFromLandscape() {
        final int displayWidth = 1600;
        final int displayHeight = 1400;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setMinAspectRatio(1.1f)
                .setUid(android.os.Process.myUid())
                .build();
        // Setup Letterbox Configuration
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        activity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.5f);
        // Non-resizable portrait activity
        prepareUnresizable(activity, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        float expectedAspectRatio = 1f * displayHeight / getExpectedSplitSize(displayWidth);
        final Rect afterBounds = activity.getBounds();
        final float afterAspectRatio = (float) (afterBounds.height()) / afterBounds.width();
        assertEquals(expectedAspectRatio, afterAspectRatio, DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    @DisableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY})
    public void testOverrideSplitScreenAspectRatioForUnresizableLandscapeApps() {
        final int displayWidth = 1400;
        final int displayHeight = 1600;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setMinAspectRatio(1.1f)
                .setUid(android.os.Process.myUid())
                .build();
        // Setup Letterbox Configuration
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        activity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.5f);
        // Non-resizable portrait activity
        prepareUnresizable(activity, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        float expectedAspectRatio = 1f * displayWidth / getExpectedSplitSize(displayHeight);
        final Rect afterBounds = activity.getBounds();
        final float afterAspectRatio = (float) (afterBounds.width()) / afterBounds.height();
        assertEquals(expectedAspectRatio, afterAspectRatio, DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    @DisableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY})
    public void testOverrideSplitScreenAspectRatioForUnresizableLandscapeAppsFromLandscape() {
        final int displayWidth = 1600;
        final int displayHeight = 1400;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setMinAspectRatio(1.1f)
                .setUid(android.os.Process.myUid())
                .build();
        // Setup Letterbox Configuration
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        activity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.5f);
        // Non-resizable portrait activity
        prepareUnresizable(activity, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        float expectedAspectRatio = 1f * displayHeight / getExpectedSplitSize(displayWidth);
        final Rect afterBounds = activity.getBounds();
        final float afterAspectRatio = (float) (afterBounds.width()) / afterBounds.height();
        assertEquals(expectedAspectRatio, afterAspectRatio, DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    public void testOverrideSplitScreenAspectRatio_splitScreenActivityInPortrait_notLetterboxed() {
        mAtm.mDevEnableNonResizableMultiWindow = true;
        final int screenWidth = 1800;
        final int screenHeight = 1000;
        setUpDisplaySizeWithApp(screenWidth, screenHeight);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        // Simulate real display with top insets.
        final int topInset = 30;
        activity.mDisplayContent.getWindowConfiguration()
                .setAppBounds(0, topInset, screenWidth, screenHeight);

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, activity.getDisplayContent());
        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, getExpectedSplitSize(screenWidth), screenHeight);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, activity.getWindowingMode());

        // Unresizable portrait-only activity.
        prepareUnresizable(activity, 3f, SCREEN_ORIENTATION_PORTRAIT);

        // Activity should have the aspect ratio of a split screen activity and occupy exactly one
        // half of the screen, so there is no letterbox
        float expectedAspectRatio = 1f * screenHeight / getExpectedSplitSize(screenWidth);
        final Rect afterBounds = activity.getBounds();
        final float afterAspectRatio = (float) (afterBounds.height()) / afterBounds.width();
        assertEquals(expectedAspectRatio, afterAspectRatio, DELTA_ASPECT_RATIO_TOLERANCE);
        assertFalse(activity.areBoundsLetterboxed());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    public void testOverrideSplitScreenAspectRatio_splitScreenActivityInLandscape_notLetterboxed() {
        mAtm.mDevEnableNonResizableMultiWindow = true;
        final int screenWidth = 1000;
        final int screenHeight = 1800;
        setUpDisplaySizeWithApp(screenWidth, screenHeight);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        // Simulate real display with top insets.
        final int leftInset = 30;
        activity.mDisplayContent.getWindowConfiguration()
                .setAppBounds(leftInset, 0, screenWidth, screenHeight);

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, activity.getDisplayContent());
        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, screenWidth, getExpectedSplitSize(screenHeight));
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, activity.getWindowingMode());

        // Unresizable landscape-only activity.
        prepareUnresizable(activity, 3f, SCREEN_ORIENTATION_LANDSCAPE);

        // Activity should have the aspect ratio of a split screen activity and occupy exactly one
        // half of the screen, so there is no letterbox
        float expectedAspectRatio = 1f * screenWidth / getExpectedSplitSize(screenHeight);
        final Rect afterBounds = activity.getBounds();
        final float afterAspectRatio = (float) (afterBounds.width()) / afterBounds.height();
        assertEquals(expectedAspectRatio, afterAspectRatio, DELTA_ASPECT_RATIO_TOLERANCE);
        assertFalse(activity.areBoundsLetterboxed());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN})
    public void testOverrideMinAspectRatioExcludePortraitFullscreen() {
        setUpDisplaySizeWithApp(2600, 1600);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.33f);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        // Non-resizable portrait activity
        prepareUnresizable(activity, 0f, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // At first, OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_FULLSCREEN does not apply, because the
        // display is in landscape
        assertEquals(1600, activity.getBounds().height());
        assertEquals(1600 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                activity.getBounds().width(), 0.5);

        rotateDisplay(activity.mDisplayContent, ROTATION_90);
        prepareUnresizable(activity, /* maxAspect */ 0, SCREEN_ORIENTATION_PORTRAIT);

        // Now the display is in portrait fullscreen, so the override is applied making the content
        // fullscreen
        assertEquals(activity.getBounds(), activity.mDisplayContent.getBounds());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_EXCLUDE_PORTRAIT_FULLSCREEN})
    public void testOverrideMinAspectRatioExcludePortraitFullscreenNotApplied() {
        // In this test, the activity is not in fullscreen, so the override is not applied
        setUpDisplaySizeWithApp(2600, 1600);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.33f);

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, activity.getDisplayContent());

        // Move first activity to split screen which takes half of the screen.
        organizer.mPrimary.setBounds(0, 0, 1300, 1600);
        organizer.putTaskToPrimary(mTask, true);

        // Non-resizable portrait activity
        prepareUnresizable(activity, /* maxAspect */ 0, SCREEN_ORIENTATION_PORTRAIT);

        // OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_FULLSCREEN does not apply here because the
        // display is not in fullscreen, so OVERRIDE_MIN_ASPECT_RATIO_LARGE applies instead
        assertEquals(1600, activity.getBounds().height());
        assertEquals(1600 / ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                activity.getBounds().width(), 0.5);
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_RESPECT_REQUESTED_ORIENTATION})
    public void testOverrideRespectRequestedOrientationIsEnabled_orientationIsRespected() {
        // Set up a display in landscape
        setUpDisplaySizeWithApp(2800, 1400);

        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */ false,
                RESIZE_MODE_UNRESIZEABLE, SCREEN_ORIENTATION_PORTRAIT);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Display should be rotated.
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, activity.mDisplayContent.getOrientation());

        // No size compat mode
        assertFalse(activity.inSizeCompatMode());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_RESPECT_REQUESTED_ORIENTATION})
    public void testOverrideRespectRequestedOrientationIsEnabled_multiWindow_orientationIgnored() {
        // Set up a display in landscape
        setUpDisplaySizeWithApp(2800, 1400);

        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */ false,
                RESIZE_MODE_UNRESIZEABLE, SCREEN_ORIENTATION_PORTRAIT);
        TaskFragment taskFragment = activity.getTaskFragment();
        spyOn(taskFragment);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        doReturn(WINDOWING_MODE_MULTI_WINDOW).when(taskFragment).getWindowingMode();

        // Display should not be rotated.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, activity.mDisplayContent.getOrientation());

        // No size compat mode
        assertFalse(activity.inSizeCompatMode());
    }

    @Test
    public void testSplitAspectRatioForUnresizableLandscapeApps() {
        // Set up a display in portrait and ignoring orientation request.
        int screenWidth = 1400;
        int screenHeight = 1600;
        setUpDisplaySizeWithApp(screenWidth, screenHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration
                        .setIsSplitScreenAspectRatioForUnresizableAppsEnabled(true);

        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.1f);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        final Rect displayBounds = new Rect(mActivity.mDisplayContent.getBounds());
        final Rect activityBounds = new Rect(mActivity.getBounds());

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        // Checking that there is no size compat mode.
        assertFitted();

        assertEquals(displayBounds.width(), activityBounds.width());
        assertTrue(activityBounds.height() < displayBounds.height() / 2);

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());
        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, screenWidth, getExpectedSplitSize(screenHeight));
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());
        // Checking that there is no size compat mode.
        assertFitted();
    }

    @Test
    public void testDisplayAspectRatioForResizablePortraitApps() {
        // Set up a display in portrait and ignoring orientation request.
        int displayWidth = 1400;
        int displayHeight = 1600;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(2f);

        // Enable display aspect ratio to take precedence before
        // fixedOrientationLetterboxAspectRatio
        mWm.mLetterboxConfiguration
                .setIsDisplayAspectRatioEnabledForFixedOrientationLetterbox(true);

        // Set up resizable app in portrait
        prepareLimitedBounds(mActivity, SCREEN_ORIENTATION_PORTRAIT, false /* isUnresizable */);

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());
        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, displayWidth, getExpectedSplitSize(displayHeight));
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        // Checking that there is no size compat mode.
        assertFitted();
        // Check that the display aspect ratio is used by the app.
        final float targetMinAspectRatio = 1f * displayHeight / displayWidth;
        assertEquals(targetMinAspectRatio, ActivityRecord
                .computeAspectRatio(mActivity.getBounds()), DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    public void testDisplayAspectRatioForResizableLandscapeApps() {
        // Set up a display in landscape and ignoring orientation request.
        int displayWidth = 1600;
        int displayHeight = 1400;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(2f);

        // Enable display aspect ratio to take precedence before
        // fixedOrientationLetterboxAspectRatio
        mWm.mLetterboxConfiguration
                .setIsDisplayAspectRatioEnabledForFixedOrientationLetterbox(true);

        // Set up resizable app in landscape
        prepareLimitedBounds(mActivity, SCREEN_ORIENTATION_LANDSCAPE, false /* isUnresizable */);

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());
        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, getExpectedSplitSize(displayWidth), displayHeight);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        // Checking that there is no size compat mode.
        assertFitted();
        // Check that the display aspect ratio is used by the app.
        final float targetMinAspectRatio = 1f * displayWidth / displayHeight;
        assertEquals(targetMinAspectRatio, ActivityRecord
                .computeAspectRatio(mActivity.getBounds()), DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    public void testDisplayAspectRatioForUnresizableLandscapeApps() {
        // Set up a display in portrait and ignoring orientation request.
        int displayWidth = 1400;
        int displayHeight = 1600;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.1f);
        // Enable display aspect ratio to take precedence before
        // fixedOrientationLetterboxAspectRatio
        mWm.mLetterboxConfiguration
                .setIsDisplayAspectRatioEnabledForFixedOrientationLetterbox(true);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        // Checking that there is no size compat mode.
        assertFitted();
        // Check that the display aspect ratio is used by the app.
        final float targetMinAspectRatio = 1f * displayHeight / displayWidth;
        assertEquals(targetMinAspectRatio, ActivityRecord
                .computeAspectRatio(mActivity.getBounds()), DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    public void testDisplayAspectRatioForUnresizablePortraitApps() {
        // Set up a display in landscape and ignoring orientation request.
        int displayWidth = 1600;
        int displayHeight = 1400;
        setUpDisplaySizeWithApp(displayWidth, displayHeight);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        mActivity.mWmService.mLetterboxConfiguration.setFixedOrientationLetterboxAspectRatio(1.1f);
        // Enable display aspect ratio to take precedence before
        // fixedOrientationLetterboxAspectRatio
        mWm.mLetterboxConfiguration
                .setIsDisplayAspectRatioEnabledForFixedOrientationLetterbox(true);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        // App should launch in fixed orientation letterbox.
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        // Checking that there is no size compat mode.
        assertFitted();
        // Check that the display aspect ratio is used by the app.
        final float targetMinAspectRatio = 1f * displayWidth / displayHeight;
        assertEquals(targetMinAspectRatio, ActivityRecord
                .computeAspectRatio(mActivity.getBounds()), DELTA_ASPECT_RATIO_TOLERANCE);
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
        assertTrue(newActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(newActivity.inSizeCompatMode());
        // Activity max bounds are sandboxed due to size compat mode.
        assertThat(newActivity.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(newActivity.getWindowConfiguration().getBounds());
        assertEquals(taskBounds, displayBounds);
        assertEquals(displayBounds.height(), newActivityBounds.height());
        assertEquals(displayBounds.height() * displayBounds.height() / displayBounds.width(),
                newActivityBounds.width());
    }

    @Test
    public void testDisplayIgnoreOrientationRequest_orientationChangedToUnspecified() {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        final DisplayContent display = mActivity.mDisplayContent;
        display.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        // Portrait fixed app without max aspect.
        prepareUnresizable(mActivity, 0, SCREEN_ORIENTATION_PORTRAIT);

        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());

        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        // Activity is not in size compat mode because the orientation change request came from the
        // app itself
        assertFalse(mActivity.inSizeCompatMode());
        assertEquals(mActivity.getResolvedOverrideConfiguration().orientation,
                Configuration.ORIENTATION_UNDEFINED);
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
    public void testDisplayIgnoreOrientationRequest_disabledViaDeviceConfig_orientationRespected() {
        // Set up a display in landscape
        setUpDisplaySizeWithApp(2800, 1400);

        final ActivityRecord activity = buildActivityRecord(/* supportsSizeChanges= */ false,
                RESIZE_MODE_UNRESIZEABLE, SCREEN_ORIENTATION_PORTRAIT);
        activity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        spyOn(activity.mWmService.mLetterboxConfiguration);
        doReturn(true).when(activity.mWmService.mLetterboxConfiguration)
                .isIgnoreOrientationRequestAllowed();

        // Display should not be rotated.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, activity.mDisplayContent.getOrientation());

        doReturn(false).when(activity.mWmService.mLetterboxConfiguration)
                .isIgnoreOrientationRequestAllowed();

        // Display should be rotated.
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, activity.mDisplayContent.getOrientation());
    }

    @Test
    public void testSandboxDisplayApis_unresizableAppNotSandboxed() {
        // Set up a display in landscape with an unresizable app.
        setUpDisplaySizeWithApp(2500, 1000);
        mActivity.mDisplayContent.setSandboxDisplayApis(false /* sandboxDisplayApis */);
        prepareUnresizable(mActivity, 1.5f, SCREEN_ORIENTATION_LANDSCAPE);
        assertFitted();

        // Activity max bounds not be sandboxed since sandboxing is disabled.
        assertMaxBoundsInheritDisplayAreaBounds();
    }

    @Test
    public void testSandboxDisplayApis_unresizableAppSandboxed() {
        // Set up a display in landscape with an unresizable app.
        setUpDisplaySizeWithApp(2500, 1000);
        mActivity.mDisplayContent.setSandboxDisplayApis(true /* sandboxDisplayApis */);
        prepareUnresizable(mActivity, 1.5f, SCREEN_ORIENTATION_LANDSCAPE);
        assertFitted();

        // Activity max bounds should be sandboxed since sandboxing is enabled.
        assertActivityMaxBoundsSandboxed();
    }

    @Test
    public void testResizableApp_notSandboxed() {
        // Set up a display in landscape with a fully resizable app.
        setUpDisplaySizeWithApp(2500, 1000);
        prepareLimitedBounds(mActivity, /* maxAspect= */ -1,
                SCREEN_ORIENTATION_UNSPECIFIED, /* isUnresizable= */ false);
        assertFitted();

        // Activity max bounds not be sandboxed since app is fully resizable.
        assertMaxBoundsInheritDisplayAreaBounds();
    }

    @Test
    public void testResizableMaxAspectApp_notSandboxed() {
        // Set up a display in landscape with a fully resizable app.
        setUpDisplaySizeWithApp(2500, 1000);
        prepareLimitedBounds(mActivity, /* maxAspect= */ 1,
                SCREEN_ORIENTATION_UNSPECIFIED, /* isUnresizable= */ false);
        assertFitted();

        // Activity max bounds not be sandboxed since app is fully resizable.
        assertMaxBoundsInheritDisplayAreaBounds();
    }

    @Test
    public void testResizableOrientationRequestApp_notSandboxed() {
        // Set up a display in landscape with a fully resizable app.
        setUpDisplaySizeWithApp(2500, 1000);
        prepareLimitedBounds(mActivity, /* maxAspect= */ -1,
                SCREEN_ORIENTATION_PORTRAIT, /* isUnresizable= */ false);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        assertFitted();

        // Activity max bounds not be sandboxed since app is fully resizable.
        assertMaxBoundsInheritDisplayAreaBounds();
    }

    @Test
    public void testResizableMaxAspectOrientationRequestApp_notSandboxed() {
        // Set up a display in landscape with a fully resizable app.
        setUpDisplaySizeWithApp(2500, 1000);
        prepareLimitedBounds(mActivity, /* maxAspect= */ 1,
                SCREEN_ORIENTATION_PORTRAIT, /* isUnresizable= */ false);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        assertFitted();

        // Activity max bounds not be sandboxed since app is fully resizable.
        assertMaxBoundsInheritDisplayAreaBounds();
    }

    @Test
    public void testUnresizableApp_isSandboxed() {
        // Set up a display in landscape with a fully resizable app.
        setUpDisplaySizeWithApp(2500, 1000);
        prepareLimitedBounds(mActivity, /* maxAspect= */ -1,
                SCREEN_ORIENTATION_UNSPECIFIED, /* isUnresizable= */ true);
        assertFitted();

        // Activity max bounds are sandboxed since app may enter size compat mode.
        assertActivityMaxBoundsSandboxed();
        assertFalse(mActivity.inSizeCompatMode());
    }

    @Test
    public void testUnresizableMaxAspectApp_isSandboxed() {
        // Set up a display in landscape with a fully resizable app.
        setUpDisplaySizeWithApp(2500, 1000);
        prepareLimitedBounds(mActivity, /* maxAspect= */ 1,
                SCREEN_ORIENTATION_UNSPECIFIED, /* isUnresizable= */ true);
        assertFitted();

        // Activity max bounds are sandboxed since app may enter size compat mode.
        assertActivityMaxBoundsSandboxed();
        assertFalse(mActivity.inSizeCompatMode());
        assertTrue(mActivity.shouldCreateCompatDisplayInsets());

        // Resize display to half the width.
        resizeDisplay(mActivity.getDisplayContent(), 500, 1000);

        // Activity now in size compat mode.
        assertActivityMaxBoundsSandboxed();
        assertTrue(mActivity.inSizeCompatMode());
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
        mAtm.mDevEnableNonResizableMultiWindow = true;
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
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());

        // Non-resizable activity in size compat mode
        assertScaled();
        final Rect newBounds = new Rect(mActivity.getWindowConfiguration().getBounds());
        assertEquals(originalBounds.width(), newBounds.width());
        assertEquals(originalBounds.height(), newBounds.height());
        assertActivityMaxBoundsSandboxed();

        recomputeNaturalConfigurationOfUnresizableActivity();

        // Split screen is also in portrait [1000,1400], so activity should be in fixed orientation
        // letterbox.
        assertEquals(ORIENTATION_PORTRAIT, mTask.getConfiguration().orientation);
        assertEquals(ORIENTATION_LANDSCAPE, mActivity.getConfiguration().orientation);
        assertFitted();
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertActivityMaxBoundsSandboxed();

        // Letterbox should fill the gap between the split screen and the letterboxed activity.
        assertLetterboxSurfacesDrawnBetweenActivityAndParentBounds(organizer.mPrimary.getBounds());
    }

    @Test
    public void testResizableFixedOrientationAppInSplitScreen_letterboxForDifferentOrientation() {
        setUpDisplaySizeWithApp(1000, 2800);
        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());

        // Resizable landscape-only activity.
        prepareLimitedBounds(mActivity, SCREEN_ORIENTATION_LANDSCAPE, /* isUnresizable= */ false);

        final Rect originalBounds = new Rect(mActivity.getBounds());

        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, 1000, 1400);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());

        // Resizable activity is not in size compat mode but in the letterbox for fixed orientation.
        assertFitted();
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
    }

    @Test
    public void testSupportsNonResizableInSplitScreen_aspectRatioLetterboxInSameOrientation() {
        // Support non resizable in multi window
        mAtm.mDevEnableNonResizableMultiWindow = true;
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
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());

        // Non-resizable activity in size compat mode
        assertScaled();
        final Rect newBounds = new Rect(mActivity.getWindowConfiguration().getBounds());
        assertEquals(originalBounds.width(), newBounds.width());
        assertEquals(originalBounds.height(), newBounds.height());
        assertActivityMaxBoundsSandboxed();

        recomputeNaturalConfigurationOfUnresizableActivity();

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

    @Test
    public void testSupportsNonResizableInSplitScreen_letterboxForAspectRatioRestriction() {
        // Support non resizable in multi window
        mAtm.mDevEnableNonResizableMultiWindow = true;
        setUpDisplaySizeWithApp(/* dw */ 1000, /* dh */ 2800);
        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());

        prepareUnresizable(mActivity, /* maxAspect */ 1.1f, SCREEN_ORIENTATION_UNSPECIFIED);

        // Bounds are letterboxed to respect the provided max aspect ratio.
        assertEquals(mActivity.getBounds(), new Rect(0, 0, 1000, 1100));

        // Move activity to split screen which has landscape size.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents */ false, "test");
        organizer.mPrimary.setBounds(0, 0, 1000, 800);

        // Non-resizable activity should be in size compat mode.
        assertScaled();
        assertEquals(mActivity.getBounds(), new Rect(60, 0, 940, 800));

        recomputeNaturalConfigurationOfUnresizableActivity();

        // Activity should still be letterboxed but not in the size compat mode.
        assertFitted();
        assertEquals(mActivity.getBounds(), new Rect(60, 0, 940, 800));

        // Letterbox should fill the gap between the split screen and the letterboxed activity.
        assertLetterboxSurfacesDrawnBetweenActivityAndParentBounds(organizer.mPrimary.getBounds());
    }

    @Test
    public void testIsReachabilityEnabled_thisLetterbox_false() {
        // Case when the reachability would be enabled otherwise
        setUpDisplaySizeWithApp(/* dw */ 1000, /* dh */ 2800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsVerticalReachabilityEnabled(true);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);
        mActivity.getWindowConfiguration().setBounds(null);

        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ false);

        assertFalse(mActivity.mLetterboxUiController.isVerticalReachabilityEnabled());
        assertFalse(mActivity.mLetterboxUiController.isHorizontalReachabilityEnabled());
    }

    @Test
    public void testIsHorizontalReachabilityEnabled_splitScreen_false() {
        mAtm.mDevEnableNonResizableMultiWindow = true;
        setUpDisplaySizeWithApp(2800, 1000);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsHorizontalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);
        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());

        // Unresizable portrait-only activity.
        prepareUnresizable(mActivity, 1.1f, SCREEN_ORIENTATION_PORTRAIT);

        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, 1400, 1000);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());

        // Horizontal reachability is disabled because the app is in split screen.
        assertFalse(mActivity.mLetterboxUiController.isHorizontalReachabilityEnabled());
    }

    @Test
    public void testIsVerticalReachabilityEnabled_splitScreen_false() {
        mAtm.mDevEnableNonResizableMultiWindow = true;
        setUpDisplaySizeWithApp(1000, 2800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsVerticalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);
        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());

        // Unresizable landscape-only activity.
        prepareUnresizable(mActivity, 1.1f, SCREEN_ORIENTATION_LANDSCAPE);

        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP, /* moveParents= */ false , "test");
        organizer.mPrimary.setBounds(0, 0, 1000, 1400);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mTask.getWindowingMode());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());

        // Vertical reachability is disabled because the app is in split screen.
        assertFalse(mActivity.mLetterboxUiController.isVerticalReachabilityEnabled());
    }

    @Test
    public void testIsVerticalReachabilityEnabled_doesNotMatchParentWidth_false() {
        setUpDisplaySizeWithApp(1000, 2800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsVerticalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);

        // Unresizable landscape-only activity.
        prepareUnresizable(mActivity, 1.1f, SCREEN_ORIENTATION_LANDSCAPE);

        // Rotate to put activity in size compat mode.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // Activity now in size compat mode.
        assertTrue(mActivity.inSizeCompatMode());

        // Vertical reachability is disabled because the app does not match parent width
        assertNotEquals(mActivity.getScreenResolvedBounds().width(),
                mActivity.mDisplayContent.getBounds().width());
        assertFalse(mActivity.mLetterboxUiController.isVerticalReachabilityEnabled());
    }

    @Test
    public void testIsVerticalReachabilityEnabled_emptyBounds_true() {
        setUpDisplaySizeWithApp(/* dw */ 1000, /* dh */ 2800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsVerticalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        // Set up activity with empty bounds to mock loading of app
        mActivity.getWindowConfiguration().setBounds(null);
        assertEquals(new Rect(0, 0, 0, 0), mActivity.getBounds());

        // Vertical reachability is still enabled as resolved bounds is not empty
        assertTrue(mActivity.mLetterboxUiController.isVerticalReachabilityEnabled());
    }

    @Test
    public void testIsHorizontalReachabilityEnabled_emptyBounds_true() {
        setUpDisplaySizeWithApp(/* dw */ 2800, /* dh */ 1000);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsHorizontalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        // Set up activity with empty bounds to mock loading of app
        mActivity.getWindowConfiguration().setBounds(null);
        assertEquals(new Rect(0, 0, 0, 0), mActivity.getBounds());

        // Horizontal reachability is still enabled as resolved bounds is not empty
        assertTrue(mActivity.mLetterboxUiController.isHorizontalReachabilityEnabled());
    }

    @Test
    public void testIsHorizontalReachabilityEnabled_portraitDisplayAndApp_true() {
        // Portrait display
        setUpDisplaySizeWithApp(1400, 1600);
        mActivity.mWmService.mLetterboxConfiguration.setIsHorizontalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);

        // 16:9f unresizable portrait app
        prepareMinAspectRatio(mActivity, OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                SCREEN_ORIENTATION_PORTRAIT);

        assertTrue(mActivity.mLetterboxUiController.isHorizontalReachabilityEnabled());
    }

    @Test
    public void testIsVerticalReachabilityEnabled_landscapeDisplayAndApp_true() {
        // Landscape display
        setUpDisplaySizeWithApp(1600, 1500);
        mActivity.mWmService.mLetterboxConfiguration.setIsVerticalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);

        // 16:9f unresizable landscape app
        prepareMinAspectRatio(mActivity, OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                SCREEN_ORIENTATION_LANDSCAPE);

        assertTrue(mActivity.mLetterboxUiController.isVerticalReachabilityEnabled());
    }

    @Test
    public void testIsHorizontalReachabilityEnabled_doesNotMatchParentHeight_false() {
        setUpDisplaySizeWithApp(2800, 1000);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsHorizontalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);

        // Unresizable portrait-only activity.
        prepareUnresizable(mActivity, 1.1f, SCREEN_ORIENTATION_PORTRAIT);

        // Rotate to put activity in size compat mode.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // Activity now in size compat mode.
        assertTrue(mActivity.inSizeCompatMode());

        // Horizontal reachability is disabled because the app does not match parent height
        assertNotEquals(mActivity.getScreenResolvedBounds().height(),
                mActivity.mDisplayContent.getBounds().height());
        assertFalse(mActivity.mLetterboxUiController.isHorizontalReachabilityEnabled());
    }

    @Test
    public void testIsHorizontalReachabilityEnabled_inSizeCompatMode_matchesParentHeight_true() {
        setUpDisplaySizeWithApp(1800, 2200);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsHorizontalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);

        // Unresizable portrait-only activity.
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        // Rotate to put activity in size compat mode.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // Activity now in size compat mode.
        assertTrue(mActivity.inSizeCompatMode());

        // Horizontal reachability is enabled because the app matches parent height
        assertEquals(mActivity.getScreenResolvedBounds().height(),
                mActivity.mDisplayContent.getBounds().height());
        assertTrue(mActivity.mLetterboxUiController.isHorizontalReachabilityEnabled());
    }

    @Test
    public void testIsVerticalReachabilityEnabled_inSizeCompatMode_matchesParentWidth_true() {
        setUpDisplaySizeWithApp(2200, 1800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsVerticalReachabilityEnabled(true);
        setUpAllowThinLetterboxed(/* thinLetterboxAllowed */ true);

        // Unresizable landscape-only activity.
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        // Rotate to put activity in size compat mode.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // Activity now in size compat mode.
        assertTrue(mActivity.inSizeCompatMode());

        // Vertical reachability is enabled because the app matches parent width
        assertEquals(mActivity.getScreenResolvedBounds().width(),
                mActivity.mDisplayContent.getBounds().width());
        assertTrue(mActivity.mLetterboxUiController.isVerticalReachabilityEnabled());
    }

    @Test
    public void testAppRequestsOrientationChange_notInSizeCompat() {
        setUpDisplaySizeWithApp(2200, 1800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);

        // Activity is not in size compat mode because the orientation change request came from the
        // app itself
        assertFalse(mActivity.inSizeCompatMode());

        rotateDisplay(mActivity.mDisplayContent, ROTATION_270);
        // Activity should go into size compat mode now because the orientation change came from the
        // system (device rotation)
        assertTrue(mActivity.inSizeCompatMode());
    }

    @Test
    public void testLetterboxDetailsForStatusBar_noLetterbox() {
        setUpDisplaySizeWithApp(2800, 1000);
        addStatusBar(mActivity.mDisplayContent);
        addWindowToActivity(mActivity); // Add a window to the activity so that we can get an
        // appearance inside letterboxDetails

        DisplayPolicy displayPolicy = mActivity.getDisplayContent().getDisplayPolicy();
        StatusBarManagerInternal statusBar = displayPolicy.getStatusBarManagerInternal();
        // We should get a null LetterboxDetails object as there is no letterboxed activity, so
        // nothing will get passed to SysUI
        verify(statusBar, never()).onSystemBarAttributesChanged(anyInt(), anyInt(),
                any(), anyBoolean(), anyInt(), anyInt(), isNull(), isNull());

    }

    @Test
    public void testLetterboxDetailsForStatusBar_letterboxedForMaxAspectRatio() {
        setUpDisplaySizeWithApp(2800, 1000);
        addStatusBar(mActivity.mDisplayContent);
        addWindowToActivity(mActivity); // Add a window to the activity so that we can get an
        // appearance inside letterboxDetails
        // Prepare unresizable activity with max aspect ratio
        prepareUnresizable(mActivity, /* maxAspect */ 1.1f, SCREEN_ORIENTATION_UNSPECIFIED);
        // Refresh the letterbox
        mActivity.mRootWindowContainer.performSurfacePlacement();

        Rect mBounds = new Rect(mActivity.getWindowConfiguration().getBounds());
        assertEquals(mBounds, new Rect(850, 0, 1950, 1000));

        DisplayPolicy displayPolicy = mActivity.getDisplayContent().getDisplayPolicy();
        LetterboxDetails[] expectedLetterboxDetails = {new LetterboxDetails(
                mBounds,
                mActivity.getDisplayContent().getBounds(),
                mActivity.findMainWindow().mAttrs.insetsFlags.appearance
        )};

        // Check that letterboxDetails actually gets passed to SysUI
        StatusBarManagerInternal statusBar = displayPolicy.getStatusBarManagerInternal();
        verify(statusBar).onSystemBarAttributesChanged(anyInt(), anyInt(),
                any(), anyBoolean(), anyInt(), anyInt(), isNull(), eq(expectedLetterboxDetails));
    }

    @Test
    public void testLetterboxDetailsForStatusBar_letterboxNotOverlappingStatusBar() {
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
        // Align to center so that we don't overlap with the status bar
        mAtm.mWindowManager.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(0.5f);
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1000, 2800)
                .setNotch(100)
                .build();
        setUpApp(display);
        TestWindowState statusBar = addStatusBar(mActivity.mDisplayContent);
        spyOn(statusBar);
        doReturn(new Rect(0, 0, statusBar.mRequestedWidth, statusBar.mRequestedHeight))
                .when(statusBar).getFrame();
        addWindowToActivity(mActivity); // Add a window to the activity so that we can get an
        // appearance inside letterboxDetails
        // Prepare unresizable activity with max aspect ratio
        prepareUnresizable(mActivity, /* maxAspect */ 1.1f, SCREEN_ORIENTATION_UNSPECIFIED);
        // Refresh the letterbox
        mActivity.mRootWindowContainer.performSurfacePlacement();

        Rect mBounds = new Rect(mActivity.getWindowConfiguration().getBounds());
        assertEquals(mBounds, new Rect(0, 900, 1000, 2000));

        DisplayPolicy displayPolicy = mActivity.getDisplayContent().getDisplayPolicy();
        LetterboxDetails[] expectedLetterboxDetails = {new LetterboxDetails(
                mBounds,
                mActivity.getDisplayContent().getBounds(),
                mActivity.findMainWindow().mAttrs.insetsFlags.appearance
        )};

        // Check that letterboxDetails actually gets passed to SysUI
        StatusBarManagerInternal statusBarManager = displayPolicy.getStatusBarManagerInternal();
        verify(statusBarManager).onSystemBarAttributesChanged(anyInt(), anyInt(),
                any(), anyBoolean(), anyInt(), anyInt(), isNull(), eq(expectedLetterboxDetails));
    }

    @Test
    public void testLetterboxDetailsForTaskBar_letterboxNotOverlappingTaskBar() {
        mAtm.mDevEnableNonResizableMultiWindow = true;
        final int screenHeight = 2200;
        final int screenWidth = 1400;
        final int taskbarHeight = 200;
        setUpDisplaySizeWithApp(screenWidth, screenHeight);

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());

        // Move first activity to split screen which takes half of the screen.
        organizer.mPrimary.setBounds(0, screenHeight / 2, screenWidth, screenHeight);
        organizer.putTaskToPrimary(mTask, true);

        final InsetsSource navSource = new InsetsSource(
                InsetsSource.createId(null, 0, navigationBars()), navigationBars());
        navSource.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        navSource.setFrame(new Rect(0, screenHeight - taskbarHeight, screenWidth, screenHeight));

        mActivity.mWmService.mLetterboxConfiguration.setLetterboxActivityCornersRadius(15);

        final WindowState w1 = addWindowToActivity(mActivity);
        w1.mAboveInsetsState.addSource(navSource);

        // Prepare unresizable activity with max aspect ratio
        prepareUnresizable(mActivity, /* maxAspect */ 1.1f, SCREEN_ORIENTATION_UNSPECIFIED);

        // Refresh the letterboxes
        mActivity.mRootWindowContainer.performSurfacePlacement();

        final ArgumentCaptor<Rect> cropCapturer = ArgumentCaptor.forClass(Rect.class);
        verify(mTransaction, times(2)).setCrop(
                eq(w1.getSurfaceControl()),
                cropCapturer.capture()
        );
        final List<Rect> capturedCrops = cropCapturer.getAllValues();

        final int expectedHeight = screenHeight / 2 - taskbarHeight;
        assertEquals(2, capturedCrops.size());
        assertEquals(expectedHeight, capturedCrops.get(0).bottom);
        assertEquals(expectedHeight, capturedCrops.get(1).bottom);
    }

    @Test
    public void testLetterboxAlignedToBottom_NotOverlappingNavbar() {
        assertLandscapeActivityAlignedToBottomWithNavbar(false /* immersive */);
    }

    @Test
    @EnableFlags(Flags.FLAG_IMMERSIVE_APP_REPOSITIONING)
    public void testImmersiveLetterboxAlignedToBottom_OverlappingNavbar() {
        assertLandscapeActivityAlignedToBottomWithNavbar(true /* immersive */);
    }

    private void assertLandscapeActivityAlignedToBottomWithNavbar(boolean immersive) {
        final int screenHeight = 2800;
        final int screenWidth = 1400;
        final int taskbarHeight = 200;
        setUpDisplaySizeWithApp(screenWidth, screenHeight);

        mActivity.mDisplayContent.setIgnoreOrientationRequest(true);
        mActivity.mWmService.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(1.0f);

        final InsetsSource navSource = new InsetsSource(
                InsetsSource.createId(null, 0, navigationBars()), navigationBars());
        navSource.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        // Immersive activity has transient navbar
        navSource.setVisible(!immersive);
        navSource.setFrame(new Rect(0, screenHeight - taskbarHeight, screenWidth, screenHeight));
        mActivity.mWmService.mLetterboxConfiguration.setLetterboxActivityCornersRadius(15);

        final WindowState w1 = addWindowToActivity(mActivity);
        w1.mAboveInsetsState.addSource(navSource);

        // Prepare unresizable landscape activity
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);
        doReturn(immersive).when(mActivity).isImmersiveMode(any());

        mActivity.mRootWindowContainer.performSurfacePlacement();

        LetterboxDetails letterboxDetails = mActivity.mLetterboxUiController.getLetterboxDetails();

        // Letterboxed activity at bottom
        assertEquals(new Rect(0, 2100, 1400, 2800), mActivity.getBounds());
        final int expectedHeight = immersive ? screenHeight : screenHeight - taskbarHeight;
        assertEquals(expectedHeight, letterboxDetails.getLetterboxInnerBounds().bottom);
    }

    @Test
    public void testSplitScreenLetterboxDetailsForStatusBar_twoLetterboxedApps() {
        mAtm.mDevEnableNonResizableMultiWindow = true;
        setUpDisplaySizeWithApp(2800, 1000);
        addStatusBar(mActivity.mDisplayContent);
        // Create another task for the second activity
        final Task newTask = new TaskBuilder(mSupervisor).setDisplay(mActivity.getDisplayContent())
                .setCreateActivity(true).build();
        ActivityRecord newActivity = newTask.getTopNonFinishingActivity();

        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());

        // Move first activity to split screen which takes half of the screen.
        organizer.mPrimary.setBounds(0, 0, 1400, 1000);
        organizer.putTaskToPrimary(mTask, true);
        // Move second activity to split screen which takes half of the screen.
        organizer.mSecondary.setBounds(1400, 0, 2800, 1000);
        organizer.putTaskToSecondary(newTask, true);

        addWindowToActivity(mActivity); // Add a window to the activity so that we can get an
        // appearance inside letterboxDetails
        // Prepare unresizable activity with max aspect ratio
        prepareUnresizable(mActivity, /* maxAspect */ 1.1f, SCREEN_ORIENTATION_UNSPECIFIED);
        addWindowToActivity(newActivity);
        prepareUnresizable(newActivity, /* maxAspect */ 1.1f, SCREEN_ORIENTATION_UNSPECIFIED);

        // Refresh the letterboxes
        newActivity.mRootWindowContainer.performSurfacePlacement();

        Rect mBounds = new Rect(mActivity.getWindowConfiguration().getBounds());
        assertEquals(mBounds, new Rect(150, 0, 1250, 1000));
        final Rect newBounds = new Rect(newActivity.getWindowConfiguration().getBounds());
        assertEquals(newBounds, new Rect(1550, 0, 2650, 1000));

        DisplayPolicy displayPolicy = mActivity.getDisplayContent().getDisplayPolicy();
        LetterboxDetails[] expectedLetterboxDetails = { new LetterboxDetails(
                mBounds,
                organizer.mPrimary.getBounds(),
                mActivity.findMainWindow().mAttrs.insetsFlags.appearance
        ), new LetterboxDetails(
                newBounds,
                organizer.mSecondary.getBounds(),
                newActivity.findMainWindow().mAttrs.insetsFlags.appearance
        )};

        // Check that letterboxDetails actually gets passed to SysUI
        StatusBarManagerInternal statusBar = displayPolicy.getStatusBarManagerInternal();
        verify(statusBar).onSystemBarAttributesChanged(anyInt(), anyInt(),
                any(), anyBoolean(), anyInt(), anyInt(), isNull(), eq(expectedLetterboxDetails));
    }

    private void recomputeNaturalConfigurationOfUnresizableActivity() {
        // Recompute the natural configuration of the non-resizable activity and the split screen.
        mActivity.clearSizeCompatMode();

        // Draw letterbox.
        mActivity.setVisible(false);
        mActivity.mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_OPEN);
        mActivity.mDisplayContent.mOpeningApps.add(mActivity);
        addWindowToActivity(mActivity);
        mActivity.mRootWindowContainer.performSurfacePlacement();
    }

    private void assertLetterboxSurfacesDrawnBetweenActivityAndParentBounds(Rect parentBounds) {
        // Letterbox should fill the gap between the parent bounds and the letterboxed activity.
        final Rect letterboxedBounds = new Rect(mActivity.getBounds());
        assertTrue(parentBounds.contains(letterboxedBounds));
        assertEquals(new Rect(letterboxedBounds.left - parentBounds.left,
                letterboxedBounds.top - parentBounds.top,
                parentBounds.right - letterboxedBounds.right,
                parentBounds.bottom - letterboxedBounds.bottom),
                mActivity.getLetterboxInsets());
    }

    @Test
    public void testUpdateResolvedBoundsHorizontalPosition_leftInsets_appCentered() {
        // Set up folded display
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1100, 2100)
                .setCanRotate(true)
                .build();
        display.setIgnoreOrientationRequest(true);
        final DisplayPolicy policy = display.getDisplayPolicy();
        DisplayPolicy.DecorInsets.Info decorInfo = policy.getDecorInsetsInfo(ROTATION_90,
                display.mBaseDisplayHeight, display.mBaseDisplayWidth);
        decorInfo.mNonDecorInsets.set(130, 0,  60, 0);
        spyOn(policy);
        doReturn(decorInfo).when(policy).getDecorInsetsInfo(ROTATION_90,
                display.mBaseDisplayHeight, display.mBaseDisplayWidth);
        mWm.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(0.5f);

        setUpApp(display);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        // Resize the display to simulate unfolding in portrait
        resizeDisplay(mTask.mDisplayContent, 2200, 1800);
        assertTrue(mActivity.inSizeCompatMode());

        // Simulate real display not taking non-decor insets into consideration
        display.getWindowConfiguration().setAppBounds(0, 0, 2200, 1800);

        // Rotate display to landscape
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // App is centered
        assertEquals(mActivity.getBounds(), new Rect(350, 50, 1450, 2150));
    }

    @Test
    public void testUpdateResolvedBoundsHorizontalPosition_left() {
        // Display configured as (2800, 1400).
        assertHorizontalPositionForDifferentDisplayConfigsForPortraitActivity(
                /* letterboxHorizontalPositionMultiplier */ 0.0f,
                // At launch.
                /* fixedOrientationLetterbox */ new Rect(0, 0, 700, 1400),
                // After 90 degree rotation.
                /* sizeCompatUnscaled */ new Rect(0, 0, 700, 1400),
                // After the display is resized to (700, 1400).
                /* sizeCompatScaled */ new Rect(0, 0, 350, 700));
    }

    @Test
    public void testUpdateResolvedBoundsHorizontalPosition_center() {
        // Display configured as (2800, 1400).
        assertHorizontalPositionForDifferentDisplayConfigsForPortraitActivity(
                /* letterboxHorizontalPositionMultiplier */ 0.5f,
                // At launch.
                /* fixedOrientationLetterbox */ new Rect(1050, 0, 1750, 1400),
                // After 90 degree rotation.
                /* sizeCompatUnscaled */ new Rect(350, 0, 1050, 1400),
                // After the display is resized to (700, 1400).
                /* sizeCompatScaled */ new Rect(525, 0, 875, 700));
    }

    @Test
    public void testUpdateResolvedBoundsHorizontalPosition_right() {
        // Display configured as (2800, 1400).
        assertHorizontalPositionForDifferentDisplayConfigsForPortraitActivity(
                /* letterboxHorizontalPositionMultiplier */ 1.0f,
                // At launch.
                /* fixedOrientationLetterbox */ new Rect(2100, 0, 2800, 1400),
                // After 90 degree rotation.
                /* sizeCompatUnscaled */ new Rect(700, 0, 1400, 1400),
                // After the display is resized to (700, 1400).
                /* sizeCompatScaled */ new Rect(1050, 0, 1400, 700));
    }

    private void assertHorizontalPositionForDifferentDisplayConfigsForPortraitActivity(
            float letterboxHorizontalPositionMultiplier, Rect fixedOrientationLetterbox,
            Rect sizeCompatUnscaled, Rect sizeCompatScaled) {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        mActivity.mWmService.mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(
                letterboxHorizontalPositionMultiplier);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(fixedOrientationLetterbox, mActivity.getBounds());
        // Rotate to put activity in size compat mode.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);
        assertTrue(mActivity.inSizeCompatMode());
        // Activity is in size compat mode but not scaled.
        assertEquals(sizeCompatUnscaled, mActivity.getBounds());
        // Force activity to scaled down for size compat mode.
        resizeDisplay(mTask.mDisplayContent, 700, 1400);
        assertTrue(mActivity.inSizeCompatMode());
        assertScaled();
        assertEquals(sizeCompatScaled, mActivity.getBounds());
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_ENABLE_INSETS_DECOUPLED_CONFIGURATION})
    public void testPortraitCloseToSquareDisplayWithTaskbar_insetsOverridden_notLetterboxed() {
        // Set up portrait close to square display.
        setUpDisplaySizeWithApp(2200, 2280);
        final DisplayContent display = mActivity.mDisplayContent;
        display.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        // Simulate insets, final app bounds are (0, 0, 2200, 2130) - landscape.
        final WindowState navbar = createWindow(null, TYPE_NAVIGATION_BAR, mDisplayContent,
                "navbar");
        final Binder owner = new Binder();
        navbar.mAttrs.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.navigationBars())
                        .setInsetsSize(Insets.of(0, 0, 0, 150))
        };
        display.getDisplayPolicy().addWindowLw(navbar, navbar.mAttrs);
        assertTrue(display.getDisplayPolicy().updateDecorInsetsInfo());
        display.sendNewConfiguration();

        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        // Activity should not be letterboxed and should have portrait app bounds even though
        // orientation is not respected with insets as insets have been decoupled.
        final Rect appBounds = activity.getWindowConfiguration().getAppBounds();
        final Rect displayBounds = display.getBounds();
        assertFalse(activity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertNotNull(appBounds);
        assertEquals(displayBounds.width(), appBounds.width());
        assertEquals(displayBounds.height(), appBounds.height());
    }

    @Test
    @DisableCompatChanges({ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED})
    public void testPortraitCloseToSquareDisplayWithTaskbar_letterboxed() {
        // Set up portrait close to square display
        setUpDisplaySizeWithApp(2200, 2280);
        final DisplayContent display = mActivity.mDisplayContent;
        display.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        // Simulate taskbar, final app bounds are (0, 0, 2200, 2130) - landscape
        final WindowState navbar = createWindow(null, TYPE_NAVIGATION_BAR, mDisplayContent,
                "navbar");
        final Binder owner = new Binder();
        navbar.mAttrs.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.navigationBars())
                        .setInsetsSize(Insets.of(0, 0, 0, 150))
        };
        display.getDisplayPolicy().addWindowLw(navbar, navbar.mAttrs);
        assertTrue(display.getDisplayPolicy().updateDecorInsetsInfo());
        display.sendNewConfiguration();

        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setScreenOrientation(SCREEN_ORIENTATION_PORTRAIT)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();

        final Rect bounds = activity.getBounds();
        // Activity should be letterboxed and should have portrait app bounds
        assertTrue(activity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertTrue(bounds.height() > bounds.width());
    }

    @Test
    @DisableCompatChanges({ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED})
    public void testFixedAspectRatioAppInPortraitCloseToSquareDisplay_notInSizeCompat() {
        setUpDisplaySizeWithApp(2200, 2280);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        final DisplayContent dc = mActivity.mDisplayContent;
        // Simulate taskbar, final app bounds are (0, 0, 2200, 2130) - landscape
        final WindowState navbar = createWindow(null, TYPE_NAVIGATION_BAR, mDisplayContent,
                "navbar");
        final Binder owner = new Binder();
        navbar.mAttrs.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.navigationBars())
                        .setInsetsSize(Insets.of(0, 0, 0, 150))
        };
        dc.getDisplayPolicy().addWindowLw(navbar, navbar.mAttrs);
        assertTrue(dc.getDisplayPolicy().updateDecorInsetsInfo());
        dc.sendNewConfiguration();

        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
        prepareMinAspectRatio(activity, OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE,
                SCREEN_ORIENTATION_LANDSCAPE);
        // To force config to update again but with the same landscape orientation.
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        assertTrue(activity.shouldCreateCompatDisplayInsets());
        assertNotNull(activity.getCompatDisplayInsets());
        // Activity is not letterboxed for fixed orientation because orientation is respected
        // with insets, and should not be in size compat mode
        assertFalse(activity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(activity.inSizeCompatMode());
    }

    @Test
    public void testApplyAspectRatio_activityAlignWithParentAppVertical() {
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
        // The display's app bounds will be (0, 100, 1000, 2350)
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1000, 2500)
                .setCanRotate(false)
                .setCutout(0, 100, 0, 150)
                .build();

        setUpApp(display);
        prepareUnresizable(mActivity, 2.1f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);
        // The activity height is 2100 and the display's app bounds height is 2250, so the activity
        // can be aligned inside parentAppBounds
        assertEquals(mActivity.getBounds(), new Rect(0, 0, 1000, 2200));
    }

    @Test
    public void testApplyAspectRatio_activityCannotAlignWithParentAppVertical() {
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
        // The display's app bounds will be (0, 100, 1000, 2150)
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1000, 2300)
                .setCanRotate(false)
                .setCutout(0, 100, 0, 150)
                .build();

        setUpApp(display);
        prepareUnresizable(mActivity, 2.1f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);
        // The activity height is 2100 and the display's app bounds height is 2050, so the activity
        // cannot be aligned inside parentAppBounds and it will fill the parentBounds of the display
        assertEquals(mActivity.getBounds(), display.getBounds());
    }

    @Test
    public void testApplyAspectRatio_activityAlignWithParentAppHorizontal() {
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
        // The display's app bounds will be (100, 0, 2350, 1000)
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 2500, 1000)
                .setCanRotate(false)
                .setCutout(100, 0, 150, 0)
                .build();

        setUpApp(display);
        prepareUnresizable(mActivity, 2.1f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);
        // The activity width is 2100 and the display's app bounds width is 2250, so the activity
        // can be aligned inside parentAppBounds
        assertEquals(mActivity.getBounds(), new Rect(175, 0, 2275, 1000));
    }
    @Test
    public void testApplyAspectRatio_activityCannotAlignWithParentAppHorizontal() {
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
        // The display's app bounds will be (100, 0, 2150, 1000)
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 2300, 1000)
                .setCanRotate(false)
                .setCutout(100, 0, 150, 0)
                .build();

        setUpApp(display);
        prepareUnresizable(mActivity, 2.1f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);
        // The activity width is 2100 and the display's app bounds width is 2050, so the activity
        // cannot be aligned inside parentAppBounds and it will fill the parentBounds of the display
        assertEquals(mActivity.getBounds(), display.getBounds());
    }

    @Test
    public void testApplyAspectRatio_containingRatioAlmostEqualToMaxRatio_boundsUnchanged() {
        setUpDisplaySizeWithApp(1981, 2576);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(0.5f);

        final Rect originalBounds = new Rect(mActivity.getBounds());
        prepareUnresizable(mActivity, 1.3f, SCREEN_ORIENTATION_UNSPECIFIED);

        // The containing aspect ratio is now 1.3003534, while the desired aspect ratio is 1.3. The
        // bounds of the activity should not be changed as the difference is too small
        assertEquals(mActivity.getBounds(), originalBounds);
    }

    @Test
    public void testUpdateResolvedBoundsHorizontalPosition_activityFillParentWidth() {
        // When activity width equals parent width, multiplier shouldn't have any effect.
        assertHorizontalPositionForDifferentDisplayConfigsForLandscapeActivity(
                /* letterboxHorizontalPositionMultiplier */ 0.0f);
        assertHorizontalPositionForDifferentDisplayConfigsForLandscapeActivity(
                /* letterboxHorizontalPositionMultiplier */ 0.5f);
        assertHorizontalPositionForDifferentDisplayConfigsForLandscapeActivity(
                /* letterboxHorizontalPositionMultiplier */ 1.0f);
    }

    @Test
    public void testUpdateResolvedBoundsVerticalPosition_topInsets_appCentered() {
        // Set up folded display
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 2100, 1100)
                .setCanRotate(true)
                .build();
        display.setIgnoreOrientationRequest(true);
        final DisplayPolicy policy = display.getDisplayPolicy();
        DisplayPolicy.DecorInsets.Info decorInfo = policy.getDecorInsetsInfo(ROTATION_90,
                display.mBaseDisplayHeight, display.mBaseDisplayWidth);
        decorInfo.mNonDecorInsets.set(0, 130,  0, 60);
        spyOn(policy);
        doReturn(decorInfo).when(policy).getDecorInsetsInfo(ROTATION_90,
                display.mBaseDisplayHeight, display.mBaseDisplayWidth);
        mWm.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(0.5f);

        setUpApp(display);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        // Resize the display to simulate unfolding in portrait
        resizeDisplay(mTask.mDisplayContent, 1800, 2200);
        assertTrue(mActivity.inSizeCompatMode());

        // Simulate real display not taking non-decor insets into consideration
        display.getWindowConfiguration().setAppBounds(0, 0, 1800, 2200);

        // Rotate display to landscape
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        // App is centered
        assertEquals(mActivity.getBounds(), new Rect(50, 350, 2150, 1450));
    }

    @Test
    public void testUpdateResolvedBoundsVerticalPosition_top() {
        // Display configured as (1400, 2800).
        assertVerticalPositionForDifferentDisplayConfigsForLandscapeActivity(
                /* letterboxVerticalPositionMultiplier */ 0.0f,
                // At launch.
                /* fixedOrientationLetterbox */ new Rect(0, 0, 1400, 700),
                // After 90 degree rotation.
                /* sizeCompatUnscaled */ new Rect(700, 0, 2100, 700),
                // After the display is resized to (1400, 700).
                /* sizeCompatScaled */ new Rect(0, 0, 700, 350));
    }

    @Test
    public void testUpdateResolvedBoundsVerticalPosition_center() {
        // Display configured as (1400, 2800).
        assertVerticalPositionForDifferentDisplayConfigsForLandscapeActivity(
                /* letterboxVerticalPositionMultiplier */ 0.5f,
                // At launch.
                /* fixedOrientationLetterbox */ new Rect(0, 1050, 1400, 1750),
                // After 90 degree rotation.
                /* sizeCompatUnscaled */ new Rect(700, 350, 2100, 1050),
                // After the display is resized to (1400, 700).
                /* sizeCompatScaled */ new Rect(0, 525, 700, 875));
    }

    @Test
    public void testUpdateResolvedBoundsVerticalPosition_bottom() {
        // Display configured as (1400, 2800).
        assertVerticalPositionForDifferentDisplayConfigsForLandscapeActivity(
                /* letterboxVerticalPositionMultiplier */ 1.0f,
                // At launch.
                /* fixedOrientationLetterbox */ new Rect(0, 2100, 1400, 2800),
                // After 90 degree rotation.
                /* sizeCompatUnscaled */ new Rect(700, 700, 2100, 1400),
                // After the display is resized to (1400, 700).
                /* sizeCompatScaled */ new Rect(0, 1050, 700, 1400));
    }

    @Test
    public void testUpdateResolvedBoundsVerticalPosition_tabletop() {
        // Set up a display in portrait with a fixed-orientation LANDSCAPE app
        setUpDisplaySizeWithApp(1400, 2800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(
                1.0f /*letterboxVerticalPositionMultiplier*/);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        Rect letterboxNoFold = new Rect(0, 2100, 1400, 2800);
        assertEquals(letterboxNoFold, mActivity.getBounds());

        // Make the activity full-screen
        mTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        setFoldablePosture(true /* isHalfFolded */, true /* isTabletop */);

        Rect letterboxHalfFold = new Rect(0, 0, 1400, 700);
        assertEquals(letterboxHalfFold, mActivity.getBounds());

        setFoldablePosture(false /* isHalfFolded */, false /* isTabletop */);

        assertEquals(letterboxNoFold, mActivity.getBounds());
    }

    @Test
    public void testGetFixedOrientationLetterboxAspectRatio_tabletop_centered() {
        // Set up a display in portrait with a fixed-orientation LANDSCAPE app
        setUpDisplaySizeWithApp(1400, 2800);
        mWm.mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(
                LETTERBOX_POSITION_MULTIPLIER_CENTER);
        mActivity.mWmService.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(
                1.0f /*letterboxVerticalPositionMultiplier*/);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        setFoldablePosture(true /* isHalfFolded */, true /* isTabletop */);

        Configuration parentConfig = mActivity.getParent().getConfiguration();

        float actual = mActivity.mLetterboxUiController
                .getFixedOrientationLetterboxAspectRatio(parentConfig);
        float expected = mActivity.mLetterboxUiController.getSplitScreenAspectRatio();

        assertEquals(expected, actual, DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    public void testPortraitAppInTabletop_notSplitScreen() {
        final int dw = 2400;
        setUpDisplaySizeWithApp(dw, 2000);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        final int initialWidth = mActivity.getBounds().width();

        setFoldablePosture(true /* isHalfFolded */, true /* isTabletop */);

        final int finalWidth = mActivity.getBounds().width();
        assertEquals(initialWidth, finalWidth);
        assertNotEquals(finalWidth, getExpectedSplitSize(dw));
    }

    @Test
    public void testUpdateResolvedBoundsHorizontalPosition_bookModeEnabled() {
        // Set up a display in landscape with a fixed-orientation PORTRAIT app
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsAutomaticReachabilityInBookModeEnabled(true);
        mWm.mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(
                1.0f /*letterboxHorizontalPositionMultiplier*/);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        Rect letterboxNoFold = new Rect(2100, 0, 2800, 1400);
        assertEquals(letterboxNoFold, mActivity.getBounds());

        // Make the activity full-screen
        mTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        setFoldablePosture(true /* isHalfFolded */, false /* isTabletop */);

        Rect letterboxHalfFold = new Rect(0, 0, 700, 1400);
        assertEquals(letterboxHalfFold, mActivity.getBounds());

        setFoldablePosture(false /* isHalfFolded */, false /* isTabletop */);

        assertEquals(letterboxNoFold, mActivity.getBounds());
    }

    @Test
    public void testUpdateResolvedBoundsHorizontalPosition_bookModeDisabled_centered() {
        // Set up a display in landscape with a fixed-orientation PORTRAIT app
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mWm.mLetterboxConfiguration.setIsAutomaticReachabilityInBookModeEnabled(false);
        mWm.mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(0.5f);
        prepareUnresizable(mActivity, 1.75f, SCREEN_ORIENTATION_PORTRAIT);

        Rect letterboxNoFold = new Rect(1000, 0, 1800, 1400);
        assertEquals(letterboxNoFold, mActivity.getBounds());

        // Make the activity full-screen
        mTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        // Stay centered and bounds don't change
        setFoldablePosture(true /* isHalfFolded */, false /* isTabletop */);
        assertEquals(letterboxNoFold, mActivity.getBounds());

        setFoldablePosture(false /* isHalfFolded */, false /* isTabletop */);
        assertEquals(letterboxNoFold, mActivity.getBounds());
    }

    private void setFoldablePosture(ActivityRecord activity, boolean isHalfFolded,
            boolean isTabletop) {
        final DisplayRotation r = activity.mDisplayContent.getDisplayRotation();
        doReturn(isHalfFolded).when(r).isDisplaySeparatingHinge();
        doReturn(false).when(r).isDeviceInPosture(any(DeviceState.class), anyBoolean());
        if (isHalfFolded) {
            doReturn(true).when(r)
                    .isDeviceInPosture(DeviceState.HALF_FOLDED, isTabletop);
        }
        activity.recomputeConfiguration();
    }

    private void setFoldablePosture(boolean isHalfFolded, boolean isTabletop) {
        setFoldablePosture(mActivity, isHalfFolded, isTabletop);
    }

    @Test
    public void testUpdateResolvedBoundsPosition_alignToTop() {
        if (Flags.insetsDecoupledConfiguration()) {
            // TODO (b/151861875): Re-enable it. This is disabled temporarily because the config
            //  bounds no longer contains display cutout.
            return;
        }
        final int notchHeight = 100;
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1000, 2800)
                .setNotch(notchHeight)
                .build();
        setUpApp(display);

        // Prepare unresizable activity with max aspect ratio
        prepareUnresizable(mActivity, /* maxAspect */ 1.1f, SCREEN_ORIENTATION_UNSPECIFIED);

        Rect mBounds = new Rect(mActivity.getWindowConfiguration().getBounds());
        Rect appBounds = new Rect(mActivity.getWindowConfiguration().getAppBounds());
        // The insets should be cut for aspect ratio and then added back because the appBounds
        // are aligned to the top of the parentAppBounds
        assertEquals(mBounds, new Rect(0, 0, 1000, 1200));
        assertEquals(appBounds, new Rect(0, notchHeight, 1000, 1200));
    }

    private void assertVerticalPositionForDifferentDisplayConfigsForLandscapeActivity(
            float letterboxVerticalPositionMultiplier, Rect fixedOrientationLetterbox,
            Rect sizeCompatUnscaled, Rect sizeCompatScaled) {
        // Set up a display in portrait and ignoring orientation request.
        setUpDisplaySizeWithApp(1400, 2800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        mActivity.mWmService.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(
                letterboxVerticalPositionMultiplier);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        assertEquals(fixedOrientationLetterbox, mActivity.getBounds());

        // Rotate to put activity in size compat mode.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        assertTrue(mActivity.inSizeCompatMode());
        // Activity is in size compat mode but not scaled.
        assertEquals(sizeCompatUnscaled, mActivity.getBounds());

        // Force activity to scaled down for size compat mode.
        resizeDisplay(mTask.mDisplayContent, 1400, 700);

        assertTrue(mActivity.inSizeCompatMode());
        assertScaled();
        assertEquals(sizeCompatScaled, mActivity.getBounds());
    }

    @Test
    public void testUpdateResolvedBoundsVerticalPosition_activityFillParentHeight() {
        // When activity height equals parent height, multiplier shouldn't have any effect.
        assertVerticalPositionForDifferentDisplayConfigsForPortraitActivity(
                /* letterboxVerticalPositionMultiplier */ 0.0f);
        assertVerticalPositionForDifferentDisplayConfigsForPortraitActivity(
                /* letterboxVerticalPositionMultiplier */ 0.5f);
        assertVerticalPositionForDifferentDisplayConfigsForPortraitActivity(
                /* letterboxVerticalPositionMultiplier */ 1.0f);
    }

    @Test
    public void testAreBoundsLetterboxed_letterboxedForAspectRatio_returnsTrue() {
        setUpDisplaySizeWithApp(1000, 2500);

        assertFalse(mActivity.areBoundsLetterboxed());
        verifyLogAppCompatState(mActivity, APP_COMPAT_STATE_CHANGED__STATE__NOT_LETTERBOXED);

        prepareUnresizable(mActivity, /* maxAspect= */ 2, SCREEN_ORIENTATION_PORTRAIT);
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());
        assertTrue(mActivity.areBoundsLetterboxed());

        verifyLogAppCompatState(mActivity,
                APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_ASPECT_RATIO);

        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);
        verifyLogAppCompatState(mActivity,
                APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_SIZE_COMPAT_MODE);
        rotateDisplay(mActivity.mDisplayContent, ROTATION_0);

        // After returning to the original rotation, bounds are computed in
        // ActivityRecord#resolveSizeCompatModeConfiguration because mCompatDisplayInsets aren't
        // null but activity doesn't enter size compat mode. Checking that areBoundsLetterboxed()
        // still returns true because of the aspect ratio restrictions.
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());
        assertTrue(mActivity.areBoundsLetterboxed());
        verifyLogAppCompatState(mActivity,
                APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_ASPECT_RATIO);

        // After setting the visibility of the activity to false, areBoundsLetterboxed() still
        // returns true but the NOT_VISIBLE App Compat state is logged.
        mActivity.setVisibility(false);
        assertTrue(mActivity.areBoundsLetterboxed());
        verifyLogAppCompatState(mActivity, APP_COMPAT_STATE_CHANGED__STATE__NOT_VISIBLE);
        mActivity.setVisibility(true);
        assertTrue(mActivity.areBoundsLetterboxed());
        verifyLogAppCompatState(mActivity,
                APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_ASPECT_RATIO);
    }

    @Test
    public void testAreBoundsLetterboxed_letterboxedForFixedOrientation_returnsTrue() {
        setUpDisplaySizeWithApp(2500, 1000);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        assertFalse(mActivity.areBoundsLetterboxed());
        verifyLogAppCompatState(mActivity, APP_COMPAT_STATE_CHANGED__STATE__NOT_LETTERBOXED);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(mActivity.inSizeCompatMode());
        assertTrue(mActivity.areBoundsLetterboxed());
        verifyLogAppCompatState(mActivity,
                APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_FIXED_ORIENTATION);
    }

    @Test
    public void testAreBoundsLetterboxed_letterboxedForSizeCompat_returnsTrue() {
        setUpDisplaySizeWithApp(1000, 2500);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        assertFalse(mActivity.areBoundsLetterboxed());
        verifyLogAppCompatState(mActivity, APP_COMPAT_STATE_CHANGED__STATE__NOT_LETTERBOXED);

        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertTrue(mActivity.inSizeCompatMode());
        assertTrue(mActivity.areBoundsLetterboxed());
        verifyLogAppCompatState(mActivity,
                APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_SIZE_COMPAT_MODE);
    }

    @Test
    public void testIsEligibleForLetterboxEducation_educationNotEnabled_returnsFalse() {
        setUpDisplaySizeWithApp(2500, 1000);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setIsEducationEnabled(false);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        assertFalse(mActivity.isEligibleForLetterboxEducation());
    }

    @Test
    public void testIsEligibleForLetterboxEducation_notEligibleForFixedOrientation_returnsFalse() {
        setUpDisplaySizeWithApp(1000, 2500);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setIsEducationEnabled(true);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        assertFalse(mActivity.isEligibleForLetterboxEducation());
    }

    @Test
    public void testIsEligibleForLetterboxEducation_windowingModeMultiWindow_returnsFalse() {
        // Support non resizable in multi window
        mAtm.mDevEnableNonResizableMultiWindow = true;
        setUpDisplaySizeWithApp(1000, 1200);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setIsEducationEnabled(true);
        final TestSplitOrganizer organizer =
                new TestSplitOrganizer(mAtm, mActivity.getDisplayContent());

        // Non-resizable landscape activity
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);
        final Rect originalBounds = new Rect(mActivity.getBounds());

        // Move activity to split screen which takes half of the screen.
        mTask.reparent(organizer.mPrimary, POSITION_TOP,
                false /*moveParents*/, "test");
        organizer.mPrimary.setBounds(0, 0, 1000, 600);

        assertFalse(mActivity.isEligibleForLetterboxEducation());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, mActivity.getWindowingMode());
    }

    @Test
    public void testIsEligibleForLetterboxEducation_fixedOrientationLandscape_returnsFalse() {
        setUpDisplaySizeWithApp(1000, 2500);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setIsEducationEnabled(true);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);

        assertFalse(mActivity.isEligibleForLetterboxEducation());
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
    }

    @Test
    public void testIsEligibleForLetterboxEducation_hasStartingWindow_returnsFalseUntilRemoved() {
        setUpDisplaySizeWithApp(2500, 1000);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setIsEducationEnabled(true);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);
        mActivity.mStartingData = mock(StartingData.class);
        mActivity.attachStartingWindow(
                createWindowState(new WindowManager.LayoutParams(TYPE_APPLICATION_STARTING),
                        mActivity));

        assertFalse(mActivity.isEligibleForLetterboxEducation());

        // Verify that after removing the starting window isEligibleForLetterboxEducation returns
        // true and mTask.dispatchTaskInfoChangedIfNeeded is called.
        spyOn(mTask);
        mActivity.removeStartingWindow();

        assertTrue(mActivity.isEligibleForLetterboxEducation());
        verify(mTask).dispatchTaskInfoChangedIfNeeded(true);
    }

    @Test
    public void testIsEligibleForLetterboxEducation_hasStartingWindowAndEducationNotEnabled() {
        setUpDisplaySizeWithApp(2500, 1000);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setIsEducationEnabled(false);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);
        mActivity.mStartingData = mock(StartingData.class);
        mActivity.attachStartingWindow(
                createWindowState(new WindowManager.LayoutParams(TYPE_APPLICATION_STARTING),
                        mActivity));

        assertFalse(mActivity.isEligibleForLetterboxEducation());

        // Verify that after removing the starting window isEligibleForLetterboxEducation still
        // returns false and mTask.dispatchTaskInfoChangedIfNeeded isn't called.
        spyOn(mTask);
        mActivity.removeStartingWindow();

        assertFalse(mActivity.isEligibleForLetterboxEducation());
        verify(mTask, never()).dispatchTaskInfoChangedIfNeeded(true);
    }

    @Test
    public void testIsEligibleForLetterboxEducation_letterboxedForFixedOrientation_returnsTrue() {
        setUpDisplaySizeWithApp(2500, 1000);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setIsEducationEnabled(true);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        assertTrue(mActivity.isEligibleForLetterboxEducation());
        assertTrue(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
    }

    @Test
    public void testIsEligibleForLetterboxEducation_sizeCompatAndEligibleForFixedOrientation() {
        setUpDisplaySizeWithApp(1000, 2500);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mActivity.mWmService.mLetterboxConfiguration.setIsEducationEnabled(true);

        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        assertTrue(mActivity.isEligibleForLetterboxEducation());
        assertFalse(mActivity.isLetterboxedForFixedOrientationAndAspectRatio());
        assertTrue(mActivity.inSizeCompatMode());
    }

    @Test
    public void testTopActivityInSizeCompatMode_pausedAndInSizeCompatMode_returnsTrue() {
        setUpDisplaySizeWithApp(1000, 2500);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        spyOn(mActivity);
        doReturn(mTask).when(mActivity).getOrganizedTask();
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);
        mActivity.setState(PAUSED, "test");

        assertTrue(mActivity.inSizeCompatMode());
        assertEquals(mActivity.getState(), PAUSED);
        assertTrue(mActivity.isVisible());
        assertTrue(mTask.getTaskInfo().appCompatTaskInfo.topActivityInSizeCompat);
    }

    /**
     * Tests that all three paths in which aspect ratio logic can be applied yield the same
     * result, which is that aspect ratio is respected on app bounds. The three paths are
     * fixed orientation, no fixed orientation but fixed aspect ratio, and size compat mode.
     */
    @Test
    public void testAllAspectRatioLogicConsistent() {
        // Create display that has all stable insets and does not rotate. Make sure that status bar
        // height is greater than notch height so that stable bounds do not equal app bounds.
        final int notchHeight = 75;
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1080, 600)
                .setSystemDecorations(true).setNotch(notchHeight)
                .setStatusBarHeight(notchHeight + 20).setCanRotate(false).build();

        // Create task on test display.
        final Task task = new TaskBuilder(mSupervisor).setDisplay(display).build();

        // Target min aspect ratio must be larger than parent aspect ratio to be applied.
        final float targetMinAspectRatio = 3.0f;

        // Create fixed portait activity with min aspect ratio greater than parent aspect ratio.
        final ActivityRecord fixedOrientationActivity = new ActivityBuilder(mAtm)
                .setTask(task).setScreenOrientation(SCREEN_ORIENTATION_PORTRAIT)
                .setMinAspectRatio(targetMinAspectRatio).build();
        final Rect fixedOrientationAppBounds = new Rect(fixedOrientationActivity.getConfiguration()
                .windowConfiguration.getAppBounds());

        // Create activity with no fixed orientation and min aspect ratio greater than parent aspect
        // ratio.
        final ActivityRecord minAspectRatioActivity = new ActivityBuilder(mAtm).setTask(task)
                .setMinAspectRatio(targetMinAspectRatio).build();
        final Rect minAspectRatioAppBounds = new Rect(minAspectRatioActivity.getConfiguration()
                .windowConfiguration.getAppBounds());

        // Create unresizeable fixed portait activity with min aspect ratio greater than parent
        // aspect ratio.
        final ActivityRecord sizeCompatActivity = new ActivityBuilder(mAtm)
                .setTask(task).setResizeMode(RESIZE_MODE_UNRESIZEABLE)
                .setScreenOrientation(SCREEN_ORIENTATION_PORTRAIT)
                .setMinAspectRatio(targetMinAspectRatio).build();
        // Resize display running unresizeable activity to make it enter size compat mode.
        resizeDisplay(display, 1800, 1000);
        final Rect sizeCompatAppBounds = new Rect(sizeCompatActivity.getConfiguration()
                .windowConfiguration.getAppBounds());

        // Check that aspect ratio of app bounds is equal to the min aspect ratio.
        assertEquals(targetMinAspectRatio, ActivityRecord
                .computeAspectRatio(fixedOrientationAppBounds), DELTA_ASPECT_RATIO_TOLERANCE);
        assertEquals(targetMinAspectRatio, ActivityRecord
                .computeAspectRatio(minAspectRatioAppBounds), DELTA_ASPECT_RATIO_TOLERANCE);
        assertEquals(targetMinAspectRatio, ActivityRecord
                .computeAspectRatio(sizeCompatAppBounds), DELTA_ASPECT_RATIO_TOLERANCE);
    }

    @Test
    public void testClearSizeCompat_resetOverrideConfig() {
        final int origDensity = 480;
        final int newDensity = 520;
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 600, 800)
                .setDensityDpi(origDensity)
                .build();
        setUpApp(display);
        prepareUnresizable(mActivity, -1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);

        // Activity should enter size compat with old density after display density change.
        display.setForcedDensity(newDensity, UserHandle.USER_CURRENT);

        assertScaled();
        assertEquals(origDensity, mActivity.getConfiguration().densityDpi);

        // Activity should exit size compat with new density.
        mActivity.clearSizeCompatMode();

        assertFitted();
        assertEquals(newDensity, mActivity.getConfiguration().densityDpi);
    }

    @Test
    public void testShouldSendFakeFocus_compatFakeFocusEnabled() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setOnTop(true)
                // Set the component to be that of the test class in order to enable compat changes
                .setComponent(ComponentName.createRelative(mContext,
                        com.android.server.wm.SizeCompatTests.class.getName()))
                .build();
        final Task task = activity.getTask();
        spyOn(activity.mLetterboxUiController);
        doReturn(true).when(activity.mLetterboxUiController).shouldSendFakeFocus();

        task.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertTrue(activity.shouldSendCompatFakeFocus());

        task.setWindowingMode(WINDOWING_MODE_PINNED);
        assertFalse(activity.shouldSendCompatFakeFocus());

        task.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertFalse(activity.shouldSendCompatFakeFocus());
    }

    @Test
    public void testShouldSendFakeFocus_compatFakeFocusDisabled() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setOnTop(true)
                // Set the component to be that of the test class in order to enable compat changes
                .setComponent(ComponentName.createRelative(mContext,
                        com.android.server.wm.SizeCompatTests.class.getName()))
                .build();
        final Task task = activity.getTask();
        spyOn(activity.mLetterboxUiController);
        doReturn(false).when(activity.mLetterboxUiController).shouldSendFakeFocus();

        task.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertFalse(activity.shouldSendCompatFakeFocus());

        task.setWindowingMode(WINDOWING_MODE_PINNED);
        assertFalse(activity.shouldSendCompatFakeFocus());

        task.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertFalse(activity.shouldSendCompatFakeFocus());
    }

    private void setUpAllowThinLetterboxed(boolean thinLetterboxAllowed) {
        spyOn(mActivity.mLetterboxUiController);
        doReturn(thinLetterboxAllowed).when(mActivity.mLetterboxUiController)
                .allowVerticalReachabilityForThinLetterbox();
        doReturn(thinLetterboxAllowed).when(mActivity.mLetterboxUiController)
                .allowHorizontalReachabilityForThinLetterbox();
    }

    private int getExpectedSplitSize(int dimensionToSplit) {
        int dividerWindowWidth =
                mActivity.mWmService.mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.docked_stack_divider_thickness);
        int dividerInsets =
                mActivity.mWmService.mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.docked_stack_divider_insets);
        return (dimensionToSplit - (dividerWindowWidth - dividerInsets * 2)) / 2;
    }

    private void assertHorizontalPositionForDifferentDisplayConfigsForLandscapeActivity(
            float letterboxHorizontalPositionMultiplier) {
        // Set up a display in landscape and ignoring orientation request.
        setUpDisplaySizeWithApp(2800, 1400);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        mActivity.mWmService.mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(
                letterboxHorizontalPositionMultiplier);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_LANDSCAPE);
        assertFitted();
        // Rotate to put activity in size compat mode.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);
        assertTrue(mActivity.inSizeCompatMode());
        // Activity is in size compat mode but not scaled.
        assertEquals(new Rect(0, 0, 1400, 700), mActivity.getBounds());
    }

    private void assertVerticalPositionForDifferentDisplayConfigsForPortraitActivity(
            float letterboxVerticalPositionMultiplier) {
        // Set up a display in portrait and ignoring orientation request.
        setUpDisplaySizeWithApp(1400, 2800);
        mActivity.mDisplayContent.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        mActivity.mWmService.mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(
                letterboxVerticalPositionMultiplier);
        prepareUnresizable(mActivity, SCREEN_ORIENTATION_PORTRAIT);

        assertFitted();

        // Rotate to put activity in size compat mode.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);

        assertTrue(mActivity.inSizeCompatMode());
        // Activity is in size compat mode but not scaled.
        assertEquals(new Rect(1050, 0, 1750, 1400), mActivity.getBounds());
    }

    private WindowState addWindowToActivity(ActivityRecord activity) {
        return addWindowToActivity(activity, WindowManager.LayoutParams.TYPE_BASE_APPLICATION);
    }

    private WindowState addWindowToActivity(ActivityRecord activity, int type) {
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = type;
        params.setFitInsetsSides(0);
        params.setFitInsetsTypes(0);
        final TestWindowState w = new TestWindowState(
                activity.mWmService, getTestSession(), new TestIWindow(), params, activity);
        makeWindowVisible(w);
        w.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        activity.addWindow(w);
        return w;
    }

    private TestWindowState addStatusBar(DisplayContent displayContent) {
        final TestWindowToken token = createTestWindowToken(
                TYPE_STATUS_BAR, displayContent);
        final WindowManager.LayoutParams attrs =
                new WindowManager.LayoutParams(TYPE_STATUS_BAR);
        final Binder owner = new Binder();
        attrs.gravity = android.view.Gravity.TOP;
        attrs.height = STATUS_BAR_HEIGHT;
        attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        attrs.setFitInsetsTypes(0 /* types */);
        attrs.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.statusBars()),
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.tappableElement()),
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.mandatorySystemGestures())
        };
        final TestWindowState statusBar = new TestWindowState(
                displayContent.mWmService, getTestSession(), new TestIWindow(), attrs, token);
        token.addWindow(statusBar);
        statusBar.setRequestedSize(displayContent.mBaseDisplayWidth,
                SystemBarUtils.getStatusBarHeight(displayContent.getDisplayUiContext()));

        final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
        displayPolicy.addWindowLw(statusBar, attrs);
        displayPolicy.layoutWindowLw(statusBar, null, displayContent.mDisplayFrames);
        return statusBar;
    }

    /**
     * Returns an ActivityRecord instance with the specified attributes on the same task. By
     * constructing the ActivityRecord, forces {@link ActivityInfo} to be loaded with the compat
     * config settings.
     */
    private ActivityRecord buildActivityRecord(boolean supportsSizeChanges, int resizeMode,
            @ScreenOrientation int screenOrientation) {
        return new ActivityBuilder(mAtm)
                .setTask(mTask)
                .setResizeMode(resizeMode)
                .setSupportsSizeChanges(supportsSizeChanges)
                .setScreenOrientation(screenOrientation)
                .setComponent(ComponentName.createRelative(mContext,
                        SizeCompatTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .build();
    }

    static void prepareMinAspectRatio(ActivityRecord activity, float minAspect,
            int screenOrientation) {
        prepareLimitedBounds(activity, -1 /* maxAspect */, minAspect, screenOrientation,
                true /* isUnresizable */);
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

    static void prepareLimitedBounds(ActivityRecord activity, float maxAspect,
            int screenOrientation, boolean isUnresizable) {
        prepareLimitedBounds(activity, maxAspect, -1 /* minAspect */, screenOrientation,
                isUnresizable);
    }

    /**
     * Setups {@link #mActivity} with restriction on its bounds, such as maxAspect, minAspect,
     * fixed orientation, and/or whether it is resizable.
     */
    static void prepareLimitedBounds(ActivityRecord activity, float maxAspect, float minAspect,
            int screenOrientation, boolean isUnresizable) {
        activity.info.resizeMode = isUnresizable
                ? RESIZE_MODE_UNRESIZEABLE
                : RESIZE_MODE_RESIZEABLE;
        final Task task = activity.getTask();
        if (task != null) {
            // Update the Task resize value as activity will follow the task.
            task.mResizeMode = activity.info.resizeMode;
            task.getRootActivity().info.resizeMode = activity.info.resizeMode;
        }
        activity.setVisibleRequested(true);
        if (maxAspect >= 0) {
            activity.info.setMaxAspectRatio(maxAspect);
        }
        if (minAspect >= 0) {
            activity.info.setMinAspectRatio(minAspect);
        }
        if (screenOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
            activity.info.screenOrientation = screenOrientation;
            activity.setRequestedOrientation(screenOrientation);
        }
        // Make sure to use the provided configuration to construct the size compat fields.
        activity.clearSizeCompatMode();
        activity.ensureActivityConfiguration();
        // Make sure the display configuration reflects the change of activity.
        if (activity.mDisplayContent.updateOrientation()) {
            activity.mDisplayContent.sendNewConfiguration();
        }
    }

    /** Asserts that the size of activity is larger than its parent so it is scaling. */
    private void assertScaled() {
        assertTrue(mActivity.inSizeCompatMode());
        assertNotEquals(1f, mActivity.getCompatScale(), 0.0001f /* delta */);
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
        assertActivityMaxBoundsSandboxed(mActivity);
    }

    /**
     * Asserts activity-level letterbox or size compat mode size compat mode on the specified
     * activity, so activity max bounds are sandboxed.
     */
    private void assertActivityMaxBoundsSandboxed(ActivityRecord activity) {
        // Activity max bounds are sandboxed due to size compat mode.
        assertThat(activity.getConfiguration().windowConfiguration.getMaxBounds())
                .isEqualTo(activity.getWindowConfiguration().getBounds());
    }

    private void verifyLogAppCompatState(ActivityRecord activity, int state) {
        verify(mActivityMetricsLogger, atLeastOnce()).logAppCompatState(
                argThat(r -> activity == r && r.getAppCompatState() == state));
    }

    static Configuration rotateDisplay(DisplayContent display, int rotation) {
        final Configuration c = new Configuration();
        display.getDisplayRotation().setRotation(rotation);
        display.computeScreenConfiguration(c);
        display.onRequestedOverrideConfigurationChanged(c);
        return c;
    }

    private static void setNeverConstrainDisplayApisFlag(@Nullable String value,
            boolean makeDefault) {
        DeviceConfig.setProperty(NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                CONFIG_NEVER_CONSTRAIN_DISPLAY_APIS, value, makeDefault);
    }

    private static void setNeverConstrainDisplayApisAllPackagesFlag(boolean value,
            boolean makeDefault) {
        DeviceConfig.setProperty(NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                CONFIG_NEVER_CONSTRAIN_DISPLAY_APIS_ALL_PACKAGES, String.valueOf(value),
                makeDefault);
    }

    private static void setAlwaysConstrainDisplayApisFlag(@Nullable String value,
            boolean makeDefault) {
        DeviceConfig.setProperty(NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                CONFIG_ALWAYS_CONSTRAIN_DISPLAY_APIS, value, makeDefault);
    }
}
