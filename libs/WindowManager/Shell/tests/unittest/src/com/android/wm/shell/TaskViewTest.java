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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceSession;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SyncTransactionQueue.TransactionRunnable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class TaskViewTest extends ShellTestCase {

    @Mock
    TaskView.Listener mViewListener;
    @Mock
    ActivityManager.RunningTaskInfo mTaskInfo;
    @Mock
    WindowContainerToken mToken;
    @Mock
    ShellTaskOrganizer mOrganizer;
    @Mock
    HandlerExecutor mExecutor;
    @Mock
    SyncTransactionQueue mSyncQueue;

    SurfaceSession mSession;
    SurfaceControl mLeash;

    Context mContext;
    TaskView mTaskView;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLeash = new SurfaceControl.Builder(mSession)
                .setName("test")
                .build();

        mContext = getContext();

        mTaskInfo = new ActivityManager.RunningTaskInfo();
        mTaskInfo.token = mToken;
        mTaskInfo.taskId = 314;
        mTaskInfo.taskDescription = mock(ActivityManager.TaskDescription.class);

        doAnswer((InvocationOnMock invocationOnMock) -> {
            final Runnable r = invocationOnMock.getArgument(0);
            r.run();
            return null;
        }).when(mExecutor).execute(any());

        when(mOrganizer.getExecutor()).thenReturn(mExecutor);

        doAnswer((InvocationOnMock invocationOnMock) -> {
            final TransactionRunnable r = invocationOnMock.getArgument(0);
            r.runWithTransaction(new SurfaceControl.Transaction());
            return null;
        }).when(mSyncQueue).runInSync(any());

        mTaskView = new TaskView(mContext, mOrganizer, mSyncQueue);
        mTaskView.setListener(mExecutor, mViewListener);
    }

    @After
    public void tearDown() {
        if (mTaskView != null) {
            mTaskView.release();
        }
    }

    @Test
    public void testSetPendingListener_throwsException() {
        TaskView taskView = new TaskView(mContext, mOrganizer, mSyncQueue);
        taskView.setListener(mExecutor, mViewListener);
        try {
            taskView.setListener(mExecutor, mViewListener);
        } catch (IllegalStateException e) {
            // pass
            return;
        }
        fail("Expected IllegalStateException");
    }

    @Test
    public void testStartActivity() {
        ActivityOptions options = ActivityOptions.makeBasic();
        mTaskView.startActivity(mock(PendingIntent.class), null, options, new Rect(0, 0, 100, 100));

        verify(mOrganizer).setPendingLaunchCookieListener(any(), eq(mTaskView));
        assertThat(options.getLaunchWindowingMode()).isEqualTo(WINDOWING_MODE_MULTI_WINDOW);
    }

    @Test
    public void testOnTaskAppeared_noSurface() {
        mTaskView.onTaskAppeared(mTaskInfo, mLeash);

        verify(mViewListener).onTaskCreated(eq(mTaskInfo.taskId), any());
        verify(mViewListener, never()).onInitialized();
        // If there's no surface the task should be made invisible
        verify(mViewListener).onTaskVisibilityChanged(eq(mTaskInfo.taskId), eq(false));
    }

    @Test
    public void testOnTaskAppeared_withSurface() {
        mTaskView.surfaceCreated(mock(SurfaceHolder.class));
        mTaskView.onTaskAppeared(mTaskInfo, mLeash);

        verify(mViewListener).onTaskCreated(eq(mTaskInfo.taskId), any());
        verify(mViewListener, never()).onTaskVisibilityChanged(anyInt(), anyBoolean());
    }

    @Test
    public void testSurfaceCreated_noTask() {
        mTaskView.surfaceCreated(mock(SurfaceHolder.class));

        verify(mViewListener).onInitialized();
        // No task, no visibility change
        verify(mViewListener, never()).onTaskVisibilityChanged(anyInt(), anyBoolean());
    }

    @Test
    public void testSurfaceCreated_withTask() {
        mTaskView.onTaskAppeared(mTaskInfo, mLeash);
        mTaskView.surfaceCreated(mock(SurfaceHolder.class));

        verify(mViewListener).onInitialized();
        verify(mViewListener).onTaskVisibilityChanged(eq(mTaskInfo.taskId), eq(true));
    }

    @Test
    public void testSurfaceDestroyed_noTask() {
        SurfaceHolder sh = mock(SurfaceHolder.class);
        mTaskView.surfaceCreated(sh);
        mTaskView.surfaceDestroyed(sh);

        verify(mViewListener, never()).onTaskVisibilityChanged(anyInt(), anyBoolean());
    }

    @Test
    public void testSurfaceDestroyed_withTask() {
        SurfaceHolder sh = mock(SurfaceHolder.class);
        mTaskView.onTaskAppeared(mTaskInfo, mLeash);
        mTaskView.surfaceCreated(sh);
        reset(mViewListener);
        mTaskView.surfaceDestroyed(sh);

        verify(mViewListener).onTaskVisibilityChanged(eq(mTaskInfo.taskId), eq(false));
    }

    @Test
    public void testOnReleased() {
        mTaskView.onTaskAppeared(mTaskInfo, mLeash);
        mTaskView.surfaceCreated(mock(SurfaceHolder.class));
        mTaskView.release();

        verify(mOrganizer).removeListener(eq(mTaskView));
        verify(mViewListener).onReleased();
    }

    @Test
    public void testOnTaskVanished() {
        mTaskView.onTaskAppeared(mTaskInfo, mLeash);
        mTaskView.surfaceCreated(mock(SurfaceHolder.class));
        mTaskView.onTaskVanished(mTaskInfo);

        verify(mViewListener).onTaskRemovalStarted(eq(mTaskInfo.taskId));
    }

    @Test
    public void testOnBackPressedOnTaskRoot() {
        mTaskView.onTaskAppeared(mTaskInfo, mLeash);
        mTaskView.onBackPressedOnTaskRoot(mTaskInfo);

        verify(mViewListener).onBackPressedOnTaskRoot(eq(mTaskInfo.taskId));
    }

    @Test
    public void testSetOnBackPressedOnTaskRoot() {
        mTaskView.onTaskAppeared(mTaskInfo, mLeash);
        verify(mOrganizer).setInterceptBackPressedOnTaskRoot(eq(mTaskInfo.token), eq(true));
    }

    @Test
    public void testUnsetOnBackPressedOnTaskRoot() {
        mTaskView.onTaskAppeared(mTaskInfo, mLeash);
        verify(mOrganizer).setInterceptBackPressedOnTaskRoot(eq(mTaskInfo.token), eq(true));

        mTaskView.onTaskVanished(mTaskInfo);
        verify(mOrganizer).setInterceptBackPressedOnTaskRoot(eq(mTaskInfo.token), eq(false));
    }
}
