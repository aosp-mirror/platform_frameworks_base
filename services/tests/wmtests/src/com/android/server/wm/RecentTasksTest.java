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
import static android.content.pm.ActivityInfo.LAUNCH_MULTIPLE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.Task.FLAG_FORCE_HIDDEN_FOR_TASK_ORG;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static java.lang.Integer.MAX_VALUE;

import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.util.SparseBooleanArray;
import android.view.Surface;
import android.window.TaskSnapshot;

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
public class RecentTasksTest extends WindowTestsBase {
    private static final int TEST_USER_0_ID = 0;
    private static final int TEST_USER_1_ID = 10;
    private static final int TEST_QUIET_USER_ID = 20;
    private static final UserInfo DEFAULT_USER_INFO = new UserInfo(TEST_USER_0_ID,
            "default", 0 /* flags */);
    private static final UserInfo QUIET_PROFILE_USER_INFO = new UserInfo(TEST_QUIET_USER_ID,
            "quiet_profile", null /* iconPath */, UserInfo.FLAG_QUIET_MODE,
            UserManager.USER_TYPE_PROFILE_MANAGED);
    private static final int INVALID_STACK_ID = 999;

    private TaskDisplayArea mTaskContainer;
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
        mRecentTasks = new TestRecentTasks(mAtm, mTaskPersister);
        spyOn(mRecentTasks);
        mAtm.setRecentTasks(mRecentTasks);
        mRecentTasks.loadParametersFromResources(mContext.getResources());

        // Set the running tasks we should use for testing in this class.
        mRunningTasks = new TestRunningTasks();
        mAtm.mTaskSupervisor.setRunningTasks(mRunningTasks);

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
        triggerTrimAndAssertTrimmed(mTasks.get(0));
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(0)), anyBoolean());
        verify(mTaskPersister, times(1)).wakeup(eq(mTasks.get(1)), anyBoolean());
    }

    @Test
    public void testAddDocumentTasksNoMultiple_expectNoTrim() {
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
    public void testAddMultipleDocumentTasks_expectNoTrim() {
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
    public void testAddTasks_expectRemovedNoTrim() {
        // Add multiple same-affinity non-document tasks, ensure that it removes, but does not trim
        // the other task
        Task task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .build();
        Task task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
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
    public void testAddMultipleTasks_expectNotRemoved() {
        // Add multiple same-affinity non-document tasks with MULTIPLE_TASK, ensure that it does not
        // remove the other task
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
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
    }

    @Test
    public void testAddTasksDifferentStacks_expectNoRemove() {
        // Adding the same task with different activity types should not trigger removal of the
        // other task
        Task task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .setParentTask(mTaskContainer.getRootHomeTask()).build();
        Task task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .build();
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
                .setActivityType(ACTIVITY_TYPE_UNDEFINED)
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .build();
        // Set the activity type again or the activity type of a root task would be remapped
        // to ACTIVITY_TYPE_STANDARD, {@link TaskDisplayArea#createRootTask()}
        task1.getWindowConfiguration().setActivityType(ACTIVITY_TYPE_UNDEFINED);
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        Task task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
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
                .setActivityType(ACTIVITY_TYPE_UNDEFINED)
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setUserId(TEST_USER_0_ID)
                .build();
        // Set the activity type again or the activity type of a root task would be remapped
        // to ACTIVITY_TYPE_STANDARD, {@link TaskDisplayArea#createRootTask()}
        task1.getWindowConfiguration().setActivityType(ACTIVITY_TYPE_UNDEFINED);
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        Task task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
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
                .build();
        doReturn(WINDOWING_MODE_UNDEFINED).when(task1).getWindowingMode();
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        Task task2 = createTaskBuilder(".Task1")
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .build();
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
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .build();
        assertEquals(WINDOWING_MODE_FULLSCREEN, task1.getWindowingMode());
        mRecentTasks.add(task1);

        Task task2 = createTaskBuilder(".Task1")
                .setWindowingMode(WINDOWING_MODE_PINNED)
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .build();
        assertEquals(WINDOWING_MODE_PINNED, task2.getWindowingMode());
        mRecentTasks.add(task2);

        assertThat(mCallbacksRecorder.mAdded).hasSize(2);
        assertThat(mCallbacksRecorder.mAdded).contains(task1);
        assertThat(mCallbacksRecorder.mAdded).contains(task2);
        assertThat(mCallbacksRecorder.mTrimmed).isEmpty();
        assertThat(mCallbacksRecorder.mRemoved).isEmpty();
    }

    @Test
    public void testRemoveAffinityTask() {
        // Add task to recents
        final String taskAffinity = "affinity";
        final int uid = 10123;
        final Task task1 = createTaskBuilder(".Task1").build();
        task1.affinity = ActivityRecord.computeTaskAffinity(taskAffinity, uid, LAUNCH_MULTIPLE);
        mRecentTasks.add(task1);

        // Add another task to recents, and make sure the previous task was removed.
        final Task task2 = createTaskBuilder(".Task2").build();
        task2.affinity = ActivityRecord.computeTaskAffinity(taskAffinity, uid, LAUNCH_MULTIPLE);
        mRecentTasks.add(task2);
        assertEquals(1, mRecentTasks.getRecentTasks(MAX_VALUE, 0 /* flags */,
                true /* getTasksAllowed */, TEST_USER_0_ID, 0).getList().size());

        // Add another single-instance task to recents, and make sure no task is removed.
        final Task task3 = createTaskBuilder(".Task3").build();
        task3.affinity = ActivityRecord.computeTaskAffinity(taskAffinity, uid,
                LAUNCH_SINGLE_INSTANCE);
        mRecentTasks.add(task3);
        assertEquals(2, mRecentTasks.getRecentTasks(MAX_VALUE, 0 /* flags */,
                true /* getTasksAllowed */, TEST_USER_0_ID, 0).getList().size());
    }

    @Test
    public void testAppendOrganizedChildTaskInfo() {
        final Task root = createTaskBuilder(".CreatedByOrganizerRoot").build();
        root.mCreatedByOrganizer = true;
        // Add organized and non-organized child.
        final Task child1 = createTaskBuilder(".Task1").setParentTask(root).build();
        final Task child2 = createTaskBuilder(".Task2").setParentTask(root).build();
        doReturn(true).when(child1).isOrganized();
        doReturn(false).when(child2).isOrganized();
        mRecentTasks.add(root);

        // Make sure only organized child will be appended.
        final List<RecentTaskInfo> infos = getRecentTasks(0 /* flags */);
        final List<RecentTaskInfo> childrenTaskInfos = infos.get(0).childrenTaskInfos;
        assertEquals(childrenTaskInfos.size(), 1);
        assertEquals(childrenTaskInfos.get(0).taskId, child1.mTaskId);
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
            final ActivityRecord r = new ActivityBuilder(mAtm).setTask(task).build();
            r.setVisibility(visible);
            return task;
        };

        final Task task1 = taskBuilder.apply(false /* visible */);
        mRecentTasks.add(task1);
        final Task task2 = taskBuilder.apply(true /* visible */);
        mRecentTasks.add(task2);
        final Task task3 = createTaskBuilder(className).build();
        mRecentTasks.add(task3);
        // Only the last added task is kept in recents and the previous 2 tasks will become hidden
        // tasks because their intents are identical.
        mRecentTasks.add(task1);
        // Go home to trigger the removal of untracked tasks.
        mRecentTasks.add(createTaskBuilder(".Home").setParentTask(mTaskContainer.getRootHomeTask())
                .build());
        triggerIdleToTrim();

        // The task was added into recents again so it is not hidden and shouldn't be removed.
        assertNotNull(task1.getTopNonFinishingActivity());
        // All activities in the invisible task should be finishing or removed.
        assertNull(task3.getTopNonFinishingActivity());
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
        triggerTrimAndAssertTrimmed(mTasks.get(0));
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
        triggerTrimAndAssertTrimmed(qt1, qt2);
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

        // Assert that the old task has been removed due to being out of the active session
        triggerTrimAndAssertTrimmed(t1);
    }

    @Test
    public void testVisibleTasks_excludedFromRecents() {
        mRecentTasks.setParameters(-1 /* min */, 4 /* max */, -1 /* ms */);

        Task excludedTask1 = createTaskBuilder(".ExcludedTask1")
                .setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .build();
        Task excludedTask2 = createTaskBuilder(".ExcludedTask2")
                .setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .build();
        Task detachedExcludedTask = createTaskBuilder(".DetachedExcludedTask")
                .setFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .build();

        // Move home to front so other task can satisfy the condition in RecentTasks#isTrimmable.
        mRootWindowContainer.getDefaultTaskDisplayArea().getRootHomeTask().moveToFront("test");
        // Avoid Task#autoRemoveFromRecents when removing from parent.
        detachedExcludedTask.setHasBeenVisible(true);
        detachedExcludedTask.removeImmediately();
        assertFalse(detachedExcludedTask.isAttached());

        mRecentTasks.add(detachedExcludedTask);
        mRecentTasks.add(excludedTask1);
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        mRecentTasks.add(mTasks.get(2));
        mRecentTasks.add(excludedTask2);

        // Except the first-most excluded task, other excluded tasks should be trimmed.
        triggerTrimAndAssertTrimmed(excludedTask1, detachedExcludedTask);
    }

    @Test
    public void testVisibleTasks_excludedFromRecents_firstTaskNotVisible() {
        // Create some set of tasks, some of which are visible and some are not
        Task homeTask = createTaskBuilder("com.android.pkg1", ".HomeTask")
                .setParentTask(mTaskContainer.getRootHomeTask())
                .build();
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
        Task homeTask = createTaskBuilder("com.android.pkg1", ".HomeTask")
                .setParentTask(mTaskContainer.getRootHomeTask())
                .build();
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
        triggerTrimAndAssertNoTasksTrimmed();
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
        triggerTrimAndAssertTrimmed(mTasks.get(0), mTasks.get(1));
    }

    /**
     * Tests that tasks on always on top multi-window tasks are not visible and not trimmed/removed.
     */
    @Test
    public void testVisibleTasks_alwaysOnTop() {
        mRecentTasks.setOnlyTestVisibleRange();
        mRecentTasks.setParameters(-1 /* min */, 3 /* max */, -1 /* ms */);

        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task alwaysOnTopTask = taskDisplayArea.createRootTask(WINDOWING_MODE_MULTI_WINDOW,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        alwaysOnTopTask.setAlwaysOnTop(true);
        alwaysOnTopTask.setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, true);

        assertFalse("Always on top tasks should not be visible recents",
                mRecentTasks.isVisibleRecentTask(alwaysOnTopTask));

        mRecentTasks.add(alwaysOnTopTask);

        // Add N+1 visible tasks.
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        mRecentTasks.add(mTasks.get(2));
        mRecentTasks.add(mTasks.get(3));

        // excludedTask is not trimmed.
        triggerTrimAndAssertTrimmed(mTasks.get(0));

        mRecentTasks.removeAllVisibleTasks(TEST_USER_0_ID);

        // Only visible tasks removed.
        triggerTrimAndAssertTrimmed(mTasks.get(0), mTasks.get(1), mTasks.get(2), mTasks.get(3));
    }

    @Test
    public void testVisibleEmbeddedTask_expectNotVisible() {
        Task task = createTaskBuilder(".Task")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .build();
        doReturn(true).when(task).isEmbedded();
        mRecentTasks.add(task);

        assertThat(mCallbacksRecorder.mAdded).hasSize(1);
        assertFalse("embedded task should not be visible recents",
                mRecentTasks.isVisibleRecentTask(task));
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
    public void testFreezeTaskListOrder_replaceTask() {
        // Create two tasks with the same affinity
        Task affinityTask1 = createTaskBuilder(".AffinityTask1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .build();
        Task affinityTask2 = createTaskBuilder(".AffinityTask2")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .build();
        affinityTask2.affinity = affinityTask1.affinity = "affinity";

        // Add some tasks
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(affinityTask1);
        mRecentTasks.add(mTasks.get(1));
        mCallbacksRecorder.clear();

        // Freeze the list
        mRecentTasks.setFreezeTaskListReordering();
        assertTrue(mRecentTasks.isFreezeTaskListReorderingSet());

        // Add the affinity task
        mRecentTasks.add(affinityTask2);

        assertRecentTasksOrder(mTasks.get(1),
                affinityTask2,
                mTasks.get(0));

        assertThat(mCallbacksRecorder.mAdded).hasSize(1);
        assertThat(mCallbacksRecorder.mAdded).contains(affinityTask2);
        assertThat(mCallbacksRecorder.mRemoved).hasSize(1);
        assertThat(mCallbacksRecorder.mRemoved).contains(affinityTask1);
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

        Task stack = mTasks.get(2).getRootTask();
        stack.moveToFront("", mTasks.get(2));
        doReturn(stack).when(mAtm.mRootWindowContainer).getTopDisplayFocusedRootTask();

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

        final Task homeStack = mTaskContainer.getRootHomeTask();
        final Task aboveHomeStack = mTaskContainer.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Add a number of tasks (beyond the max) but ensure that nothing is trimmed because all
        // the tasks belong in stacks above the home stack
        mRecentTasks.add(createTaskBuilder(".HomeTask1").setParentTask(homeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task1").setParentTask(aboveHomeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task2").setParentTask(aboveHomeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task3").setParentTask(aboveHomeStack).build());

        triggerTrimAndAssertNoTasksTrimmed();
    }

    @Test
    public void testBehindHomeStackTasks_expectTaskTrimmed() {
        mRecentTasks.setParameters(-1 /* min */, 1 /* max */, -1 /* ms */);

        final Task behindHomeStack = mTaskContainer.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task homeStack = mTaskContainer.getRootHomeTask();
        final Task aboveHomeStack = mTaskContainer.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Add a number of tasks (beyond the max) but ensure that only the task in the stack behind
        // the home stack is trimmed once a new task is added
        final Task behindHomeTask = createTaskBuilder(".Task1")
                .setParentTask(behindHomeStack)
                .build();
        mRecentTasks.add(behindHomeTask);
        mRecentTasks.add(createTaskBuilder(".HomeTask1").setParentTask(homeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task2").setParentTask(aboveHomeStack).build());

        triggerTrimAndAssertTrimmed(behindHomeTask);
    }

    @Test
    public void testOtherDisplayTasks_expectNoTrim() {
        mRecentTasks.setParameters(-1 /* min */, 1 /* max */, -1 /* ms */);

        final Task homeTask = mTaskContainer.getRootHomeTask();
        final DisplayContent otherDisplay = addNewDisplayContentAt(DisplayContent.POSITION_TOP);
        final Task otherDisplayRootTask = otherDisplay.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Add a number of tasks (beyond the max) on each display, ensure that the tasks are not
        // removed
        mRecentTasks.add(createTaskBuilder(".HomeTask1").setParentTask(homeTask).build());
        mRecentTasks.add(createTaskBuilder(".Task1").setParentTask(otherDisplayRootTask).build());
        mRecentTasks.add(createTaskBuilder(".Task2").setParentTask(otherDisplayRootTask).build());
        mRecentTasks.add(createTaskBuilder(".HomeTask2").setParentTask(homeTask).build());

        triggerTrimAndAssertNoTasksTrimmed();
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
        mRecentTasks.add(createTaskBuilder("com.android.pkg1", ".HomeTask")
                .setParentTask(mTaskContainer.getRootHomeTask()).build());
        Task t2 = createTaskBuilder("com.android.pkg2", ".Task2").build();
        mRecentTasks.add(t2);
        mRecentTasks.add(createTaskBuilder("com.android.pkg1", ".PipTask")
                .setWindowingMode(WINDOWING_MODE_PINNED).build());
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
        triggerTrimAndAssertTrimmed(t1, t2, t3, t4, t5, t6, t7);
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
        triggerTrimAndAssertTrimmed(t1, t2);
    }

    @Test
    public void testNotRestoreRecentTaskApis() {
        final Task task = createTaskBuilder(".Task").build();
        final int taskId = task.mTaskId;
        mRecentTasks.add(task);
        // Only keep the task in RecentTasks.
        task.removeIfPossible();

        // The following APIs should not restore task from recents to the active list.
        assertNotRestoreTask(() -> mAtm.setFocusedTask(taskId));
        assertNotRestoreTask(() -> mAtm.startSystemLockTaskMode(taskId));
        assertNotRestoreTask(() -> mAtm.cancelTaskWindowTransition(taskId));
        assertNotRestoreTask(
                () -> mAtm.resizeTask(taskId, null /* bounds */, 0 /* resizeMode */));
    }

    @Test
    public void addTask_callsTaskNotificationController() {
        final Task task = createTaskBuilder(".Task").build();

        mRecentTasks.add(task);
        mRecentTasks.remove(task);

        TaskChangeNotificationController controller =
                mAtm.getTaskChangeNotificationController();
        verify(controller, times(2)).notifyTaskListUpdated();
    }

    @Test
    public void removeTask_callsTaskNotificationController() {
        final Task task = createTaskBuilder(".Task").build();

        mRecentTasks.add(task);
        mRecentTasks.remove(task);

        // 2 calls - Once for add and once for remove
        TaskChangeNotificationController controller =
                mAtm.getTaskChangeNotificationController();
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
                mAtm.getTaskChangeNotificationController();
        verify(controller, times(4)).notifyTaskListUpdated();
    }

    @Test
    public void testTaskInfo_expectNoExtras() {
        final Bundle data = new Bundle();
        data.putInt("key", 100);
        final Task task1 = createTaskBuilder(".Task").build();
        final ActivityRecord r1 = new ActivityBuilder(mAtm)
                .setTask(task1)
                .setIntentExtras(data)
                .build();
        mRecentTasks.add(r1.getTask());

        final List<RecentTaskInfo> infos = getRecentTasks(0 /* flags */);
        assertTrue(infos.size() == 1);
        for (int i = 0; i < infos.size(); i++)  {
            final Bundle extras = infos.get(i).baseIntent.getExtras();
            assertTrue(extras == null || extras.isEmpty());
        }
    }

    @Test
    public void testLastSnapshotData_snapshotSaved() {
        final TaskSnapshot snapshot = createSnapshot(new Point(100, 100), new Point(80, 80));
        final Task task1 = createTaskBuilder(".Task").build();
        task1.onSnapshotChanged(snapshot);

        mRecentTasks.add(task1);
        final List<RecentTaskInfo> infos = getRecentTasks(0 /* flags */);
        final RecentTaskInfo.PersistedTaskSnapshotData lastSnapshotData =
                infos.get(0).lastSnapshotData;
        assertTrue(lastSnapshotData.taskSize.equals(100, 100));
        assertTrue(lastSnapshotData.bufferSize.equals(80, 80));
    }

    @Test
    public void testLastSnapshotData_noBuffer() {
        final Task task1 = createTaskBuilder(".Task").build();
        final TaskSnapshot snapshot = createSnapshot(new Point(100, 100), null);
        task1.onSnapshotChanged(snapshot);

        mRecentTasks.add(task1);
        final List<RecentTaskInfo> infos = getRecentTasks(0 /* flags */);
        final RecentTaskInfo.PersistedTaskSnapshotData lastSnapshotData =
                infos.get(0).lastSnapshotData;
        assertTrue(lastSnapshotData.taskSize.equals(100, 100));
        assertNull(lastSnapshotData.bufferSize);
    }

    @Test
    public void testLastSnapshotData_notSet() {
        final Task task1 = createTaskBuilder(".Task").build();

        mRecentTasks.add(task1);
        final List<RecentTaskInfo> infos = getRecentTasks(0 /* flags */);
        final RecentTaskInfo.PersistedTaskSnapshotData lastSnapshotData =
                infos.get(0).lastSnapshotData;
        assertNull(lastSnapshotData.taskSize);
        assertNull(lastSnapshotData.bufferSize);
    }

    @Test
    public void testCreateRecentTaskInfo_detachedTask() {
        final Task task = createTaskBuilder(".Task").setCreateActivity(true).build();
        final TaskDisplayArea tda = task.getDisplayArea();

        assertTrue(task.isAttached());
        assertTrue(task.supportsMultiWindow());

        RecentTaskInfo info = mRecentTasks.createRecentTaskInfo(task, true);

        assertTrue(info.supportsMultiWindow);
        assertTrue(info.supportsSplitScreenMultiWindow);

        // The task can be put in split screen even if it is not attached now.
        task.removeImmediately();

        info = mRecentTasks.createRecentTaskInfo(task, true);

        assertTrue(info.supportsMultiWindow);
        assertTrue(info.supportsSplitScreenMultiWindow);

        // Test non-resizable.
        // The non-resizable task cannot be put in split screen because of the config.
        doReturn(false).when(tda).supportsNonResizableMultiWindow();
        doReturn(false).when(task).isResizeable();

        info = mRecentTasks.createRecentTaskInfo(task, true);

        assertFalse(info.supportsMultiWindow);
        assertFalse(info.supportsSplitScreenMultiWindow);

        // Even if it is not attached, the non-resizable task can be put in split screen as long as
        // the device supports it.
        doReturn(true).when(tda).supportsNonResizableMultiWindow();

        info = mRecentTasks.createRecentTaskInfo(task, true);

        assertTrue(info.supportsMultiWindow);
        assertTrue(info.supportsSplitScreenMultiWindow);

    }

    private TaskSnapshot createSnapshot(Point taskSize, Point bufferSize) {
        HardwareBuffer buffer = null;
        if (bufferSize != null) {
            buffer = mock(HardwareBuffer.class);
            doReturn(bufferSize.x).when(buffer).getWidth();
            doReturn(bufferSize.y).when(buffer).getHeight();
        }
        return new TaskSnapshot(1, new ComponentName("", ""), buffer,
                ColorSpace.get(ColorSpace.Named.SRGB), ORIENTATION_PORTRAIT,
                Surface.ROTATION_0, taskSize, new Rect() /* insets */, false /* isLowResolution */,
                true /* isRealSnapshot */, WINDOWING_MODE_FULLSCREEN, 0 /* mSystemUiVisibility */,
                false /* isTranslucent */, false /* hasImeSurface */);
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

    private List<RecentTaskInfo> getRecentTasks(int flags) {
        doNothing().when(mRecentTasks).loadUserRecentsLocked(anyInt());
        doReturn(true).when(mRecentTasks).isUserRunning(anyInt(), anyInt());
        return mRecentTasks.getRecentTasks(MAX_VALUE, flags, true /* getTasksAllowed */,
                TEST_USER_0_ID, 0 /* callingUid */).getList();
    }

    /**
     * Ensures that the recent tasks list is in the provided order. Note that the expected tasks
     * should be ordered from least to most recent.
     */
    private void assertGetRecentTasksOrder(int getRecentTaskFlags, Task... expectedTasks) {
        List<RecentTaskInfo> infos = getRecentTasks(getRecentTaskFlags);
        assertTrue(expectedTasks.length == infos.size());
        for (int i = 0; i < infos.size(); i++)  {
            assertTrue(expectedTasks[i].mTaskId == infos.get(i).taskId);
        }
    }

    private void assertNotRestoreTask(Runnable action) {
        // Verify stack count doesn't change because task with fullscreen mode and standard type
        // would have its own stack.
        final int originalStackCount = mTaskContainer.getRootTaskCount();
        action.run();
        assertEquals(originalStackCount, mTaskContainer.getRootTaskCount());
    }

    private void doTestRecentTasksApis(boolean expectCallable) {
        assertSecurityException(expectCallable, () -> mAtm.removeTask(INVALID_STACK_ID));
        assertSecurityException(expectCallable,
                () -> mAtm.removeRootTasksInWindowingModes(
                        new int[]{WINDOWING_MODE_UNDEFINED}));
        assertSecurityException(expectCallable,
                () -> mAtm.removeRootTasksWithActivityTypes(
                        new int[]{ACTIVITY_TYPE_UNDEFINED}));
        assertSecurityException(expectCallable, () -> mAtm.removeTask(0));
        assertSecurityException(expectCallable,
                () -> mAtm.moveTaskToRootTask(0, INVALID_STACK_ID, true));
        assertSecurityException(expectCallable, () -> mAtm.getAllRootTaskInfos());
        assertSecurityException(expectCallable,
                () -> mAtm.getRootTaskInfo(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_UNDEFINED));
        assertSecurityException(expectCallable, () -> {
            try {
                mAtm.getFocusedRootTaskInfo();
            } catch (RemoteException e) {
                // Ignore
            }
        });
        assertSecurityException(expectCallable,
                () -> mAtm.startActivityFromRecents(0, new Bundle()));
        assertSecurityException(expectCallable, () -> mAtm.getTaskSnapshot(0, true));
        assertSecurityException(expectCallable, () -> mAtm.registerTaskStackListener(null));
        assertSecurityException(expectCallable,
                () -> mAtm.unregisterTaskStackListener(null));
        assertSecurityException(expectCallable, () -> mAtm.getTaskDescription(0));
        assertSecurityException(expectCallable, () -> mAtm.cancelTaskWindowTransition(0));
        assertSecurityException(expectCallable, () -> mAtm.startRecentsActivity(null, 0,
                null));
        assertSecurityException(expectCallable, () -> mAtm.cancelRecentsAnimation(true));
        assertSecurityException(expectCallable, () -> mAtm.stopAppSwitches());
        assertSecurityException(expectCallable, () -> mAtm.resumeAppSwitches());
    }

    private void testGetTasksApis(boolean expectCallable) {
        mAtm.getRecentTasks(MAX_VALUE, 0, TEST_USER_0_ID);
        mAtm.getTasks(MAX_VALUE);
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
        return new TaskBuilder(mAtm.mTaskSupervisor)
                .setComponent(new ComponentName(packageName, className))
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

    private void triggerIdleToTrim() {
        doNothing().when(mAtm).scheduleAppGcsLocked();
        final ActivityRecord r = mRootWindowContainer.topRunningActivity();
        mSupervisor.activityIdleInternal(r != null ? r : mock(ActivityRecord.class),
                false /* fromTimeout */, false /* processPausingActivities */, null /* config */);
    }

    private void triggerTrimAndAssertNoTasksTrimmed() {
        triggerTrimAndAssertTrimmed();
    }

    private void triggerTrimAndAssertTrimmed(Task... tasks) {
        triggerIdleToTrim();
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
        private boolean mIsTrimmableOverride;

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
        void getTasks(int maxNum, List<RunningTaskInfo> list, int flags,
                RootWindowContainer root, int callingUid, ArraySet<Integer> profileIds) {
            mLastAllowed = (flags & FLAG_ALLOWED) == FLAG_ALLOWED;
            super.getTasks(maxNum, list, flags, root, callingUid, profileIds);
        }
    }
}
