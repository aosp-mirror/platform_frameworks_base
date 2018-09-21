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

import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;
import android.util.SparseBooleanArray;

/**
 * Tests for {@link TaskPersister}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskPersisterTest
 */
public class TaskPersisterTest extends AndroidTestCase {
    private static final String TEST_USER_NAME = "AM-Test-User";

    private TaskPersister mTaskPersister;
    private int testUserId;
    private UserManager mUserManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mUserManager = UserManager.get(getContext());
        mTaskPersister = new TaskPersister(getContext().getFilesDir());
        // In ARC, the maximum number of supported users is one, which is different from the ones of
        // most phones (more than 4). This prevents TaskPersisterTest from creating another user for
        // test. However, since guest users can be added as much as possible, we create guest user
        // in the test.
        testUserId = createUser(TEST_USER_NAME, UserInfo.FLAG_GUEST);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        mTaskPersister.unloadUserDataFromMemory(testUserId);
        removeUser(testUserId);
    }

    private int getRandomTaskIdForUser(int userId) {
        int taskId = (int) (Math.random() * UserHandle.PER_USER_RANGE);
        taskId += UserHandle.PER_USER_RANGE * userId;
        return taskId;
    }

    public void testTaskIdsPersistence() {
        SparseBooleanArray taskIdsOnFile = new SparseBooleanArray();
        for (int i = 0; i < 100; i++) {
            taskIdsOnFile.put(getRandomTaskIdForUser(testUserId), true);
        }
        mTaskPersister.writePersistedTaskIdsForUser(taskIdsOnFile, testUserId);
        SparseBooleanArray newTaskIdsOnFile = mTaskPersister
                .loadPersistedTaskIdsForUser(testUserId);
        assertTrue("TaskIds written differ from TaskIds read back from file",
                taskIdsOnFile.equals(newTaskIdsOnFile));
    }

    private int createUser(String name, int flags) {
        UserInfo user = mUserManager.createUser(name, flags);
        if (user == null) {
            fail("Error while creating the test user: " + TEST_USER_NAME);
        }
        return user.id;
    }

    private void removeUser(int userId) {
        if (!mUserManager.removeUser(userId)) {
            fail("Error while removing the test user: " + TEST_USER_NAME);
        }
    }
}