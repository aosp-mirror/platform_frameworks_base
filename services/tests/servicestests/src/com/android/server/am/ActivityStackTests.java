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
import static com.android.server.am.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link ActivityStack} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.ActivityStackTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStackTests extends ActivityTestsBase {
    private static final int TEST_STACK_ID = 100;
    private static final ComponentName testActivityComponent =
            ComponentName.unflattenFromString("com.foo/.BarActivity");
    private static final ComponentName testOverlayComponent =
            ComponentName.unflattenFromString("com.foo/.OverlayActivity");

    private ActivityManagerService mService;
    private ActivityStackSupervisor mSupervisor;
    private ActivityStack mStack;
    private TaskRecord mTask;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mService = createActivityManagerService();
        mSupervisor = mService.mStackSupervisor;
        mStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mTask = createTask(mSupervisor, testActivityComponent, mStack);
    }

    @Test
    public void testEmptyTaskCleanupOnRemove() throws Exception {
        assertNotNull(mTask.getWindowContainerController());
        mStack.removeTask(mTask, "testEmptyTaskCleanupOnRemove", REMOVE_TASK_MODE_DESTROYING);
        assertNull(mTask.getWindowContainerController());
    }

    @Test
    public void testOccupiedTaskCleanupOnRemove() throws Exception {
        final ActivityRecord r = createActivity(mService, testActivityComponent, mTask);
        assertNotNull(mTask.getWindowContainerController());
        mStack.removeTask(mTask, "testOccupiedTaskCleanupOnRemove", REMOVE_TASK_MODE_DESTROYING);
        assertNotNull(mTask.getWindowContainerController());
    }

    @Test
    public void testNoPauseDuringResumeTopActivity() throws Exception {
        final ActivityRecord r = createActivity(mService, testActivityComponent, mTask);

        // Simulate the a resumed activity set during
        // {@link ActivityStack#resumeTopActivityUncheckedLocked}.
        mSupervisor.inResumeTopActivity = true;
        mStack.mResumedActivity = r;

        final boolean waiting = mStack.goToSleepIfPossible(false);

        // Ensure we report not being ready for sleep.
        assertFalse(waiting);

        // Make sure the resumed activity is untouched.
        assertEquals(mStack.mResumedActivity, r);
    }

    @Test
    public void testStopActivityWhenActivityDestroyed() throws Exception {
        final ActivityRecord r = createActivity(mService, testActivityComponent, mTask);
        r.info.flags |= ActivityInfo.FLAG_NO_HISTORY;
        mSupervisor.setFocusStackUnchecked("testStopActivityWithDestroy", mStack);
        mStack.stopActivityLocked(r);
        // Mostly testing to make sure there is a crash in the call part, so if we get here we are
        // good-to-go!
    }

    @Test
    public void testFindTaskWithOverlay() throws Exception {
        final ActivityRecord r = createActivity(mService, testActivityComponent, mTask, 0);
        // Overlay must be for a different user to prevent recognizing a matching top activity
        final ActivityRecord taskOverlay = createActivity(mService, testOverlayComponent, mTask,
                UserHandle.PER_USER_RANGE * 2);
        taskOverlay.mTaskOverlay = true;

        final ActivityStackSupervisor.FindTaskResult result =
                new ActivityStackSupervisor.FindTaskResult();
        mStack.findTaskLocked(r, result);

        assertEquals(mTask.getTopActivity(false /* includeOverlays */), r);
        assertEquals(mTask.getTopActivity(true /* includeOverlays */), taskOverlay);
        assertNotNull(result.r);
    }
}
