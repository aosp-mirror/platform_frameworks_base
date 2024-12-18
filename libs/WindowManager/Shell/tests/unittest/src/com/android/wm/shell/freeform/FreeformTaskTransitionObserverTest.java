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
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.SurfaceControl;
import android.window.IWindowContainerToken;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import com.android.window.flags.Flags;
import com.android.wm.shell.desktopmode.DesktopImmersiveController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.FocusTransitionObserver;
import com.android.wm.shell.transition.TransitionInfoBuilder;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/** Tests for {@link FreeformTaskTransitionObserver}. */
@SmallTest
public class FreeformTaskTransitionObserverTest {

    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Mock private ShellInit mShellInit;
    @Mock private Transitions mTransitions;
    @Mock private DesktopImmersiveController mDesktopImmersiveController;
    @Mock private WindowDecorViewModel mWindowDecorViewModel;
    @Mock private TaskChangeListener mTaskChangeListener;
    @Mock private FocusTransitionObserver mFocusTransitionObserver;

    private FreeformTaskTransitionObserver mTransitionObserver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        PackageManager pm = mock(PackageManager.class);
        doReturn(true).when(pm).hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT);
        final Context context = mock(Context.class);
        doReturn(pm).when(context).getPackageManager();

        mTransitionObserver =
                new FreeformTaskTransitionObserver(
                        context,
                        mShellInit,
                        mTransitions,
                        Optional.of(mDesktopImmersiveController),
                        mWindowDecorViewModel,
                        Optional.of(mTaskChangeListener),
                        mFocusTransitionObserver);

        final ArgumentCaptor<Runnable> initRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mShellInit).addInitCallback(initRunnableCaptor.capture(), same(mTransitionObserver));
        initRunnableCaptor.getValue().run();
    }

    @Test
    public void init_registersObserver() {
        verify(mTransitions).registerObserver(same(mTransitionObserver));
    }

    @Test
    public void openTransition_createsWindowDecor() {
        final TransitionInfo.Change change = createChange(TRANSIT_OPEN, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info =
                new TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change).build();

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mWindowDecorViewModel)
                .onTaskOpening(change.getTaskInfo(), change.getLeash(), startT, finishT);
    }

    @Test
    public void openTransition_notifiesOnTaskOpening() {
        final TransitionInfo.Change change = createChange(TRANSIT_OPEN, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info =
                new TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change).build();

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mTaskChangeListener).onTaskOpening(change.getTaskInfo());
    }

    @Test
    public void toFrontTransition_notifiesOnTaskMovingToFront() {
        final TransitionInfo.Change change =
                createChange(TRANSIT_TO_FRONT, /* taskId= */ 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info =
                new TransitionInfoBuilder(TRANSIT_TO_FRONT, /* flags= */ 0)
                        .addChange(change)
                        .build();

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mTaskChangeListener).onTaskMovingToFront(change.getTaskInfo());
    }

    @Test
    public void toBackTransition_notifiesOnTaskMovingToBack() {
        final TransitionInfo.Change change =
                createChange(TRANSIT_TO_BACK, /* taskId= */ 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info =
                new TransitionInfoBuilder(TRANSIT_TO_BACK, /* flags= */ 0)
                        .addChange(change)
                        .build();

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mTaskChangeListener).onTaskMovingToBack(change.getTaskInfo());
    }

    @Test
    public void changeTransition_notifiesOnTaskChange() {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CHANGE, /* taskId= */ 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info =
                new TransitionInfoBuilder(TRANSIT_CHANGE, /* flags= */ 0).addChange(change).build();

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mTaskChangeListener).onTaskChanging(change.getTaskInfo());
    }

    @Test
    public void closeTransition_preparesWindowDecor() {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CLOSE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info =
                new TransitionInfoBuilder(TRANSIT_CLOSE, 0).addChange(change).build();

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mWindowDecorViewModel).onTaskClosing(change.getTaskInfo(), startT, finishT);
    }

    @Test
    public void closeTransition_notifiesOnTaskClosing() {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CLOSE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info =
                new TransitionInfoBuilder(TRANSIT_CLOSE, 0).addChange(change).build();

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mTaskChangeListener).onTaskClosing(change.getTaskInfo());
    }

    @Test
    public void closeTransition_doesntCloseWindowDecorDuringTransition() throws Exception {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CLOSE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info =
                new TransitionInfoBuilder(TRANSIT_CLOSE, 0).addChange(change).build();

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);

        verify(mWindowDecorViewModel, never()).destroyWindowDecoration(change.getTaskInfo());
    }

    @Test
    public void closeTransition_closesWindowDecorAfterTransition() throws Exception {
        final TransitionInfo.Change change =
                createChange(TRANSIT_CLOSE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info =
                new TransitionInfoBuilder(TRANSIT_CLOSE, 0).addChange(change).build();

        final AutoCloseable windowDecor = mock(AutoCloseable.class);

        final IBinder transition = mock(IBinder.class);
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);
        mTransitionObserver.onTransitionStarting(transition);
        mTransitionObserver.onTransitionFinished(transition, false);

        verify(mWindowDecorViewModel).destroyWindowDecoration(change.getTaskInfo());
    }

    @Test
    public void transitionFinished_closesMergedWindowDecoration() throws Exception {
        // The playing transition
        final TransitionInfo.Change change1 =
                createChange(TRANSIT_OPEN, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info1 =
                new TransitionInfoBuilder(TRANSIT_OPEN, 0).addChange(change1).build();

        final IBinder transition1 = mock(IBinder.class);
        final SurfaceControl.Transaction startT1 = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT1 = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition1, info1, startT1, finishT1);
        mTransitionObserver.onTransitionStarting(transition1);

        // The merged transition
        final TransitionInfo.Change change2 =
                createChange(TRANSIT_CLOSE, 2, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info2 =
                new TransitionInfoBuilder(TRANSIT_CLOSE, 0).addChange(change2).build();

        final IBinder transition2 = mock(IBinder.class);
        final SurfaceControl.Transaction startT2 = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT2 = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition2, info2, startT2, finishT2);
        mTransitionObserver.onTransitionMerged(transition2, transition1);

        mTransitionObserver.onTransitionFinished(transition1, false);

        verify(mWindowDecorViewModel).destroyWindowDecoration(change2.getTaskInfo());
    }

    @Test
    public void closeTransition_closesWindowDecorsOnTransitionMerge() throws Exception {
        // The playing transition
        final TransitionInfo.Change change1 =
                createChange(TRANSIT_CLOSE, 1, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info1 =
                new TransitionInfoBuilder(TRANSIT_CLOSE, 0).addChange(change1).build();

        final IBinder transition1 = mock(IBinder.class);
        final SurfaceControl.Transaction startT1 = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT1 = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition1, info1, startT1, finishT1);
        mTransitionObserver.onTransitionStarting(transition1);

        // The merged transition
        final TransitionInfo.Change change2 =
                createChange(TRANSIT_CLOSE, 2, WINDOWING_MODE_FREEFORM);
        final TransitionInfo info2 =
                new TransitionInfoBuilder(TRANSIT_CLOSE, 0).addChange(change2).build();

        final IBinder transition2 = mock(IBinder.class);
        final SurfaceControl.Transaction startT2 = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT2 = mock(SurfaceControl.Transaction.class);
        mTransitionObserver.onTransitionReady(transition2, info2, startT2, finishT2);
        mTransitionObserver.onTransitionMerged(transition2, transition1);

        mTransitionObserver.onTransitionFinished(transition1, false);

        verify(mWindowDecorViewModel).destroyWindowDecoration(change1.getTaskInfo());
        verify(mWindowDecorViewModel).destroyWindowDecoration(change2.getTaskInfo());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void onTransitionReady_forwardsToDesktopImmersiveController() {
        final IBinder transition = mock(IBinder.class);
        final TransitionInfo info = new TransitionInfoBuilder(TRANSIT_CHANGE, 0).build();
        final SurfaceControl.Transaction startT = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.Transaction finishT = mock(SurfaceControl.Transaction.class);

        mTransitionObserver.onTransitionReady(transition, info, startT, finishT);

        verify(mDesktopImmersiveController).onTransitionReady(transition, info, startT, finishT);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void onTransitionMerged_forwardsToDesktopImmersiveController() {
        final IBinder merged = mock(IBinder.class);
        final IBinder playing = mock(IBinder.class);

        mTransitionObserver.onTransitionMerged(merged, playing);

        verify(mDesktopImmersiveController).onTransitionMerged(merged, playing);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void onTransitionStarting_forwardsToDesktopImmersiveController() {
        final IBinder transition = mock(IBinder.class);

        mTransitionObserver.onTransitionStarting(transition);

        verify(mDesktopImmersiveController).onTransitionStarting(transition);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FULLY_IMMERSIVE_IN_DESKTOP)
    public void onTransitionFinished_forwardsToDesktopImmersiveController() {
        final IBinder transition = mock(IBinder.class);

        mTransitionObserver.onTransitionFinished(transition, /* aborted= */ false);

        verify(mDesktopImmersiveController).onTransitionFinished(transition, /* aborted= */ false);
    }

    private static TransitionInfo.Change createChange(int mode, int taskId, int windowingMode) {
        final ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setWindowingMode(windowingMode);

        final TransitionInfo.Change change =
                new TransitionInfo.Change(
                        new WindowContainerToken(mock(IWindowContainerToken.class)),
                        mock(SurfaceControl.class));
        change.setMode(mode);
        change.setTaskInfo(taskInfo);
        return change;
    }
}
