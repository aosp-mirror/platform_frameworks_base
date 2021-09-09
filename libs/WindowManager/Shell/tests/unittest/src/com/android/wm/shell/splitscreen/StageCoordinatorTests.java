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

package com.android.wm.shell.splitscreen;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.internal.util.FrameworkStatsLog.SPLITSCREEN_UICHANGED__EXIT_REASON__RETURN_HOME;
import static com.android.wm.shell.common.split.SplitLayout.SPLIT_POSITION_BOTTOM_OR_RIGHT;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.graphics.Rect;
import android.window.WindowContainerTransaction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.transition.Transitions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link StageCoordinator} */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StageCoordinatorTests extends ShellTestCase {
    @Mock private ShellTaskOrganizer mTaskOrganizer;
    @Mock private SyncTransactionQueue mSyncQueue;
    @Mock private RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    @Mock private MainStage mMainStage;
    @Mock private SideStage mSideStage;
    @Mock private DisplayImeController mDisplayImeController;
    @Mock private DisplayInsetsController mDisplayInsetsController;
    @Mock private Transitions mTransitions;
    @Mock private TransactionPool mTransactionPool;
    @Mock private SplitscreenEventLogger mLogger;
    private StageCoordinator mStageCoordinator;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mStageCoordinator = new SplitTestUtils.TestStageCoordinator(mContext, DEFAULT_DISPLAY,
                mSyncQueue, mRootTDAOrganizer, mTaskOrganizer, mMainStage, mSideStage,
                mDisplayImeController, mDisplayInsetsController, null /* splitLayout */,
                mTransitions, mTransactionPool, mLogger);
    }

    @Test
    public void testMoveToSideStage() {
        final ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder().build();

        mStageCoordinator.moveToSideStage(task, SPLIT_POSITION_BOTTOM_OR_RIGHT);

        verify(mMainStage).activate(any(Rect.class), any(WindowContainerTransaction.class));
        verify(mSideStage).addTask(eq(task), any(Rect.class),
                any(WindowContainerTransaction.class));
    }

    @Test
    public void testRemoveFromSideStage() {
        final ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder().build();

        doReturn(false).when(mMainStage).isActive();
        mStageCoordinator.removeFromSideStage(task.taskId);

        verify(mSideStage).removeTask(
                eq(task.taskId), any(), any(WindowContainerTransaction.class));
    }

    @Test
    public void testExitSplitScreen() {
        mStageCoordinator.exitSplitScreen(INVALID_TASK_ID,
                SPLITSCREEN_UICHANGED__EXIT_REASON__RETURN_HOME);
        verify(mSideStage).removeAllTasks(any(WindowContainerTransaction.class), eq(false));
        verify(mMainStage).deactivate(any(WindowContainerTransaction.class), eq(false));
    }

    @Test
    public void testExitSplitScreenToMainStage() {
        final int testTaskId = 12345;
        when(mMainStage.containsTask(eq(testTaskId))).thenReturn(true);
        when(mSideStage.containsTask(eq(testTaskId))).thenReturn(false);
        mStageCoordinator.exitSplitScreen(testTaskId,
                SPLITSCREEN_UICHANGED__EXIT_REASON__RETURN_HOME);
        verify(mMainStage).reorderChild(eq(testTaskId), eq(true),
                any(WindowContainerTransaction.class));
        verify(mSideStage).removeAllTasks(any(WindowContainerTransaction.class), eq(false));
        verify(mMainStage).deactivate(any(WindowContainerTransaction.class), eq(true));
    }

    @Test
    public void testExitSplitScreenToSideStage() {
        final int testTaskId = 12345;
        when(mMainStage.containsTask(eq(testTaskId))).thenReturn(false);
        when(mSideStage.containsTask(eq(testTaskId))).thenReturn(true);
        mStageCoordinator.exitSplitScreen(testTaskId,
                SPLITSCREEN_UICHANGED__EXIT_REASON__RETURN_HOME);
        verify(mSideStage).reorderChild(eq(testTaskId), eq(true),
                any(WindowContainerTransaction.class));
        verify(mSideStage).removeAllTasks(any(WindowContainerTransaction.class), eq(true));
        verify(mMainStage).deactivate(any(WindowContainerTransaction.class), eq(false));
    }
}
