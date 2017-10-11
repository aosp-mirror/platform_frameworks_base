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
import static org.junit.Assert.assertNull;

import android.content.ComponentName;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
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
    private static final int TEST_STACK_ID = 100;

    private final ComponentName testActivityComponent =
            ComponentName.unflattenFromString("com.foo/.BarActivity");
    @Test
    public void testStackCleanupOnClearingTask() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord task = createTask(service, testActivityComponent, TEST_STACK_ID);
        final ActivityRecord record = createActivity(service, testActivityComponent, task);

        record.setTask(null);
        assertEquals(getActivityRemovedFromStackCount(service, TEST_STACK_ID), 1);
    }

    @Test
    public void testStackCleanupOnActivityRemoval() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord task = createTask(service, testActivityComponent, TEST_STACK_ID);
        final ActivityRecord record = createActivity(service, testActivityComponent, task);

        task.removeActivity(record);
        assertEquals(getActivityRemovedFromStackCount(service, TEST_STACK_ID),  1);
    }

    @Test
    public void testStackCleanupOnTaskRemoval() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord task = createTask(service, testActivityComponent, TEST_STACK_ID);
        final ActivityRecord record = createActivity(service, testActivityComponent, task);

        service.mStackSupervisor.getStack(TEST_STACK_ID)
                .removeTask(task, null /*reason*/, ActivityStack.REMOVE_TASK_MODE_MOVING);

        // Stack should be gone on task removal.
        assertNull(service.mStackSupervisor.getStack(TEST_STACK_ID));
    }

    @Test
    public void testNoCleanupMovingActivityInSameStack() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord oldTask = createTask(service, testActivityComponent, TEST_STACK_ID);
        final ActivityRecord record = createActivity(service, testActivityComponent, oldTask);
        final TaskRecord newTask = createTask(service, testActivityComponent, TEST_STACK_ID);

        record.reparent(newTask, 0, null /*reason*/);
        assertEquals(getActivityRemovedFromStackCount(service, TEST_STACK_ID), 0);
    }

    private static int getActivityRemovedFromStackCount(ActivityManagerService service,
            int stackId) {
        final ActivityStack stack = service.mStackSupervisor.getStack(stackId);
        if (stack instanceof ActivityStackReporter) {
            return ((ActivityStackReporter) stack).onActivityRemovedFromStackInvocationCount();
        }

        return -1;
    }
}
