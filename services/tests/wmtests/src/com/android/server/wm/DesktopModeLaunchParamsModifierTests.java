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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.server.wm.DesktopModeLaunchParamsModifier.DESKTOP_MODE_INITIAL_BOUNDS_SCALE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_DISPLAY;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.Gravity;

import androidx.test.filters.SmallTest;

import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Test;
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
public class DesktopModeLaunchParamsModifierTests extends LaunchParamsModifierTestsBase {
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
    public void testUsesDesiredBoundsIfEmptyLayoutAndActivityOptionsBounds() {
        setupDesktopModeLaunchParamsModifier();

        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).setDisplay(display).build();

        final int desiredWidth =
                (int) (DISPLAY_STABLE_BOUNDS.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight =
                (int) (DISPLAY_STABLE_BOUNDS.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(task).calculate());
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

    private void setupDesktopModeLaunchParamsModifier() {
        setupDesktopModeLaunchParamsModifier(/*isDesktopModeSupported=*/ true,
                /*enforceDeviceRestrictions=*/ true);
    }

    private void setupDesktopModeLaunchParamsModifier(boolean isDesktopModeSupported,
            boolean enforceDeviceRestrictions) {
        doReturn(isDesktopModeSupported)
                .when(() -> DesktopModeLaunchParamsModifier.isDesktopModeSupported(any()));
        doReturn(enforceDeviceRestrictions)
                .when(DesktopModeLaunchParamsModifier::enforceDeviceRestrictions);
    }
}
