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

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.WindowLayout;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import android.view.Display;
import android.view.Gravity;
import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;

import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.junit.Assert.assertEquals;


/**
 * Tests for exercising resizing bounds.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.LaunchBoundsTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LaunchBoundsTests extends ActivityTestsBase {
    private final ComponentName testActivityComponent =
            ComponentName.unflattenFromString("com.foo/.BarActivity");
    private final ComponentName testActivityComponent2 =
            ComponentName.unflattenFromString("com.foo/.BarActivity2");

    private final static int STACK_WIDTH = 100;
    private final static int STACK_HEIGHT = 200;

    private final static Rect STACK_BOUNDS = new Rect(0, 0, STACK_WIDTH, STACK_HEIGHT);

    private ActivityManagerService mService;
    private ActivityStack mStack;
    private TaskRecord mTask;

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
        mTask = createTask(mService.mStackSupervisor, testActivityComponent, mStack);
    }

    /**
     * Ensures that the setup bounds are set as expected with the stack bounds set and the task
     * bounds still {@code null}.
     * @throws Exception
     */
    @Test
    public void testInitialBounds() throws Exception {
        assertEquals(mStack.mBounds, STACK_BOUNDS);
        assertEquals(mTask.mBounds, null);
    }

    /**
     * Ensures that a task positioned with no {@link WindowLayout} receives the default launch
     * position.
     * @throws Exception
     */
    @Test
    public void testLaunchNoWindowLayout() throws Exception {
        final Rect expectedTaskBounds = getDefaultBounds(Gravity.NO_GRAVITY);

        mStack.layoutTaskInStack(mTask, null);

        // We expect the task to be placed in the middle of the screen with margins applied.
        assertEquals(mTask.mBounds, expectedTaskBounds);
    }

    /**
     * Ensures that a task positioned with an empty {@link WindowLayout} receives the default launch
     * position.
     * @throws Exception
     */
    @Test
    public void testlaunchEmptyWindowLayout() throws Exception {
        final Rect expectedTaskBounds = getDefaultBounds(Gravity.NO_GRAVITY);

        WindowLayout layout = new WindowLayout(0, 0, 0, 0, 0, 0, 0);
        mStack.layoutTaskInStack(mTask, layout);
        assertEquals(mTask.mBounds, expectedTaskBounds);
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
        final WindowLayout gravityLayout = new WindowLayout(0, 0, 0, 0, gravity, 0, 0);
        mStack.layoutTaskInStack(mTask, gravityLayout);
        assertEquals(mTask.mBounds, getDefaultBounds(gravity));
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
        mStack.layoutTaskInStack(mTask, layout /*windowLayout*/);

        // Second task will be laid out on top of the first so starting bounds is the same.
        final Rect expectedBounds = new Rect(mTask.mBounds);

        ActivityRecord activity = null;
        TaskRecord secondTask = null;

        // wrap with try/finally to ensure cleanup of activity/stack.
        try {
            // empty tasks are ignored in conflicts
            activity = createActivity(mService, testActivityComponent, mTask);

            // Create secondary task
            secondTask = createTask(mService.mStackSupervisor, testActivityComponent,
                    mStack);

            // layout second task
            mStack.layoutTaskInStack(secondTask, layout /*windowLayout*/);

            if ((gravity & (Gravity.TOP | Gravity.RIGHT)) == (Gravity.TOP | Gravity.RIGHT)
                    || (gravity & (Gravity.BOTTOM | Gravity.RIGHT))
                    == (Gravity.BOTTOM | Gravity.RIGHT)) {
                expectedBounds.offset(-LaunchingTaskPositioner.getHorizontalStep(mStack.mBounds),
                        0);
            } else if ((gravity & Gravity.TOP) == Gravity.TOP
                    || (gravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
                expectedBounds.offset(LaunchingTaskPositioner.getHorizontalStep(mStack.mBounds), 0);
            } else {
                expectedBounds.offset(LaunchingTaskPositioner.getHorizontalStep(mStack.mBounds),
                        LaunchingTaskPositioner.getVerticalStep(mStack.mBounds));
            }

            assertEquals(secondTask.mBounds, expectedBounds);
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
        bounds.set(mStack.mBounds);

        final int verticalInset = LaunchingTaskPositioner.getFreeformStartTop(mStack.mBounds);
        final int horizontalInset = LaunchingTaskPositioner.getFreeformStartLeft(mStack.mBounds);

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
