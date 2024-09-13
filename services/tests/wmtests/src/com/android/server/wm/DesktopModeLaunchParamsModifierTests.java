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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL_VALUE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_16_9;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_3_2;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_4_3;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_DISPLAY_SIZE;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_SPLIT_SCREEN;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.util.DisplayMetrics.DENSITY_DEFAULT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.DesktopModeBoundsCalculator.DESKTOP_MODE_INITIAL_BOUNDS_SCALE;
import static com.android.server.wm.DesktopModeBoundsCalculator.DESKTOP_MODE_LANDSCAPE_APP_PADDING;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_DISPLAY;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import android.app.ActivityOptions;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.Gravity;

import androidx.test.filters.SmallTest;

import com.android.window.flags.Flags;

import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

/**
 * Tests for desktop mode task bounds.
 *
 * Build/Install/Run:
 * atest WmTests:DesktopModeLaunchParamsModifierTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DesktopModeLaunchParamsModifierTests extends
        LaunchParamsModifierTestsBase<DesktopModeLaunchParamsModifier> {
    private static final Rect LANDSCAPE_DISPLAY_BOUNDS = new Rect(0, 0, 2560, 1600);
    private static final Rect PORTRAIT_DISPLAY_BOUNDS = new Rect(0, 0, 1600, 2560);
    private static final float LETTERBOX_ASPECT_RATIO = 1.3f;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Before
    public void setUp() throws Exception {
        mActivity = new ActivityBuilder(mAtm).build();
        mCurrent = new LaunchParamsController.LaunchParams();
        mCurrent.reset();
        mResult = new LaunchParamsController.LaunchParams();
        mResult.reset();

        mTarget = new DesktopModeLaunchParamsModifier(mContext);
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfDesktopWindowingIsDisabled() {
        setupDesktopModeLaunchParamsModifier();

        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(null).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfDesktopWindowingIsEnabledOnUnsupportedDevice() {
        setupDesktopModeLaunchParamsModifier(/*isDesktopModeSupported=*/ false,
                /*enforceDeviceRestrictions=*/ true);

        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(null).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsContinueIfDesktopWindowingIsEnabledAndUnsupportedDeviceOverridden() {
        setupDesktopModeLaunchParamsModifier(/*isDesktopModeSupported=*/ true,
                /*enforceDeviceRestrictions=*/ false);

        final Task task = new TaskBuilder(mSupervisor).build();
        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfTaskIsNull() {
        setupDesktopModeLaunchParamsModifier();

        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(null).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfNotBoundsPhase() {
        setupDesktopModeLaunchParamsModifier();

        final Task task = new TaskBuilder(mSupervisor).build();
        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(task).setPhase(
                PHASE_DISPLAY).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfTaskNotUsingActivityTypeStandardOrUndefined() {
        setupDesktopModeLaunchParamsModifier();

        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_ASSISTANT).build();
        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(task).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsContinueIfTaskUsingActivityTypeStandard() {
        setupDesktopModeLaunchParamsModifier();

        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).build();
        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsContinueIfTaskUsingActivityTypeUndefined() {
        setupDesktopModeLaunchParamsModifier();

        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_UNDEFINED).build();
        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfCurrentParamsHasBounds() {
        setupDesktopModeLaunchParamsModifier();

        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).build();
        mCurrent.mBounds.set(/* left */ 0, /* top */ 0, /* right */ 100, /* bottom */ 100);
        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(task).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @DisableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUsesDesiredBoundsIfEmptyLayoutAndActivityOptionsBounds() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();

        final int desiredWidth =
                (int) (DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultLandscapeBounds_landscapeDevice_resizable_undefinedOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);

        final int desiredWidth =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultLandscapeBounds_landscapeDevice_resizable_landscapeOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_LANDSCAPE,
                task, /* ignoreOrientationRequest */ true);

        final int desiredWidth =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultLandscapeBounds_landscapeDevice_userFullscreenOverride() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_LANDSCAPE,
                task, /* ignoreOrientationRequest */ true);

        spyOn(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides());
        doReturn(true).when(
                        mActivity.mAppCompatController.getAppCompatAspectRatioOverrides())
                .isUserFullscreenOverrideEnabled();

        final int desiredWidth =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultLandscapeBounds_landscapeDevice_systemFullscreenOverride() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_LANDSCAPE,
                task, /* ignoreOrientationRequest */ true);

        spyOn(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides());
        doReturn(true).when(
                        mActivity.mAppCompatController.getAppCompatAspectRatioOverrides())
                .isSystemOverrideToFullscreenEnabled();

        final int desiredWidth =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testResizablePortraitBounds_landscapeDevice_resizable_portraitOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ true);

        spyOn(activity.mAppCompatController.getDesktopAppCompatAspectRatioPolicy());
        doReturn(LETTERBOX_ASPECT_RATIO).when(activity.mAppCompatController
                .getDesktopAppCompatAspectRatioPolicy()).calculateAspectRatio(any());

        final int desiredWidth =
                (int) ((LANDSCAPE_DISPLAY_BOUNDS.height() / LETTERBOX_ASPECT_RATIO) + 0.5f);
        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL})
    public void testSmallAspectRatioOverride_landscapeDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ false);

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth =
                (int) (desiredHeight / OVERRIDE_MIN_ASPECT_RATIO_SMALL_VALUE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testMediumAspectRatioOverride_landscapeDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ false);

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth =
                (int) (desiredHeight / OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE})
    public void testLargeAspectRatioOverride_landscapeDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ false);

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth =
                (int) (desiredHeight / OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    public void testSplitScreenAspectRatioOverride_landscapeDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ false);

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth =
                (int) (desiredHeight / activity.mAppCompatController
                        .getAppCompatAspectRatioOverrides().getSplitScreenAspectRatio());

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL})
    public void testSmallAspectRatioOverride_portraitDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ false);
        // Mock desired aspect ratio so min override can take effect.
        setDesiredAspectRatio(activity, /* aspectRatio */ 1f);

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (desiredWidth * OVERRIDE_MIN_ASPECT_RATIO_SMALL_VALUE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testMediumAspectRatioOverride_portraitDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ false);
        // Mock desired aspect ratio so min override can take effect.
        setDesiredAspectRatio(activity, /* aspectRatio */ 1f);

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (desiredWidth * OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE})
    public void testLargeAspectRatioOverride_portraitDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ false);
        // Mock desired aspect ratio so min override can take effect.
        setDesiredAspectRatio(activity, /* aspectRatio */ 1f);

        final int desiredHeight =
                (int) (PORTRAIT_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth = (int) (desiredHeight / OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    public void testSplitScreenAspectRatioOverride_portraitDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ false);
        // Mock desired aspect ratio so min override can take effect.
        setDesiredAspectRatio(activity, /* aspectRatio */ 1f);

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (desiredWidth * activity.mAppCompatController
                .getAppCompatAspectRatioOverrides().getSplitScreenAspectRatio());

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatio32Override_landscapeDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValue3_2 = 3 / 2f;
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_3_2,
                userAspectRatioOverrideValue3_2);

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth = (int) (desiredHeight / userAspectRatioOverrideValue3_2);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatio43Override_landscapeDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValue4_3 = 4 / 3f;
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_4_3,
                userAspectRatioOverrideValue4_3);

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth = (int) (desiredHeight / userAspectRatioOverrideValue4_3);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatio169Override_landscapeDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValue16_9 = 16 / 9f;
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_16_9,
                userAspectRatioOverrideValue16_9);

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth = (int) (desiredHeight / userAspectRatioOverrideValue16_9);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatioSplitScreenOverride_landscapeDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValueSplitScreen = activity.mAppCompatController
                .getAppCompatAspectRatioOverrides().getSplitScreenAspectRatio();
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_SPLIT_SCREEN,
                userAspectRatioOverrideValueSplitScreen);

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth = (int) (desiredHeight / userAspectRatioOverrideValueSplitScreen);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatioDisplaySizeOverride_landscapeDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValueDisplaySize = activity.mAppCompatController
                .getAppCompatAspectRatioOverrides().getDisplaySizeMinAspectRatio();
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_DISPLAY_SIZE,
                userAspectRatioOverrideValueDisplaySize);

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth = (int) (desiredHeight / userAspectRatioOverrideValueDisplaySize);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatio32Override_portraitDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValue3_2 = 3 / 2f;
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_3_2,
                userAspectRatioOverrideValue3_2);

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (desiredWidth * userAspectRatioOverrideValue3_2);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatio43Override_portraitDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValue4_3 = 4 / 3f;
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_4_3,
                userAspectRatioOverrideValue4_3);

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (desiredWidth * userAspectRatioOverrideValue4_3);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatio169Override_portraitDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValue16_9 = 16 / 9f;
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_16_9,
                userAspectRatioOverrideValue16_9);

        final int desiredHeight =
                (int) (PORTRAIT_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth = (int) (desiredHeight / userAspectRatioOverrideValue16_9);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatioSplitScreenOverride_portraitDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValueSplitScreen = activity.mAppCompatController
                .getAppCompatAspectRatioOverrides().getSplitScreenAspectRatio();
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_SPLIT_SCREEN,
                userAspectRatioOverrideValueSplitScreen);

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (desiredWidth * userAspectRatioOverrideValueSplitScreen);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUserAspectRatioDisplaySizeOverride_portraitDevice() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);
        final float userAspectRatioOverrideValueDisplaySize = activity.mAppCompatController
                .getAppCompatAspectRatioOverrides().getDisplaySizeMinAspectRatio();
        applyUserMinAspectRatioOverride(activity, USER_MIN_ASPECT_RATIO_DISPLAY_SIZE,
                userAspectRatioOverrideValueDisplaySize);

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (desiredWidth * userAspectRatioOverrideValueDisplaySize);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultLandscapeBounds_landscapeDevice_unResizable_landscapeOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ false);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_LANDSCAPE,
                task, /* ignoreOrientationRequest */ true);


        final int desiredWidth =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUnResizablePortraitBounds_landscapeDevice_unResizable_portraitOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_LANDSCAPE,
                LANDSCAPE_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ false);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ true);

        spyOn(activity.mAppCompatController.getDesktopAppCompatAspectRatioPolicy());
        doReturn(LETTERBOX_ASPECT_RATIO).when(activity.mAppCompatController
                .getDesktopAppCompatAspectRatioPolicy()).calculateAspectRatio(any());

        final int desiredHeight =
                (int) (LANDSCAPE_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredWidth = (int) (desiredHeight / LETTERBOX_ASPECT_RATIO);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultPortraitBounds_portraitDevice_resizable_undefinedOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_UNSPECIFIED,
                task, /* ignoreOrientationRequest */ true);

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (PORTRAIT_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultPortraitBounds_portraitDevice_resizable_portraitOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ true);

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (PORTRAIT_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultPortraitBounds_portraitDevice_userFullscreenOverride() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ true);

        spyOn(activity.mAppCompatController.getAppCompatAspectRatioOverrides());
        doReturn(true).when(
                        activity.mAppCompatController.getAppCompatAspectRatioOverrides())
                .isUserFullscreenOverrideEnabled();

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (PORTRAIT_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultPortraitBounds_portraitDevice_systemFullscreenOverride() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ true);

        spyOn(activity.mAppCompatController.getAppCompatAspectRatioOverrides());
        doReturn(true).when(
                        activity.mAppCompatController.getAppCompatAspectRatioOverrides())
                .isSystemOverrideToFullscreenEnabled();

        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (PORTRAIT_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testResizableLandscapeBounds_portraitDevice_resizable_landscapeOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ true);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_LANDSCAPE,
                task, /* ignoreOrientationRequest */ true);

        spyOn(activity.mAppCompatController.getDesktopAppCompatAspectRatioPolicy());
        doReturn(LETTERBOX_ASPECT_RATIO).when(activity.mAppCompatController
                .getDesktopAppCompatAspectRatioPolicy()).calculateAspectRatio(any());

        final int desiredWidth = PORTRAIT_DISPLAY_BOUNDS.width()
                - (DESKTOP_MODE_LANDSCAPE_APP_PADDING * 2);
        final int desiredHeight = (int)
                ((PORTRAIT_DISPLAY_BOUNDS.width() / LETTERBOX_ASPECT_RATIO) + 0.5f);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void testDefaultPortraitBounds_portraitDevice_unResizable_portraitOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ false);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_PORTRAIT,
                task, /* ignoreOrientationRequest */ true);


        final int desiredWidth =
                (int) (PORTRAIT_DISPLAY_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (PORTRAIT_DISPLAY_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void testUnResizableLandscapeBounds_portraitDevice_unResizable_landscapeOrientation() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createDisplayContent(ORIENTATION_PORTRAIT,
                PORTRAIT_DISPLAY_BOUNDS);
        final Task task = createTask(display, /* isResizeable */ false);
        final ActivityRecord activity = createActivity(display, SCREEN_ORIENTATION_LANDSCAPE,
                task, /* ignoreOrientationRequest */ true);

        spyOn(activity.mAppCompatController.getDesktopAppCompatAspectRatioPolicy());
        doReturn(LETTERBOX_ASPECT_RATIO).when(activity.mAppCompatController
                .getDesktopAppCompatAspectRatioPolicy()).calculateAspectRatio(any());

        final int desiredWidth = PORTRAIT_DISPLAY_BOUNDS.width()
                - (DESKTOP_MODE_LANDSCAPE_APP_PADDING * 2);
        final int desiredHeight = (int) (desiredWidth / LETTERBOX_ASPECT_RATIO);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setActivity(activity).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyActivityOptionsBounds() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchBounds(new Rect(0, 0, DISPLAY_BOUNDS.width(), DISPLAY_BOUNDS.height()));

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setTask(task).setOptions(options).calculate());
        assertEquals(DISPLAY_BOUNDS.width(), mResult.mBounds.width());
        assertEquals(DISPLAY_BOUNDS.height(), mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBounds_CenterToDisplay() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder().setWidth(120)
                .setHeight(80).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(layout)
                .calculate());
        assertEquals(new Rect(800, 400, 920, 480), mResult.mBounds);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBounds_LeftGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder().setWidth(120)
                .setHeight(80).setGravity(Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(layout)
                .calculate());
        assertEquals(new Rect(100, 400, 220, 480), mResult.mBounds);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBounds_TopGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder().setWidth(120)
                .setHeight(80).setGravity(Gravity.TOP).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(layout)
                .calculate());
        assertEquals(new Rect(800, 200, 920, 280), mResult.mBounds);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBounds_TopLeftGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder().setWidth(120)
                .setHeight(80).setGravity(Gravity.TOP | Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(layout)
                .calculate());
        assertEquals(new Rect(100, 200, 220, 280), mResult.mBounds);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBounds_RightGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder().setWidth(120)
                .setHeight(80).setGravity(Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(layout)
                .calculate());
        assertEquals(new Rect(1500, 400, 1620, 480), mResult.mBounds);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBounds_BottomGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.BOTTOM).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(layout)
                .calculate());
        assertEquals(new Rect(800, 600, 920, 680), mResult.mBounds);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBounds_RightBottomGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.BOTTOM | Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(layout)
                .calculate());
        assertEquals(new Rect(1500, 600, 1620, 680), mResult.mBounds);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutFractionBounds() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidthFraction(0.125f).setHeightFraction(0.1f).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task)
                .setLayout(layout).calculate());
        assertEquals(new Rect(765, 416, 955, 464), mResult.mBounds);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_LeftGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(
                layout).calculate());
        assertEquals(DISPLAY_STABLE_BOUNDS.left, mResult.mBounds.left);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_TopGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder().setGravity(Gravity.TOP)
                .build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(
                layout).calculate());
        assertEquals(DISPLAY_STABLE_BOUNDS.top, mResult.mBounds.top);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_TopLeftGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.TOP | Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(
                layout).calculate());
        assertEquals(DISPLAY_STABLE_BOUNDS.left, mResult.mBounds.left);
        assertEquals(DISPLAY_STABLE_BOUNDS.top, mResult.mBounds.top);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_RightGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder().setGravity(Gravity.RIGHT)
                .build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(
                layout).calculate());
        assertEquals(DISPLAY_STABLE_BOUNDS.right, mResult.mBounds.right);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_BottomGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.BOTTOM).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(
                layout).calculate());
        assertEquals(DISPLAY_STABLE_BOUNDS.bottom, mResult.mBounds.bottom);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_BottomRightGravity() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.BOTTOM | Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).setLayout(
                layout).calculate());

        assertEquals(DISPLAY_STABLE_BOUNDS.right, mResult.mBounds.right);
        assertEquals(DISPLAY_STABLE_BOUNDS.bottom, mResult.mBounds.bottom);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testUsesDisplayAreaAndWindowingModeFromSource() {
        setupDesktopModeLaunchParamsModifier();

        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).build();
        TaskDisplayArea mockTaskDisplayArea = mock(TaskDisplayArea.class);
        mCurrent.mPreferredTaskDisplayArea = mockTaskDisplayArea;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).calculate());
        assertEquals(mockTaskDisplayArea, mResult.mPreferredTaskDisplayArea);
        assertEquals(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode);
    }

    private Task createTask(DisplayContent display, Boolean isResizeable) {
        final int resizeMode = isResizeable ? RESIZE_MODE_RESIZEABLE
                : RESIZE_MODE_UNRESIZEABLE;
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();
        task.setResizeMode(resizeMode);
        return task;
    }

    private ActivityRecord createActivity(DisplayContent display, int orientation, Task task,
            boolean ignoreOrientationRequest) {
        final ActivityRecord activity = new ActivityBuilder(task.mAtmService)
                .setTask(task)
                .setComponent(ComponentName.createRelative(task.mAtmService.mContext,
                        DesktopModeLaunchParamsModifierTests.class.getName()))
                .setUid(android.os.Process.myUid())
                .setScreenOrientation(orientation)
                .setOnTop(true).build();
        activity.onDisplayChanged(display);
        activity.setOccludesParent(true);
        activity.setVisible(true);
        activity.setVisibleRequested(true);
        activity.mDisplayContent.setIgnoreOrientationRequest(ignoreOrientationRequest);

        return activity;
    }

    private void setDesiredAspectRatio(ActivityRecord activity, float aspectRatio) {
        final DesktopAppCompatAspectRatioPolicy desktopAppCompatAspectRatioPolicy =
                activity.mAppCompatController.getDesktopAppCompatAspectRatioPolicy();
        spyOn(desktopAppCompatAspectRatioPolicy);
        doReturn(aspectRatio).when(desktopAppCompatAspectRatioPolicy)
                .getDesiredAspectRatio(any());
    }

    private void applyUserMinAspectRatioOverride(ActivityRecord activity, int overrideCode,
            float overrideValue) {
        // Set desired aspect ratio to be below minimum so override can take effect.
        final DesktopAppCompatAspectRatioPolicy desktopAppCompatAspectRatioPolicy =
                activity.mAppCompatController.getDesktopAppCompatAspectRatioPolicy();
        spyOn(desktopAppCompatAspectRatioPolicy);
        doReturn(1f).when(desktopAppCompatAspectRatioPolicy)
                .getDesiredAspectRatio(any());

        // Enable user aspect ratio settings
        final AppCompatConfiguration appCompatConfiguration =
                activity.mWmService.mAppCompatConfiguration;
        spyOn(appCompatConfiguration);
        doReturn(true).when(appCompatConfiguration)
                .isUserAppAspectRatioSettingsEnabled();

        // Simulate user min aspect ratio override being set.
        final AppCompatAspectRatioOverrides appCompatAspectRatioOverrides =
                activity.mAppCompatController.getAppCompatAspectRatioOverrides();
        spyOn(appCompatAspectRatioOverrides);
        doReturn(overrideValue).when(appCompatAspectRatioOverrides).getUserMinAspectRatio();
        doReturn(overrideCode).when(appCompatAspectRatioOverrides)
                .getUserMinAspectRatioOverrideCode();
    }

    private TestDisplayContent createDisplayContent(int orientation, Rect displayBounds) {
        final TestDisplayContent display = new TestDisplayContent
                .Builder(mAtm, displayBounds.width(), displayBounds.height())
                .setPosition(DisplayContent.POSITION_TOP).build();
        display.setBounds(displayBounds);
        display.getConfiguration().densityDpi = DENSITY_DEFAULT;
        display.getConfiguration().orientation = ORIENTATION_LANDSCAPE;
        display.getDefaultTaskDisplayArea().setWindowingMode(orientation);

        return display;
    }

    private void setupDesktopModeLaunchParamsModifier() {
        setupDesktopModeLaunchParamsModifier(/*isDesktopModeSupported=*/ true,
                /*enforceDeviceRestrictions=*/ true);
    }

    private void setupDesktopModeLaunchParamsModifier(boolean isDesktopModeSupported,
            boolean enforceDeviceRestrictions) {
        doReturn(isDesktopModeSupported)
                .when(() -> DesktopModeHelper.isDesktopModeSupported(any()));
        doReturn(enforceDeviceRestrictions)
                .when(DesktopModeHelper::shouldEnforceDeviceRestrictions);
    }
}
