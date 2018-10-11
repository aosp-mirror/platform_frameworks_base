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

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.RemoteException;
import android.test.AndroidTestCase;

import java.util.List;

public class ActivityManagerTest extends AndroidTestCase {

    IActivityManager service;
    @Override
    public void setUp() throws Exception {
        super.setUp();
        service = ActivityManager.getService();
    }

    public void testTaskIdsForRunningUsers() throws RemoteException {
        for(int userId : service.getRunningUserIds()) {
            testTaskIdsForUser(userId);
        }
    }

    private void testTaskIdsForUser(int userId) throws RemoteException {
        List<ActivityManager.RecentTaskInfo> recentTasks = service.getRecentTasks(
                100, 0, userId).getList();
        if(recentTasks != null) {
            for(ActivityManager.RecentTaskInfo recentTask : recentTasks) {
                int taskId = recentTask.persistentId;
                assertEquals("The task id " + taskId + " should not belong to user " + userId,
                        taskId / UserHandle.PER_USER_RANGE, userId);
            }
        }
    }
}
