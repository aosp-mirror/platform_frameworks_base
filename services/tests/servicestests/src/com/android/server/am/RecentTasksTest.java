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
 * limitations under the License.
 */

package com.android.server.am;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import android.content.ComponentName;
import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseBooleanArray;

import com.android.server.am.RecentTasks.Callbacks;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * runtest --path frameworks/base/services/tests/servicestests/src/com/android/server/am/RecentTasksTest.java
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RecentTasksTest extends ActivityTestsBase {
    private static final int TEST_USER_0_ID = 0;
    private static final int TEST_USER_1_ID = 10;

    private Context mContext = InstrumentationRegistry.getContext();
    private ActivityManagerService mService;
    private ActivityStack mStack;
    private TestTaskPersister mTaskPersister;
    private RecentTasks mRecentTasks;

    private static ArrayList<TaskRecord> mTasks = new ArrayList<>();
    private static ArrayList<TaskRecord> mSameDocumentTasks = new ArrayList<>();

    private CallbacksRecorder mCallbacksRecorder;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mService = createActivityManagerService();
        mStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mTaskPersister = new TestTaskPersister(mContext.getFilesDir());
        mRecentTasks = new RecentTasks(mService, mTaskPersister);
        mCallbacksRecorder = new CallbacksRecorder();
        mRecentTasks.registerCallback(mCallbacksRecorder);

        mTasks.add(createTask(".Task1"));
        mTasks.add(createTask(".Task2"));
        mTasks.add(createTask(".Task3"));

        mSameDocumentTasks.add(createDocumentTask(".DocumentTask1", null /* affinity */));
        mSameDocumentTasks.add(createDocumentTask(".DocumentTask1", null /* affinity */));
    }

    @Test
    public void testCallbacks() throws Exception {
        // Add some tasks
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        assertTrue(mCallbacksRecorder.added.contains(mTasks.get(0))
                && mCallbacksRecorder.added.contains(mTasks.get(1)));
        assertTrue(mCallbacksRecorder.removed.isEmpty());
        mCallbacksRecorder.clear();

        // Remove some tasks
        mRecentTasks.remove(mTasks.get(0));
        mRecentTasks.remove(mTasks.get(1));
        assertTrue(mCallbacksRecorder.added.isEmpty());
        assertTrue(mCallbacksRecorder.removed.contains(mTasks.get(0)));
        assertTrue(mCallbacksRecorder.removed.contains(mTasks.get(1)));
        mCallbacksRecorder.clear();

        // Add a task which will trigger the trimming of another
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1", null /* affinity */);
        documentTask1.maxRecents = 1;
        TaskRecord documentTask2 = createDocumentTask(".DocumentTask1", null /* affinity */);
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask2);
        assertTrue(mCallbacksRecorder.added.contains(documentTask1));
        assertTrue(mCallbacksRecorder.added.contains(documentTask2));
        assertTrue(mCallbacksRecorder.removed.contains(documentTask1));
        mCallbacksRecorder.clear();

        // Remove the callback, ensure we don't get any calls
        mRecentTasks.unregisterCallback(mCallbacksRecorder);
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.remove(mTasks.get(0));
        assertTrue(mCallbacksRecorder.added.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
    }

    @Test
    public void testUsersTasks() throws Exception {
        // Setup some tasks for the users
        mTaskPersister.userTaskIdsOverride = new SparseBooleanArray();
        mTaskPersister.userTaskIdsOverride.put(1, true);
        mTaskPersister.userTaskIdsOverride.put(2, true);
        mTaskPersister.userTasksOverride = new ArrayList<>();
        mTaskPersister.userTasksOverride.add(createTask(".UserTask1"));
        mTaskPersister.userTasksOverride.add(createTask(".UserTask2"));

        // Assert no user tasks are initially loaded
        assertTrue(mRecentTasks.usersWithRecentsLoadedLocked().length == 0);

        // Load user 0 tasks
        mRecentTasks.loadUserRecentsLocked(TEST_USER_0_ID);
        assertTrue(arrayContainsUser(mRecentTasks.usersWithRecentsLoadedLocked(), TEST_USER_0_ID));
        assertTrue(mRecentTasks.containsTaskId(1, TEST_USER_0_ID));
        assertTrue(mRecentTasks.containsTaskId(2, TEST_USER_0_ID));

        // Load user 1 tasks
        mRecentTasks.loadUserRecentsLocked(TEST_USER_1_ID);
        assertTrue(arrayContainsUser(mRecentTasks.usersWithRecentsLoadedLocked(), TEST_USER_0_ID));
        assertTrue(arrayContainsUser(mRecentTasks.usersWithRecentsLoadedLocked(), TEST_USER_1_ID));
        assertTrue(mRecentTasks.containsTaskId(1, TEST_USER_0_ID));
        assertTrue(mRecentTasks.containsTaskId(2, TEST_USER_0_ID));
        assertTrue(mRecentTasks.containsTaskId(1, TEST_USER_1_ID));
        assertTrue(mRecentTasks.containsTaskId(2, TEST_USER_1_ID));

        // Unload user 1 tasks
        mRecentTasks.unloadUserDataFromMemoryLocked(TEST_USER_1_ID);
        assertTrue(arrayContainsUser(mRecentTasks.usersWithRecentsLoadedLocked(), TEST_USER_0_ID));
        assertFalse(arrayContainsUser(mRecentTasks.usersWithRecentsLoadedLocked(), TEST_USER_1_ID));
        assertTrue(mRecentTasks.containsTaskId(1, TEST_USER_0_ID));
        assertTrue(mRecentTasks.containsTaskId(2, TEST_USER_0_ID));

        // Unload user 0 tasks
        mRecentTasks.unloadUserDataFromMemoryLocked(TEST_USER_0_ID);
        assertFalse(arrayContainsUser(mRecentTasks.usersWithRecentsLoadedLocked(), TEST_USER_0_ID));
        assertFalse(arrayContainsUser(mRecentTasks.usersWithRecentsLoadedLocked(), TEST_USER_1_ID));
    }

    private ComponentName createComponent(String className) {
        return new ComponentName(mContext.getPackageName(), className);
    }

    private TaskRecord createTask(String className) {
        return createTask(mService.mStackSupervisor, createComponent(className), mStack);
    }

    private TaskRecord createDocumentTask(String className, String affinity) {
        TaskRecord task = createTask(mService.mStackSupervisor, createComponent(className),
                FLAG_ACTIVITY_NEW_DOCUMENT, mStack);
        task.affinity = affinity;
        return task;
    }

    private boolean arrayContainsUser(int[] userIds, int targetUserId) {
        Arrays.sort(userIds);
        return Arrays.binarySearch(userIds, targetUserId) >= 0;
    }

    private static class CallbacksRecorder implements Callbacks {
        ArrayList<TaskRecord> added = new ArrayList<>();
        ArrayList<TaskRecord> removed = new ArrayList<>();

        void clear() {
            added.clear();
            removed.clear();
        }

        @Override
        public void onRecentTaskAdded(TaskRecord task) {
            added.add(task);
        }

        @Override
        public void onRecentTaskRemoved(TaskRecord task) {
            removed.add(task);
        }
    }

    private static class TestTaskPersister extends TaskPersister {

        SparseBooleanArray userTaskIdsOverride;
        ArrayList<TaskRecord> userTasksOverride;

        TestTaskPersister(File workingDir) {
            super(workingDir);
        }

        @Override
        SparseBooleanArray loadPersistedTaskIdsForUser(int userId) {
            if (userTaskIdsOverride != null) {
                return userTaskIdsOverride;
            }
            return super.loadPersistedTaskIdsForUser(userId);
        }

        @Override
        List<TaskRecord> restoreTasksForUserLocked(int userId, SparseBooleanArray preaddedTasks) {
            if (userTasksOverride != null) {
                return userTasksOverride;
            }
            return super.restoreTasksForUserLocked(userId, preaddedTasks);
        }
    }
}