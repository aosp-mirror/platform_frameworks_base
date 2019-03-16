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

import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import static java.lang.Integer.MAX_VALUE;

import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.MutableLong;
import android.util.SparseBooleanArray;

import androidx.test.filters.MediumTest;

import com.android.server.wm.RecentTasks.Callbacks;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Build/Install/Run:
 *  atest WmTests:RecentTasksTest
 */
@MediumTest
@Presubmit
public class RecentTasksTest extends ActivityTestsBase {
    private static final int TEST_USER_0_ID = 0;
    private static final int TEST_USER_1_ID = 10;
    private static final int TEST_QUIET_USER_ID = 20;
    private static final UserInfo DEFAULT_USER_INFO = new UserInfo();
    private static final UserInfo QUIET_USER_INFO = new UserInfo();
    private static int sLastTaskId = 1;
    private static int sLastStackId = 1;
    private static final int INVALID_STACK_ID = 999;

    private TestActivityTaskManagerService mTestService;
    private ActivityDisplay mDisplay;
    private ActivityDisplay mOtherDisplay;
    private ActivityDisplay mSingleTaskDisplay;
    private ActivityStack mStack;
    private ActivityStack mHomeStack;
    private TestTaskPersister mTaskPersister;
    private TestRecentTasks mRecentTasks;
    private TestRunningTasks mRunningTasks;

    private ArrayList<TaskRecord> mTasks;
    private ArrayList<TaskRecord> mSameDocumentTasks;

    private CallbacksRecorder mCallbacksRecorder;

    @Before
    public void setUp() throws Exception {
        mTaskPersister = new TestTaskPersister(mContext.getFilesDir());
        mTestService = new MyTestActivityTaskManagerService(mContext);
        mRecentTasks = (TestRecentTasks) mTestService.getRecentTasks();
        mRecentTasks.loadParametersFromResources(mContext.getResources());
        mRunningTasks = (TestRunningTasks) mTestService.mStackSupervisor.mRunningTasks;
        mHomeStack = mTestService.mRootActivityContainer.getDefaultDisplay().getOrCreateStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        mStack = mTestService.mRootActivityContainer.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mCallbacksRecorder = new CallbacksRecorder();
        mRecentTasks.registerCallback(mCallbacksRecorder);
        QUIET_USER_INFO.flags = UserInfo.FLAG_MANAGED_PROFILE | UserInfo.FLAG_QUIET_MODE;

        mTasks = new ArrayList<>();
        mTasks.add(createTaskBuilder(".Task1").build());
        mTasks.add(createTaskBuilder(".Task2").build());
        mTasks.add(createTaskBuilder(".Task3").build());
        mTasks.add(createTaskBuilder(".Task4").build());
        mTasks.add(createTaskBuilder(".Task5").build());

        mSameDocumentTasks = new ArrayList<>();
        mSameDocumentTasks.add(createDocumentTask(".DocumentTask1"));
        mSameDocumentTasks.add(createDocumentTask(".DocumentTask1"));
    }

    @Test
    public void testCallbacks() {
        // Add some tasks
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        assertThat(mCallbacksRecorder.mAdded).contains(mTasks.get(0));
        assertThat(mCallbacksRecorder.mAdded).contains(mTasks.get(1));
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
        mCallbacksRecorder.clear();

        // Remove some tasks
        mRecentTasks.remove(mTasks.get(0));
        mRecentTasks.remove(mTasks.get(1));
        assertThat(mCallbacksRecorder.mAdded).isEmpty();
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).contains(mTasks.get(0));
        assertThat(mCallbacksRecorder.mRemoved).contains(mTasks.get(1));
        mCallbacksRecorder.clear();

        // Remove the callback, ensure we don't get any calls
        mRecentTasks.unregisterCallback(mCallbacksRecorder);
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.remove(mTasks.get(0));
        assertThat(mCallbacksRecorder.mAdded).isEmpty();
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
    }

    @Test
    public void testAddTasksNoMultiple_expectNoTrim() {
        // Add same non-multiple-task document tasks will remove the task (to re-add it) but not
        // trim it
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1");
        TaskRecord documentTask2 = createDocumentTask(".DocumentTask1");
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask2);
        assertThat(mCallbacksRecorder.mAdded).contains(documentTask1);
        assertThat(mCallbacksRecorder.mAdded).contains(documentTask2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).contains(documentTask1);
    }

    @Test
    public void testAddTasksMaxTaskRecents_expectNoTrim() {
        // Add a task hitting max-recents for that app will remove the task (to add the next one)
        // but not trim it
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1");
        TaskRecord documentTask2 = createDocumentTask(".DocumentTask1");
        documentTask1.maxRecents = 1;
        documentTask2.maxRecents = 1;
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask2);
        assertThat(mCallbacksRecorder.mAdded).contains(documentTask1);
        assertThat(mCallbacksRecorder.mAdded).contains(documentTask2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).contains(documentTask1);
    }

    @Test
    public void testAddTasksSameTask_expectNoTrim() {
        // Add a task that is already in the task list does not trigger any callbacks, it just
        // moves in the list
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1");
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask1);
        assertThat(mCallbacksRecorder.mAdded).hasSize(1);
        assertThat(mCallbacksRecorder.mAdded).contains(documentTask1);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
    }

    @Test
    public void testAddTasksMultipleDocumentTasks_expectNoTrim() {
        // Add same multiple-task document tasks does not trim the first tasks
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1",
                FLAG_ACTIVITY_MULTIPLE_TASK);
        TaskRecord documentTask2 = createDocumentTask(".DocumentTask1",
                FLAG_ACTIVITY_MULTIPLE_TASK);
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask2);
        assertThat(mCallbacksRecorder.mAdded).hasSize(2);
        assertThat(mCallbacksRecorder.mAdded).contains(documentTask1);
        assertThat(mCallbacksRecorder.mAdded).contains(documentTask2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
    }

    @Test
    public void testAddTasksMultipleTasks_expectRemovedNoTrim() {
        // Add multiple same-affinity non-document tasks, ensure that it removes the other task,
        // but that it does not trim it
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .build();
        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .build();
        mRecentTasks.add(task1);
        assertThat(mCallbacksRecorder.mAdded).hasSize(1);
        assertThat(mCallbacksRecorder.mAdded).contains(task1);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
        mCallbacksRecorder.clear();
        mRecentTasks.add(task2);
        assertThat(mCallbacksRecorder.mAdded).hasSize(1);
        assertThat(mCallbacksRecorder.mAdded).contains(task2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).hasSize(1);
        assertThat(mCallbacksRecorder.mRemoved).contains(task1);
    }

    @Test
    public void testAddTasksDifferentStacks_expectNoRemove() {
        // Adding the same task with different activity types should not trigger removal of the
        // other task
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .setStack(mHomeStack).build();
        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .setStack(mStack).build();
        mRecentTasks.add(task1);
        mRecentTasks.add(task2);
        assertThat(mCallbacksRecorder.mAdded).hasSize(2);
        assertThat(mCallbacksRecorder.mAdded).contains(task1);
        assertThat(mCallbacksRecorder.mAdded).contains(task2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
    }

    @Test
    public void testAddTaskCompatibleActivityType_expectRemove() {
        // Test with undefined activity type since the type is not persisted by the task persister
        // and we want to ensure that a new task will match a restored task
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        setTaskActivityType(task1, ACTIVITY_TYPE_UNDEFINED);
        assertThat(task1.getActivityType()).isEqualTo(ACTIVITY_TYPE_UNDEFINED);
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        assertEquals(ACTIVITY_TYPE_STANDARD, task2.getActivityType());
        mRecentTasks.add(task2);
        assertThat(mCallbacksRecorder.mAdded).hasSize(1);
        assertThat(mCallbacksRecorder.mAdded).contains(task2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).hasSize(1);
        assertThat(mCallbacksRecorder.mRemoved).contains(task1);
    }

    @Test
    public void testAddTaskCompatibleActivityTypeDifferentUser_expectNoRemove() {
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .setUserId(TEST_USER_0_ID)
                .build();
        setTaskActivityType(task1, ACTIVITY_TYPE_UNDEFINED);
        assertEquals(ACTIVITY_TYPE_UNDEFINED, task1.getActivityType());
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .setUserId(TEST_USER_1_ID)
                .build();
        assertEquals(ACTIVITY_TYPE_STANDARD, task2.getActivityType());
        mRecentTasks.add(task2);
        assertThat(mCallbacksRecorder.mAdded).hasSize(1);
        assertThat(mCallbacksRecorder.mAdded).contains(task2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
    }

    @Test
    public void testAddTaskCompatibleWindowingMode_expectRemove() {
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        setTaskWindowingMode(task1, WINDOWING_MODE_UNDEFINED);
        assertEquals(WINDOWING_MODE_UNDEFINED, task1.getWindowingMode());
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        setTaskWindowingMode(task2, WINDOWING_MODE_FULLSCREEN);
        assertEquals(WINDOWING_MODE_FULLSCREEN, task2.getWindowingMode());
        mRecentTasks.add(task2);

        assertThat(mCallbacksRecorder.mAdded).hasSize(1);
        assertThat(mCallbacksRecorder.mAdded).contains(task2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).hasSize(1);
        assertThat(mCallbacksRecorder.mRemoved).contains(task1);
    }

    @Test
    public void testAddTaskIncompatibleWindowingMode_expectNoRemove() {
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        setTaskWindowingMode(task1, WINDOWING_MODE_FULLSCREEN);
        assertEquals(WINDOWING_MODE_FULLSCREEN, task1.getWindowingMode());
        mRecentTasks.add(task1);

        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        setTaskWindowingMode(task2, WINDOWING_MODE_PINNED);
        assertEquals(WINDOWING_MODE_PINNED, task2.getWindowingMode());
        mRecentTasks.add(task2);

        assertThat(mCallbacksRecorder.mAdded).hasSize(2);
        assertThat(mCallbacksRecorder.mAdded).contains(task1);
        assertThat(mCallbacksRecorder.mAdded).contains(task2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
    }

    @Test
    public void testUsersTasks() {
        mRecentTasks.setOnlyTestVisibleRange();

        // Setup some tasks for the users
        mTaskPersister.mUserTaskIdsOverride = new SparseBooleanArray();
        mTaskPersister.mUserTaskIdsOverride.put(1, true);
        mTaskPersister.mUserTaskIdsOverride.put(2, true);
        mTaskPersister.mUserTasksOverride = new ArrayList<>();
        mTaskPersister.mUserTasksOverride.add(createTaskBuilder(".UserTask1").build());
        mTaskPersister.mUserTasksOverride.add(createTaskBuilder(".UserTask2").build());

        // Assert no user tasks are initially loaded
        assertThat(mRecentTasks.usersWithRecentsLoadedLocked()).hasLength(0);

        // Load user 0 tasks
        mRecentTasks.loadUserRecentsLocked(TEST_USER_0_ID);
        assertThat(mRecentTasks.usersWithRecentsLoadedLocked()).asList().contains(TEST_USER_0_ID);
        assertTrue(mRecentTasks.containsTaskId(1, TEST_USER_0_ID));
        assertTrue(mRecentTasks.containsTaskId(2, TEST_USER_0_ID));

        // Load user 1 tasks
        mRecentTasks.loadUserRecentsLocked(TEST_USER_1_ID);
        assertThat(mRecentTasks.usersWithRecentsLoadedLocked()).asList().contains(TEST_USER_0_ID);
        assertThat(mRecentTasks.usersWithRecentsLoadedLocked()).asList().contains(TEST_USER_1_ID);
        assertTrue(mRecentTasks.containsTaskId(1, TEST_USER_0_ID));
        assertTrue(mRecentTasks.containsTaskId(2, TEST_USER_0_ID));
        assertTrue(mRecentTasks.containsTaskId(1, TEST_USER_1_ID));
        assertTrue(mRecentTasks.containsTaskId(2, TEST_USER_1_ID));

        // Unload user 1 tasks
        mRecentTasks.unloadUserDataFromMemoryLocked(TEST_USER_1_ID);
        assertThat(mRecentTasks.usersWithRecentsLoadedLocked()).asList().contains(TEST_USER_0_ID);
        assertThat(mRecentTasks.usersWithRecentsLoadedLocked()).asList()
                .doesNotContain(TEST_USER_1_ID);
        assertTrue(mRecentTasks.containsTaskId(1, TEST_USER_0_ID));
        assertTrue(mRecentTasks.containsTaskId(2, TEST_USER_0_ID));

        // Unload user 0 tasks
        mRecentTasks.unloadUserDataFromMemoryLocked(TEST_USER_0_ID);
        assertThat(mRecentTasks.usersWithRecentsLoadedLocked()).asList()
                .doesNotContain(TEST_USER_0_ID);
        assertThat(mRecentTasks.usersWithRecentsLoadedLocked()).asList()
                .doesNotContain(TEST_USER_1_ID);
    }

    @Test
    public void testOrderedIteration() {
        mRecentTasks.setOnlyTestVisibleRange();
        TaskRecord task1 = createTaskBuilder(".Task1").build();
        task1.lastActiveTime = new Random().nextInt();
        TaskRecord task2 = createTaskBuilder(".Task1").build();
        task2.lastActiveTime = new Random().nextInt();
        TaskRecord task3 = createTaskBuilder(".Task1").build();
        task3.lastActiveTime = new Random().nextInt();
        TaskRecord task4 = createTaskBuilder(".Task1").build();
        task4.lastActiveTime = new Random().nextInt();
        mRecentTasks.add(task1);
        mRecentTasks.add(task2);
        mRecentTasks.add(task3);
        mRecentTasks.add(task4);

        MutableLong prevLastActiveTime = new MutableLong(0);
        final ArrayList<TaskRecord> tasks = mRecentTasks.getRawTasks();
        for (int i = 0; i < tasks.size(); i++) {
            final TaskRecord task = tasks.get(i);
            assertThat(prevLastActiveTime.value).isLessThan(task.lastActiveTime);
            prevLastActiveTime.value = task.lastActiveTime;
        }
    }

    @Test
    public void testTrimToGlobalMaxNumRecents() {
        mRecentTasks.setOnlyTestVisibleRange();

        // Limit the global maximum number of recent tasks to a fixed size
        mRecentTasks.setGlobalMaxNumTasks(2 /* globalMaxNumTasks */);

        // Add N+1 tasks
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        mRecentTasks.add(mTasks.get(2));

        // Ensure that the last task was trimmed as an inactive task
        assertTrimmed(mTasks.get(0));
    }

    @Test
    public void testTrimQuietProfileTasks() {
        mRecentTasks.setOnlyTestVisibleRange();
        TaskRecord qt1 = createTaskBuilder(".QuietTask1").setUserId(TEST_QUIET_USER_ID).build();
        TaskRecord qt2 = createTaskBuilder(".QuietTask2").setUserId(TEST_QUIET_USER_ID).build();
        mRecentTasks.add(qt1);
        mRecentTasks.add(qt2);

        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));

        // Ensure that the quiet user's tasks was trimmed once the new tasks were added
        assertTrimmed(qt1, qt2);
    }

    @Test
    public void testSessionDuration() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.setParameters(-1 /* min */, -1 /* max */, 50 /* ms */);

        TaskRecord t1 = createTaskBuilder(".Task1").build();
        t1.touchActiveTime();
        mRecentTasks.add(t1);

        // Force a small sleep just beyond the session duration
        SystemClock.sleep(75);

        TaskRecord t2 = createTaskBuilder(".Task2").build();
        t2.touchActiveTime();
        mRecentTasks.add(t2);

        // Assert that the old task has been removed due to being out of the active session
        assertTrimmed(t1);
    }

    @Test
    public void testVisibleTasks_excludedFromRecents() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.setParameters(-1 /* min */, 4 /* max */, -1 /* ms */);

        TaskRecord excludedTask1 = createTaskBuilder(".ExcludedTask1")
                .setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .build();
        TaskRecord excludedTask2 = createTaskBuilder(".ExcludedTask2")
                .setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .build();

        mRecentTasks.add(excludedTask1);
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        mRecentTasks.add(mTasks.get(2));
        mRecentTasks.add(excludedTask2);

        // The last excluded task should be trimmed, while the first-most excluded task should not
        assertTrimmed(excludedTask1);
    }

    @Test
    public void testVisibleTasks_minNum() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.setParameters(5 /* min */, -1 /* max */, 25 /* ms */);

        for (int i = 0; i < 4; i++) {
            final TaskRecord task = mTasks.get(i);
            task.touchActiveTime();
            mRecentTasks.add(task);
        }

        // Force a small sleep just beyond the session duration
        SystemClock.sleep(50);

        // Add a new task to trigger tasks to be trimmed
        mRecentTasks.add(mTasks.get(4));

        // Ensure that there are a minimum number of tasks regardless of session length
        assertNoTasksTrimmed();
    }

    @Test
    public void testVisibleTasks_maxNum() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.setParameters(-1 /* min */, 3 /* max */, -1 /* ms */);

        for (int i = 0; i < 5; i++) {
            final TaskRecord task = mTasks.get(i);
            task.touchActiveTime();
            mRecentTasks.add(task);
        }

        // Ensure that only the last number of max tasks are kept
        assertTrimmed(mTasks.get(0), mTasks.get(1));
    }

    /**
     * Tests that tasks on singleTaskDisplay are not visible and not trimmed/removed.
     */
    @Test
    public void testVisibleTasks_singleTaskDisplay() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.setParameters(-1 /* min */, 3 /* max */, -1 /* ms */);

        ActivityStack singleTaskStack = mSingleTaskDisplay.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        TaskRecord excludedTask1 = createTaskBuilder(".ExcludedTask1")
                .setStack(singleTaskStack)
                .build();

        assertFalse("Tasks on singleTaskDisplay should not be visible recents",
                mRecentTasks.isVisibleRecentTask(excludedTask1));

        mRecentTasks.add(excludedTask1);

        // Add N+1 visible tasks.
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        mRecentTasks.add(mTasks.get(2));
        mRecentTasks.add(mTasks.get(3));

        // excludedTask is not trimmed.
        assertTrimmed(mTasks.get(0));

        mRecentTasks.removeAllVisibleTasks();

        // Only visible tasks removed.
        assertTrimmed(mTasks.get(0), mTasks.get(1), mTasks.get(2), mTasks.get(3));
    }

    @Test
    public void testFreezeTaskListOrder_reorderExistingTask() {
        // Add some tasks
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        mRecentTasks.add(mTasks.get(2));
        mRecentTasks.add(mTasks.get(3));
        mRecentTasks.add(mTasks.get(4));
        mCallbacksRecorder.clear();

        // Freeze the list
        mRecentTasks.setFreezeTaskListReordering();
        assertTrue(mRecentTasks.isFreezeTaskListReorderingSet());

        // Relaunch a few tasks
        mRecentTasks.add(mTasks.get(3));
        mRecentTasks.add(mTasks.get(2));

        // Commit the task ordering with a specific task focused
        mRecentTasks.resetFreezeTaskListReordering(mTasks.get(2));
        assertFalse(mRecentTasks.isFreezeTaskListReorderingSet());

        // Ensure that the order of the task list is the same as before, but with the focused task
        // at the front
        assertRecentTasksOrder(mTasks.get(2),
                mTasks.get(4),
                mTasks.get(3),
                mTasks.get(1),
                mTasks.get(0));

        assertThat(mCallbacksRecorder.mAdded).isEmpty();
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
    }

    @Test
    public void testFreezeTaskListOrder_addRemoveTasks() {
        // Add some tasks
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        mRecentTasks.add(mTasks.get(2));
        mCallbacksRecorder.clear();

        // Freeze the list
        mRecentTasks.setFreezeTaskListReordering();
        assertTrue(mRecentTasks.isFreezeTaskListReorderingSet());

        // Add and remove some tasks
        mRecentTasks.add(mTasks.get(3));
        mRecentTasks.add(mTasks.get(4));
        mRecentTasks.remove(mTasks.get(0));
        mRecentTasks.remove(mTasks.get(1));

        // Unfreeze the list
        mRecentTasks.resetFreezeTaskListReordering(null);
        assertFalse(mRecentTasks.isFreezeTaskListReorderingSet());

        // Ensure that the order of the task list accounts for the added and removed tasks (added
        // at the end)
        assertRecentTasksOrder(mTasks.get(4),
                mTasks.get(3),
                mTasks.get(2));

        assertThat(mCallbacksRecorder.mAdded).hasSize(2);
        assertThat(mCallbacksRecorder.mAdded).contains(mTasks.get(3));
        assertThat(mCallbacksRecorder.mAdded).contains(mTasks.get(4));
        assertThat(mCallbacksRecorder.mRemoved).hasSize(2);
        assertThat(mCallbacksRecorder.mRemoved).contains(mTasks.get(0));
        assertThat(mCallbacksRecorder.mRemoved).contains(mTasks.get(1));
    }

    @Test
    public void testFreezeTaskListOrder_timeout() {
        // Add some tasks
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        mRecentTasks.add(mTasks.get(2));
        mRecentTasks.add(mTasks.get(3));
        mRecentTasks.add(mTasks.get(4));

        // Freeze the list
        long freezeTime = SystemClock.elapsedRealtime();
        mRecentTasks.setFreezeTaskListReordering();
        assertTrue(mRecentTasks.isFreezeTaskListReorderingSet());

        // Relaunch a few tasks
        mRecentTasks.add(mTasks.get(2));
        mRecentTasks.add(mTasks.get(1));

        // Override the freeze timeout params to simulate the timeout (simulate the freeze at 100ms
        // ago with a timeout of 1ms)
        mRecentTasks.setFreezeTaskListTimeoutParams(freezeTime - 100, 1);

        ActivityStack stack = mTasks.get(2).getStack();
        stack.moveToFront("", mTasks.get(2));
        doReturn(stack).when(mTestService.mRootActivityContainer).getTopDisplayFocusedStack();
        mRecentTasks.resetFreezeTaskListReorderingOnTimeout();
        assertFalse(mRecentTasks.isFreezeTaskListReorderingSet());

        // Ensure that the order of the task list is the same as before, but with the focused task
        // at the front
        assertRecentTasksOrder(mTasks.get(2),
                mTasks.get(4),
                mTasks.get(3),
                mTasks.get(1),
                mTasks.get(0));
    }

    @Test
    public void testBackStackTasks_expectNoTrim() {
        mRecentTasks.setParameters(-1 /* min */, 1 /* max */, -1 /* ms */);

        final MyTestActivityStackSupervisor supervisor =
                (MyTestActivityStackSupervisor) mTestService.mStackSupervisor;
        final ActivityStack homeStack = mDisplay.getHomeStack();
        final ActivityStack aboveHomeStack = new MyTestActivityStack(mDisplay, supervisor);

        // Add a number of tasks (beyond the max) but ensure that nothing is trimmed because all
        // the tasks belong in stacks above the home stack
        mRecentTasks.add(createTaskBuilder(".HomeTask1").setStack(homeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task1").setStack(aboveHomeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task2").setStack(aboveHomeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task3").setStack(aboveHomeStack).build());

        assertNoTasksTrimmed();
    }

    @Test
    public void testBehindHomeStackTasks_expectTaskTrimmed() {
        mRecentTasks.setParameters(-1 /* min */, 1 /* max */, -1 /* ms */);

        final MyTestActivityStackSupervisor supervisor =
                (MyTestActivityStackSupervisor) mTestService.mStackSupervisor;
        final ActivityStack behindHomeStack = new MyTestActivityStack(mDisplay, supervisor);
        final ActivityStack homeStack = mDisplay.getHomeStack();
        final ActivityStack aboveHomeStack = new MyTestActivityStack(mDisplay, supervisor);

        // Add a number of tasks (beyond the max) but ensure that only the task in the stack behind
        // the home stack is trimmed once a new task is added
        final TaskRecord behindHomeTask = createTaskBuilder(".Task1")
                .setStack(behindHomeStack)
                .build();
        mRecentTasks.add(behindHomeTask);
        mRecentTasks.add(createTaskBuilder(".HomeTask1").setStack(homeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task2").setStack(aboveHomeStack).build());

        assertTrimmed(behindHomeTask);
    }

    @Test
    public void testOtherDisplayTasks_expectNoTrim() {
        mRecentTasks.setParameters(-1 /* min */, 1 /* max */, -1 /* ms */);

        final MyTestActivityStackSupervisor supervisor =
                (MyTestActivityStackSupervisor) mTestService.mStackSupervisor;
        final ActivityStack homeStack = mDisplay.getHomeStack();
        final ActivityStack otherDisplayStack = new MyTestActivityStack(mOtherDisplay, supervisor);

        // Add a number of tasks (beyond the max) on each display, ensure that the tasks are not
        // removed
        mRecentTasks.add(createTaskBuilder(".HomeTask1").setStack(homeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task1").setStack(otherDisplayStack).build());
        mRecentTasks.add(createTaskBuilder(".Task2").setStack(otherDisplayStack).build());
        mRecentTasks.add(createTaskBuilder(".HomeTask2").setStack(homeStack).build());

        assertNoTasksTrimmed();
    }

    @Test
    public void testRemovePackageByName() {
        // Add a number of tasks with the same package name
        mRecentTasks.add(createTaskBuilder("com.android.pkg1", ".Task1").build());
        mRecentTasks.add(createTaskBuilder("com.android.pkg2", ".Task2").build());
        mRecentTasks.add(createTaskBuilder("com.android.pkg3", ".Task3").build());
        mRecentTasks.add(createTaskBuilder("com.android.pkg1", ".Task4").build());
        mRecentTasks.removeTasksByPackageName("com.android.pkg1", TEST_USER_0_ID);

        final ArrayList<TaskRecord> tasks = mRecentTasks.getRawTasks();
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).intent.getComponent().getPackageName().equals("com.android.pkg1")) {
                fail("Expected com.android.pkg1 tasks to be removed");
            }
        }
    }

    @Test
    public void testRemoveAllVisibleTasks() {
        mRecentTasks.setParameters(-1 /* min */, 3 /* max */, 100 /* ms */);

        // Create some set of tasks, some of which are visible and some are not
        TaskRecord t1 = createTaskBuilder("com.android.pkg1", ".Task1").build();
        mRecentTasks.add(t1);
        mRecentTasks.add(setTaskActivityType(
                createTaskBuilder("com.android.pkg1", ".HomeTask").build(),
                ACTIVITY_TYPE_HOME));
        TaskRecord t2 = createTaskBuilder("com.android.pkg2", ".Task2").build();
        mRecentTasks.add(t2);
        mRecentTasks.add(setTaskWindowingMode(
                createTaskBuilder("com.android.pkg1", ".PipTask").build(),
                WINDOWING_MODE_PINNED));
        TaskRecord t3 = createTaskBuilder("com.android.pkg3", ".Task3").build();
        mRecentTasks.add(t3);

        // Create some more tasks that are out of visible range, but are still visible
        TaskRecord t4 = createTaskBuilder("com.android.pkg3", ".Task4").build();
        mRecentTasks.add(t4);
        TaskRecord t5 = createTaskBuilder("com.android.pkg3", ".Task5").build();
        mRecentTasks.add(t5);

        // Create some more tasks that are out of the active session range, but are still visible
        TaskRecord t6 = createTaskBuilder("com.android.pkg3", ".Task6").build();
        t6.lastActiveTime = SystemClock.elapsedRealtime() - 200;
        mRecentTasks.add(t6);
        TaskRecord t7 = createTaskBuilder("com.android.pkg3", ".Task7").build();
        t7.lastActiveTime = SystemClock.elapsedRealtime() - 200;
        mRecentTasks.add(t7);

        // Remove all the visible tasks and ensure that they are removed
        mRecentTasks.removeAllVisibleTasks();
        assertTrimmed(t1, t2, t3, t4, t5, t6, t7);
    }

    @Test
    public void testNotRestoreRecentTaskApis() {
        final TaskRecord task = createTaskBuilder(".Task").build();
        final int taskId = task.taskId;
        mRecentTasks.add(task);
        // Only keep the task in RecentTasks.
        task.removeWindowContainer();
        mStack.remove();

        // The following APIs should not restore task from recents to the active list.
        assertNotRestoreTask(() -> mTestService.setFocusedTask(taskId));
        assertNotRestoreTask(() -> mTestService.startSystemLockTaskMode(taskId));
        assertNotRestoreTask(() -> mTestService.cancelTaskWindowTransition(taskId));
        assertNotRestoreTask(
                () -> mTestService.resizeTask(taskId, null /* bounds */, 0 /* resizeMode */));
        assertNotRestoreTask(
                () -> mTestService.setTaskWindowingMode(taskId, WINDOWING_MODE_FULLSCREEN,
                        false/* toTop */));
        assertNotRestoreTask(
                () -> mTestService.setTaskWindowingModeSplitScreenPrimary(taskId,
                        SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT,
                        false /* toTop */, false /* animate */, null /* initialBounds */,
                        true /* showRecents */));
    }

    /**
     * Ensures that the recent tasks list is in the provided order. Note that the expected tasks
     * should be ordered from least to most recent.
     */
    private void assertRecentTasksOrder(TaskRecord... expectedTasks) {
        ArrayList<TaskRecord> tasks = mRecentTasks.getRawTasks();
        assertTrue(expectedTasks.length == tasks.size());
        for (int i = 0; i < tasks.size(); i++)  {
            assertTrue(expectedTasks[i] == tasks.get(i));
        }
    }

    private void assertNotRestoreTask(Runnable action) {
        // Verify stack count doesn't change because task with fullscreen mode and standard type
        // would have its own stack.
        final int orignalStackCount = mDisplay.getChildCount();
        action.run();
        assertEquals(orignalStackCount, mDisplay.getChildCount());
    }

    @Test
    public void testNotRecentsComponent_denyApiAccess() throws Exception {
        doReturn(PackageManager.PERMISSION_DENIED).when(mTestService)
                .checkGetTasksPermission(anyString(), anyInt(), anyInt());
        // Expect the following methods to fail due to recents component not being set
        mRecentTasks.setIsCallerRecentsOverride(TestRecentTasks.DENY_THROW_SECURITY_EXCEPTION);
        doTestRecentTasksApis(false /* expectNoSecurityException */);
        // Don't throw for the following tests
        mRecentTasks.setIsCallerRecentsOverride(TestRecentTasks.DENY);
        testGetTasksApis(false /* expectNoSecurityException */);
    }

    @Test
    public void testRecentsComponent_allowApiAccessWithoutPermissions() {
        doReturn(PackageManager.PERMISSION_DENIED).when(mTestService)
                .checkGetTasksPermission(anyString(), anyInt(), anyInt());

        // Set the recents component and ensure that the following calls do not fail
        mRecentTasks.setIsCallerRecentsOverride(TestRecentTasks.GRANT);
        doTestRecentTasksApis(true /* expectNoSecurityException */);
        testGetTasksApis(true /* expectNoSecurityException */);
    }

    private void doTestRecentTasksApis(boolean expectCallable) {
        assertSecurityException(expectCallable, () -> mTestService.removeStack(INVALID_STACK_ID));
        assertSecurityException(expectCallable,
                () -> mTestService.removeStacksInWindowingModes(
                        new int[]{WINDOWING_MODE_UNDEFINED}));
        assertSecurityException(expectCallable,
                () -> mTestService.removeStacksWithActivityTypes(
                        new int[]{ACTIVITY_TYPE_UNDEFINED}));
        assertSecurityException(expectCallable, () -> mTestService.removeTask(0));
        assertSecurityException(expectCallable,
                () -> mTestService.setTaskWindowingMode(0, WINDOWING_MODE_UNDEFINED, true));
        assertSecurityException(expectCallable,
                () -> mTestService.moveTaskToStack(0, INVALID_STACK_ID, true));
        assertSecurityException(expectCallable,
                () -> mTestService.setTaskWindowingModeSplitScreenPrimary(0,
                        SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, true, true, new Rect(), true));
        assertSecurityException(expectCallable, () -> mTestService.dismissSplitScreenMode(true));
        assertSecurityException(expectCallable, () -> mTestService.dismissPip(true, 0));
        assertSecurityException(expectCallable,
                () -> mTestService.moveTopActivityToPinnedStack(INVALID_STACK_ID, new Rect()));
        assertSecurityException(expectCallable,
                () -> mTestService.resizeStack(INVALID_STACK_ID, new Rect(), true, true, true, 0));
        assertSecurityException(expectCallable,
                () -> mTestService.resizeDockedStack(new Rect(), new Rect(), new Rect(), new Rect(),
                        new Rect()));
        assertSecurityException(expectCallable,
                () -> mTestService.resizePinnedStack(new Rect(), new Rect()));
        assertSecurityException(expectCallable, () -> mTestService.getAllStackInfos());
        assertSecurityException(expectCallable,
                () -> mTestService.getStackInfo(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_UNDEFINED));
        assertSecurityException(expectCallable, () -> {
            try {
                mTestService.getFocusedStackInfo();
            } catch (RemoteException e) {
                // Ignore
            }
        });
        assertSecurityException(expectCallable,
                () -> mTestService.moveTasksToFullscreenStack(INVALID_STACK_ID, true));
        assertSecurityException(expectCallable,
                () -> mTestService.startActivityFromRecents(0, new Bundle()));
        assertSecurityException(expectCallable, () -> mTestService.getTaskSnapshot(0, true));
        assertSecurityException(expectCallable, () -> mTestService.registerTaskStackListener(null));
        assertSecurityException(expectCallable,
                () -> mTestService.unregisterTaskStackListener(null));
        assertSecurityException(expectCallable, () -> mTestService.getTaskDescription(0));
        assertSecurityException(expectCallable, () -> mTestService.cancelTaskWindowTransition(0));
        assertSecurityException(expectCallable, () -> mTestService.startRecentsActivity(null, null,
                null));
        assertSecurityException(expectCallable, () -> mTestService.cancelRecentsAnimation(true));
        assertSecurityException(expectCallable, () -> mTestService.stopAppSwitches());
        assertSecurityException(expectCallable, () -> mTestService.resumeAppSwitches());
    }

    private void testGetTasksApis(boolean expectCallable) {
        mTestService.getRecentTasks(MAX_VALUE, 0, TEST_USER_0_ID);
        mTestService.getTasks(MAX_VALUE);
        if (expectCallable) {
            assertTrue(mRecentTasks.mLastAllowed);
            assertTrue(mRunningTasks.mLastAllowed);
        } else {
            assertFalse(mRecentTasks.mLastAllowed);
            assertFalse(mRunningTasks.mLastAllowed);
        }
    }

    private TaskBuilder createTaskBuilder(String className) {
        return createTaskBuilder(mContext.getPackageName(), className);
    }

    private TaskBuilder createTaskBuilder(String packageName, String className) {
        return new TaskBuilder(mTestService.mStackSupervisor)
                .setComponent(new ComponentName(packageName, className))
                .setStack(mStack)
                .setTaskId(sLastTaskId++)
                .setUserId(TEST_USER_0_ID);
    }

    private TaskRecord createDocumentTask(String className) {
        return createDocumentTask(className, 0);
    }

    private TaskRecord createDocumentTask(String className, int flags) {
        TaskRecord task = createTaskBuilder(className)
                .setFlags(FLAG_ACTIVITY_NEW_DOCUMENT | flags)
                .build();
        task.affinity = null;
        task.maxRecents = ActivityTaskManager.getMaxAppRecentsLimitStatic();
        return task;
    }

    private TaskRecord setTaskActivityType(TaskRecord task,
            @WindowConfiguration.ActivityType int activityType) {
        Configuration config1 = new Configuration();
        config1.windowConfiguration.setActivityType(activityType);
        task.onConfigurationChanged(config1);
        return task;
    }

    private TaskRecord setTaskWindowingMode(TaskRecord task,
            @WindowConfiguration.WindowingMode int windowingMode) {
        Configuration config1 = new Configuration();
        config1.windowConfiguration.setWindowingMode(windowingMode);
        task.onConfigurationChanged(config1);
        return task;
    }

    private void assertNoTasksTrimmed() {
        assertTrimmed();
    }

    private void assertTrimmed(TaskRecord... tasks) {
        final ArrayList<TaskRecord> trimmed = mCallbacksRecorder.mTrimmed;
        final ArrayList<TaskRecord> removed = mCallbacksRecorder.mRemoved;
        assertWithMessage("Expected " + tasks.length + " trimmed tasks, got " + trimmed.size())
                .that(trimmed).hasSize(tasks.length);
        assertWithMessage("Expected " + tasks.length + " removed tasks, got " + removed.size())
                .that(removed).hasSize(tasks.length);
        for (TaskRecord task : tasks) {
            assertWithMessage("Expected trimmed task: " + task).that(trimmed).contains(task);
            assertWithMessage("Expected removed task: " + task).that(removed).contains(task);
        }
    }

    private void assertSecurityException(boolean expectCallable, Runnable runnable) {
        boolean noSecurityException = true;
        try {
            runnable.run();
        } catch (SecurityException se) {
            noSecurityException = false;
        } catch (Exception e) {
            // We only care about SecurityExceptions, fall through here.
        }
        if (noSecurityException != expectCallable) {
            fail("Expected callable: " + expectCallable + " but got no security exception: "
                    + noSecurityException);
        }
    }

    private class MyTestActivityTaskManagerService extends TestActivityTaskManagerService {
        MyTestActivityTaskManagerService(Context context) {
            super(context);
        }

        @Override
        protected RecentTasks createRecentTasks() {
            return new TestRecentTasks(this, mTaskPersister);
        }

        @Override
        protected ActivityStackSupervisor createStackSupervisor() {
            if (mTestStackSupervisor == null) {
                mTestStackSupervisor = new MyTestActivityStackSupervisor(this, mH.getLooper());
            }
            return mTestStackSupervisor;
        }

        @Override
        void createDefaultDisplay() {
            super.createDefaultDisplay();
            mDisplay = mRootActivityContainer.getActivityDisplay(DEFAULT_DISPLAY);
            mOtherDisplay = TestActivityDisplay.create(mTestStackSupervisor, DEFAULT_DISPLAY + 1);
            mSingleTaskDisplay = TestActivityDisplay.create(mTestStackSupervisor,
                    DEFAULT_DISPLAY + 2);
            mSingleTaskDisplay.setDisplayToSingleTaskInstance();
            mRootActivityContainer.addChild(mOtherDisplay, ActivityDisplay.POSITION_TOP);
            mRootActivityContainer.addChild(mDisplay, ActivityDisplay.POSITION_TOP);
            mRootActivityContainer.addChild(mSingleTaskDisplay, ActivityDisplay.POSITION_TOP);
        }
    }

    private class MyTestActivityStackSupervisor extends TestActivityStackSupervisor {
        MyTestActivityStackSupervisor(ActivityTaskManagerService service, Looper looper) {
            super(service, looper);
        }

        @Override
        RunningTasks createRunningTasks() {
            mRunningTasks = new TestRunningTasks();
            return mRunningTasks;
        }
    }

    private static class MyTestActivityStack extends TestActivityStack {
        private ActivityDisplay mDisplay = null;

        MyTestActivityStack(ActivityDisplay display, ActivityStackSupervisor supervisor) {
            super(display, sLastStackId++, supervisor, WINDOWING_MODE_FULLSCREEN,
                    ACTIVITY_TYPE_STANDARD, true /* onTop */, false /* createActivity */);
            mDisplay = display;
        }

        @Override
        ActivityDisplay getDisplay() {
            if (mDisplay != null) {
                return mDisplay;
            }
            return super.getDisplay();
        }
    }

    private static class CallbacksRecorder implements Callbacks {
        public final ArrayList<TaskRecord> mAdded = new ArrayList<>();
        public final ArrayList<TaskRecord> mTrimmed = new ArrayList<>();
        public final ArrayList<TaskRecord> mRemoved = new ArrayList<>();

        void clear() {
            mAdded.clear();
            mTrimmed.clear();
            mRemoved.clear();
        }

        @Override
        public void onRecentTaskAdded(TaskRecord task) {
            mAdded.add(task);
        }

        @Override
        public void onRecentTaskRemoved(TaskRecord task, boolean wasTrimmed, boolean killProcess) {
            if (wasTrimmed) {
                mTrimmed.add(task);
            }
            mRemoved.add(task);
        }
    }

    private static class TestTaskPersister extends TaskPersister {
        public SparseBooleanArray mUserTaskIdsOverride;
        public ArrayList<TaskRecord> mUserTasksOverride;

        TestTaskPersister(File workingDir) {
            super(workingDir);
        }

        @Override
        SparseBooleanArray loadPersistedTaskIdsForUser(int userId) {
            if (mUserTaskIdsOverride != null) {
                return mUserTaskIdsOverride;
            }
            return super.loadPersistedTaskIdsForUser(userId);
        }

        @Override
        List<TaskRecord> restoreTasksForUserLocked(int userId, SparseBooleanArray preaddedTasks) {
            if (mUserTasksOverride != null) {
                return mUserTasksOverride;
            }
            return super.restoreTasksForUserLocked(userId, preaddedTasks);
        }
    }

    private static class TestRecentTasks extends RecentTasks {
        static final int GRANT = 0;
        static final int DENY = 1;
        static final int DENY_THROW_SECURITY_EXCEPTION = 2;

        private boolean mOverrideIsCallerRecents;
        private boolean mIsTrimmableOverride;
        private int mIsCallerRecentsPolicy;

        public boolean mLastAllowed;

        TestRecentTasks(ActivityTaskManagerService service, TaskPersister taskPersister) {
            super(service, taskPersister);
        }

        @Override
        Set<Integer> getProfileIds(int userId) {
            Set<Integer> profileIds = new HashSet<>();
            profileIds.add(TEST_USER_0_ID);
            profileIds.add(TEST_QUIET_USER_ID);
            return profileIds;
        }

        @Override
        UserInfo getUserInfo(int userId) {
            switch (userId) {
                case TEST_USER_0_ID:
                case TEST_USER_1_ID:
                    return DEFAULT_USER_INFO;
                case TEST_QUIET_USER_ID:
                    return QUIET_USER_INFO;
            }
            return null;
        }

        @Override
        int[] getCurrentProfileIds() {
            return new int[] { TEST_USER_0_ID, TEST_QUIET_USER_ID };
        }

        @Override
        boolean isCallerRecents(int callingUid) {
            if (mOverrideIsCallerRecents) {
                switch (mIsCallerRecentsPolicy) {
                    case GRANT:
                        return true;
                    case DENY:
                        return false;
                    case DENY_THROW_SECURITY_EXCEPTION:
                        throw new SecurityException();
                }
            }
            return super.isCallerRecents(callingUid);
        }

        void setIsCallerRecentsOverride(int policy) {
            mOverrideIsCallerRecents = true;
            mIsCallerRecentsPolicy = policy;
        }

        /**
         * To simplify the setup for some tests, the caller can request that we only rely on the
         * visible range test to determine what is trimmable. In this case, we don't try to
         * use the stack order to determine additionally if the task is trimmable when it is not
         * in the visible range.
         */
        void setOnlyTestVisibleRange() {
            mIsTrimmableOverride = true;
        }

        @Override
        ParceledListSlice<RecentTaskInfo> getRecentTasks(int maxNum, int flags,
                boolean getTasksAllowed,
                boolean getDetailedTasks, int userId, int callingUid) {
            mLastAllowed = getTasksAllowed;
            return super.getRecentTasks(maxNum, flags, getTasksAllowed, getDetailedTasks, userId,
                    callingUid);
        }

        @Override
        protected boolean isTrimmable(TaskRecord task) {
            return mIsTrimmableOverride || super.isTrimmable(task);
        }
    }

    private static class TestRunningTasks extends RunningTasks {
        public boolean mLastAllowed;

        @Override
        void getTasks(int maxNum, List<RunningTaskInfo> list, int ignoreActivityType,
                int ignoreWindowingMode, ArrayList<ActivityDisplay> activityDisplays,
                int callingUid, boolean allowed) {
            mLastAllowed = allowed;
            super.getTasks(maxNum, list, ignoreActivityType, ignoreWindowingMode, activityDisplays,
                    callingUid, allowed);
        }
    }
}
