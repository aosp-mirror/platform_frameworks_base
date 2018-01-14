/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import android.content.pm.ActivityInfo.WindowLayout;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import android.view.Gravity;

import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;

import org.mockito.invocation.InvocationOnMock;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import static com.android.server.am.LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.junit.Assert.assertEquals;


/**
 * Tests for exercising resizing task bounds.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:TaskLaunchParamsModifierTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TaskLaunchParamsModifierTests extends ActivityTestsBase {
    private final static int STACK_WIDTH = 100;
    private final static int STACK_HEIGHT = 200;

    private final static Rect STACK_BOUNDS = new Rect(0, 0, STACK_WIDTH, STACK_HEIGHT);

    private ActivityManagerService mService;
    private ActivityStack mStack;
    private TaskRecord mTask;

    private TaskLaunchParamsModifier mPositioner;

    private LaunchParamsController.LaunchParams mCurrent;
    private LaunchParamsController.LaunchParams mResult;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mService = createActivityManagerService();
        mStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mStack.requestResize(STACK_BOUNDS);

        // We must create the task after resizing to make sure it does not inherit the stack
        // dimensions on resize.
        mTask = new TaskBuilder(mService.mStackSupervisor).setStack(mStack).build();

        mPositioner = new TaskLaunchParamsModifier();

        mResult = new LaunchParamsController.LaunchParams();
        mCurrent = new LaunchParamsController.LaunchParams();
    }

    /**
     * Ensures that the setup bounds are set as expected with the stack bounds set and the task
     * bounds still {@code null}.
     * @throws Exception
     */
    @Test
    public void testInitialBounds() throws Exception {
        assertEquals(mStack.getOverrideBounds(), STACK_BOUNDS);
        assertEquals(mTask.getOverrideBounds(), new Rect());
    }

    /**
     * Ensures that a task positioned with no {@link WindowLayout} receives the default launch
     * position.
     * @throws Exception
     */
    @Test
    public void testLaunchNoWindowLayout() throws Exception {
        assertEquals(RESULT_CONTINUE, mPositioner.onCalculate(mTask, null /*layout*/,
                null /*record*/, null /*source*/, null /*options*/, mCurrent, mResult));
        assertEquals(getDefaultBounds(Gravity.NO_GRAVITY), mResult.mBounds);
    }

    /**
     * Ensures that a task positioned with an empty {@link WindowLayout} receives the default launch
     * position.
     * @throws Exception
     */
    @Test
    public void testlaunchEmptyWindowLayout() throws Exception {
        assertEquals(RESULT_CONTINUE, mPositioner.onCalculate(mTask,
                new WindowLayout(0, 0, 0, 0, Gravity.NO_GRAVITY, 0, 0), null /*activity*/,
                null /*source*/, null /*options*/, mCurrent, mResult));
        assertEquals(mResult.mBounds, getDefaultBounds(Gravity.NO_GRAVITY));
    }

    /**
     * Ensures that a task positioned with a {@link WindowLayout} gravity specified is positioned
     * according to specification.
     * @throws Exception
     */
    @Test
    public void testlaunchWindowLayoutGravity() throws Exception {
        // Unspecified gravity should be ignored
        testGravity(Gravity.NO_GRAVITY);

        // Unsupported gravity should be ignored
        testGravity(Gravity.LEFT);
        testGravity(Gravity.RIGHT);

        // Test defaults for vertical gravities
        testGravity(Gravity.TOP);
        testGravity(Gravity.BOTTOM);

        // Test corners
        testGravity(Gravity.TOP | Gravity.LEFT);
        testGravity(Gravity.TOP | Gravity.RIGHT);
        testGravity(Gravity.BOTTOM | Gravity.LEFT);
        testGravity(Gravity.BOTTOM | Gravity.RIGHT);
    }

    private void testGravity(int gravity) {
        try {
            assertEquals(RESULT_CONTINUE, mPositioner.onCalculate(mTask,
                    new WindowLayout(0, 0, 0, 0, gravity, 0, 0), null /*activity*/,
                    null /*source*/, null /*options*/, mCurrent, mResult));
            assertEquals(mResult.mBounds, getDefaultBounds(gravity));
        } finally {
            mCurrent.reset();
            mResult.reset();
        }
    }

    /**
     * Ensures that a task which causes a conflict with another task when positioned is adjusted as
     * expected.
     * @throws Exception
     */
    @Test
    public void testLaunchWindowCenterConflict() throws Exception {
        testConflict(Gravity.NO_GRAVITY);
        testConflict(Gravity.TOP);
        testConflict(Gravity.BOTTOM);
        testConflict(Gravity.TOP | Gravity.LEFT);
        testConflict(Gravity.TOP | Gravity.RIGHT);
        testConflict(Gravity.BOTTOM | Gravity.LEFT);
        testConflict(Gravity.BOTTOM | Gravity.RIGHT);
    }

    private void testConflict(int gravity) {
        final WindowLayout layout = new WindowLayout(0, 0, 0, 0, gravity, 0, 0);

        // layout first task
        mService.mStackSupervisor.getLaunchParamsController().layoutTask(mTask, layout);

        // Second task will be laid out on top of the first so starting bounds is the same.
        final Rect expectedBounds = new Rect(mTask.getOverrideBounds());

        ActivityRecord activity = null;
        TaskRecord secondTask = null;

        // wrap with try/finally to ensure cleanup of activity/stack.
        try {
            // empty tasks are ignored in conflicts
            activity = new ActivityBuilder(mService).setTask(mTask).build();

            // Create secondary task
            secondTask = new TaskBuilder(mService.mStackSupervisor).setStack(mStack).build();

            // layout second task
            assertEquals(RESULT_CONTINUE,
                    mPositioner.onCalculate(secondTask, layout, null /*activity*/,
                            null /*source*/, null /*options*/, mCurrent, mResult));

            if ((gravity & (Gravity.TOP | Gravity.RIGHT)) == (Gravity.TOP | Gravity.RIGHT)
                    || (gravity & (Gravity.BOTTOM | Gravity.RIGHT))
                    == (Gravity.BOTTOM | Gravity.RIGHT)) {
                expectedBounds.offset(-TaskLaunchParamsModifier.getHorizontalStep(
                        mStack.getOverrideBounds()), 0);
            } else if ((gravity & Gravity.TOP) == Gravity.TOP
                    || (gravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
                expectedBounds.offset(
                        TaskLaunchParamsModifier.getHorizontalStep(mStack.getOverrideBounds()), 0);
            } else {
                expectedBounds.offset(
                        TaskLaunchParamsModifier.getHorizontalStep(mStack.getOverrideBounds()),
                        TaskLaunchParamsModifier.getVerticalStep(mStack.getOverrideBounds()));
            }

            assertEquals(mResult.mBounds, expectedBounds);
        } finally {
            // Remove task and activity to prevent influencing future tests
            if (activity != null) {
                mTask.removeActivity(activity);
            }

            if (secondTask != null) {
                mStack.removeTask(secondTask, "cleanup", ActivityStack.REMOVE_TASK_MODE_DESTROYING);
            }
        }
    }

    private Rect getDefaultBounds(int gravity) {
        final Rect bounds = new Rect();
        bounds.set(mStack.getOverrideBounds());

        final int verticalInset =
                TaskLaunchParamsModifier.getFreeformStartTop(mStack.getOverrideBounds());
        final int horizontalInset =
                TaskLaunchParamsModifier.getFreeformStartLeft(mStack.getOverrideBounds());

        bounds.inset(horizontalInset, verticalInset);

        if ((gravity & (Gravity.TOP | Gravity.RIGHT)) == (Gravity.TOP | Gravity.RIGHT)) {
            bounds.offsetTo(horizontalInset * 2, 0);
        } else if ((gravity & Gravity.TOP) == Gravity.TOP) {
            bounds.offsetTo(0, 0);
        } else if ((gravity & (Gravity.BOTTOM | Gravity.RIGHT))
                == (Gravity.BOTTOM | Gravity.RIGHT)) {
            bounds.offsetTo(horizontalInset * 2, verticalInset * 2);
        } else if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            bounds.offsetTo(0, verticalInset * 2);
        }

        return bounds;
    }
}
