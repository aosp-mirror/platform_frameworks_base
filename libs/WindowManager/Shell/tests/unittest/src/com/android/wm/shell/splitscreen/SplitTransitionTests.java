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

package com.android.wm.shell.splitscreen;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER;

import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_SIDE;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DRAG_DIVIDER;
import static com.android.wm.shell.splitscreen.SplitTestUtils.createMockSurface;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_SCREEN_PAIR_OPEN;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.split.SplitLayout;
import com.android.wm.shell.transition.Transitions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.Optional;

/** Tests for {@link StageCoordinator} */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitTransitionTests extends ShellTestCase {
    @Mock private ShellTaskOrganizer mTaskOrganizer;
    @Mock private SyncTransactionQueue mSyncQueue;
    @Mock private RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    @Mock private DisplayController mDisplayController;
    @Mock private DisplayImeController mDisplayImeController;
    @Mock private DisplayInsetsController mDisplayInsetsController;
    @Mock private TransactionPool mTransactionPool;
    @Mock private Transitions mTransitions;
    @Mock private SurfaceSession mSurfaceSession;
    @Mock private SplitscreenEventLogger mLogger;
    @Mock private IconProvider mIconProvider;
    @Mock private ShellExecutor mMainExecutor;
    private SplitLayout mSplitLayout;
    private MainStage mMainStage;
    private SideStage mSideStage;
    private StageCoordinator mStageCoordinator;
    private SplitScreenTransitions mSplitScreenTransitions;

    private ActivityManager.RunningTaskInfo mMainChild;
    private ActivityManager.RunningTaskInfo mSideChild;

    @Before
    @UiThreadTest
    public void setup() {
        MockitoAnnotations.initMocks(this);
        final ShellExecutor mockExecutor = mock(ShellExecutor.class);
        doReturn(mockExecutor).when(mTransitions).getMainExecutor();
        doReturn(mockExecutor).when(mTransitions).getAnimExecutor();
        doReturn(mock(SurfaceControl.Transaction.class)).when(mTransactionPool).acquire();
        mSplitLayout = SplitTestUtils.createMockSplitLayout();
        mMainStage = new MainStage(mContext, mTaskOrganizer, DEFAULT_DISPLAY, mock(
                StageTaskListener.StageListenerCallbacks.class), mSyncQueue, mSurfaceSession,
                mIconProvider, null);
        mMainStage.onTaskAppeared(new TestRunningTaskInfoBuilder().build(), createMockSurface());
        mSideStage = new SideStage(mContext, mTaskOrganizer, DEFAULT_DISPLAY, mock(
                StageTaskListener.StageListenerCallbacks.class), mSyncQueue, mSurfaceSession,
                mIconProvider, null);
        mSideStage.onTaskAppeared(new TestRunningTaskInfoBuilder().build(), createMockSurface());
        mStageCoordinator = new SplitTestUtils.TestStageCoordinator(mContext, DEFAULT_DISPLAY,
                mSyncQueue, mTaskOrganizer, mMainStage, mSideStage, mDisplayController,
                mDisplayImeController, mDisplayInsetsController, mSplitLayout, mTransitions,
                mTransactionPool, mLogger, mMainExecutor, Optional.empty(), Optional::empty);
        mSplitScreenTransitions = mStageCoordinator.getSplitTransitions();
        doAnswer((Answer<IBinder>) invocation -> mock(IBinder.class))
                .when(mTransitions).startTransition(anyInt(), any(), any());

        mMainChild = new TestRunningTaskInfoBuilder()
                .setParentTaskId(mMainStage.mRootTaskInfo.taskId).build();
        mSideChild = new TestRunningTaskInfoBuilder()
                .setParentTaskId(mSideStage.mRootTaskInfo.taskId).build();
    }

    @Test
    public void testLaunchToSide() {
        ActivityManager.RunningTaskInfo newTask = new TestRunningTaskInfoBuilder()
                .setParentTaskId(mSideStage.mRootTaskInfo.taskId).build();
        ActivityManager.RunningTaskInfo reparentTask = new TestRunningTaskInfoBuilder()
                .setParentTaskId(mMainStage.mRootTaskInfo.taskId).build();

        // Create a request to start a new task in side stage
        TransitionRequestInfo request = new TransitionRequestInfo(TRANSIT_TO_FRONT, newTask, null);
        IBinder transition = mock(IBinder.class);
        WindowContainerTransaction result =
                mStageCoordinator.handleRequest(transition, request);

        // it should handle the transition to enter split screen.
        assertNotNull(result);
        assertTrue(containsSplitEnter(result));

        // simulate the transition
        TransitionInfo.Change openChange = createChange(TRANSIT_OPEN, newTask);
        TransitionInfo.Change reparentChange = createChange(TRANSIT_CHANGE, reparentTask);

        TransitionInfo info = new TransitionInfo(TRANSIT_TO_FRONT, 0);
        info.addChange(openChange);
        info.addChange(reparentChange);
        mSideStage.onTaskAppeared(newTask, createMockSurface());
        mMainStage.onTaskAppeared(reparentTask, createMockSurface());
        boolean accepted = mStageCoordinator.startAnimation(transition, info,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        assertTrue(accepted);
        assertTrue(mStageCoordinator.isSplitScreenVisible());
    }

    @Test
    public void testLaunchPair() {
        TransitionInfo info = createEnterPairInfo();

        TestRemoteTransition testRemote = new TestRemoteTransition();

        IBinder transition = mSplitScreenTransitions.startEnterTransition(
                TRANSIT_SPLIT_SCREEN_PAIR_OPEN, new WindowContainerTransaction(),
                new RemoteTransition(testRemote), mStageCoordinator);
        mMainStage.onTaskAppeared(mMainChild, createMockSurface());
        mSideStage.onTaskAppeared(mSideChild, createMockSurface());
        boolean accepted = mStageCoordinator.startAnimation(transition, info,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        assertTrue(accepted);

        // Make sure split-screen is now visible
        assertTrue(mStageCoordinator.isSplitScreenVisible());
        assertTrue(testRemote.mCalled);
    }

    @Test
    public void testMonitorInSplit() {
        enterSplit();

        ActivityManager.RunningTaskInfo newTask = new TestRunningTaskInfoBuilder()
                .setParentTaskId(mSideStage.mRootTaskInfo.taskId).build();

        // Create a request to start a new task in side stage
        TransitionRequestInfo request = new TransitionRequestInfo(TRANSIT_TO_FRONT, newTask, null);
        IBinder transition = mock(IBinder.class);
        WindowContainerTransaction result =
                mStageCoordinator.handleRequest(transition, request);

        // while in split, it should handle everything:
        assertNotNull(result);

        // Not exiting, just opening up another side-stage task.
        assertFalse(containsSplitExit(result));

        // simulate the transition
        TransitionInfo.Change openChange = createChange(TRANSIT_TO_FRONT, newTask);
        TransitionInfo.Change hideChange = createChange(TRANSIT_TO_BACK, mSideChild);

        TransitionInfo info = new TransitionInfo(TRANSIT_TO_FRONT, 0);
        info.addChange(openChange);
        info.addChange(hideChange);
        mSideStage.onTaskAppeared(newTask, createMockSurface());
        boolean accepted = mStageCoordinator.startAnimation(transition, info,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        assertFalse(accepted);
        assertTrue(mStageCoordinator.isSplitScreenVisible());

        // same, but create request to close the new task
        request = new TransitionRequestInfo(TRANSIT_CLOSE, newTask, null);
        transition = mock(IBinder.class);
        result = mStageCoordinator.handleRequest(transition, request);
        assertNotNull(result);
        assertFalse(containsSplitExit(result));

        TransitionInfo.Change showChange = createChange(TRANSIT_TO_FRONT, mSideChild);
        TransitionInfo.Change closeChange = createChange(TRANSIT_CLOSE, newTask);

        info = new TransitionInfo(TRANSIT_CLOSE, 0);
        info.addChange(showChange);
        info.addChange(closeChange);
        mSideStage.onTaskVanished(newTask);
        accepted = mStageCoordinator.startAnimation(transition, info,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        assertFalse(accepted);
        assertTrue(mStageCoordinator.isSplitScreenVisible());
    }

    @Test
    public void testEnterRecents() {
        enterSplit();

        ActivityManager.RunningTaskInfo homeTask = new TestRunningTaskInfoBuilder()
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .setActivityType(ACTIVITY_TYPE_HOME)
                .build();

        // Create a request to bring home forward
        TransitionRequestInfo request = new TransitionRequestInfo(TRANSIT_TO_FRONT, homeTask, null);
        IBinder transition = mock(IBinder.class);
        WindowContainerTransaction result = mStageCoordinator.handleRequest(transition, request);

        assertTrue(result.isEmpty());

        // make sure we haven't made any local changes yet (need to wait until transition is ready)
        assertTrue(mStageCoordinator.isSplitScreenVisible());

        // simulate the transition
        TransitionInfo.Change homeChange = createChange(TRANSIT_TO_FRONT, homeTask);
        TransitionInfo.Change mainChange = createChange(TRANSIT_TO_BACK, mMainChild);
        TransitionInfo.Change sideChange = createChange(TRANSIT_TO_BACK, mSideChild);

        TransitionInfo info = new TransitionInfo(TRANSIT_TO_FRONT, 0);
        info.addChange(homeChange);
        info.addChange(mainChange);
        info.addChange(sideChange);
        mMainStage.onTaskVanished(mMainChild);
        mSideStage.onTaskVanished(mSideChild);
        mStageCoordinator.startAnimation(transition, info,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        assertTrue(mStageCoordinator.isSplitScreenVisible());
    }

    @Test
    public void testDismissFromBeingOccluded() {
        enterSplit();

        ActivityManager.RunningTaskInfo normalTask = new TestRunningTaskInfoBuilder()
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .build();

        // Create a request to bring a normal task forward
        TransitionRequestInfo request =
                new TransitionRequestInfo(TRANSIT_TO_FRONT, normalTask, null);
        IBinder transition = mock(IBinder.class);
        WindowContainerTransaction result = mStageCoordinator.handleRequest(transition, request);

        assertTrue(containsSplitExit(result));

        // make sure we haven't made any local changes yet (need to wait until transition is ready)
        assertTrue(mStageCoordinator.isSplitScreenVisible());

        // simulate the transition
        TransitionInfo.Change normalChange = createChange(TRANSIT_TO_FRONT, normalTask);
        TransitionInfo.Change mainChange = createChange(TRANSIT_TO_BACK, mMainChild);
        TransitionInfo.Change sideChange = createChange(TRANSIT_TO_BACK, mSideChild);

        TransitionInfo info = new TransitionInfo(TRANSIT_TO_FRONT, 0);
        info.addChange(normalChange);
        info.addChange(mainChange);
        info.addChange(sideChange);
        mMainStage.onTaskVanished(mMainChild);
        mSideStage.onTaskVanished(mSideChild);
        mStageCoordinator.startAnimation(transition, info,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        assertFalse(mStageCoordinator.isSplitScreenVisible());
    }

    @Test
    public void testDismissFromMultiWindowSupport() {
        enterSplit();

        // simulate the transition
        TransitionInfo.Change mainChange = createChange(TRANSIT_TO_BACK, mMainChild);
        TransitionInfo.Change sideChange = createChange(TRANSIT_TO_BACK, mSideChild);
        TransitionInfo info = new TransitionInfo(TRANSIT_TO_BACK, 0);
        info.addChange(mainChange);
        info.addChange(sideChange);
        IBinder transition = mSplitScreenTransitions.startDismissTransition(
                new WindowContainerTransaction(), mStageCoordinator,
                EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW, STAGE_TYPE_SIDE);
        boolean accepted = mStageCoordinator.startAnimation(transition, info,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        assertTrue(accepted);
        assertFalse(mStageCoordinator.isSplitScreenVisible());
    }

    @Test
    public void testDismissSnap() {
        enterSplit();

        // simulate the transition
        TransitionInfo.Change mainChange = createChange(TRANSIT_TO_BACK, mMainChild);
        TransitionInfo.Change sideChange = createChange(TRANSIT_CHANGE, mSideChild);

        TransitionInfo info = new TransitionInfo(TRANSIT_TO_BACK, 0);
        info.addChange(mainChange);
        info.addChange(sideChange);
        IBinder transition = mSplitScreenTransitions.startDismissTransition(
                new WindowContainerTransaction(), mStageCoordinator, EXIT_REASON_DRAG_DIVIDER,
                STAGE_TYPE_SIDE);
        mMainStage.onTaskVanished(mMainChild);
        mSideStage.onTaskVanished(mSideChild);
        boolean accepted = mStageCoordinator.startAnimation(transition, info,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        assertTrue(accepted);
        assertFalse(mStageCoordinator.isSplitScreenVisible());
    }

    @Test
    public void testDismissFromAppFinish() {
        enterSplit();

        // Create a request to exit the "last" task on side stage
        TransitionRequestInfo request = new TransitionRequestInfo(TRANSIT_CLOSE, mSideChild, null);
        IBinder transition = mock(IBinder.class);
        WindowContainerTransaction result = mStageCoordinator.handleRequest(transition, request);

        assertTrue(containsSplitExit(result));

        // make sure we haven't made any local changes yet (need to wait until transition is ready)
        assertTrue(mStageCoordinator.isSplitScreenVisible());

        // simulate the transition
        TransitionInfo.Change mainChange = createChange(TRANSIT_CHANGE, mMainChild);
        TransitionInfo.Change sideChange = createChange(TRANSIT_CLOSE, mSideChild);

        TransitionInfo info = new TransitionInfo(TRANSIT_CLOSE, 0);
        info.addChange(mainChange);
        info.addChange(sideChange);
        mMainStage.onTaskVanished(mMainChild);
        mSideStage.onTaskVanished(mSideChild);
        boolean accepted = mStageCoordinator.startAnimation(transition, info,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        assertTrue(accepted);
        assertFalse(mStageCoordinator.isSplitScreenVisible());
    }

    private TransitionInfo createEnterPairInfo() {
        TransitionInfo.Change mainChange = createChange(TRANSIT_OPEN, mMainChild);
        TransitionInfo.Change sideChange = createChange(TRANSIT_OPEN, mSideChild);

        TransitionInfo info = new TransitionInfo(TRANSIT_SPLIT_SCREEN_PAIR_OPEN, 0);
        info.addChange(mainChange);
        info.addChange(sideChange);
        return info;
    }

    private void enterSplit() {
        TransitionInfo enterInfo = createEnterPairInfo();
        IBinder enterTransit = mSplitScreenTransitions.startEnterTransition(
                TRANSIT_SPLIT_SCREEN_PAIR_OPEN, new WindowContainerTransaction(),
                new RemoteTransition(new TestRemoteTransition()), mStageCoordinator);
        mMainStage.onTaskAppeared(mMainChild, createMockSurface());
        mSideStage.onTaskAppeared(mSideChild, createMockSurface());
        mStageCoordinator.startAnimation(enterTransit, enterInfo,
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                mock(Transitions.TransitionFinishCallback.class));
        mMainStage.activate(new WindowContainerTransaction(), true /* includingTopTask */);
    }

    private boolean containsSplitEnter(@NonNull WindowContainerTransaction wct) {
        for (int i = 0; i < wct.getHierarchyOps().size(); ++i) {
            WindowContainerTransaction.HierarchyOp op = wct.getHierarchyOps().get(i);
            if (op.getType() == HIERARCHY_OP_TYPE_REORDER
                    && op.getContainer() == mStageCoordinator.mRootTaskInfo.token.asBinder()) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSplitExit(@NonNull WindowContainerTransaction wct) {
        // reparenting of child tasks to null constitutes exiting split.
        boolean reparentedMain = false;
        boolean reparentedSide = false;
        for (int i = 0; i < wct.getHierarchyOps().size(); ++i) {
            WindowContainerTransaction.HierarchyOp op = wct.getHierarchyOps().get(i);
            if (op.getType() == HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT) {
                if (op.getContainer() == mMainStage.mRootTaskInfo.token.asBinder()
                        && op.getNewParent() == null) {
                    reparentedMain = true;
                } else if (op.getContainer() == mSideStage.mRootTaskInfo.token.asBinder()
                        && op.getNewParent() == null) {
                    reparentedSide = true;
                }
            }
        }
        return reparentedMain && reparentedSide;
    }

    private static TransitionInfo.Change createChange(@TransitionInfo.TransitionMode int mode,
            ActivityManager.RunningTaskInfo taskInfo) {
        TransitionInfo.Change out = new TransitionInfo.Change(taskInfo.token, createMockSurface());
        out.setMode(mode);
        out.setTaskInfo(taskInfo);
        return out;
    }

    class TestRemoteTransition extends IRemoteTransition.Stub {
        boolean mCalled = false;
        final WindowContainerTransaction mRemoteFinishWCT = new WindowContainerTransaction();

        @Override
        public void startAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction startTransaction,
                IRemoteTransitionFinishedCallback finishCallback)
                throws RemoteException {
            mCalled = true;
            finishCallback.onTransitionFinished(mRemoteFinishWCT, null /* sct */);
        }

        @Override
        public void mergeAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, IBinder mergeTarget,
                IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {
        }
    }

}
