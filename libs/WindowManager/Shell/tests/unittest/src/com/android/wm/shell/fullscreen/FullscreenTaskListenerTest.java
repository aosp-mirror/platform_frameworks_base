/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.fullscreen;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;

import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.SystemProperties;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.recents.RecentTasksController;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
public class FullscreenTaskListenerTest {
    private static final boolean ENABLE_SHELL_TRANSITIONS =
            SystemProperties.getBoolean("persist.wm.debug.shell_transit", false);

    @Mock
    private SyncTransactionQueue mSyncQueue;
    @Mock
    private FullscreenUnfoldController mUnfoldController;
    @Mock
    private RecentTasksController mRecentTasksController;
    @Mock
    private SurfaceControl mSurfaceControl;

    private Optional<FullscreenUnfoldController> mFullscreenUnfoldController;

    private FullscreenTaskListener mListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mFullscreenUnfoldController = Optional.of(mUnfoldController);
        mListener = new FullscreenTaskListener(mSyncQueue, mFullscreenUnfoldController,
                Optional.empty());
    }

    @Test
    public void testAnimatableTaskAppeared_notifiesUnfoldController() {
        assumeFalse(ENABLE_SHELL_TRANSITIONS);
        RunningTaskInfo info = createTaskInfo(/* visible */ true, /* taskId */ 0);

        mListener.onTaskAppeared(info, mSurfaceControl);

        verify(mUnfoldController).onTaskAppeared(eq(info), any());
    }

    @Test
    public void testMultipleAnimatableTasksAppeared_notifiesUnfoldController() {
        assumeFalse(ENABLE_SHELL_TRANSITIONS);
        RunningTaskInfo animatable1 = createTaskInfo(/* visible */ true, /* taskId */ 0);
        RunningTaskInfo animatable2 = createTaskInfo(/* visible */ true, /* taskId */ 1);

        mListener.onTaskAppeared(animatable1, mSurfaceControl);
        mListener.onTaskAppeared(animatable2, mSurfaceControl);

        InOrder order = inOrder(mUnfoldController);
        order.verify(mUnfoldController).onTaskAppeared(eq(animatable1), any());
        order.verify(mUnfoldController).onTaskAppeared(eq(animatable2), any());
    }

    @Test
    public void testNonAnimatableTaskAppeared_doesNotNotifyUnfoldController() {
        assumeFalse(ENABLE_SHELL_TRANSITIONS);
        RunningTaskInfo info = createTaskInfo(/* visible */ false, /* taskId */ 0);

        mListener.onTaskAppeared(info, mSurfaceControl);

        verifyNoMoreInteractions(mUnfoldController);
    }

    @Test
    public void testNonAnimatableTaskChanged_doesNotNotifyUnfoldController() {
        assumeFalse(ENABLE_SHELL_TRANSITIONS);
        RunningTaskInfo info = createTaskInfo(/* visible */ false, /* taskId */ 0);
        mListener.onTaskAppeared(info, mSurfaceControl);

        mListener.onTaskInfoChanged(info);

        verifyNoMoreInteractions(mUnfoldController);
    }

    @Test
    public void testNonAnimatableTaskVanished_doesNotNotifyUnfoldController() {
        assumeFalse(ENABLE_SHELL_TRANSITIONS);
        RunningTaskInfo info = createTaskInfo(/* visible */ false, /* taskId */ 0);
        mListener.onTaskAppeared(info, mSurfaceControl);

        mListener.onTaskVanished(info);

        verifyNoMoreInteractions(mUnfoldController);
    }

    @Test
    public void testAnimatableTaskBecameInactive_notifiesUnfoldController() {
        assumeFalse(ENABLE_SHELL_TRANSITIONS);
        RunningTaskInfo animatableTask = createTaskInfo(/* visible */ true, /* taskId */ 0);
        mListener.onTaskAppeared(animatableTask, mSurfaceControl);
        RunningTaskInfo notAnimatableTask = createTaskInfo(/* visible */ false, /* taskId */ 0);

        mListener.onTaskInfoChanged(notAnimatableTask);

        verify(mUnfoldController).onTaskVanished(eq(notAnimatableTask));
    }

    @Test
    public void testAnimatableTaskVanished_notifiesUnfoldController() {
        assumeFalse(ENABLE_SHELL_TRANSITIONS);
        RunningTaskInfo taskInfo = createTaskInfo(/* visible */ true, /* taskId */ 0);
        mListener.onTaskAppeared(taskInfo, mSurfaceControl);

        mListener.onTaskVanished(taskInfo);

        verify(mUnfoldController).onTaskVanished(eq(taskInfo));
    }

    private RunningTaskInfo createTaskInfo(boolean visible, int taskId) {
        final RunningTaskInfo info = spy(new RunningTaskInfo());
        info.isVisible = visible;
        info.positionInParent = new Point();
        when(info.getWindowingMode()).thenReturn(WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        final Configuration configuration = new Configuration();
        configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_STANDARD);
        when(info.getConfiguration()).thenReturn(configuration);
        info.taskId = taskId;
        return info;
    }
}
