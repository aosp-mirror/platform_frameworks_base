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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
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

    @Test
    public void testEmptyTaskCleanupOnRemove() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord task = createTask(service, testActivityComponent, TEST_STACK_ID);
        assertNotNull(task.getWindowContainerController());
        service.mStackSupervisor.getStack(TEST_STACK_ID).removeTask(task,
                "testEmptyTaskCleanupOnRemove", ActivityStack.REMOVE_TASK_MODE_DESTROYING);
        assertNull(task.getWindowContainerController());
    }

    @Test
    public void testOccupiedTaskCleanupOnRemove() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord task = createTask(service, testActivityComponent, TEST_STACK_ID);
        final ActivityRecord activityRecord = createActivity(service, testActivityComponent, task);
        assertNotNull(task.getWindowContainerController());
        service.mStackSupervisor.getStack(TEST_STACK_ID).removeTask(task,
                "testOccupiedTaskCleanupOnRemove", ActivityStack.REMOVE_TASK_MODE_DESTROYING);
        assertNotNull(task.getWindowContainerController());
    }

    @Test
    public void testNoPauseDuringResumeTopActivity() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord task = createTask(service, testActivityComponent, TEST_STACK_ID);
        final ActivityRecord activityRecord = createActivity(service, testActivityComponent, task);
        final ActivityStack testStack = service.mStackSupervisor.getStack(TEST_STACK_ID);

        // Simulate the a resumed activity set during
        // {@link ActivityStack#resumeTopActivityUncheckedLocked}.
        service.mStackSupervisor.inResumeTopActivity = true;
        testStack.mResumedActivity = activityRecord;

        final boolean waiting = testStack.checkReadyForSleepLocked();

        // Ensure we report not being ready for sleep.
        assertTrue(waiting);

        // Make sure the resumed activity is untouched.
        assertEquals(testStack.mResumedActivity, activityRecord);
    }

    @Test
    public void testStopActivityWhenActivityDestroyed() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord task = createTask(service, testActivityComponent, TEST_STACK_ID);
        final ActivityRecord activityRecord = createActivity(service, testActivityComponent, task);
        activityRecord.info.flags |= ActivityInfo.FLAG_NO_HISTORY;
        final ActivityStack testStack = service.mStackSupervisor.getStack(TEST_STACK_ID);
        service.mStackSupervisor.setFocusStackUnchecked("testStopActivityWithDestroy", testStack);

        testStack.stopActivityLocked(activityRecord);
    }

    /**
     * This test verifies that {@link ActivityStack#STACK_VISIBLE_ACTIVITY_BEHIND} is returned from
     * {@link ActivityStack#shouldBeVisible(ActivityRecord)} from a fullscreen workspace stack with
     * a visible behind activity when top focused stack is the home stack.
     */
    @Test
    public void testShouldBeVisibleWithVisibleBehindActivity() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord task = createTask(service, testActivityComponent,
                ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID);
        final ActivityStack fullscreenWorkspaceStackId = task.getStack();
        final ActivityStack homeStack = service.mStackSupervisor.getStack(
                ActivityManager.StackId.HOME_STACK_ID, true /*createStaticStackIfNeeded*/,
                true /*onTop*/);
        final ActivityRecord activityRecord = createActivity(service, testActivityComponent, task);
        service.mStackSupervisor.setFocusStackUnchecked("testEmptyStackShouldBeVisible", homeStack);
        service.mStackSupervisor.requestVisibleBehindLocked(activityRecord, true);
        assertEquals(ActivityStack.STACK_VISIBLE_ACTIVITY_BEHIND,
                fullscreenWorkspaceStackId.shouldBeVisible(null /*starting*/));
    }

    @Test
    public void testNavigateUpTo() {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord task = createTask(service, testActivityComponent, TEST_STACK_ID);
        final ActivityRecord activityRecord = createActivity(service, testActivityComponent, task);
        activityRecord.app = new ProcessRecord(null, activityRecord.appInfo,
                activityRecord.processName, activityRecord.getUid());
        final ActivityStack testStack = service.mStackSupervisor.getStack(TEST_STACK_ID);
        // No-op if the source activity record doesn't have attached process (app.thread == null).
        assertFalse(testStack.navigateUpToLocked(activityRecord, activityRecord.intent,
                0 /* resultCode */, null /* resultData */));
    }
}
