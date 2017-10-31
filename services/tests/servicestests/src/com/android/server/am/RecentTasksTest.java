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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import static java.lang.Integer.MAX_VALUE;

import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.MutableLong;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.server.am.RecentTasks.Callbacks;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * runtest --path frameworks/base/services/tests/servicestests/src/com/android/server/am/RecentTasksTest.java
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
    private static final ComponentName MY_COMPONENT = new ComponentName(
            RecentTasksTest.class.getPackage().getName(), RecentTasksTest.class.getName());
    private static int LAST_TASK_ID = 1;
    private static int INVALID_STACK_ID = 999;

    private Context mContext = InstrumentationRegistry.getContext();
    private ActivityManagerService mService;
    private ActivityStack mStack;
    private TestTaskPersister mTaskPersister;
    private RecentTasks mRecentTasks;
    private RunningTasks mRunningTasks;

    private static ArrayList<TaskRecord> mTasks = new ArrayList<>();
    private static ArrayList<TaskRecord> mSameDocumentTasks = new ArrayList<>();

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
        mRecentTasks = mService.getRecentTasks();
        mRecentTasks.loadParametersFromResources(mContext.getResources());
        mStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mCallbacksRecorder = new CallbacksRecorder();
        mRecentTasks.registerCallback(mCallbacksRecorder);
        QUIET_USER_INFO.flags = UserInfo.FLAG_MANAGED_PROFILE | UserInfo.FLAG_QUIET_MODE;

        mTasks.add(createTask(".Task1"));
        mTasks.add(createTask(".Task2"));
        mTasks.add(createTask(".Task3"));
        mTasks.add(createTask(".Task4"));
        mTasks.add(createTask(".Task5"));

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

        // Add a task which will trigger the trimming of another
        TaskRecord documentTask1 = createDocumentTask(".DocumentTask1", null /* affinity */);
        documentTask1.maxRecents = 1;
        TaskRecord documentTask2 = createDocumentTask(".DocumentTask1", null /* affinity */);
        mRecentTasks.add(documentTask1);
        mRecentTasks.add(documentTask2);
        assertTrue(mCallbacksRecorder.added.contains(documentTask1));
        assertTrue(mCallbacksRecorder.added.contains(documentTask2));
        assertTrue(mCallbacksRecorder.trimmed.contains(documentTask1));
        assertTrue(mCallbacksRecorder.removed.contains(documentTask1));
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

    @Test
    public void testOrderedIteration() throws Exception {
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
        TaskRecord qt1 = createTask(".QuietTask1", TEST_QUIET_USER_ID);
        TaskRecord qt2 = createTask(".QuietTask2", TEST_QUIET_USER_ID);
        mRecentTasks.add(qt1);
        mRecentTasks.add(qt2);

        mRecentTasks.add(mTasks.get(0));
        mRecentTasks.add(mTasks.get(1));

        // Ensure that the quiet user's tasks was trimmed once the new tasks were added
        assertTrimmed(qt1, qt2);
    }

    @Test
    public void testSessionDuration() throws Exception {
        mRecentTasks.setParameters(-1 /* min */, -1 /* max */, 50 /* ms */);

        TaskRecord t1 = createTask(".Task1");
        t1.touchActiveTime();
        mRecentTasks.add(t1);

        // Force a small sleep just beyond the session duration
        SystemClock.sleep(75);

        TaskRecord t2 = createTask(".Task2");
        t2.touchActiveTime();
        mRecentTasks.add(t2);

        // Assert that the old task has been removed due to being out of the active session
        assertTrimmed(t1);
    }

    @Test
    public void testVisibleTasks_excludedFromRecents() throws Exception {
        mRecentTasks.setParameters(-1 /* min */, 4 /* max */, -1 /* ms */);

        TaskRecord excludedTask1 = createTask(".ExcludedTask1", FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                TEST_USER_0_ID);
        TaskRecord excludedTask2 = createTask(".ExcludedTask2", FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                TEST_USER_0_ID);

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
        assertTrue(mCallbacksRecorder.trimmed.isEmpty());
        assertTrue(mCallbacksRecorder.removed.isEmpty());
    }

    @Test
    public void testVisibleTasks_maxNum() throws Exception {
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
    public void testNotRecentsComponent_denyApiAccess() throws Exception {
        doReturn(PackageManager.PERMISSION_DENIED).when(mService).checkPermission(anyString(),
                anyInt(), anyInt());

        // Expect the following methods to fail due to recents component not being set
        ((TestRecentTasks) mRecentTasks).setIsCallerRecentsOverride(
                TestRecentTasks.DENY_THROW_SECURITY_EXCEPTION);
        testRecentTasksApis(false /* expectNoSecurityException */);
        // Don't throw for the following tests
        ((TestRecentTasks) mRecentTasks).setIsCallerRecentsOverride(TestRecentTasks.DENY);
        testGetTasksApis(false /* expectNoSecurityException */);
    }

    @Test
    public void testRecentsComponent_allowApiAccessWithoutPermissions() throws Exception {
        doReturn(PackageManager.PERMISSION_DENIED).when(mService).checkPermission(anyString(),
                anyInt(), anyInt());

        // Set the recents component and ensure that the following calls do not fail
        ((TestRecentTasks) mRecentTasks).setIsCallerRecentsOverride(TestRecentTasks.GRANT);
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
                        SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, true, true, new Rect()));
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
        assertSecurityException(expectCallable, () -> mService.cancelTaskThumbnailTransition(0));
        assertSecurityException(expectCallable, () -> mService.startRecentsActivity(null, null, 0));
    }

    private void testGetTasksApis(boolean expectCallable) {
        mService.getRecentTasks(MAX_VALUE, 0, TEST_USER_0_ID);
        mService.getTasks(MAX_VALUE);
        if (expectCallable) {
            assertTrue(((TestRecentTasks) mRecentTasks).mLastAllowed);
            assertTrue(((TestRunningTasks) mRunningTasks).mLastAllowed);
        } else {
            assertFalse(((TestRecentTasks) mRecentTasks).mLastAllowed);
            assertFalse(((TestRunningTasks) mRunningTasks).mLastAllowed);
        }
    }

    private ComponentName createComponent(String className) {
        return new ComponentName(mContext.getPackageName(), className);
    }

    private TaskRecord createTask(String className) {
        return createTask(className, TEST_USER_0_ID);
    }

    private TaskRecord createTask(String className, int userId) {
        return createTask(className, 0 /* flags */, userId);
    }

    private TaskRecord createTask(String className, int flags, int userId) {
        TaskRecord task = new TaskBuilder(mService.mStackSupervisor)
                .setComponent(createComponent(className))
                .setStack(mStack).setFlags(flags).setTaskId(LAST_TASK_ID++).build();
        task.userId = userId;
        task.touchActiveTime();
        return task;
    }

    private TaskRecord createDocumentTask(String className, String affinity) {
        TaskRecord task = createTask(className, FLAG_ACTIVITY_NEW_DOCUMENT, TEST_USER_0_ID);
        task.affinity = affinity;
        return task;
    }

    private boolean arrayContainsUser(int[] userIds, int targetUserId) {
        Arrays.sort(userIds);
        return Arrays.binarySearch(userIds, targetUserId) >= 0;
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
        protected ActivityStackSupervisor createStackSupervisor() {
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
        RunningTasks createRunningTasks() {
            mRunningTasks = new TestRunningTasks();
            return mRunningTasks;
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
        private int mIsCallerRecentsPolicy;
        boolean mLastAllowed;

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

        @Override
        ParceledListSlice<RecentTaskInfo> getRecentTasks(int maxNum, int flags,
                boolean getTasksAllowed,
                boolean getDetailedTasks, int userId, int callingUid) {
            mLastAllowed = getTasksAllowed;
            return super.getRecentTasks(maxNum, flags, getTasksAllowed, getDetailedTasks, userId,
                    callingUid);
        }
    }

    private static class TestRunningTasks extends RunningTasks {
        boolean mLastAllowed;

        @Override
        void getTasks(int maxNum, List<RunningTaskInfo> list, int ignoreActivityType,
                int ignoreWindowingMode, SparseArray<ActivityDisplay> activityDisplays,
                int callingUid, boolean allowed) {
            mLastAllowed = allowed;
            super.getTasks(maxNum, list, ignoreActivityType, ignoreWindowingMode, activityDisplays,
                    callingUid, allowed);
        }
    }
}