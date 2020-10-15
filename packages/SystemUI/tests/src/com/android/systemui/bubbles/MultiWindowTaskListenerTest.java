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

package com.android.systemui.bubbles;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_MULTI_WINDOW;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.os.Binder;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.wm.shell.ShellTaskOrganizer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
// TODO: Place in com.android.wm.shell vs. com.android.wm.shell.bubbles on shell migration.
public class MultiWindowTaskListenerTest extends SysuiTestCase {

    @Mock
    ShellTaskOrganizer mOrganizer;
    @Mock
    MultiWindowTaskListener.Listener mPendingListener;
    @Mock
    SurfaceControl mLeash;
    @Mock
    ActivityManager.RunningTaskInfo mTaskInfo;
    @Mock
    WindowContainerToken mToken;

    Handler mHandler;
    MultiWindowTaskListener mTaskListener;
    TestableLooper mTestableLooper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mHandler = new Handler(mTestableLooper.getLooper());

        mTaskInfo = new ActivityManager.RunningTaskInfo();
        mTaskInfo.token = mToken;

        mTaskListener = new MultiWindowTaskListener(mHandler, mOrganizer);
    }

    private void addTaskAndVerify() {
        final Binder cookie = new Binder();
        mTaskInfo.addLaunchCookie(cookie);
        mTaskListener.setPendingLaunchCookieListener(cookie, mPendingListener);
        mTaskListener.onTaskAppeared(mTaskInfo, mLeash);
        mTestableLooper.processAllMessages();
        verify(mPendingListener).onTaskAppeared(eq(mTaskInfo), eq(mLeash));
    }

    @Test
    public void testListenForMultiWindowMode() {
        mTaskListener = new MultiWindowTaskListener(mHandler, mOrganizer);
        verify(mOrganizer).addListener(eq(mTaskListener), eq(TASK_LISTENER_TYPE_MULTI_WINDOW));
    }

    @Test
    public void testRemovePendingListener() {
        addTaskAndVerify();
        reset(mPendingListener);

        mTaskListener.removeListener(mPendingListener);

        // If it was removed, our pendingListener shouldn't get triggered:
        mTaskListener.onTaskAppeared(mTaskInfo, mLeash);
        mTaskListener.onTaskInfoChanged(mTaskInfo);
        mTaskListener.onBackPressedOnTaskRoot(mTaskInfo);
        mTaskListener.onTaskVanished(mTaskInfo);

        mTestableLooper.processAllMessages();
        verify(mPendingListener, never()).onTaskAppeared(any(), any());
        verify(mPendingListener, never()).onTaskInfoChanged(any());
        verify(mPendingListener, never()).onBackPressedOnTaskRoot(any());
        verify(mPendingListener, never()).onTaskVanished(any());
    }

    @Test
    public void testOnTaskAppeared() {
        addTaskAndVerify();
        verify(mOrganizer).setInterceptBackPressedOnTaskRoot(eq(mToken), eq(true));
    }

    @Test
    public void testOnTaskAppeared_nullListener() {
        mTaskListener.onTaskAppeared(mTaskInfo, mLeash);
        mTestableLooper.processAllMessages();

        verify(mOrganizer, never()).setInterceptBackPressedOnTaskRoot(any(), anyBoolean());
        verify(mPendingListener, never()).onTaskAppeared(any(), any());
    }

    @Test
    public void testOnTaskVanished() {
        addTaskAndVerify();
        mTaskListener.onTaskVanished(mTaskInfo);
        mTestableLooper.processAllMessages();

        verify(mPendingListener).onTaskVanished(eq(mTaskInfo));
    }

    @Test
    public void testOnTaskVanished_neverAdded() {
        mTaskListener.onTaskVanished(mTaskInfo);
        mTestableLooper.processAllMessages();

        verify(mPendingListener, never()).onTaskVanished(any());
    }

    @Test
    public void testOnTaskInfoChanged() {
        addTaskAndVerify();
        mTaskListener.onTaskInfoChanged(mTaskInfo);
        mTestableLooper.processAllMessages();

        verify(mPendingListener).onTaskInfoChanged(eq(mTaskInfo));
    }

    @Test
    public void testOnTaskInfoChanged_neverAdded() {
        mTaskListener.onTaskInfoChanged(mTaskInfo);
        mTestableLooper.processAllMessages();

        verify(mPendingListener, never()).onTaskInfoChanged(any());
    }

    @Test
    public void testOnBackPressedOnTaskRoot() {
        addTaskAndVerify();
        mTaskListener.onBackPressedOnTaskRoot(mTaskInfo);
        mTestableLooper.processAllMessages();

        verify(mPendingListener).onBackPressedOnTaskRoot(eq(mTaskInfo));
    }

    @Test
    public void testOnBackPressedOnTaskRoot_neverAdded() {
        mTaskListener.onBackPressedOnTaskRoot(mTaskInfo);
        mTestableLooper.processAllMessages();

        verify(mPendingListener, never()).onBackPressedOnTaskRoot(any());
    }
}
