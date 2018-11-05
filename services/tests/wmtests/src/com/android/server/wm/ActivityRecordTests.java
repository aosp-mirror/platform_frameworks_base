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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wm.ActivityStack.ActivityState.INITIALIZING;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_MOVING;
import static com.android.server.policy.WindowManagerPolicy.NAV_BAR_BOTTOM;
import static com.android.server.policy.WindowManagerPolicy.NAV_BAR_LEFT;
import static com.android.server.policy.WindowManagerPolicy.NAV_BAR_RIGHT;

import static junit.framework.TestCase.assertNotNull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.app.ActivityOptions;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.PauseActivityItem;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.MutableBoolean;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * Tests for the {@link ActivityRecord} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityRecordTests
 */
@MediumTest
@Presubmit
public class ActivityRecordTests extends ActivityTestsBase {
    private TestActivityStack mStack;
    private TaskRecord mTask;
    private ActivityRecord mActivity;

    @Before
    public void setUp() throws Exception {
        setupActivityTaskManagerService();
        mStack = new StackBuilder(mSupervisor).build();
        mTask = mStack.getChildAt(0);
        mActivity = mTask.getTopActivity();
    }

    @Test
    public void testStackCleanupOnClearingTask() {
        mActivity.setTask(null);
        assertEquals(mStack.onActivityRemovedFromStackInvocationCount(), 1);
    }

    @Test
    public void testStackCleanupOnActivityRemoval() {
        mTask.removeActivity(mActivity);
        assertEquals(mStack.onActivityRemovedFromStackInvocationCount(),  1);
    }

    @Test
    public void testStackCleanupOnTaskRemoval() {
        mStack.removeTask(mTask, null /*reason*/, REMOVE_TASK_MODE_MOVING);
        // Stack should be gone on task removal.
        assertNull(mService.mStackSupervisor.getStack(mStack.mStackId));
    }

    @Test
    public void testNoCleanupMovingActivityInSameStack() {
        final TaskRecord newTask = new TaskBuilder(mService.mStackSupervisor).setStack(mStack)
                .build();
        mActivity.reparent(newTask, 0, null /*reason*/);
        assertEquals(mStack.onActivityRemovedFromStackInvocationCount(), 0);
    }

    @Test
    public void testPausingWhenVisibleFromStopped() throws Exception {
        final MutableBoolean pauseFound = new MutableBoolean(false);
        doAnswer((InvocationOnMock invocationOnMock) -> {
            final ClientTransaction transaction = invocationOnMock.getArgument(0);
            if (transaction.getLifecycleStateRequest() instanceof PauseActivityItem) {
                pauseFound.value = true;
            }
            return null;
        }).when(mActivity.app.getThread()).scheduleTransaction(any());

        mActivity.setState(STOPPED, "testPausingWhenVisibleFromStopped");

        // The activity is in the focused stack so it should not move to paused.
        mActivity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(mActivity.isState(STOPPED));
        assertFalse(pauseFound.value);

        // Clear focused stack
        final ActivityDisplay display = mActivity.mStackSupervisor.getDefaultDisplay();
        when(display.getFocusedStack()).thenReturn(null);

        // In the unfocused stack, the activity should move to paused.
        mActivity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(mActivity.isState(PAUSING));
        assertTrue(pauseFound.value);

        // Make sure that the state does not change for current non-stopping states.
        mActivity.setState(INITIALIZING, "testPausingWhenVisibleFromStopped");

        mActivity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);

        assertTrue(mActivity.isState(INITIALIZING));

        // Make sure the state does not change if we are not the current top activity.
        mActivity.setState(STOPPED, "testPausingWhenVisibleFromStopped behind");

        // Make sure that the state does not change when we have an activity becoming translucent
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        mStack.mTranslucentActivityWaiting = topActivity;
        mActivity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);

        assertTrue(mActivity.isState(STOPPED));
    }

    @Test
    public void testPositionLimitedAspectRatioNavBarBottom() {
        verifyPositionWithLimitedAspectRatio(NAV_BAR_BOTTOM, new Rect(0, 0, 1000, 2000), 1.5f,
                new Rect(0, 0, 1000, 1500));
    }

    @Test
    public void testPositionLimitedAspectRatioNavBarLeft() {
        verifyPositionWithLimitedAspectRatio(NAV_BAR_LEFT, new Rect(0, 0, 2000, 1000), 1.5f,
                new Rect(500, 0, 2000, 1000));
    }

    @Test
    public void testPositionLimitedAspectRatioNavBarRight() {
        verifyPositionWithLimitedAspectRatio(NAV_BAR_RIGHT, new Rect(0, 0, 2000, 1000), 1.5f,
                new Rect(0, 0, 1500, 1000));
    }

    private void verifyPositionWithLimitedAspectRatio(int navBarPosition, Rect taskBounds,
            float aspectRatio, Rect expectedActivityBounds) {
        // Verify with nav bar on the right.
        when(mService.mWindowManager.getNavBarPosition()).thenReturn(navBarPosition);
        mTask.getConfiguration().windowConfiguration.setAppBounds(taskBounds);
        mActivity.info.maxAspectRatio = aspectRatio;
        mActivity.ensureActivityConfiguration(
                0 /* globalChanges */, false /* preserveWindow */);
        assertEquals(expectedActivityBounds, mActivity.getBounds());
    }

    @Test
    public void testCanBeLaunchedOnDisplay() {
        mService.mSupportsMultiWindow = true;
        final ActivityRecord activity = new ActivityBuilder(mService).build();

        // An activity can be launched on default display.
        assertTrue(activity.canBeLaunchedOnDisplay(DEFAULT_DISPLAY));
        // An activity cannot be launched on a non-existent display.
        assertFalse(activity.canBeLaunchedOnDisplay(DEFAULT_DISPLAY + 1));
    }

    @Test
    public void testsApplyOptionsLocked() {
        ActivityOptions activityOptions = ActivityOptions.makeBasic();

        // Set and apply options for ActivityRecord. Pending options should be cleared
        mActivity.updateOptionsLocked(activityOptions);
        mActivity.applyOptionsLocked();
        assertNull(mActivity.pendingOptions);

        // Set options for two ActivityRecords in same Task. Apply one ActivityRecord options.
        // Pending options should be cleared for both ActivityRecords
        ActivityRecord activity2 = new ActivityBuilder(mService).setTask(mTask).build();
        activity2.updateOptionsLocked(activityOptions);
        mActivity.updateOptionsLocked(activityOptions);
        mActivity.applyOptionsLocked();
        assertNull(mActivity.pendingOptions);
        assertNull(activity2.pendingOptions);

        // Set options for two ActivityRecords in separate Tasks. Apply one ActivityRecord options.
        // Pending options should be cleared for only ActivityRecord that was applied
        TaskRecord task2 = new TaskBuilder(mService.mStackSupervisor).setStack(mStack).build();
        activity2 = new ActivityBuilder(mService).setTask(task2).build();
        activity2.updateOptionsLocked(activityOptions);
        mActivity.updateOptionsLocked(activityOptions);
        mActivity.applyOptionsLocked();
        assertNull(mActivity.pendingOptions);
        assertNotNull(activity2.pendingOptions);
    }
}
