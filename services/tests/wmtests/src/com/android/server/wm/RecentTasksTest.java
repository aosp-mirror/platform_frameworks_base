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

import static android.app.ActivityManager.RECENT_WITH_EXCLUDED;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static java.lang.Integer.MAX_VALUE;

import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.util.SparseBooleanArray;

import androidx.test.filters.MediumTest;

import com.android.server.wm.RecentTasks.Callbacks;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * Build/Install/Run:
 *  atest WmTests:RecentTasksTest
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RecentTasksTest extends ActivityTestsBase {
    private static final int TEST_USER_0_ID = 0;
    private static final int TEST_USER_1_ID = 10;
    private static final int TEST_QUIET_USER_ID = 20;
    private static final UserInfo DEFAULT_USER_INFO = new UserInfo();
    private static final UserInfo QUIET_PROFILE_USER_INFO = new UserInfo(TEST_QUIET_USER_ID,
            "quiet_profile", null /* iconPath */, UserInfo.FLAG_QUIET_MODE,
            UserManager.USER_TYPE_PROFILE_MANAGED);
    private static final int INVALID_STACK_ID = 999;

    private TaskDisplayArea mTaskContainer;
    private ActivityStack mStack;
    private TestTaskPersister mTaskPersister;
    private TestRecentTasks mRecentTasks;
    private TestRunningTasks mRunningTasks;

    private ArrayList<Task> mTasks;
    private ArrayList<Task> mSameDocumentTasks;

    private CallbacksRecorder mCallbacksRecorder;

    @Before
    public void setUp() throws Exception {
        mTaskPersister = new TestTaskPersister(mContext.getFilesDir());
        spyOn(mTaskPersister);
        mTaskContainer = mRootWindowContainer.getDefaultTaskDisplayArea();

        // Set the recent tasks we should use for testing in this class.
        mRecentTasks = new TestRecentTasks(mService, mTaskPersister);
        spyOn(mRecentTasks);
        mService.setRecentTasks(mRecentTasks);
        mRecentTasks.loadParametersFromResources(mContext.getResources());

        // Set the running tasks we should use for testing in this class.
        mRunningTasks = new TestRunningTasks();
        mService.mStackSupervisor.setRunningTasks(mRunningTasks);

        mStack = mTaskContainer.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mCallbacksRecorder = new CallbacksRecorder();
        mRecentTasks.registerCallback(mCallbacksRecorder);

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
    public void testPersister() {
        // Add some tasks, ensure the persister is woken
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(0)), anyBoolean());
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(1)), anyBoolean());
        reset(mTaskPersister);

        // Update a task, ensure the persister is woken
        mRecentTasks.add(mTasks.get(0));
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(0)), anyBoolean());
        reset(mTaskPersister);

        // Remove some tasks, ensure the persister is woken
        mRecentTasks.remove(mTasks.get(0));
        mRecentTasks.remove(mTasks.get(1));
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(0)), anyBoolean());
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(1)), anyBoolean());
        reset(mTaskPersister);
    }

    @Test
    public void testPersisterTrimmed() {
        mRecentTasks.setOnlyTestVisibleRange();

        // Limit the global maximum number of recent tasks to a fixed size
        mRecentTasks.setGlobalMaxNumTasks(1 /* globalMaxNumTasks */);

        mRecentTasks.add(mTasks.get(0));
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(0)), anyBoolean());
        reset(mTaskPersister);

        // Add N+1 tasks to ensure the previous task is trimmed
        mRecentTasks.add(mTasks.get(1));
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(0)), anyBoolean());
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(1)), anyBoolean());
        assertTrimmed(mTasks.get(0));
    }

    @Test
    public void testAddTasksNoMultiple_expectNoTrim() {
        // Add same non-multiple-task document tasks will remove the task (to re-add it) but not
        // trim it
        Task documentTask1 = createDocumentTask(".DocumentTask1");
        Task documentTask2 = createDocumentTask(".DocumentTask1");
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
        Task documentTask1 = createDocumentTask(".DocumentTask1");
        Task documentTask2 = createDocumentTask(".DocumentTask1");
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
        Task documentTask1 = createDocumentTask(".DocumentTask1");
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
        Task documentTask1 = createDocumentTask(".DocumentTask1",
                FLAG_ACTIVITY_MULTIPLE_TASK);
        Task documentTask2 = createDocumentTask(".DocumentTask1",
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
        Task task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .build();
        Task task2 = createTaskBuilder(".Task1")
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
        Task task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .setStack(mTaskContainer.getRootHomeTask()).build();
        Task task2 = createTaskBuilder(".Task1")
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
        Task task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        setTaskActivityType(task1, ACTIVITY_TYPE_UNDEFINED);
        assertThat(task1.getActivityType()).isEqualTo(ACTIVITY_TYPE_UNDEFINED);
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        Task task2 = createTaskBuilder(".Task1")
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
        Task task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .setUserId(TEST_USER_0_ID)
                .build();
        setTaskActivityType(task1, ACTIVITY_TYPE_UNDEFINED);
        assertEquals(ACTIVITY_TYPE_UNDEFINED, task1.getActivityType());
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        Task task2 = createTaskBuilder(".Task1")
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
        Task task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        setTaskWindowingMode(task1, WINDOWING_MODE_UNDEFINED);
        assertEquals(WINDOWING_MODE_UNDEFINED, task1.getWindowingMode());
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        Task task2 = createTaskBuilder(".Task1")
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
        Task task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        setTaskWindowingMode(task1, WINDOWING_MODE_FULLSCREEN);
        assertEquals(WINDOWING_MODE_FULLSCREEN, task1.getWindowingMode());
        mRecentTasks.add(task1);

        Task task2 = createTaskBuilder(".Task1")
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
    public void testAddTasksHomeClearUntrackedTasks_expectFinish() {
        // There may be multiple tasks with the same base intent by flags (FLAG_ACTIVITY_NEW_TASK |
        // FLAG_ACTIVITY_MULTIPLE_TASK). If the previous task is still active, it should be removed
        // because user may not be able to return to the task.
        final String className = ".PermissionsReview";
        final Function<Boolean, Task> taskBuilder = visible -> {
            final Task task = createTaskBuilder(className).build();
            // Make the task non-empty.
            final ActivityRecord r = new ActivityBuilder(mService).setTask(task).build();
            r.setVisibility(visible);
            return task;
        };

        final Task task1 = taskBuilder.apply(false /* visible */);
        mRecentTasks.add(task1);
        final Task task2 = taskBuilder.apply(true /* visible */);
        mRecentTasks.add(task2);
        // Only the last task is kept in recents and the previous 2 tasks will becomes untracked
        // tasks because their intents are identical.
        mRecentTasks.add(createTaskBuilder(className).build());
        // Go home to trigger the removal of untracked tasks.
        mRecentTasks.add(createTaskBuilder(".Home").setStack(mTaskContainer.getRootHomeTask())
                .build());

        // All activities in the invisible task should be finishing or removed.
        assertNull(task1.getTopNonFinishingActivity());
        // The visible task should not be affected.
        assertNotNull(task2.getTopNonFinishingActivity());
    }

    @Test
    public void testUsersTasks() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.unloadUserDataFromMemoryLocked(TEST_USER_0_ID);

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
        Task task1 = createTaskBuilder(".Task1").build();
        task1.lastActiveTime = new Random().nextInt();
        Task task2 = createTaskBuilder(".Task1").build();
        task2.lastActiveTime = new Random().nextInt();
        Task task3 = createTaskBuilder(".Task1").build();
        task3.lastActiveTime = new Random().nextInt();
        Task task4 = createTaskBuilder(".Task1").build();
        task4.lastActiveTime = new Random().nextInt();
        mRecentTasks.add(task1);
        mRecentTasks.add(task2);
        mRecentTasks.add(task3);
        mRecentTasks.add(task4);

        long prevLastActiveTime = 0;
        final ArrayList<Task> tasks = mRecentTasks.getRawTasks();
        for (int i = 0; i < tasks.size(); i++) {
            final Task task = tasks.get(i);
            assertThat(prevLastActiveTime).isLessThan(task.lastActiveTime);
            prevLastActiveTime = task.lastActiveTime;
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
        Task qt1 = createTaskBuilder(".QuietTask1").setUserId(TEST_QUIET_USER_ID).build();
        Task qt2 = createTaskBuilder(".QuietTask2").setUserId(TEST_QUIET_USER_ID).build();
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

        Task t1 = createTaskBuilder(".Task1").build();
        t1.touchActiveTime();
        mRecentTasks.add(t1);

        // Force a small sleep just beyond the session duration
        SystemClock.sleep(75);

        Task t2 = createTaskBuilder(".Task2").build();
        t2.touchActiveTime();
        mRecentTasks.add(t2);

        // Assert that the old task has been removed due to being out of the active session
        assertTrimmed(t1);
    }

    @Test
    public void testVisibleTasks_excludedFromRecents() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.setParameters(-1 /* min */, 4 /* max */, -1 /* ms */);

        Task excludedTask1 = createTaskBuilder(".ExcludedTask1")
                .setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .build();
        Task excludedTask2 = createTaskBuilder(".ExcludedTask2")
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
    public void testVisibleTasks_excludedFromRecents_firstTaskNotVisible() {
        // Create some set of tasks, some of which are visible and some are not
        Task homeTask = setTaskActivityType(
                createTaskBuilder("com.android.pkg1", ".HomeTask").build(),
                ACTIVITY_TYPE_HOME);
        homeTask.mUserSetupComplete = true;
        mRecentTasks.add(homeTask);
        Task excludedTask1 = createTaskBuilder(".ExcludedTask1")
                .setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .build();
        excludedTask1.mUserSetupComplete = true;
        mRecentTasks.add(excludedTask1);

        // Expect that the first visible excluded-from-recents task is visible
        assertGetRecentTasksOrder(0 /* flags */, excludedTask1);
    }

    @Test
    public void testVisibleTasks_excludedFromRecents_withExcluded() {
        // Create some set of tasks, some of which are visible and some are not
        Task t1 = createTaskBuilder("com.android.pkg1", ".Task1").build();
        t1.mUserSetupComplete = true;
        mRecentTasks.add(t1);
        Task homeTask = setTaskActivityType(
                createTaskBuilder("com.android.pkg1", ".HomeTask").build(),
                ACTIVITY_TYPE_HOME);
        homeTask.mUserSetupComplete = true;
        mRecentTasks.add(homeTask);
        Task excludedTask1 = createTaskBuilder(".ExcludedTask1")
                .setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .build();
        excludedTask1.mUserSetupComplete = true;
        mRecentTasks.add(excludedTask1);
        Task excludedTask2 = createTaskBuilder(".ExcludedTask2")
                .setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .build();
        excludedTask2.mUserSetupComplete = true;
        mRecentTasks.add(excludedTask2);
        Task t2 = createTaskBuilder("com.android.pkg2", ".Task1").build();
        t2.mUserSetupComplete = true;
        mRecentTasks.add(t2);

        assertGetRecentTasksOrder(RECENT_WITH_EXCLUDED, t2, excludedTask2, excludedTask1, t1);
    }

    @Test
    public void testVisibleTasks_minNum() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.setParameters(5 /* min */, -1 /* max */, 25 /* ms */);

        for (int i = 0; i < 4; i++) {
            final Task task = mTasks.get(i);
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
            final Task task = mTasks.get(i);
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
    public void testVisibleTasks_alwaysOnTop() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.setParameters(-1 /* min */, 3 /* max */, -1 /* ms */);

        final DisplayContent display = mRootWindowContainer.getDefaultDisplay();
        final Task alwaysOnTopTask = display.createStack(WINDOWING_MODE_MULTI_WINDOW,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        alwaysOnTopTask.setAlwaysOnTop(true);

        assertFalse("Always on top tasks should not be visible recents",
                mRecentTasks.isVisibleRecentTask(alwaysOnTopTask));

        mRecentTasks.add(alwaysOnTopTask);

        // Add N+1 visible tasks.
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        mRecentTasks.add(mTasks.get(2));
        mRecentTasks.add(mTasks.get(3));

        // excludedTask is not trimmed.
        assertTrimmed(mTasks.get(0));

        mRecentTasks.removeAllVisibleTasks(TEST_USER_0_ID);

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
        mRecentTasks.setFreezeTaskListReordering();
        assertTrue(mRecentTasks.isFreezeTaskListReorderingSet());

        // Relaunch a few tasks
        mRecentTasks.add(mTasks.get(2));
        mRecentTasks.add(mTasks.get(1));

        ActivityStack stack = mTasks.get(2).getStack();
        stack.moveToFront("", mTasks.get(2));
        doReturn(stack).when(mService.mRootWindowContainer).getTopDisplayFocusedStack();

        // Simulate the reset from the timeout
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

        final ActivityStack homeStack = mTaskContainer.getRootHomeTask();
        final ActivityStack aboveHomeStack = mTaskContainer.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

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

        final ActivityStack behindHomeStack = mTaskContainer.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityStack homeStack = mTaskContainer.getRootHomeTask();
        final ActivityStack aboveHomeStack = mTaskContainer.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Add a number of tasks (beyond the max) but ensure that only the task in the stack behind
        // the home stack is trimmed once a new task is added
        final Task behindHomeTask = createTaskBuilder(".Task1")
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

        final ActivityStack homeStack = mTaskContainer.getRootHomeTask();
        final DisplayContent otherDisplay = addNewDisplayContentAt(DisplayContent.POSITION_TOP);
        final ActivityStack otherDisplayStack = otherDisplay.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

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

        final ArrayList<Task> tasks = mRecentTasks.getRawTasks();
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
        Task t1 = createTaskBuilder("com.android.pkg1", ".Task1").build();
        mRecentTasks.add(t1);
        mRecentTasks.add(setTaskActivityType(
                createTaskBuilder("com.android.pkg1", ".HomeTask").build(),
                ACTIVITY_TYPE_HOME));
        Task t2 = createTaskBuilder("com.android.pkg2", ".Task2").build();
        mRecentTasks.add(t2);
        mRecentTasks.add(setTaskWindowingMode(
                createTaskBuilder("com.android.pkg1", ".PipTask").build(),
                WINDOWING_MODE_PINNED));
        Task t3 = createTaskBuilder("com.android.pkg3", ".Task3").build();
        mRecentTasks.add(t3);

        // Create some more tasks that are out of visible range, but are still visible
        Task t4 = createTaskBuilder("com.android.pkg3", ".Task4").build();
        mRecentTasks.add(t4);
        Task t5 = createTaskBuilder("com.android.pkg3", ".Task5").build();
        mRecentTasks.add(t5);

        // Create some more tasks that are out of the active session range, but are still visible
        Task t6 = createTaskBuilder("com.android.pkg3", ".Task6").build();
        t6.lastActiveTime = SystemClock.elapsedRealtime() - 200;
        mRecentTasks.add(t6);
        Task t7 = createTaskBuilder("com.android.pkg3", ".Task7").build();
        t7.lastActiveTime = SystemClock.elapsedRealtime() - 200;
        mRecentTasks.add(t7);

        // Remove all the visible tasks and ensure that they are removed
        mRecentTasks.removeAllVisibleTasks(TEST_USER_0_ID);
        assertTrimmed(t1, t2, t3, t4, t5, t6, t7);
    }

    @Test
    public void testRemoveAllVisibleTasksPerUser() {
        mRecentTasks.setParameters(-1 /* min */, 3 /* max */, 100 /* ms */);

        // Create a visible task per user
        Task t1 = createTaskBuilder(".Task1")
                .setUserId(TEST_USER_0_ID)
                .build();
        mRecentTasks.add(t1);

        Task t2 = createTaskBuilder(".Task2")
                .setUserId(TEST_QUIET_USER_ID)
                .build();
        mRecentTasks.add(t2);

        Task t3 = createTaskBuilder(".Task3")
                .setUserId(TEST_USER_1_ID)
                .build();
        mRecentTasks.add(t3);

        // Remove all the visible tasks and ensure that they are removed
        mRecentTasks.removeAllVisibleTasks(TEST_USER_0_ID);
        assertTrimmed(t1, t2);
    }

    @Test
    public void testNotRestoreRecentTaskApis() {
        final Task task = createTaskBuilder(".Task").build();
        final int taskId = task.mTaskId;
        mRecentTasks.add(task);
        // Only keep the task in RecentTasks.
        task.removeIfPossible();
        mStack.removeIfPossible();

        // The following APIs should not restore task from recents to the active list.
        assertNotRestoreTask(() -> mService.setFocusedTask(taskId));
        assertNotRestoreTask(() -> mService.startSystemLockTaskMode(taskId));
        assertNotRestoreTask(() -> mService.cancelTaskWindowTransition(taskId));
        assertNotRestoreTask(
                () -> mService.resizeTask(taskId, null /* bounds */, 0 /* resizeMode */));
        assertNotRestoreTask(
                () -> mService.setTaskWindowingMode(taskId, WINDOWING_MODE_FULLSCREEN,
                        false/* toTop */));
        assertNotRestoreTask(
                () -> mService.setTaskWindowingModeSplitScreenPrimary(taskId, false /* toTop */));
    }

    @Test
    public void addTask_callsTaskNotificationController() {
        final Task task = createTaskBuilder(".Task").build();

        mRecentTasks.add(task);
        mRecentTasks.remove(task);

        TaskChangeNotificationController controller =
                mService.getTaskChangeNotificationController();
        verify(controller, times(2)).notifyTaskListUpdated();
    }

    @Test
    public void removeTask_callsTaskNotificationController() {
        final Task task = createTaskBuilder(".Task").build();

        mRecentTasks.add(task);
        mRecentTasks.remove(task);

        // 2 calls - Once for add and once for remove
        TaskChangeNotificationController controller =
                mService.getTaskChangeNotificationController();
        verify(controller, times(2)).notifyTaskListUpdated();
    }

    @Test
    public void removeALlVisibleTask_callsTaskNotificationController_twice() {
        final Task task1 = createTaskBuilder(".Task").build();
        final Task task2 = createTaskBuilder(".Task2").build();

        mRecentTasks.add(task1);
        mRecentTasks.add(task2);
        mRecentTasks.removeAllVisibleTasks(TEST_USER_0_ID);

        // 4 calls - Twice for add and twice for remove
        TaskChangeNotificationController controller =
                mService.getTaskChangeNotificationController();
        verify(controller, times(4)).notifyTaskListUpdated();
    }

    @Test
    public void testTaskInfo_expectNoExtras() {
        doNothing().when(mRecentTasks).loadUserRecentsLocked(anyInt());
        doReturn(true).when(mRecentTasks).isUserRunning(anyInt(), anyInt());

        final Bundle data = new Bundle();
        data.putInt("key", 100);
        final Task task1 = createTaskBuilder(".Task").build();
        final ActivityRecord r1 = new ActivityBuilder(mService)
                .setTask(task1)
                .setIntentExtras(data)
                .build();
        mRecentTasks.add(r1.getTask());

        final List<RecentTaskInfo> infos = mRecentTasks.getRecentTasks(MAX_VALUE, 0 /* flags */,
                true /* getTasksAllowed */, TEST_USER_0_ID, 0).getList();
        assertTrue(infos.size() == 1);
        for (int i = 0; i < infos.size(); i++)  {
            final Bundle extras = infos.get(i).baseIntent.getExtras();
            assertTrue(extras == null || extras.isEmpty());
        }
    }

    /**
     * Ensures that the raw recent tasks list is in the provided order. Note that the expected tasks
     * should be ordered from least to most recent.
     */
    private void assertRecentTasksOrder(Task... expectedTasks) {
        ArrayList<Task> tasks = mRecentTasks.getRawTasks();
        assertTrue(expectedTasks.length == tasks.size());
        for (int i = 0; i < tasks.size(); i++)  {
            assertTrue(expectedTasks[i] == tasks.get(i));
        }
    }

    /**
     * Ensures that the recent tasks list is in the provided order. Note that the expected tasks
     * should be ordered from least to most recent.
     */
    private void assertGetRecentTasksOrder(int getRecentTaskFlags, Task... expectedTasks) {
        doNothing().when(mRecentTasks).loadUserRecentsLocked(anyInt());
        doReturn(true).when(mRecentTasks).isUserRunning(anyInt(), anyInt());
        List<RecentTaskInfo> infos = mRecentTasks.getRecentTasks(MAX_VALUE, getRecentTaskFlags,
                true /* getTasksAllowed */, TEST_USER_0_ID, 0).getList();
        assertTrue(expectedTasks.length == infos.size());
        for (int i = 0; i < infos.size(); i++)  {
            assertTrue(expectedTasks[i].mTaskId == infos.get(i).taskId);
        }
    }

    private void assertNotRestoreTask(Runnable action) {
        // Verify stack count doesn't change because task with fullscreen mode and standard type
        // would have its own stack.
        final int originalStackCount = mTaskContainer.getStackCount();
        action.run();
        assertEquals(originalStackCount, mTaskContainer.getStackCount());
    }

    @Test
    public void testNotRecentsComponent_denyApiAccess() throws Exception {
        doReturn(PackageManager.PERMISSION_DENIED).when(mService)
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
        doReturn(PackageManager.PERMISSION_DENIED).when(mService)
                .checkGetTasksPermission(anyString(), anyInt(), anyInt());
        // Set the recents component and ensure that the following calls do not fail
        mRecentTasks.setIsCallerRecentsOverride(TestRecentTasks.GRANT);
        doTestRecentTasksApis(true /* expectNoSecurityException */);
        testGetTasksApis(true /* expectNoSecurityException */);
    }

    private void doTestRecentTasksApis(boolean expectCallable) {
        assertSecurityException(expectCallable, () -> mService.removeStack(INVALID_STACK_ID));
        assertSecurityException(expectCallable,
                () -> mService.removeStacksInWindowingModes(
                        new int[]{WINDOWING_MODE_UNDEFINED}));
        assertSecurityException(expectCallable,
                () -> mService.removeStacksWithActivityTypes(
                        new int[]{ACTIVITY_TYPE_UNDEFINED}));
        assertSecurityException(expectCallable, () -> mService.removeTask(0));
        assertSecurityException(expectCallable,
                () -> mService.setTaskWindowingMode(0, WINDOWING_MODE_UNDEFINED, true));
        assertSecurityException(expectCallable,
                () -> mService.moveTaskToStack(0, INVALID_STACK_ID, true));
        assertSecurityException(expectCallable,
                () -> mService.setTaskWindowingModeSplitScreenPrimary(0, true));
        assertSecurityException(expectCallable,
                () -> mService.moveTopActivityToPinnedStack(INVALID_STACK_ID, new Rect()));
        assertSecurityException(expectCallable, () -> mService.getAllStackInfos());
        assertSecurityException(expectCallable,
                () -> mService.getStackInfo(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_UNDEFINED));
        assertSecurityException(expectCallable, () -> {
            try {
                mService.getFocusedStackInfo();
            } catch (RemoteException e) {
                // Ignore
            }
        });
        assertSecurityException(expectCallable,
                () -> mService.moveTasksToFullscreenStack(INVALID_STACK_ID, true));
        assertSecurityException(expectCallable,
                () -> mService.startActivityFromRecents(0, new Bundle()));
        assertSecurityException(expectCallable, () -> mService.getTaskSnapshot(0, true));
        assertSecurityException(expectCallable, () -> mService.registerTaskStackListener(null));
        assertSecurityException(expectCallable,
                () -> mService.unregisterTaskStackListener(null));
        assertSecurityException(expectCallable, () -> mService.getTaskDescription(0));
        assertSecurityException(expectCallable, () -> mService.cancelTaskWindowTransition(0));
        assertSecurityException(expectCallable, () -> mService.startRecentsActivity(null, null,
                null));
        assertSecurityException(expectCallable, () -> mService.cancelRecentsAnimation(true));
        assertSecurityException(expectCallable, () -> mService.stopAppSwitches());
        assertSecurityException(expectCallable, () -> mService.resumeAppSwitches());
    }

    private void testGetTasksApis(boolean expectCallable) {
        mService.getRecentTasks(MAX_VALUE, 0, TEST_USER_0_ID);
        mService.getTasks(MAX_VALUE);
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
        return new TaskBuilder(mService.mStackSupervisor)
                .setComponent(new ComponentName(packageName, className))
                .setStack(mStack)
                .setUserId(TEST_USER_0_ID);
    }

    private Task createDocumentTask(String className) {
        return createDocumentTask(className, 0);
    }

    private Task createDocumentTask(String className, int flags) {
        Task task = createTaskBuilder(className)
                .setFlags(FLAG_ACTIVITY_NEW_DOCUMENT | flags)
                .build();
        task.affinity = null;
        task.maxRecents = ActivityTaskManager.getMaxAppRecentsLimitStatic();
        return task;
    }

    private Task setTaskActivityType(Task task,
            @WindowConfiguration.ActivityType int activityType) {
        Configuration config1 = new Configuration();
        config1.windowConfiguration.setActivityType(activityType);
        task.onConfigurationChanged(config1);
        return task;
    }

    private Task setTaskWindowingMode(Task task,
            @WindowConfiguration.WindowingMode int windowingMode) {
        Configuration config1 = new Configuration();
        config1.windowConfiguration.setWindowingMode(windowingMode);
        task.onConfigurationChanged(config1);
        return task;
    }

    private void assertNoTasksTrimmed() {
        assertTrimmed();
    }

    private void assertTrimmed(Task... tasks) {
        final ArrayList<Task> trimmed = mCallbacksRecorder.mTrimmed;
        final ArrayList<Task> removed = mCallbacksRecorder.mRemoved;
        assertWithMessage("Expected " + tasks.length + " trimmed tasks, got " + trimmed.size())
                .that(trimmed).hasSize(tasks.length);
        assertWithMessage("Expected " + tasks.length + " removed tasks, got " + removed.size())
                .that(removed).hasSize(tasks.length);
        for (Task task : tasks) {
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

    private static class CallbacksRecorder implements Callbacks {
        public final ArrayList<Task> mAdded = new ArrayList<>();
        public final ArrayList<Task> mTrimmed = new ArrayList<>();
        public final ArrayList<Task> mRemoved = new ArrayList<>();

        void clear() {
            mAdded.clear();
            mTrimmed.clear();
            mRemoved.clear();
        }

        @Override
        public void onRecentTaskAdded(Task task) {
            mAdded.add(task);
        }

        @Override
        public void onRecentTaskRemoved(Task task, boolean wasTrimmed, boolean killProcess) {
            if (wasTrimmed) {
                mTrimmed.add(task);
            }
            mRemoved.add(task);
        }
    }

    private static class TestTaskPersister extends TaskPersister {
        public SparseBooleanArray mUserTaskIdsOverride;
        public ArrayList<Task> mUserTasksOverride;

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
        List<Task> restoreTasksForUserLocked(int userId, SparseBooleanArray preaddedTasks) {
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
                    return QUIET_PROFILE_USER_INFO;
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
                boolean getTasksAllowed, int userId, int callingUid) {
            mLastAllowed = getTasksAllowed;
            return super.getRecentTasks(maxNum, flags, getTasksAllowed, userId, callingUid);
        }

        @Override
        protected boolean isTrimmable(Task task) {
            return mIsTrimmableOverride || super.isTrimmable(task);
        }
    }

    private static class TestRunningTasks extends RunningTasks {
        public boolean mLastAllowed;

        @Override
        void getTasks(int maxNum, List<RunningTaskInfo> list, boolean filterOnlyVisibleRecents,
                RootWindowContainer root, int callingUid, boolean allowed, boolean crossUser,
                ArraySet<Integer> profileIds) {
            mLastAllowed = allowed;
            super.getTasks(maxNum, list, filterOnlyVisibleRecents, root, callingUid, allowed,
                    crossUser, profileIds);
        }
    }
}
