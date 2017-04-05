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

import static org.junit.Assert.assertTrue;

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
// TODO(b/36916522): Currently failing in CI.
// @Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityRecordTests extends ActivityTestsBase {
    private final ComponentName testActivityComponent =
            ComponentName.unflattenFromString("com.foo/.BarActivity");
    @Test
    public void testStackCleanupOnClearingTask() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TestActivityStack testStack = new ActivityStackBuilder(service).build();
        final TaskRecord task = createTask(service, testActivityComponent, testStack);
        final ActivityRecord record = createActivity(service, testActivityComponent, task);

        record.setTask(null);
        assertTrue(testStack.onActivityRemovedFromStackInvocationCount() == 1);
    }

    @Test
    public void testStackCleanupOnActivityRemoval() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TestActivityStack testStack = new ActivityStackBuilder(service).build();
        final TaskRecord task = createTask(service, testActivityComponent, testStack);
        final ActivityRecord record = createActivity(service, testActivityComponent, task);

        task.removeActivity(record);
        assertTrue(testStack.onActivityRemovedFromStackInvocationCount() == 1);
    }

    @Test
    public void testStackCleanupOnTaskRemoval() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TestActivityStack testStack = new ActivityStackBuilder(service).build();
        final TaskRecord task = createTask(service, testActivityComponent, testStack);
        final ActivityRecord record = createActivity(service, testActivityComponent, task);

        testStack.removeTask(task, null /*reason*/, ActivityStack.REMOVE_TASK_MODE_MOVING);
        assertTrue(testStack.onActivityRemovedFromStackInvocationCount() == 1);
    }

    @Test
    public void testNoCleanupMovingActivityInSameStack() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TestActivityStack testStack = new ActivityStackBuilder(service).build();
        final TaskRecord oldTask = createTask(service, testActivityComponent, testStack);
        final ActivityRecord record = createActivity(service, testActivityComponent, oldTask);
        final TaskRecord newTask = createTask(service, testActivityComponent, testStack);

        record.reparent(newTask, 0, null /*reason*/);
        assertTrue(testStack.onActivityRemovedFromStackInvocationCount() == 0);
    }
}
