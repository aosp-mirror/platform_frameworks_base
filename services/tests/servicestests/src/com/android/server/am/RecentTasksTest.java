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

import static android.app.ActivityManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManager.RunningTaskInfo;
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
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.am.RecentTasks.Callbacks;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static java.lang.Integer.MAX_VALUE;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * atest FrameworksServicesTests:RecentTasksTest
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RecentTasksTest extends ActivityTestsBase {
    private static final int TEST_USER_0_ID = 0;
    private static final int TEST_USER_1_ID = 10;
    private static final int TEST_QUIET_USER_ID = 20;
    private static final UserInfo DEFAULT_USER_INFO = new UserInfo();
    private static final UserInfo QUIET_USER_INFO = new UserInfo();
    private static int LAST_TASK_ID = 1;
    private static int LAST_STACK_ID = 1;
    private static int INVALID_STACK_ID = 999;

    private Context mContext = InstrumentationRegistry.getContext();
    private ActivityManagerService mService;
    private ActivityDisplay mDisplay;
    private ActivityDisplay mOtherDisplay;
    private ActivityStack mStack;
    private ActivityStack mHomeStack;
    private TestTaskPersister mTaskPersister;
    private TestRecentTasks mRecentTasks;
    private TestRunningTasks mRunningTasks;

    private ArrayList<TaskRecord> mTasks;
    private ArrayList<TaskRecord> mSameDocumentTasks;

    private CallbacksRecorder mCallbacksRecorder;

    class TestUserController extends UserController {
        TestUserController(ActivityManagerService service) {
            super(service);
        }

        @Override
        int[] getCurrentProfileIds() {
            return new int[] { TEST_USER_0_ID, TEST_QUIET_USER_ID };
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
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mTaskPersister = new TestTaskPersister(mContext.getFilesDir());
        mService = setupActivityManagerService(new MyTestActivityManagerService(mContext));
        mRecentTasks = (TestRecentTasks) mService.getRecentTasks();
        mRecentTasks.loadParametersFromResources(mContext.getResources());
        mHomeStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, true /* onTop */);
        mStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        ((MyTestActivityStackSupervisor) mService.mStackSupervisor).setHomeStack(mHomeStack);
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
    public void testCallbacks() throws Exception {
        // Add some tasks
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));
        assertTrue(mCallbacksRecorder.added.contains(mTasks.get(0))
                && mCallbacksRecorder.added.contains(mTasks.get(1)));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
        mCallbacksRecorder.clear();

        // Remove some tasks
        mRecentTasks.remove(mTasks.get(0));
        mRecentTasks.remove(mTasks.get(1));
        assertTrue(mCallbacksRecorder.added.isEmpty());
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.contains(mTasks.get(0)));
        assertTrue(mCallbacksRecorder.removed.contains(mTasks.get(1)));
        mCallbacksRecorder.clear();

        // Remove the callback, ensure we don't get any calls
        mRecentTasks.unregisterCallback(mCallbacksRecorder);
        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.remove(mTasks.get(0));
        assertTrue(mCallbacksRecorder.added.isEmpty());
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
    }

    @Test
    public void testAddTasksNoMultiple_expectNoTrim() throws Exception {
        // Add same non-multiple-task document tasks will remove the task (to re-add it) but not
        // trim it
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1");
        TaskRecord documentTask2 = createDocumentTask(".DocumentTask1");
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask2);
        assertTrue(mCallbacksRecorder.added.contains(documentTask1));
        assertTrue(mCallbacksRecorder.added.contains(documentTask2));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.contains(documentTask1));
    }

    @Test
    public void testAddTasksMaxTaskRecents_expectNoTrim() throws Exception {
        // Add a task hitting max-recents for that app will remove the task (to add the next one)
        // but not trim it
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1");
        TaskRecord documentTask2 = createDocumentTask(".DocumentTask1");
        documentTask1.maxRecents = 1;
        documentTask2.maxRecents = 1;
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask2);
        assertTrue(mCallbacksRecorder.added.contains(documentTask1));
        assertTrue(mCallbacksRecorder.added.contains(documentTask2));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.contains(documentTask1));
    }

    @Test
    public void testAddTasksSameTask_expectNoTrim() throws Exception {
        // Add a task that is already in the task list does not trigger any callbacks, it just
        // moves in the list
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1");
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask1);
        assertTrue(mCallbacksRecorder.added.size() == 1);
        assertTrue(mCallbacksRecorder.added.contains(documentTask1));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
    }

    @Test
    public void testAddTasksMultipleDocumentTasks_expectNoTrim() throws Exception {
        // Add same multiple-task document tasks does not trim the first tasks
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1",
                FLAG_ACTIVITY_MULTIPLE_TASK);
        TaskRecord documentTask2 = createDocumentTask(".DocumentTask1",
                FLAG_ACTIVITY_MULTIPLE_TASK);
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask2);
        assertTrue(mCallbacksRecorder.added.size() == 2);
        assertTrue(mCallbacksRecorder.added.contains(documentTask1));
        assertTrue(mCallbacksRecorder.added.contains(documentTask2));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
    }

    @Test
    public void testAddTasksMultipleTasks_expectRemovedNoTrim() throws Exception {
        // Add multiple same-affinity non-document tasks, ensure that it removes the other task,
        // but that it does not trim it
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .build();
        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK)
                .build();
        mRecentTasks.add(task1);
        assertTrue(mCallbacksRecorder.added.size() == 1);
        assertTrue(mCallbacksRecorder.added.contains(task1));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
        mCallbacksRecorder.clear();
        mRecentTasks.add(task2);
        assertTrue(mCallbacksRecorder.added.size() == 1);
        assertTrue(mCallbacksRecorder.added.contains(task2));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.size() == 1);
        assertTrue(mCallbacksRecorder.removed.contains(task1));
    }

    @Test
    public void testAddTasksDifferentStacks_expectNoRemove() throws Exception {
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
        assertTrue(mCallbacksRecorder.added.size() == 2);
        assertTrue(mCallbacksRecorder.added.contains(task1));
        assertTrue(mCallbacksRecorder.added.contains(task2));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
    }

    @Test
    public void testAddTaskCompatibleActivityType_expectRemove() throws Exception {
        // Test with undefined activity type since the type is not persisted by the task persister
        // and we want to ensure that a new task will match a restored task
        Configuration config1 = new Configuration();
        config1.windowConfiguration.setActivityType(ACTIVITY_TYPE_UNDEFINED);
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        task1.onConfigurationChanged(config1);
        assertTrue(task1.getActivityType() == ACTIVITY_TYPE_UNDEFINED);
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        assertTrue(task2.getActivityType() == ACTIVITY_TYPE_STANDARD);
        mRecentTasks.add(task2);
        assertTrue(mCallbacksRecorder.added.size() == 1);
        assertTrue(mCallbacksRecorder.added.contains(task2));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.size() == 1);
        assertTrue(mCallbacksRecorder.removed.contains(task1));
    }

    @Test
    public void testAddTaskCompatibleActivityTypeDifferentUser_expectNoRemove() throws Exception {
        Configuration config1 = new Configuration();
        config1.windowConfiguration.setActivityType(ACTIVITY_TYPE_UNDEFINED);
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .setUserId(TEST_USER_0_ID)
                .build();
        task1.onConfigurationChanged(config1);
        assertTrue(task1.getActivityType() == ACTIVITY_TYPE_UNDEFINED);
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .setUserId(TEST_USER_1_ID)
                .build();
        assertTrue(task2.getActivityType() == ACTIVITY_TYPE_STANDARD);
        mRecentTasks.add(task2);
        assertTrue(mCallbacksRecorder.added.size() == 1);
        assertTrue(mCallbacksRecorder.added.contains(task2));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
    }

    @Test
    public void testAddTaskCompatibleWindowingMode_expectRemove() throws Exception {
        Configuration config1 = new Configuration();
        config1.windowConfiguration.setWindowingMode(WINDOWING_MODE_UNDEFINED);
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        task1.onConfigurationChanged(config1);
        assertTrue(task1.getWindowingMode() == WINDOWING_MODE_UNDEFINED);
        mRecentTasks.add(task1);
        mCallbacksRecorder.clear();

        Configuration config2 = new Configuration();
        config2.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        task2.onConfigurationChanged(config2);
        assertTrue(task2.getWindowingMode() == WINDOWING_MODE_FULLSCREEN);
        mRecentTasks.add(task2);

        assertTrue(mCallbacksRecorder.added.size() == 1);
        assertTrue(mCallbacksRecorder.added.contains(task2));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.size() == 1);
        assertTrue(mCallbacksRecorder.removed.contains(task1));
    }

    @Test
    public void testAddTaskIncompatibleWindowingMode_expectNoRemove() throws Exception {
        Configuration config1 = new Configuration();
        config1.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        TaskRecord task1 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        task1.onConfigurationChanged(config1);
        assertTrue(task1.getWindowingMode() == WINDOWING_MODE_FULLSCREEN);
        mRecentTasks.add(task1);

        Configuration config2 = new Configuration();
        config2.windowConfiguration.setWindowingMode(WINDOWING_MODE_PINNED);
        TaskRecord task2 = createTaskBuilder(".Task1")
                .setFlags(FLAG_ACTIVITY_NEW_TASK)
                .setStack(mStack)
                .build();
        task2.onConfigurationChanged(config2);
        assertTrue(task2.getWindowingMode() == WINDOWING_MODE_PINNED);
        mRecentTasks.add(task2);

        assertTrue(mCallbacksRecorder.added.size() == 2);
        assertTrue(mCallbacksRecorder.added.contains(task1));
        assertTrue(mCallbacksRecorder.added.contains(task2));
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
    }

    @Test
    public void testUsersTasks() throws Exception {
        mRecentTasks.setOnlyTestVisibleRange();

        // Setup some tasks for the users
        mTaskPersister.userTaskIdsOverride = new SparseBooleanArray();
        mTaskPersister.userTaskIdsOverride.put(1, true);
        mTaskPersister.userTaskIdsOverride.put(2, true);
        mTaskPersister.userTasksOverride = new ArrayList<>();
        mTaskPersister.userTasksOverride.add(createTaskBuilder(".UserTask1").build());
        mTaskPersister.userTasksOverride.add(createTaskBuilder(".UserTask2").build());

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

    @Test
    public void testOrderedIteration() throws Exception {
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
            assertTrue(task.lastActiveTime >= prevLastActiveTime.value);
            prevLastActiveTime.value = task.lastActiveTime;
        }
    }

    @Test
    public void testTrimToGlobalMaxNumRecents() throws Exception {
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
    public void testTrimQuietProfileTasks() throws Exception {
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
    public void testSessionDuration() throws Exception {
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
    public void testVisibleTasks_excludedFromRecents() throws Exception {
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
    public void testVisibleTasks_minNum() throws Exception {
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
    public void testVisibleTasks_maxNum() throws Exception {
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

    @Test
    public void testBackStackTasks_expectNoTrim() throws Exception {
        mRecentTasks.setParameters(-1 /* min */, 1 /* max */, -1 /* ms */);

        final MyTestActivityStackSupervisor supervisor =
                (MyTestActivityStackSupervisor) mService.mStackSupervisor;
        final ActivityStack homeStack = new MyTestActivityStack(mDisplay, supervisor);
        final ActivityStack aboveHomeStack = new MyTestActivityStack(mDisplay, supervisor);
        supervisor.setHomeStack(homeStack);

        // Add a number of tasks (beyond the max) but ensure that nothing is trimmed because all
        // the tasks belong in stacks above the home stack
        mRecentTasks.add(createTaskBuilder(".HomeTask1").setStack(homeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task1").setStack(aboveHomeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task2").setStack(aboveHomeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task3").setStack(aboveHomeStack).build());

        assertNoTasksTrimmed();
    }

    @Test
    public void testBehindHomeStackTasks_expectTaskTrimmed() throws Exception {
        mRecentTasks.setParameters(-1 /* min */, 1 /* max */, -1 /* ms */);

        final MyTestActivityStackSupervisor supervisor =
                (MyTestActivityStackSupervisor) mService.mStackSupervisor;
        final ActivityStack behindHomeStack = new MyTestActivityStack(mDisplay, supervisor);
        final ActivityStack homeStack = new MyTestActivityStack(mDisplay, supervisor);
        final ActivityStack aboveHomeStack = new MyTestActivityStack(mDisplay, supervisor);
        supervisor.setHomeStack(homeStack);

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
    public void testOtherDisplayTasks_expectNoTrim() throws Exception {
        mRecentTasks.setParameters(-1 /* min */, 1 /* max */, -1 /* ms */);

        final MyTestActivityStackSupervisor supervisor =
                (MyTestActivityStackSupervisor) mService.mStackSupervisor;
        final ActivityStack homeStack = new MyTestActivityStack(mDisplay, supervisor);
        final ActivityStack otherDisplayStack = new MyTestActivityStack(mOtherDisplay, supervisor);
        supervisor.setHomeStack(homeStack);

        // Add a number of tasks (beyond the max) on each display, ensure that the tasks are not
        // removed
        mRecentTasks.add(createTaskBuilder(".HomeTask1").setStack(homeStack).build());
        mRecentTasks.add(createTaskBuilder(".Task1").setStack(otherDisplayStack).build());
        mRecentTasks.add(createTaskBuilder(".Task2").setStack(otherDisplayStack).build());
        mRecentTasks.add(createTaskBuilder(".HomeTask2").setStack(homeStack).build());

        assertNoTasksTrimmed();
    }

    @Test
    public void testRemovePackageByName() throws Exception {
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
    public void testNotRecentsComponent_denyApiAccess() throws Exception {
        doReturn(PackageManager.PERMISSION_DENIED).when(mService).checkPermission(anyString(),
                anyInt(), anyInt());

        // Expect the following methods to fail due to recents component not being set
        mRecentTasks.setIsCallerRecentsOverride(TestRecentTasks.DENY_THROW_SECURITY_EXCEPTION);
        testRecentTasksApis(false /* expectNoSecurityException */);
        // Don't throw for the following tests
        mRecentTasks.setIsCallerRecentsOverride(TestRecentTasks.DENY);
        testGetTasksApis(false /* expectNoSecurityException */);
    }

    @Test
    public void testRecentsComponent_allowApiAccessWithoutPermissions() throws Exception {
        doReturn(PackageManager.PERMISSION_DENIED).when(mService).checkPermission(anyString(),
                anyInt(), anyInt());

        // Set the recents component and ensure that the following calls do not fail
        mRecentTasks.setIsCallerRecentsOverride(TestRecentTasks.GRANT);
        testRecentTasksApis(true /* expectNoSecurityException */);
        testGetTasksApis(true /* expectNoSecurityException */);
    }

    private void testRecentTasksApis(boolean expectCallable) {
        assertSecurityException(expectCallable, () -> mService.removeStack(INVALID_STACK_ID));
        assertSecurityException(expectCallable,
                () -> mService.removeStacksInWindowingModes(new int[] {WINDOWING_MODE_UNDEFINED}));
        assertSecurityException(expectCallable,
                () -> mService.removeStacksWithActivityTypes(new int[] {ACTIVITY_TYPE_UNDEFINED}));
        assertSecurityException(expectCallable, () -> mService.removeTask(0));
        assertSecurityException(expectCallable,
                () -> mService.setTaskWindowingMode(0, WINDOWING_MODE_UNDEFINED, true));
        assertSecurityException(expectCallable,
                () -> mService.moveTaskToStack(0, INVALID_STACK_ID, true));
        assertSecurityException(expectCallable,
                () -> mService.setTaskWindowingModeSplitScreenPrimary(0,
                        SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, true, true, new Rect(), true));
        assertSecurityException(expectCallable, () -> mService.dismissSplitScreenMode(true));
        assertSecurityException(expectCallable, () -> mService.dismissPip(true, 0));
        assertSecurityException(expectCallable,
                () -> mService.moveTopActivityToPinnedStack(INVALID_STACK_ID, new Rect()));
        assertSecurityException(expectCallable,
                () -> mService.resizeStack(INVALID_STACK_ID, new Rect(), true, true, true, 0));
        assertSecurityException(expectCallable,
                () -> mService.resizeDockedStack(new Rect(), new Rect(), new Rect(), new Rect(),
                        new Rect()));
        assertSecurityException(expectCallable,
                () -> mService.resizePinnedStack(new Rect(), new Rect()));
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
        assertSecurityException(expectCallable,
                () -> mService.getTaskSnapshot(0, true));
        assertSecurityException(expectCallable, () -> {
            try {
                mService.registerTaskStackListener(null);
            } catch (RemoteException e) {
                // Ignore
            }
        });
        assertSecurityException(expectCallable, () -> {
            try {
                mService.unregisterTaskStackListener(null);
            } catch (RemoteException e) {
                // Ignore
            }
        });
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
            assertTrue(mRecentTasks.lastAllowed);
            assertTrue(mRunningTasks.lastAllowed);
        } else {
            assertFalse(mRecentTasks.lastAllowed);
            assertFalse(mRunningTasks.lastAllowed);
        }
    }

    private TaskBuilder createTaskBuilder(String className) {
        return createTaskBuilder(mContext.getPackageName(), className);
    }

    private TaskBuilder createTaskBuilder(String packageName, String className) {
        return new TaskBuilder(mService.mStackSupervisor)
                .setComponent(new ComponentName(packageName, className))
                .setStack(mStack)
                .setTaskId(LAST_TASK_ID++)
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
        task.maxRecents = ActivityManager.getMaxAppRecentsLimitStatic();
        return task;
    }

    private boolean arrayContainsUser(int[] userIds, int targetUserId) {
        Arrays.sort(userIds);
        return Arrays.binarySearch(userIds, targetUserId) >= 0;
    }

    private void assertNoTasksTrimmed() {
        assertTrimmed();
    }

    private void assertTrimmed(TaskRecord... tasks) {
        final ArrayList<TaskRecord> trimmed = mCallbacksRecorder.trimmed;
        final ArrayList<TaskRecord> removed = mCallbacksRecorder.removed;
        assertTrue("Expected " + tasks.length + " trimmed tasks, got " + trimmed.size(),
                trimmed.size() == tasks.length);
        assertTrue("Expected " + tasks.length + " removed tasks, got " + removed.size(),
                removed.size() == tasks.length);
        for (TaskRecord task : tasks) {
            assertTrue("Expected trimmed task: " + task, trimmed.contains(task));
            assertTrue("Expected removed task: " + task, removed.contains(task));
        }
    }

    private void assertSecurityException(boolean expectCallable, Runnable runnable) {
        boolean noSecurityException = true;
        try {
            runnable.run();
        } catch (SecurityException se) {
            noSecurityException = false;
        } catch (Exception e) {
            // We only care about SecurityExceptions, fall through here
            e.printStackTrace();
        }
        if (noSecurityException != expectCallable) {
            fail("Expected callable: " + expectCallable + " but got no security exception: "
                    + noSecurityException);
        }
    }

    private class MyTestActivityManagerService extends TestActivityManagerService {
        MyTestActivityManagerService(Context context) {
            super(context);
        }

        @Override
        protected ActivityStackSupervisor createTestSupervisor() {
            return new MyTestActivityStackSupervisor(this, mHandlerThread.getLooper());
        }

        @Override
        protected RecentTasks createRecentTasks() {
            return new TestRecentTasks(this, mTaskPersister, new TestUserController(this));
        }

        @Override
        public boolean isUserRunning(int userId, int flags) {
            return true;
        }
    }

    private class MyTestActivityStackSupervisor extends TestActivityStackSupervisor {
        public MyTestActivityStackSupervisor(ActivityManagerService service, Looper looper) {
            super(service, looper);
        }

        @Override
        public void initialize() {
            super.initialize();
            mDisplay = new TestActivityDisplay(this, DEFAULT_DISPLAY);
            mOtherDisplay = new TestActivityDisplay(this, DEFAULT_DISPLAY);
            attachDisplay(mOtherDisplay);
            attachDisplay(mDisplay);
        }

        @Override
        RunningTasks createRunningTasks() {
            mRunningTasks = new TestRunningTasks();
            return mRunningTasks;
        }

        void setHomeStack(ActivityStack stack) {
            mHomeStack = stack;
        }
    }

    private class MyTestActivityStack extends TestActivityStack {
        private ActivityDisplay mDisplay = null;

        MyTestActivityStack(ActivityDisplay display, ActivityStackSupervisor supervisor) {
            super(display, LAST_STACK_ID++, supervisor, WINDOWING_MODE_FULLSCREEN,
                    ACTIVITY_TYPE_STANDARD, true);
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
        ArrayList<TaskRecord> added = new ArrayList<>();
        ArrayList<TaskRecord> trimmed = new ArrayList<>();
        ArrayList<TaskRecord> removed = new ArrayList<>();

        void clear() {
            added.clear();
            trimmed.clear();
            removed.clear();
        }

        @Override
        public void onRecentTaskAdded(TaskRecord task) {
            added.add(task);
        }

        @Override
        public void onRecentTaskRemoved(TaskRecord task, boolean wasTrimmed) {
            if (wasTrimmed) {
                trimmed.add(task);
            }
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

    private static class TestRecentTasks extends RecentTasks {
        static final int GRANT = 0;
        static final int DENY = 1;
        static final int DENY_THROW_SECURITY_EXCEPTION = 2;

        private boolean mOverrideIsCallerRecents;
        private boolean mIsTrimmableOverride;
        private int mIsCallerRecentsPolicy;

        boolean lastAllowed;

        TestRecentTasks(ActivityManagerService service, TaskPersister taskPersister,
                UserController userController) {
            super(service, taskPersister, userController);
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
            lastAllowed = getTasksAllowed;
            return super.getRecentTasks(maxNum, flags, getTasksAllowed, getDetailedTasks, userId,
                    callingUid);
        }

        @Override
        protected boolean isTrimmable(TaskRecord task) {
            return mIsTrimmableOverride || super.isTrimmable(task);
        }
    }

    private static class TestRunningTasks extends RunningTasks {
        boolean lastAllowed;

        @Override
        void getTasks(int maxNum, List<RunningTaskInfo> list, int ignoreActivityType,
                int ignoreWindowingMode, SparseArray<ActivityDisplay> activityDisplays,
                int callingUid, boolean allowed) {
            lastAllowed = allowed;
            super.getTasks(maxNum, list, ignoreActivityType, ignoreWindowingMode, activityDisplays,
                    callingUid, allowed);
        }
    }
}
