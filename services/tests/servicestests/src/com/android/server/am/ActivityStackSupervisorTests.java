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

import static org.junit.Assert.assertNull;

import android.os.Debug;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
import org.junit.Test;

import static com.android.server.am.ActivityStackSupervisor.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE;

/**
 * Tests for the {@link ActivityStackSupervisor} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.ActivityStackSupervisorTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStackSupervisorTests extends ActivityTestsBase {
    /**
     * This test ensures that we do not try to restore a task based off an invalid task id. The
     * stack supervisor is a test version so there will be no tasks present. We should expect
     * {@code null} to be returned in this case.
     */
    @Test
    public void testRestoringInvalidTask() throws Exception {
        Debug.waitForDebugger();
        final ActivityManagerService service = createActivityManagerService();
        TaskRecord task = service.mStackSupervisor.anyTaskForIdLocked(0 /*taskId*/,
                MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE, 0 /*stackId*/);
        assertNull(task);
    }
}
