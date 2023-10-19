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

package com.android.wm.shell.unfold;

import static android.view.WindowManager.TRANSIT_CHANGE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback;
import com.android.wm.shell.unfold.animation.FullscreenUnfoldTaskAnimator;
import com.android.wm.shell.unfold.animation.SplitTaskUnfoldAnimator;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class UnfoldTransitionHandlerTest {

    private UnfoldTransitionHandler mUnfoldTransitionHandler;

    private final TestShellUnfoldProgressProvider mShellUnfoldProgressProvider =
            new TestShellUnfoldProgressProvider();
    private final TestTransactionPool mTransactionPool = new TestTransactionPool();

    private FullscreenUnfoldTaskAnimator mFullscreenUnfoldTaskAnimator;
    private SplitTaskUnfoldAnimator mSplitTaskUnfoldAnimator;
    private Transitions mTransitions;

    private final IBinder mTransition = new Binder();

    @Before
    public void before() {
        final ShellExecutor executor = new TestSyncExecutor();
        final ShellInit shellInit = new ShellInit(executor);

        mFullscreenUnfoldTaskAnimator = mock(FullscreenUnfoldTaskAnimator.class);
        mSplitTaskUnfoldAnimator = mock(SplitTaskUnfoldAnimator.class);
        mTransitions = mock(Transitions.class);

        mUnfoldTransitionHandler = new UnfoldTransitionHandler(
                shellInit,
                mShellUnfoldProgressProvider,
                mFullscreenUnfoldTaskAnimator,
                mSplitTaskUnfoldAnimator,
                mTransactionPool,
                executor,
                mTransitions
        );

        shellInit.init();
    }

    @Test
    public void handleRequest_physicalDisplayChange_handlesTransition() {
        ActivityManager.RunningTaskInfo triggerTaskInfo = new ActivityManager.RunningTaskInfo();
        TransitionRequestInfo.DisplayChange displayChange = new TransitionRequestInfo.DisplayChange(
                Display.DEFAULT_DISPLAY).setPhysicalDisplayChanged(true);
        TransitionRequestInfo requestInfo = new TransitionRequestInfo(TRANSIT_CHANGE,
                triggerTaskInfo, /* remoteTransition= */ null, displayChange, 0 /* flags */);

        WindowContainerTransaction result = mUnfoldTransitionHandler.handleRequest(mTransition,
                requestInfo);

        assertThat(result).isNotNull();
    }

    @Test
    public void handleRequest_noPhysicalDisplayChange_doesNotHandleTransition() {
        ActivityManager.RunningTaskInfo triggerTaskInfo = new ActivityManager.RunningTaskInfo();
        TransitionRequestInfo.DisplayChange displayChange = new TransitionRequestInfo.DisplayChange(
                Display.DEFAULT_DISPLAY).setPhysicalDisplayChanged(false);
        TransitionRequestInfo requestInfo = new TransitionRequestInfo(TRANSIT_CHANGE,
                triggerTaskInfo, /* remoteTransition= */ null, displayChange, 0 /* flags */);

        WindowContainerTransaction result = mUnfoldTransitionHandler.handleRequest(mTransition,
                requestInfo);

        assertThat(result).isNull();
    }

    @Test
    public void startAnimation_animationHasNotFinishedYet_doesNotFinishTheTransition() {
        TransitionRequestInfo requestInfo = createUnfoldTransitionRequestInfo();
        mUnfoldTransitionHandler.handleRequest(mTransition, requestInfo);
        TransitionFinishCallback finishCallback = mock(TransitionFinishCallback.class);

        mUnfoldTransitionHandler.startAnimation(
                mTransition,
                mock(TransitionInfo.class),
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                finishCallback
        );

        verify(finishCallback, never()).onTransitionFinished(any());
    }

    @Test
    public void startAnimation_animationFinishes_finishesTheTransition() {
        TransitionRequestInfo requestInfo = createUnfoldTransitionRequestInfo();
        mUnfoldTransitionHandler.handleRequest(mTransition, requestInfo);
        TransitionFinishCallback finishCallback = mock(TransitionFinishCallback.class);

        mUnfoldTransitionHandler.startAnimation(
                mTransition,
                mock(TransitionInfo.class),
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                finishCallback
        );
        mShellUnfoldProgressProvider.onStateChangeStarted();
        mShellUnfoldProgressProvider.onStateChangeFinished();

        verify(finishCallback).onTransitionFinished(any());
    }

    @Test
    public void startAnimation_animationIsAlreadyFinished_finishesTheTransition() {
        TransitionRequestInfo requestInfo = createUnfoldTransitionRequestInfo();
        mUnfoldTransitionHandler.handleRequest(mTransition, requestInfo);
        TransitionFinishCallback finishCallback = mock(TransitionFinishCallback.class);

        mShellUnfoldProgressProvider.onStateChangeStarted();
        mShellUnfoldProgressProvider.onStateChangeFinished();
        mUnfoldTransitionHandler.startAnimation(
                mTransition,
                mock(TransitionInfo.class),
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                finishCallback
        );

        verify(finishCallback).onTransitionFinished(any());
    }

    @Test
    public void startAnimationSecondTimeAfterFold_animationAlreadyFinished_finishesTransition() {
        TransitionRequestInfo requestInfo = createUnfoldTransitionRequestInfo();
        TransitionFinishCallback finishCallback = mock(TransitionFinishCallback.class);

        // First unfold
        mShellUnfoldProgressProvider.onFoldStateChanged(/* isFolded= */ false);
        mShellUnfoldProgressProvider.onStateChangeStarted();
        mShellUnfoldProgressProvider.onStateChangeFinished();
        mUnfoldTransitionHandler.handleRequest(mTransition, requestInfo);
        mUnfoldTransitionHandler.startAnimation(
                mTransition,
                mock(TransitionInfo.class),
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                finishCallback
        );
        clearInvocations(finishCallback);

        // Fold
        mShellUnfoldProgressProvider.onFoldStateChanged(/* isFolded= */ true);

        // Second unfold
        mShellUnfoldProgressProvider.onFoldStateChanged(/* isFolded= */ false);
        mShellUnfoldProgressProvider.onStateChangeStarted();
        mShellUnfoldProgressProvider.onStateChangeFinished();
        mUnfoldTransitionHandler.handleRequest(mTransition, requestInfo);
        mUnfoldTransitionHandler.startAnimation(
                mTransition,
                mock(TransitionInfo.class),
                mock(SurfaceControl.Transaction.class),
                mock(SurfaceControl.Transaction.class),
                finishCallback
        );

        verify(finishCallback).onTransitionFinished(any());
    }

    private TransitionRequestInfo createUnfoldTransitionRequestInfo() {
        ActivityManager.RunningTaskInfo triggerTaskInfo = new ActivityManager.RunningTaskInfo();
        TransitionRequestInfo.DisplayChange displayChange = new TransitionRequestInfo.DisplayChange(
                Display.DEFAULT_DISPLAY).setPhysicalDisplayChanged(true);
        return new TransitionRequestInfo(TRANSIT_CHANGE,
                triggerTaskInfo, /* remoteTransition= */ null, displayChange, 0 /* flags */);
    }

    private static class TestShellUnfoldProgressProvider implements ShellUnfoldProgressProvider,
            ShellUnfoldProgressProvider.UnfoldListener {

        private final List<UnfoldListener> mListeners = new ArrayList<>();

        @Override
        public void addListener(Executor executor, UnfoldListener listener) {
            mListeners.add(listener);
        }

        @Override
        public void onFoldStateChanged(boolean isFolded) {
            mListeners.forEach(unfoldListener -> unfoldListener.onFoldStateChanged(isFolded));
        }

        @Override
        public void onStateChangeFinished() {
            mListeners.forEach(UnfoldListener::onStateChangeFinished);
        }

        @Override
        public void onStateChangeProgress(float progress) {
            mListeners.forEach(unfoldListener -> unfoldListener.onStateChangeProgress(progress));
        }

        @Override
        public void onStateChangeStarted() {
            mListeners.forEach(UnfoldListener::onStateChangeStarted);
        }
    }

    private static class TestTransactionPool extends TransactionPool {
        @Override
        public SurfaceControl.Transaction acquire() {
            return mock(SurfaceControl.Transaction.class);
        }

        @Override
        public void release(SurfaceControl.Transaction t) {
        }
    }

    private static class TestSyncExecutor implements ShellExecutor {
        @Override
        public void execute(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void executeDelayed(Runnable runnable, long delayMillis) {
            runnable.run();
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
        }

        @Override
        public boolean hasCallback(Runnable runnable) {
            return false;
        }
    }
}