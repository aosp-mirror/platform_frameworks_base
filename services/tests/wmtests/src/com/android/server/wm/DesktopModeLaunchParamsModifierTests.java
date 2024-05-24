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

import static com.android.server.wm.DesktopModeLaunchParamsModifier.DESKTOP_MODE_INITIAL_BOUNDS_SCALE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_BOUNDS;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_DISPLAY;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_DONE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.graphics.Rect;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.wm.LaunchParamsController.LaunchParamsModifier.Result;
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
public class DesktopModeLaunchParamsModifierTests extends WindowTestsBase {

    private ActivityRecord mActivity;

    private DesktopModeLaunchParamsModifier mTarget;

    private LaunchParamsController.LaunchParams mCurrent;
    private LaunchParamsController.LaunchParams mResult;

    @Before
    public void setUp() throws Exception {
        mActivity = new ActivityBuilder(mAtm).build();
        mTarget = new DesktopModeLaunchParamsModifier();
        mCurrent = new LaunchParamsController.LaunchParams();
        mCurrent.reset();
        mResult = new LaunchParamsController.LaunchParams();
        mResult.reset();
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsContinueIfDesktopWindowingIsDisabled() {
        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setTask(null).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfTaskIsNull() {
        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(null).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfNotBoundsPhase() {
        final Task task = new TaskBuilder(mSupervisor).build();
        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(task).setPhase(
                PHASE_DISPLAY).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfTaskNotUsingActivityTypeStandardOrUndefined() {
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_ASSISTANT).build();
        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(task).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsDoneIfTaskUsingActivityTypeStandard() {
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).build();
        assertEquals(RESULT_DONE, new CalculateRequestBuilder().setTask(task).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsDoneIfTaskUsingActivityTypeUndefined() {
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_UNDEFINED).build();
        assertEquals(RESULT_DONE, new CalculateRequestBuilder().setTask(task).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testReturnsSkipIfCurrentParamsHasBounds() {
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).build();
        mCurrent.mBounds.set(/* left */ 0, /* top */ 0, /* right */ 100, /* bottom */ 100);
        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setTask(task).calculate());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testUsesDefaultBounds() {
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).build();
        final int displayHeight = 1600;
        final int displayWidth = 2560;
        task.getDisplayArea().setBounds(new Rect(0, 0, displayWidth, displayHeight));
        final int desiredWidth = (int) (displayWidth * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        final int desiredHeight = (int) (displayHeight * DESKTOP_MODE_INITIAL_BOUNDS_SCALE);
        assertEquals(RESULT_DONE, new CalculateRequestBuilder().setTask(task).calculate());
        assertEquals(desiredWidth, mResult.mBounds.width());
        assertEquals(desiredHeight, mResult.mBounds.height());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void testUsesDisplayAreaAndWindowingModeFromSource() {
        final Task task = new TaskBuilder(mSupervisor).setActivityType(
                ACTIVITY_TYPE_STANDARD).build();
        TaskDisplayArea mockTaskDisplayArea = mock(TaskDisplayArea.class);
        mCurrent.mPreferredTaskDisplayArea = mockTaskDisplayArea;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;

        assertEquals(RESULT_DONE, new CalculateRequestBuilder().setTask(task).calculate());
        assertEquals(mockTaskDisplayArea, mResult.mPreferredTaskDisplayArea);
        assertEquals(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode);
    }

    private class CalculateRequestBuilder {
        private Task mTask;
        private int mPhase = PHASE_BOUNDS;
        private final ActivityRecord mActivity =
                DesktopModeLaunchParamsModifierTests.this.mActivity;
        private final LaunchParamsController.LaunchParams mCurrentParams = mCurrent;
        private final LaunchParamsController.LaunchParams mOutParams = mResult;

        private CalculateRequestBuilder setTask(Task task) {
            mTask = task;
            return this;
        }

        private CalculateRequestBuilder setPhase(int phase) {
            mPhase = phase;
            return this;
        }

        @Result
        private int calculate() {
            return mTarget.onCalculate(mTask, /* layout*/ null, mActivity, /* source */
                    null, /* options */ null, /* request */ null, mPhase, mCurrentParams,
                    mOutParams);
        }
    }
}
