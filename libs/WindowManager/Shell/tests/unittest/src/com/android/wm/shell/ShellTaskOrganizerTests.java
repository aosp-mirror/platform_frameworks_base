/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;
import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_MULTI_WINDOW;
import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_PIP;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.SurfaceControl;
import android.window.ITaskOrganizer;
import android.window.ITaskOrganizerController;
import android.window.TaskAppearedInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.sizecompatui.SizeCompatUIController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * Tests for the shell task organizer.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:ShellTaskOrganizerTests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ShellTaskOrganizerTests {

    @Mock
    private ITaskOrganizerController mTaskOrganizerController;
    @Mock
    private Context mContext;
    @Mock
    private SizeCompatUIController mSizeCompatUI;

    ShellTaskOrganizer mOrganizer;
    private final SyncTransactionQueue mSyncTransactionQueue = mock(SyncTransactionQueue.class);
    private final TransactionPool mTransactionPool = mock(TransactionPool.class);
    private final ShellExecutor mTestExecutor = mock(ShellExecutor.class);

    private class TrackingTaskListener implements ShellTaskOrganizer.TaskListener {
        final ArrayList<RunningTaskInfo> appeared = new ArrayList<>();
        final ArrayList<RunningTaskInfo> vanished = new ArrayList<>();
        final ArrayList<RunningTaskInfo> infoChanged = new ArrayList<>();

        @Override
        public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
            appeared.add(taskInfo);
        }

        @Override
        public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
            infoChanged.add(taskInfo);
        }

        @Override
        public void onTaskVanished(RunningTaskInfo taskInfo) {
            vanished.add(taskInfo);
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        try {
            doReturn(ParceledListSlice.<TaskAppearedInfo>emptyList())
                    .when(mTaskOrganizerController).registerTaskOrganizer(any());
        } catch (RemoteException e) {}
        mOrganizer = spy(new ShellTaskOrganizer(mTaskOrganizerController, mTestExecutor, mContext,
                mSizeCompatUI));
    }

    @Test
    public void registerOrganizer_sendRegisterTaskOrganizer() throws RemoteException {
        mOrganizer.registerOrganizer();

        verify(mTaskOrganizerController).registerTaskOrganizer(any(ITaskOrganizer.class));
    }

    @Test
    public void testOneListenerPerType() {
        mOrganizer.addListenerForType(new TrackingTaskListener(), TASK_LISTENER_TYPE_MULTI_WINDOW);
        try {
            mOrganizer.addListenerForType(
                    new TrackingTaskListener(), TASK_LISTENER_TYPE_MULTI_WINDOW);
            fail("Expected exception due to already registered listener");
        } catch (Exception e) {
            // Expected failure
        }
    }

    @Test
    public void testRegisterWithExistingTasks() throws RemoteException {
        // Setup some tasks
        RunningTaskInfo task1 = createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW);
        RunningTaskInfo task2 = createTaskInfo(2, WINDOWING_MODE_MULTI_WINDOW);
        ArrayList<TaskAppearedInfo> taskInfos = new ArrayList<>();
        taskInfos.add(new TaskAppearedInfo(task1, new SurfaceControl()));
        taskInfos.add(new TaskAppearedInfo(task2, new SurfaceControl()));
        doReturn(new ParceledListSlice(taskInfos))
                .when(mTaskOrganizerController).registerTaskOrganizer(any());

        // Register and expect the tasks to be stored
        mOrganizer.registerOrganizer();

        // Check that the tasks are next reported when the listener is added
        TrackingTaskListener listener = new TrackingTaskListener();
        mOrganizer.addListenerForType(listener, TASK_LISTENER_TYPE_MULTI_WINDOW);
        assertTrue(listener.appeared.contains(task1));
        assertTrue(listener.appeared.contains(task2));
    }

    @Test
    public void testAppearedVanished() {
        RunningTaskInfo taskInfo = createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW);
        TrackingTaskListener listener = new TrackingTaskListener();
        mOrganizer.addListenerForType(listener, TASK_LISTENER_TYPE_MULTI_WINDOW);
        mOrganizer.onTaskAppeared(taskInfo, null);
        assertTrue(listener.appeared.contains(taskInfo));

        mOrganizer.onTaskVanished(taskInfo);
        assertTrue(listener.vanished.contains(taskInfo));
    }

    @Test
    public void testAddListenerExistingTasks() {
        RunningTaskInfo taskInfo = createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW);
        mOrganizer.onTaskAppeared(taskInfo, null);

        TrackingTaskListener listener = new TrackingTaskListener();
        mOrganizer.addListenerForType(listener, TASK_LISTENER_TYPE_MULTI_WINDOW);
        assertTrue(listener.appeared.contains(taskInfo));
    }

    @Test
    public void testWindowingModeChange() {
        RunningTaskInfo taskInfo = createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW);
        TrackingTaskListener mwListener = new TrackingTaskListener();
        TrackingTaskListener pipListener = new TrackingTaskListener();
        mOrganizer.addListenerForType(mwListener, TASK_LISTENER_TYPE_MULTI_WINDOW);
        mOrganizer.addListenerForType(pipListener, TASK_LISTENER_TYPE_PIP);
        mOrganizer.onTaskAppeared(taskInfo, null);
        assertTrue(mwListener.appeared.contains(taskInfo));
        assertTrue(pipListener.appeared.isEmpty());

        taskInfo = createTaskInfo(1, WINDOWING_MODE_PINNED);
        mOrganizer.onTaskInfoChanged(taskInfo);
        assertTrue(mwListener.vanished.contains(taskInfo));
        assertTrue(pipListener.appeared.contains(taskInfo));
    }

    @Test
    public void testAddListenerForTaskId_afterTypeListener() {
        RunningTaskInfo task1 = createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW);
        TrackingTaskListener mwListener = new TrackingTaskListener();
        TrackingTaskListener task1Listener = new TrackingTaskListener();
        mOrganizer.addListenerForType(mwListener, TASK_LISTENER_TYPE_MULTI_WINDOW);
        mOrganizer.onTaskAppeared(task1, null);
        assertTrue(mwListener.appeared.contains(task1));

        // Add task 1 specific listener
        mOrganizer.addListenerForTaskId(task1Listener, 1);
        assertTrue(mwListener.vanished.contains(task1));
        assertTrue(task1Listener.appeared.contains(task1));
    }

    @Test
    public void testAddListenerForTaskId_beforeTypeListener() {
        RunningTaskInfo task1 = createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW);
        TrackingTaskListener mwListener = new TrackingTaskListener();
        TrackingTaskListener task1Listener = new TrackingTaskListener();
        mOrganizer.onTaskAppeared(task1, null);
        mOrganizer.addListenerForTaskId(task1Listener, 1);
        assertTrue(task1Listener.appeared.contains(task1));

        mOrganizer.addListenerForType(mwListener, TASK_LISTENER_TYPE_MULTI_WINDOW);
        assertFalse(mwListener.appeared.contains(task1));
    }

    @Test
    public void testGetTaskListener() {
        RunningTaskInfo task1 = createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW);

        TrackingTaskListener mwListener = new TrackingTaskListener();
        mOrganizer.addListenerForType(mwListener, TASK_LISTENER_TYPE_MULTI_WINDOW);

        TrackingTaskListener cookieListener = new TrackingTaskListener();
        IBinder cookie = new Binder();
        task1.addLaunchCookie(cookie);
        mOrganizer.setPendingLaunchCookieListener(cookie, cookieListener);

        // Priority goes to the cookie listener so we would expect the task appear to show up there
        // instead of the multi-window type listener.
        mOrganizer.onTaskAppeared(task1, null);
        assertTrue(cookieListener.appeared.contains(task1));
        assertFalse(mwListener.appeared.contains(task1));

        TrackingTaskListener task1Listener = new TrackingTaskListener();

        boolean gotException = false;
        try {
            mOrganizer.addListenerForTaskId(task1Listener, 1);
        } catch (Exception e) {
            gotException = true;
        }
        // It should not be possible to add a task id listener for a task already mapped to a
        // listener through cookie.
        assertTrue(gotException);
    }

    @Test
    public void testGetParentTaskListener() {
        RunningTaskInfo task1 = createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW);
        TrackingTaskListener mwListener = new TrackingTaskListener();
        mOrganizer.onTaskAppeared(task1, null);
        mOrganizer.addListenerForTaskId(mwListener, task1.taskId);
        RunningTaskInfo task2 = createTaskInfo(1, WINDOWING_MODE_MULTI_WINDOW);
        task2.parentTaskId = task1.taskId;

        mOrganizer.onTaskAppeared(task2, null);

        assertTrue(mwListener.appeared.contains(task2));
    }

    @Test
    public void testOnSizeCompatActivityChanged() {
        final RunningTaskInfo taskInfo1 = createTaskInfo(12, WINDOWING_MODE_FULLSCREEN);
        taskInfo1.displayId = DEFAULT_DISPLAY;
        taskInfo1.topActivityToken = mock(IBinder.class);
        taskInfo1.topActivityInSizeCompat = false;
        final TrackingTaskListener taskListener = new TrackingTaskListener();
        mOrganizer.addListenerForType(taskListener, TASK_LISTENER_TYPE_FULLSCREEN);
        mOrganizer.onTaskAppeared(taskInfo1, null);

        // sizeCompatActivity is null if top activity is not in size compat.
        verify(mSizeCompatUI).onSizeCompatInfoChanged(taskInfo1.displayId, taskInfo1.taskId,
                null /* taskConfig */, null /* sizeCompatActivity*/, null /* taskListener */);

        // sizeCompatActivity is non-null if top activity is in size compat.
        clearInvocations(mSizeCompatUI);
        final RunningTaskInfo taskInfo2 =
                createTaskInfo(taskInfo1.taskId, taskInfo1.getWindowingMode());
        taskInfo2.displayId = taskInfo1.displayId;
        taskInfo2.topActivityToken = taskInfo1.topActivityToken;
        taskInfo2.topActivityInSizeCompat = true;
        mOrganizer.onTaskInfoChanged(taskInfo2);
        verify(mSizeCompatUI).onSizeCompatInfoChanged(taskInfo1.displayId, taskInfo1.taskId,
                taskInfo1.configuration, taskInfo1.topActivityToken, taskListener);

        clearInvocations(mSizeCompatUI);
        mOrganizer.onTaskVanished(taskInfo1);
        verify(mSizeCompatUI).onSizeCompatInfoChanged(taskInfo1.displayId, taskInfo1.taskId,
                null /* taskConfig */, null /* sizeCompatActivity*/, null /* taskListener */);
    }

    private static RunningTaskInfo createTaskInfo(int taskId, int windowingMode) {
        RunningTaskInfo taskInfo = new RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setWindowingMode(windowingMode);
        return taskInfo;
    }

}
