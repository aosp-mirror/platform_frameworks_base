/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.desktopmode;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import static androidx.test.internal.runner.junit4.statement.UiThreadStatement.runOnUiThread;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.IWindowContainerToken;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.MoveToDesktopAnimator;

import junit.framework.AssertionFailedError;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Supplier;

/** Tests of {@link com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler} */
@SmallTest
public class EnterDesktopTaskTransitionHandlerTest {

    @Mock
    private Transitions mTransitions;
    @Mock
    IBinder mToken;
    @Mock
    Supplier<SurfaceControl.Transaction> mTransactionFactory;
    @Mock
    SurfaceControl.Transaction mStartT;
    @Mock
    SurfaceControl.Transaction mFinishT;
    @Mock
    SurfaceControl.Transaction mAnimationT;
    @Mock
    Transitions.TransitionFinishCallback mTransitionFinishCallback;
    @Mock
    ShellExecutor mExecutor;
    @Mock
    SurfaceControl mSurfaceControl;
    @Mock
    MoveToDesktopAnimator mMoveToDesktopAnimator;
    @Mock
    PointF mPosition;

    private EnterDesktopTaskTransitionHandler mEnterDesktopTaskTransitionHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        doReturn(mExecutor).when(mTransitions).getMainExecutor();
        doReturn(mAnimationT).when(mTransactionFactory).get();
        doReturn(mPosition).when(mMoveToDesktopAnimator).getPosition();

        mEnterDesktopTaskTransitionHandler = new EnterDesktopTaskTransitionHandler(mTransitions,
                mTransactionFactory);
    }

    @Test
    public void testEnterFreeformAnimation() {
        final int taskId = 1;
        WindowContainerTransaction wct = new WindowContainerTransaction();
        doReturn(mToken).when(mTransitions)
                .startTransition(Transitions.TRANSIT_START_DRAG_TO_DESKTOP_MODE, wct,
                        mEnterDesktopTaskTransitionHandler);
        doReturn(taskId).when(mMoveToDesktopAnimator).getTaskId();

        mEnterDesktopTaskTransitionHandler.startMoveToDesktop(wct,
                mMoveToDesktopAnimator, null);

        TransitionInfo.Change change =
                createChange(WindowManager.TRANSIT_CHANGE, taskId, WINDOWING_MODE_FREEFORM);
        TransitionInfo info = createTransitionInfo(Transitions.TRANSIT_START_DRAG_TO_DESKTOP_MODE,
                change);


        assertTrue(mEnterDesktopTaskTransitionHandler
                .startAnimation(mToken, info, mStartT, mFinishT, mTransitionFinishCallback));

        verify(mStartT).setWindowCrop(mSurfaceControl, null);
        verify(mStartT).apply();
    }

    @Test
    public void testTransitEnterDesktopModeAnimation() throws Throwable {
        final int transitionType = Transitions.TRANSIT_FINALIZE_DRAG_TO_DESKTOP_MODE;
        final int taskId = 1;
        WindowContainerTransaction wct = new WindowContainerTransaction();
        doReturn(mToken).when(mTransitions)
                .startTransition(transitionType, wct, mEnterDesktopTaskTransitionHandler);
        mEnterDesktopTaskTransitionHandler.finalizeMoveToDesktop(wct, null);

        TransitionInfo.Change change =
                createChange(WindowManager.TRANSIT_CHANGE, taskId, WINDOWING_MODE_FREEFORM);
        change.setEndAbsBounds(new Rect(0, 0, 1, 1));
        TransitionInfo info = createTransitionInfo(
                Transitions.TRANSIT_FINALIZE_DRAG_TO_DESKTOP_MODE, change);

        runOnUiThread(() -> {
            try {
                assertTrue(mEnterDesktopTaskTransitionHandler
                                .startAnimation(mToken, info, mStartT, mFinishT,
                                        mTransitionFinishCallback));
            } catch (Exception e) {
                throw new AssertionFailedError(e.getMessage());
            }
        });

        verify(mStartT).setWindowCrop(mSurfaceControl, change.getEndAbsBounds().width(),
                change.getEndAbsBounds().height());
        verify(mStartT).apply();
    }

    private TransitionInfo.Change createChange(@WindowManager.TransitionType int type, int taskId,
            @WindowConfiguration.WindowingMode int windowingMode) {
        final ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setWindowingMode(windowingMode);
        final TransitionInfo.Change change = new TransitionInfo.Change(
                new WindowContainerToken(mock(IWindowContainerToken.class)), mSurfaceControl);
        change.setMode(type);
        change.setTaskInfo(taskInfo);
        return change;
    }

    private static TransitionInfo createTransitionInfo(
            @WindowManager.TransitionType int type, @NonNull TransitionInfo.Change change) {
        TransitionInfo info = new TransitionInfo(type, 0);
        info.addChange(change);
        return info;
    }

}
