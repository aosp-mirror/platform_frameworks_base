/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.am;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.IActivityManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Tests for {@link ActivityManager}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ActivityManagerTest
 */
@FlakyTest(detail = "Promote to presubmit if stable")
@Presubmit
public class ActivityManagerTest {

    private IActivityManager mService;

    @Before
    public void setUp() throws Exception {
        mService = ActivityManager.getService();
    }

    @Test
    public void testTaskIdsForRunningUsers() throws RemoteException {
        int[] runningUserIds = mService.getRunningUserIds();
        assertThat(runningUserIds).isNotEmpty();
        for (int userId : runningUserIds) {
            testTaskIdsForUser(userId);
        }
    }

    private void testTaskIdsForUser(int userId) throws RemoteException {
        List<?> recentTasks = mService.getRecentTasks(100, 0, userId).getList();
        if (recentTasks != null) {
            for (Object elem : recentTasks) {
                assertThat(elem).isInstanceOf(RecentTaskInfo.class);
                RecentTaskInfo recentTask = (RecentTaskInfo) elem;
                int taskId = recentTask.taskId;
                assertEquals("The task id " + taskId + " should not belong to user " + userId,
                             taskId / UserHandle.PER_USER_RANGE, userId);
            }
        }
    }
}
