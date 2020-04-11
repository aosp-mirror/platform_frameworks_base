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

import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import android.app.WaitResult;
import android.content.pm.ActivityInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link ActivityStackSupervisor} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStackSupervisorTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityStackSupervisorTests extends ActivityTestsBase {
    private ActivityStack mFullscreenStack;

    @Before
    public void setUp() throws Exception {
        mFullscreenStack = mRootWindowContainer.getDefaultTaskDisplayArea().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
    }

    /**
     * Ensures that an activity is removed from the stopping activities list once it is resumed.
     */
    @Test
    public void testStoppingActivityRemovedWhenResumed() {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();
        mSupervisor.mStoppingActivities.add(firstActivity);

        firstActivity.completeResumeLocked();

        assertFalse(mSupervisor.mStoppingActivities.contains(firstActivity));
    }

    /**
     * Ensures that waiting results are notified of launches.
     */
    @Test
    public void testReportWaitingActivityLaunchedIfNeeded() {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();

        final WaitResult taskToFrontWait = new WaitResult();
        mSupervisor.mWaitingActivityLaunched.add(taskToFrontWait);
        // #notifyAll will be called on the ActivityTaskManagerService#mGlobalLock. The lock is hold
        // implicitly by WindowManagerGlobalLockRule.
        mSupervisor.reportWaitingActivityLaunchedIfNeeded(firstActivity, START_TASK_TO_FRONT);

        assertThat(mSupervisor.mWaitingActivityLaunched).isEmpty();
        assertEquals(taskToFrontWait.result, START_TASK_TO_FRONT);
        assertNull(taskToFrontWait.who);

        final WaitResult deliverToTopWait = new WaitResult();
        mSupervisor.mWaitingActivityLaunched.add(deliverToTopWait);
        mSupervisor.reportWaitingActivityLaunchedIfNeeded(firstActivity, START_DELIVERED_TO_TOP);

        assertThat(mSupervisor.mWaitingActivityLaunched).isEmpty();
        assertEquals(deliverToTopWait.result, START_DELIVERED_TO_TOP);
        assertEquals(deliverToTopWait.who, firstActivity.mActivityComponent);
    }

    /**
     * Ensures that {@link TaskChangeNotificationController} notifies only when an activity is
     * forced to resize on secondary display.
     */
    @Test
    public void testHandleNonResizableTaskOnSecondaryDisplay() {
        // Create an unresizable task on secondary display.
        final DisplayContent newDisplay = addNewDisplayContentAt(DisplayContent.POSITION_TOP);
        final ActivityStack stack = new StackBuilder(mRootWindowContainer)
                .setDisplay(newDisplay).build();
        final ActivityRecord unresizableActivity = stack.getTopNonFinishingActivity();
        final Task task = unresizableActivity.getTask();
        unresizableActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
        task.setResizeMode(unresizableActivity.info.resizeMode);

        final TaskChangeNotificationController taskChangeNotifier =
                mService.getTaskChangeNotificationController();
        spyOn(taskChangeNotifier);

        mSupervisor.handleNonResizableTaskIfNeeded(task, newDisplay.getWindowingMode(),
                newDisplay.getDefaultTaskDisplayArea(), stack);
        // The top activity is unresizable, so it should notify the activity is forced resizing.
        verify(taskChangeNotifier).notifyActivityForcedResizable(eq(task.mTaskId),
                eq(FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY),
                eq(unresizableActivity.packageName));
        reset(taskChangeNotifier);

        // Put a resizable activity on top of the unresizable task.
        final ActivityRecord resizableActivity = new ActivityBuilder(mService)
                .setTask(task).build();
        resizableActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;

        mSupervisor.handleNonResizableTaskIfNeeded(task, newDisplay.getWindowingMode(),
                newDisplay.getDefaultTaskDisplayArea(), stack);
        // For the resizable activity, it is no need to force resizing or dismiss the docked stack.
        verify(taskChangeNotifier, never()).notifyActivityForcedResizable(anyInt() /* taskId */,
                anyInt() /* reason */, anyString() /* packageName */);
        verify(taskChangeNotifier, never()).notifyActivityDismissingDockedStack();
    }

    /**
     * Ensures that notify focus task changes.
     */
    @Test
    public void testNotifyTaskFocusChanged() {
        final ActivityRecord fullScreenActivityA = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();
        final Task taskA = fullScreenActivityA.getTask();

        final TaskChangeNotificationController taskChangeNotifier =
                mService.getTaskChangeNotificationController();
        spyOn(taskChangeNotifier);

        mService.setResumedActivityUncheckLocked(fullScreenActivityA, "resumeA");
        verify(taskChangeNotifier).notifyTaskFocusChanged(eq(taskA.mTaskId) /* taskId */,
                eq(true) /* focused */);
        reset(taskChangeNotifier);

        final ActivityRecord fullScreenActivityB = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();
        final Task taskB = fullScreenActivityB.getTask();

        mService.setResumedActivityUncheckLocked(fullScreenActivityB, "resumeB");
        verify(taskChangeNotifier).notifyTaskFocusChanged(eq(taskA.mTaskId) /* taskId */,
                eq(false) /* focused */);
        verify(taskChangeNotifier).notifyTaskFocusChanged(eq(taskB.mTaskId) /* taskId */,
                eq(true) /* focused */);
    }
}
