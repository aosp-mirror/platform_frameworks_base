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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;

import android.app.WaitResult;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link ActivityStackSupervisor} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStackSupervisorTests
 */
@MediumTest
@Presubmit
public class ActivityStackSupervisorTests extends ActivityTestsBase {
    private ActivityStack mFullscreenStack;

    @Before
    public void setUp() throws Exception {
        mFullscreenStack = mRootActivityContainer.getDefaultDisplay().createStack(
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
    @SuppressWarnings("SynchronizeOnNonFinalField")
    @Test
    public void testReportWaitingActivityLaunchedIfNeeded() {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();

        // #notifyAll will be called on the ActivityManagerService. we must hold the object lock
        // when this happens.
        synchronized (mService.mGlobalLock) {
            final WaitResult taskToFrontWait = new WaitResult();
            mSupervisor.mWaitingActivityLaunched.add(taskToFrontWait);
            mSupervisor.reportWaitingActivityLaunchedIfNeeded(firstActivity, START_TASK_TO_FRONT);

            assertThat(mSupervisor.mWaitingActivityLaunched).isEmpty();
            assertEquals(taskToFrontWait.result, START_TASK_TO_FRONT);
            assertNull(taskToFrontWait.who);

            final WaitResult deliverToTopWait = new WaitResult();
            mSupervisor.mWaitingActivityLaunched.add(deliverToTopWait);
            mSupervisor.reportWaitingActivityLaunchedIfNeeded(firstActivity,
                    START_DELIVERED_TO_TOP);

            assertThat(mSupervisor.mWaitingActivityLaunched).isEmpty();
            assertEquals(deliverToTopWait.result, START_DELIVERED_TO_TOP);
            assertEquals(deliverToTopWait.who, firstActivity.mActivityComponent);
        }
    }
}
