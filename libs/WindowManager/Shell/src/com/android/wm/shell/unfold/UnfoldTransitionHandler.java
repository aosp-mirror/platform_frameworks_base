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

package com.android.wm.shell.unfold;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_CHANGE;

import android.os.IBinder;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback;
import com.android.wm.shell.transition.Transitions.TransitionHandler;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider.UnfoldListener;
import com.android.wm.shell.unfold.animation.FullscreenUnfoldTaskAnimator;

import java.util.concurrent.Executor;

/**
 * Transition handler that is responsible for animating app surfaces when unfolding of foldable
 * devices. It does not handle the folding animation, which is done in
 * {@link com.android.wm.shell.fullscreen.FullscreenUnfoldController}.
 */
public class UnfoldTransitionHandler implements TransitionHandler, UnfoldListener {

    private final ShellUnfoldProgressProvider mUnfoldProgressProvider;
    private final Transitions mTransitions;
    private final UnfoldBackgroundController mUnfoldBackgroundController;
    private final Executor mExecutor;
    private final TransactionPool mTransactionPool;

    @Nullable
    private TransitionFinishCallback mFinishCallback;
    @Nullable
    private IBinder mTransition;

    private final FullscreenUnfoldTaskAnimator mFullscreenAnimator;

    public UnfoldTransitionHandler(ShellUnfoldProgressProvider unfoldProgressProvider,
            FullscreenUnfoldTaskAnimator animator, TransactionPool transactionPool,
            UnfoldBackgroundController unfoldBackgroundController,
            Executor executor, Transitions transitions) {
        mUnfoldProgressProvider = unfoldProgressProvider;
        mFullscreenAnimator = animator;
        mTransactionPool = transactionPool;
        mUnfoldBackgroundController = unfoldBackgroundController;
        mExecutor = executor;
        mTransitions = transitions;
    }

    public void init() {
        mFullscreenAnimator.init();
        mTransitions.addHandler(this);
        mUnfoldProgressProvider.addListener(mExecutor, this);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull TransitionFinishCallback finishCallback) {
        if (transition != mTransition) return false;

        mUnfoldBackgroundController.ensureBackground(startTransaction);
        startTransaction.apply();

        mFullscreenAnimator.clearTasks();
        info.getChanges().forEach(change -> {
            final boolean allowedToAnimate = change.getTaskInfo() != null
                    && change.getTaskInfo().isVisible()
                    && change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                    && change.getTaskInfo().getActivityType() != ACTIVITY_TYPE_HOME
                    && change.getMode() == TRANSIT_CHANGE;

            if (allowedToAnimate) {
                mFullscreenAnimator.addTask(change.getTaskInfo(), change.getLeash());
            }
        });

        mFullscreenAnimator.resetAllSurfaces(finishTransaction);
        mUnfoldBackgroundController.removeBackground(finishTransaction);
        mFinishCallback = finishCallback;
        return true;
    }

    @Override
    public void onStateChangeProgress(float progress) {
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        mFullscreenAnimator.applyAnimationProgress(progress, transaction);
        transaction.apply();
        mTransactionPool.release(transaction);
    }

    @Override
    public void onStateChangeFinished() {
        if (mFinishCallback != null) {
            mFinishCallback.onTransitionFinished(null, null);
            mFinishCallback = null;
            mTransition = null;
            mFullscreenAnimator.clearTasks();
        }
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (request.getType() == TRANSIT_CHANGE && request.getDisplayChange() != null) {
            mTransition = transition;
            return new WindowContainerTransaction();
        }
        return null;
    }

    public boolean willHandleTransition() {
        return mTransition != null;
    }
}
