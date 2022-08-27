/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.freeform;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.wm.shell.transition.Transitions.TRANSIT_MAXIMIZE;
import static com.android.wm.shell.transition.Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.window.IWindowContainerToken;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests of {@link FreeformTaskTransitionObserver}
 */
@SmallTest
public class FreeformTaskTransitionObserverTest {

    @Mock
    private ShellInit mShellInit;
    @Mock
    private Transitions mTransitions;
    @Mock
    private FullscreenTaskListener<?> mFullscreenTaskListener;
    @Mock
    private FreeformTaskListener<?> mFreeformTaskListener;

    private FreeformTaskTransitionObserver mTransitionObserver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        PackageManager pm = mock(PackageManager.class);
        doReturn(true).when(pm).hasSystemFeature(
                PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT);
        final Context context = mock(Context.class);
        doReturn(pm).when(context).getPackageManager();

        mTransitionObserver = new FreeformTaskTransitionObserver(
                context, mShellInit, mTransitions, mFullscreenTaskListener, mFreeformTaskListener);
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            final ArgumentCaptor<Runnable> initRunnableCaptor = ArgumentCaptor.forClass(
                    Runnable.class);
            verify(mShellInit).addInitCallback(initRunnableCaptor.capture(),
                    same(mTransitionObserver));
            initRunnableCaptor.getValue().run();
        } else {
            mTransitionObserver.onInit();
        }
    }

    @Test
    public void testRegistersObserverAtInit() {
        verify(mTransitions).registerObserver(same(mTransitionObserver));
    }

    @Test
    public void testCreatesWindowDecorOnOpenTransition_freeform() {
        final TransitionInfo.Change change =
                createChange(TRANSIT_OPEN, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        info.addChange(change);

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mFreeformTaskListener).createWindowDecoration(change, startT, finishT);
    }

    @Test
    public void testObtainsWindowDecorOnCloseTransition_freeform() {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CLOSE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info = new TransitionInfo(TRANSIT_CLOSE, 0);
        info.addChange(change);

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mFreeformTaskListener).giveWindowDecoration(change.getTaskInfo(), startT, finishT);
    }

    @Test
    public void testDoesntCloseWindowDecorDuringCloseTransition() throws Exception {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CLOSE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info = new TransitionInfo(TRANSIT_CLOSE, 0);
        info.addChange(change);

        final AutoCloseable windowDecor = mock(AutoCloseable.class);
        doReturn(windowDecor).when(mFreeformTaskListener).giveWindowDecoration(
                eq(change.getTaskInfo()), any(), any());

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(windowDecor, never()).close();
    }

    @Test
    public void testClosesWindowDecorAfterCloseTransition() throws Exception {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CLOSE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info = new TransitionInfo(TRANSIT_CLOSE, 0);
        info.addChange(change);

        final AutoCloseable windowDecor = mock(AutoCloseable.class);
        doReturn(windowDecor).when(mFreeformTaskListener).giveWindowDecoration(
                eq(change.getTaskInfo()), any(), any());

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);
        mTransitionObserver.onTransitionFinished(transition, false);

        verify(windowDecor).close();
    }

    @Test
    public void testClosesMergedWindowDecorationAfterTransitionFinishes() throws Exception {
        // The playing transition
        final TransitionInfo.Change change1 =
                createChange(TRANSIT_OPEN, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info1 = new TransitionInfo(TRANSIT_OPEN, 0);
        info1.addChange(change1);

        final IBinder transition1 = mock(IBinder.class);
        final SurfaceControl.Transaction startT1 = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT1 = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition1, info1, startT1, finishT1);
        mTransitionObserver.onTransitionStarting(transition1);

        // The merged transition
        final TransitionInfo.Change change2 =
                createChange(TRANSIT_CLOSE, 2, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info2 = new TransitionInfo(TRANSIT_CLOSE, 0);
        info2.addChange(change2);

        final AutoCloseable windowDecor2 = mock(AutoCloseable.class);
        doReturn(windowDecor2).when(mFreeformTaskListener).giveWindowDecoration(
                eq(change2.getTaskInfo()), any(), any());

        final IBinder transition2 = mock(IBinder.class);
        final SurfaceControl.Transaction startT2 = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT2 = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition2, info2, startT2, finishT2);
        mTransitionObserver.onTransitionMerged(transition2, transition1);

        mTransitionObserver.onTransitionFinished(transition1, false);

        verify(windowDecor2).close();
    }

    @Test
    public void testClosesAllWindowDecorsOnTransitionMergeAfterCloseTransitions() throws Exception {
        // The playing transition
        final TransitionInfo.Change change1 =
                createChange(TRANSIT_CLOSE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info1 = new TransitionInfo(TRANSIT_CLOSE, 0);
        info1.addChange(change1);

        final AutoCloseable windowDecor1 = mock(AutoCloseable.class);
        doReturn(windowDecor1).when(mFreeformTaskListener).giveWindowDecoration(
                eq(change1.getTaskInfo()), any(), any());

        final IBinder transition1 = mock(IBinder.class);
        final SurfaceControl.Transaction startT1 = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT1 = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition1, info1, startT1, finishT1);
        mTransitionObserver.onTransitionStarting(transition1);

        // The merged transition
        final TransitionInfo.Change change2 =
                createChange(TRANSIT_CLOSE, 2, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info2 = new TransitionInfo(TRANSIT_CLOSE, 0);
        info2.addChange(change2);

        final AutoCloseable windowDecor2 = mock(AutoCloseable.class);
        doReturn(windowDecor2).when(mFreeformTaskListener).giveWindowDecoration(
                eq(change2.getTaskInfo()), any(), any());

        final IBinder transition2 = mock(IBinder.class);
        final SurfaceControl.Transaction startT2 = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT2 = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition2, info2, startT2, finishT2);
        mTransitionObserver.onTransitionMerged(transition2, transition1);

        mTransitionObserver.onTransitionFinished(transition1, false);

        verify(windowDecor1).close();
        verify(windowDecor2).close();
    }

    @Test
    public void testTransfersWindowDecorOnMaximize() {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CHANGE, 1, WINDOWING_MODE_FULLSCREEN);
        final TransitionInfo info = new TransitionInfo(TRANSIT_MAXIMIZE, 0);
        info.addChange(change);

        final AutoCloseable windowDecor = mock(AutoCloseable.class);
        doReturn(windowDecor).when(mFreeformTaskListener).giveWindowDecoration(
                eq(change.getTaskInfo()), any(), any());

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mFreeformTaskListener).giveWindowDecoration(change.getTaskInfo(), startT, finishT);
        verify(mFullscreenTaskListener).adoptWindowDecoration(
                eq(change), same(startT), same(finishT), any());
    }

    @Test
    public void testTransfersWindowDecorOnRestoreFromMaximize() {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CHANGE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info = new TransitionInfo(TRANSIT_RESTORE_FROM_MAXIMIZE, 0);
        info.addChange(change);

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mFullscreenTaskListener).giveWindowDecoration(change.getTaskInfo(), startT, finishT);
        verify(mFreeformTaskListener).adoptWindowDecoration(
                eq(change), same(startT), same(finishT), any());
    }

    private static TransitionInfo.Change createChange(int mode, int taskId, int windowingMode) {
        final ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setWindowingMode(windowingMode);

        final TransitionInfo.Change change = new TransitionInfo.Change(
                new WindowContainerToken(mock(IWindowContainerToken.class)),
                mock(SurfaceControl.class));
        change.setMode(mode);
        change.setTaskInfo(taskInfo);
        return change;
    }
}
