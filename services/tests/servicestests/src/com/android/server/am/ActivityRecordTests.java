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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManagerPolicy.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicy.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicy.NAV_BAR_RIGHT;
import static com.android.server.am.ActivityStack.REMOVE_TASK_MODE_MOVING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link ActivityRecord} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.ActivityRecordTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityRecordTests extends ActivityTestsBase {
    private final ComponentName testActivityComponent =
            ComponentName.unflattenFromString("com.foo/.BarActivity");
    private final ComponentName secondaryActivityComponent =
            ComponentName.unflattenFromString("com.foo/.BarActivity2");

    private ActivityManagerService mService;
    private ActivityStack mStack;
    private TaskRecord mTask;
    private ActivityRecord mActivity;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mService = createActivityManagerService();
        mStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mTask = createTask(mService.mStackSupervisor, testActivityComponent, mStack);
        mActivity = createActivity(mService, testActivityComponent, mTask);
    }

    @Test
    public void testStackCleanupOnClearingTask() throws Exception {
        mActivity.setTask(null);
        assertEquals(getActivityRemovedFromStackCount(), 1);
    }

    @Test
    public void testStackCleanupOnActivityRemoval() throws Exception {
        mTask.removeActivity(mActivity);
        assertEquals(getActivityRemovedFromStackCount(),  1);
    }

    @Test
    public void testStackCleanupOnTaskRemoval() throws Exception {
        mStack.removeTask(mTask, null /*reason*/, REMOVE_TASK_MODE_MOVING);
        // Stack should be gone on task removal.
        assertNull(mService.mStackSupervisor.getStack(mStack.mStackId));
    }

    @Test
    public void testNoCleanupMovingActivityInSameStack() throws Exception {
        final TaskRecord newTask =
                createTask(mService.mStackSupervisor, testActivityComponent, mStack);
        mActivity.reparent(newTask, 0, null /*reason*/);
        assertEquals(getActivityRemovedFromStackCount(), 0);
    }

    @Test
    public void testPositionLimitedAspectRatioNavBarBottom() throws Exception {
        verifyPositionWithLimitedAspectRatio(NAV_BAR_BOTTOM, new Rect(0, 0, 1000, 2000), 1.5f,
                new Rect(0, 0, 1000, 1500));
    }

    @Test
    public void testPositionLimitedAspectRatioNavBarLeft() throws Exception {
        verifyPositionWithLimitedAspectRatio(NAV_BAR_LEFT, new Rect(0, 0, 2000, 1000), 1.5f,
                new Rect(500, 0, 2000, 1000));
    }

    @Test
    public void testPositionLimitedAspectRatioNavBarRight() throws Exception {
        verifyPositionWithLimitedAspectRatio(NAV_BAR_RIGHT, new Rect(0, 0, 2000, 1000), 1.5f,
                new Rect(0, 0, 1500, 1000));
    }

    private void verifyPositionWithLimitedAspectRatio(int navBarPosition, Rect taskBounds,
            float aspectRatio, Rect expectedActivityBounds) {
        // Verify with nav bar on the right.
        when(mService.mWindowManager.getNavBarPosition()).thenReturn(navBarPosition);
        mTask.getConfiguration().windowConfiguration.setAppBounds(taskBounds);
        mActivity.info.maxAspectRatio = aspectRatio;
        mActivity.ensureActivityConfigurationLocked(
                0 /* globalChanges */, false /* preserveWindow */);
        assertEquals(expectedActivityBounds, mActivity.getBounds());
    }

    private int getActivityRemovedFromStackCount() {
        if (mStack instanceof ActivityStackReporter) {
            return ((ActivityStackReporter) mStack).onActivityRemovedFromStackInvocationCount();
        }

        return -1;
    }


    @Test
    public void testCanBeLaunchedOnDisplay() throws Exception {
        testSupportsLaunchingResizeable(false /*taskPresent*/, true /*taskResizeable*/,
                true /*activityResizeable*/, true /*expected*/);

        testSupportsLaunchingResizeable(false /*taskPresent*/, true /*taskResizeable*/,
                false /*activityResizeable*/, false /*expected*/);

        testSupportsLaunchingResizeable(true /*taskPresent*/, false /*taskResizeable*/,
                true /*activityResizeable*/, false /*expected*/);

        testSupportsLaunchingResizeable(true /*taskPresent*/, true /*taskResizeable*/,
                false /*activityResizeable*/, true /*expected*/);
    }

    private void testSupportsLaunchingResizeable(boolean taskPresent, boolean taskResizeable,
            boolean activityResizeable, boolean expected) {
        mService.mSupportsMultiWindow = true;

        final TaskRecord task = taskPresent
                ? createTask(mService.mStackSupervisor, testActivityComponent, mStack) : null;

        if (task != null) {
            task.setResizeMode(taskResizeable ? RESIZE_MODE_RESIZEABLE : RESIZE_MODE_UNRESIZEABLE);
        }

        final ActivityRecord record = createActivity(mService, secondaryActivityComponent, task);
        record.info.resizeMode = activityResizeable
                ? RESIZE_MODE_RESIZEABLE : RESIZE_MODE_UNRESIZEABLE;

        record.canBeLaunchedOnDisplay(DEFAULT_DISPLAY);

        assertEquals(((TestActivityStackSupervisor) mService.mStackSupervisor)
                .getLastResizeableFromCanPlaceEntityOnDisplay(), expected);
    }
}
