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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.util.DisplayMetrics.DENSITY_DEFAULT;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.Gravity;

import androidx.test.filters.SmallTest;

import com.android.server.wm.LaunchParamsController.LaunchParams;

import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

/**
 * Tests for default task bounds.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskLaunchParamsModifierTests
 */
@SmallTest
@Presubmit
public class TaskLaunchParamsModifierTests extends ActivityTestsBase {

    private ActivityRecord mActivity;

    private TaskLaunchParamsModifier mTarget;

    private LaunchParams mCurrent;
    private LaunchParams mResult;

    @Before
    public void setUp() throws Exception {
        mActivity = new ActivityBuilder(mService).build();
        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        mActivity.info.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;

        mTarget = new TaskLaunchParamsModifier(mSupervisor);

        mCurrent = new LaunchParams();
        mCurrent.reset();
        mResult = new LaunchParams();
        mResult.reset();
    }

    @Test
    public void testReturnsSkipWithEmptyActivity() {
        final TaskRecord task = new TaskBuilder(mSupervisor).build();
        assertEquals(RESULT_SKIP, mTarget.onCalculate(task, /* layout */ null,
                /* activity */ null, /* source */ null, /* options */ null, mCurrent, mResult));
    }

    // =============================
    // Display ID Related Tests
    // =============================
    @Test
    public void testDefaultToPrimaryDisplay() {
        createNewActivityDisplay(WINDOWING_MODE_FREEFORM);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(DEFAULT_DISPLAY, mResult.mPreferredDisplayId);
    }

    @Test
    public void testUsesDefaultDisplayIfPreviousDisplayNotExists() {
        mCurrent.mPreferredDisplayId = 19;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(DEFAULT_DISPLAY, mResult.mPreferredDisplayId);
    }

    @Test
    public void testUsesPreviousDisplayIdIfSet() {
        createNewActivityDisplay(WINDOWING_MODE_FREEFORM);
        final TestActivityDisplay display = createNewActivityDisplay(WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredDisplayId = display.mDisplayId;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(display.mDisplayId, mResult.mPreferredDisplayId);
    }

    @Test
    public void testUsesSourcesDisplayIdIfSet() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);
        final TestActivityDisplay fullscreenDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        ActivityRecord source = createSourceActivity(fullscreenDisplay);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, source, /* options */ null, mCurrent, mResult));

        assertEquals(fullscreenDisplay.mDisplayId, mResult.mPreferredDisplayId);
    }

    @Test
    public void testUsesOptionsDisplayIdIfSet() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);
        final TestActivityDisplay fullscreenDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        ActivityRecord source = createSourceActivity(freeformDisplay);

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(fullscreenDisplay.mDisplayId);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, source, options, mCurrent, mResult));

        assertEquals(fullscreenDisplay.mDisplayId, mResult.mPreferredDisplayId);
    }

    @Test
    public void testUsesTasksDisplayIdPriorToSourceIfSet() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);
        final TestActivityDisplay fullscreenDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        ActivityRecord reusableActivity = createSourceActivity(fullscreenDisplay);
        ActivityRecord source = createSourceActivity(freeformDisplay);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(reusableActivity.getTaskRecord(),
                /* layout */ null, mActivity, source, /* options */ null, mCurrent, mResult));

        assertEquals(fullscreenDisplay.mDisplayId, mResult.mPreferredDisplayId);
    }

    @Test
    public void testUsesTaskDisplayIdIfSet() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);
        ActivityRecord source = createSourceActivity(freeformDisplay);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(source.getTaskRecord(), null /* layout */,
                null /* activity */, null /* source */, null /* options */, mCurrent, mResult));

        assertEquals(freeformDisplay.mDisplayId, mResult.mPreferredDisplayId);
    }

    @Test
    public void testUsesNoDisplaySourceHandoverDisplayIdIfSet() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);
        final TestActivityDisplay fullscreenDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredDisplayId = fullscreenDisplay.mDisplayId;
        ActivityRecord reusableActivity = createSourceActivity(fullscreenDisplay);
        ActivityRecord source = createSourceActivity(freeformDisplay);
        source.mHandoverLaunchDisplayId = freeformDisplay.mDisplayId;
        source.noDisplay = true;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(reusableActivity.getTaskRecord(),
                null /* layout */, mActivity, source, null /* options */, mCurrent, mResult));

        assertEquals(freeformDisplay.mDisplayId, mResult.mPreferredDisplayId);
    }

    // =====================================
    // Launch Windowing Mode Related Tests
    // =====================================
    @Test
    public void testBoundsInOptionsInfersFreeformOnFreeformDisplay() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchBounds(new Rect(0, 0, 100, 100));

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testBoundsInOptionsInfersFreeformWithResizeableActivity() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchBounds(new Rect(0, 0, 100, 100));

        mCurrent.mPreferredDisplayId = DEFAULT_DISPLAY;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testKeepsPictureInPictureLaunchModeInOptions() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_PINNED);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_PINNED, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testKeepsPictureInPictureLaunchModeWithBoundsInOptions() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_PINNED);
        options.setLaunchBounds(new Rect(0, 0, 100, 100));

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_PINNED, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testKeepsFullscreenLaunchModeInOptionsOnNonFreeformDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredDisplayId = DEFAULT_DISPLAY;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testNonEmptyLayoutInfersFreeformOnFreeformDisplay() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testNonEmptyLayoutInfersFreeformWithEmptySize() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testNonEmptyLayoutUsesFullscreenWithResizeableActivity() {
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).build();

        mCurrent.mPreferredDisplayId = DEFAULT_DISPLAY;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testRespectsFullyResolvedCurrentParam_Fullscreen() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        mCurrent.mWindowingMode = WINDOWING_MODE_FULLSCREEN;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testRespectsModeFromFullyResolvedCurrentParam_NonEmptyBounds() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testForceMaximizesPreDApp() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        options.setLaunchBounds(new Rect(0, 0, 200, 100));

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.CUPCAKE;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testForceMaximizesAppWithoutMultipleDensitySupport() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        options.setLaunchBounds(new Rect(0, 0, 200, 100));

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        mActivity.appInfo.flags = 0;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testForceMaximizesUnresizeableApp() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        options.setLaunchBounds(new Rect(0, 0, 200, 100));

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testSkipsForceMaximizingAppsOnNonFreeformDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        options.setLaunchBounds(new Rect(0, 0, 200, 100));

        mCurrent.mPreferredDisplayId = DEFAULT_DISPLAY;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testUsesFullscreenOnNonFreeformDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(DEFAULT_DISPLAY);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testUsesFreeformByDefaultForPostNApp() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testUsesFreeformByDefaultForPreNResizeableAppWithoutOrientationRequest() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testSkipsFreeformForPreNResizeableAppOnNonFullscreenDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(DEFAULT_DISPLAY);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    // ================================
    // Launching Bounds Related Tests
    // ===============================
    @Test
    public void testKeepsBoundsWithPictureInPictureLaunchModeInOptions() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_PINNED);

        final Rect expected = new Rect(0, 0, 100, 100);
        options.setLaunchBounds(expected);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(expected, mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_LeftGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(0, mResult.mBounds.left);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_TopGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.TOP).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(0, mResult.mBounds.top);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_TopLeftGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.TOP | Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(0, mResult.mBounds.left);
        assertEquals(0, mResult.mBounds.top);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_RightGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(1920, mResult.mBounds.right);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_BottomGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.BOTTOM).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(1080, mResult.mBounds.bottom);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_BottomRightGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.BOTTOM | Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(1920, mResult.mBounds.right);
        assertEquals(1080, mResult.mBounds.bottom);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_CenterToDisplay() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(900, 500, 1020, 580), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_LeftGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(0, 500, 120, 580), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_TopGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.TOP).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(900, 0, 1020, 80), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_TopLeftGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.TOP | Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(0, 0, 120, 80), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_RightGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(1800, 500, 1920, 580), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_BottomGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.BOTTOM).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(900, 1000, 1020, 1080), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_RightBottomGravity() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.BOTTOM | Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(1800, 1000, 1920, 1080), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutFractionBoundsOnFreeformDisplay() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidthFraction(0.0625f).setHeightFraction(0.1f).build();

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(900, 486, 1020, 594), mResult.mBounds);
    }

    @Test
    public void testRespectBoundsFromFullyResolvedCurrentParam_NonEmptyBounds() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(0, 0, 200, 100), mResult.mBounds);
    }

    @Test
    public void testReturnBoundsForFullscreenWindowingMode() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        mCurrent.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(0, 0, 200, 100), mResult.mBounds);
    }

    @Test
    public void testUsesDisplayOrientationForNoSensorOrientation() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);

        mActivity.info.screenOrientation = SCREEN_ORIENTATION_NOSENSOR;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        final int orientationForDisplay = orientationFromBounds(freeformDisplay.getBounds());
        final int orientationForTask = orientationFromBounds(mResult.mBounds);
        assertEquals("Launch bounds orientation should be the same as the display, but"
                        + " display orientation is "
                        + ActivityInfo.screenOrientationToString(orientationForDisplay)
                        + " launch bounds orientation is "
                        + ActivityInfo.screenOrientationToString(orientationForTask),
                orientationForDisplay, orientationForTask);
    }

    @Test
    public void testRespectsAppRequestedOrientation_Landscape() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);

        mActivity.info.screenOrientation = SCREEN_ORIENTATION_LANDSCAPE;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, orientationFromBounds(mResult.mBounds));
    }

    @Test
    public void testRespectsAppRequestedOrientation_Portrait() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);

        mActivity.info.screenOrientation = SCREEN_ORIENTATION_PORTRAIT;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, orientationFromBounds(mResult.mBounds));
    }

    @Test
    public void testDefaultSizeSmallerThanBigScreen() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        final int resultWidth = mResult.mBounds.width();
        final int displayWidth = freeformDisplay.getBounds().width();
        assertTrue("Result width " + resultWidth + " is not smaller than " + displayWidth,
                resultWidth < displayWidth);

        final int resultHeight = mResult.mBounds.height();
        final int displayHeight = freeformDisplay.getBounds().height();
        assertTrue("Result width " + resultHeight + " is not smaller than "
                        + displayHeight, resultHeight < displayHeight);
    }

    @Test
    public void testDefaultFreeformSizeCenteredToDisplay() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        final Rect displayBounds = freeformDisplay.getBounds();
        assertEquals("Distance to left and right should be equal.",
                mResult.mBounds.left - displayBounds.left,
                displayBounds.right - mResult.mBounds.right);
        assertEquals("Distance to top and bottom should be equal.",
                mResult.mBounds.top - displayBounds.top,
                displayBounds.bottom - mResult.mBounds.bottom);
    }

    @Test
    public void testCascadesToSourceSizeForFreeform() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        final ActivityRecord source = createSourceActivity(freeformDisplay);
        source.setBounds(0, 0, 412, 732);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, source, options, mCurrent, mResult));

        final Rect displayBounds = freeformDisplay.getBounds();
        assertTrue("Left bounds should be larger than 0.", mResult.mBounds.left > 0);
        assertTrue("Top bounds should be larger than 0.", mResult.mBounds.top > 0);
        assertTrue("Bounds should be centered at somewhere in the left half, but it's "
                + "centerX is " + mResult.mBounds.centerX(),
                mResult.mBounds.centerX() < displayBounds.centerX());
        assertTrue("Bounds should be centered at somewhere in the top half, but it's "
                        + "centerY is " + mResult.mBounds.centerY(),
                mResult.mBounds.centerY() < displayBounds.centerY());
    }

    @Test
    public void testAdjustBoundsToFitDisplay_TopLeftOutOfDisplay() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        final ActivityRecord source = createSourceActivity(freeformDisplay);
        source.setBounds(0, 0, 200, 400);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, source, options, mCurrent, mResult));

        final Rect displayBounds = freeformDisplay.getBounds();
        assertTrue("display bounds doesn't contain result. display bounds: "
                + displayBounds + " result: " + mResult.mBounds,
                displayBounds.contains(mResult.mBounds));
    }

    @Test
    public void testAdjustBoundsToFitDisplay_BottomRightOutOfDisplay() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        final ActivityRecord source = createSourceActivity(freeformDisplay);
        source.setBounds(1720, 680, 1920, 1080);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, source, options, mCurrent, mResult));

        final Rect displayBounds = freeformDisplay.getBounds();
        assertTrue("display bounds doesn't contain result. display bounds: "
                        + displayBounds + " result: " + mResult.mBounds,
                displayBounds.contains(mResult.mBounds));
    }

    @Test
    public void testAdjustBoundsToFitNewDisplay_LargerThanDisplay() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(100, 200, 2120, 1380);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertTrue("Result bounds should start from origin, but it's " + mResult.mBounds,
                mResult.mBounds.left == 0 && mResult.mBounds.top == 0);
    }

    @Test
    public void testAdjustBoundsToFitNewDisplay_LargerThanDisplay_RTL() {
        final Configuration overrideConfig =
                mRootActivityContainer.getRequestedOverrideConfiguration();
        // Egyptian Arabic is a RTL language.
        overrideConfig.setLayoutDirection(new Locale("ar", "EG"));
        mRootActivityContainer.onRequestedOverrideConfigurationChanged(overrideConfig);

        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(100, 200, 2120, 1380);

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertTrue("Result bounds should start from origin, but it's " + mResult.mBounds,
                mResult.mBounds.left == -100 && mResult.mBounds.top == 0);
    }

    @Test
    public void testRespectsLayoutMinDimensions() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setMinWidth(500).setMinHeight(800).build();

        mActivity.appInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, layout, mActivity,
                /* source */ null, options, mCurrent, mResult));

        assertEquals(500, mResult.mBounds.width());
        assertEquals(800, mResult.mBounds.height());
    }

    @Test
    public void testRotatesInPlaceInitialBoundsMismatchOrientation() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(100, 100, 500, 300));

        mActivity.info.screenOrientation = SCREEN_ORIENTATION_PORTRAIT;

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(new Rect(200, 0, 400, 400), mResult.mBounds);
    }

    @Test
    public void testShiftsToRightForCloseToLeftBoundsWhenConflict() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(50, 50, 100, 150));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(50, 50, 500, 300));

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(new Rect(170, 50, 620, 300), mResult.mBounds);
    }

    @Test
    public void testShiftsToLeftForCloseToRightBoundsWhenConflict() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(1720, 50, 1830, 150));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(1720, 50, 1850, 300));

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(new Rect(1600, 50, 1730, 300), mResult.mBounds);
    }

    @Test
    public void testShiftsToRightFirstForHorizontallyCenteredAndCloseToTopWhenConflict() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(0, 0, 100, 300));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(0, 0, 1800, 200));

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(new Rect(120, 0, 1920, 200), mResult.mBounds);
    }

    @Test
    public void testShiftsToLeftNoSpaceOnRightForHorizontallyCenteredAndCloseToTopWhenConflict() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(120, 0, 240, 300));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(120, 0, 1860, 200));

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(new Rect(0, 0, 1740, 200), mResult.mBounds);
    }

    @Test
    public void testShiftsToBottomRightFirstForCenteredBoundsWhenConflict() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(120, 0, 240, 100));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(120, 0, 1800, 1013));

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(new Rect(240, 67, 1920, 1080), mResult.mBounds);
    }

    @Test
    public void testShiftsToTopLeftIfNoSpaceOnBottomRightForCenteredBoundsWhenConflict() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(120, 67, 240, 100));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(120, 67, 1800, 1020));

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, options, mCurrent, mResult));

        assertEquals(new Rect(0, 0, 1680, 953), mResult.mBounds);
    }

    @Test
    public void testAdjustsBoundsToFitInDisplayFullyResolvedBounds() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredDisplayId = Display.INVALID_DISPLAY;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(-100, -200, 200, 100);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(0, 0, 300, 300), mResult.mBounds);
    }

    @Test
    public void testAdjustsBoundsToAvoidConflictFullyResolvedBounds() {
        final TestActivityDisplay freeformDisplay = createNewActivityDisplay(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(0, 0, 200, 100));

        mCurrent.mPreferredDisplayId = freeformDisplay.mDisplayId;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, mTarget.onCalculate(/* task */ null, /* layout */ null,
                mActivity, /* source */ null, /* options */ null, mCurrent, mResult));

        assertEquals(new Rect(120, 0, 320, 100), mResult.mBounds);
    }

    private TestActivityDisplay createNewActivityDisplay(int windowingMode) {
        final TestActivityDisplay display = addNewActivityDisplayAt(ActivityDisplay.POSITION_TOP);
        display.setWindowingMode(windowingMode);
        display.setBounds(/* left */ 0, /* top */ 0, /* right */ 1920, /* bottom */ 1080);
        display.getConfiguration().densityDpi = DENSITY_DEFAULT;
        display.getConfiguration().orientation = ORIENTATION_LANDSCAPE;
        return display;
    }

    private ActivityRecord createSourceActivity(TestActivityDisplay display) {
        final TestActivityStack stack = display.createStack(display.getWindowingMode(),
                ACTIVITY_TYPE_STANDARD, true);
        return new ActivityBuilder(mService).setStack(stack).setCreateTask(true).build();
    }

    private void addFreeformTaskTo(TestActivityDisplay display, Rect bounds) {
        final TestActivityStack stack = display.createStack(display.getWindowingMode(),
                ACTIVITY_TYPE_STANDARD, true);
        stack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(stack).build();
        task.setBounds(bounds);
    }

    private void assertEquivalentWindowingMode(int expected, int actual, int parentWindowingMode) {
        if (expected != parentWindowingMode) {
            assertEquals(expected, actual);
        } else {
            assertEquals(WINDOWING_MODE_UNDEFINED, actual);
        }
    }

    private int orientationFromBounds(Rect bounds) {
        return bounds.width() > bounds.height() ? SCREEN_ORIENTATION_LANDSCAPE
                : SCREEN_ORIENTATION_PORTRAIT;
    }

    private static class WindowLayoutBuilder {
        private int mWidth = -1;
        private int mHeight = -1;
        private float mWidthFraction = -1f;
        private float mHeightFraction = -1f;
        private int mGravity = Gravity.NO_GRAVITY;
        private int mMinWidth = -1;
        private int mMinHeight = -1;

        private WindowLayoutBuilder setWidth(int width) {
            mWidth = width;
            return this;
        }

        private WindowLayoutBuilder setHeight(int height) {
            mHeight = height;
            return this;
        }

        private WindowLayoutBuilder setWidthFraction(float widthFraction) {
            mWidthFraction = widthFraction;
            return this;
        }

        private WindowLayoutBuilder setHeightFraction(float heightFraction) {
            mHeightFraction = heightFraction;
            return this;
        }

        private WindowLayoutBuilder setGravity(int gravity) {
            mGravity = gravity;
            return this;
        }

        private WindowLayoutBuilder setMinWidth(int minWidth) {
            mMinWidth = minWidth;
            return this;
        }

        private WindowLayoutBuilder setMinHeight(int minHeight) {
            mMinHeight = minHeight;
            return this;
        }

        private ActivityInfo.WindowLayout build() {
            return new ActivityInfo.WindowLayout(mWidth, mWidthFraction, mHeight, mHeightFraction,
                    mGravity, mMinWidth, mMinHeight);
        }
    }
}
