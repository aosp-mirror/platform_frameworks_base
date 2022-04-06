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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class UnfoldTransitionHandler implements TransitionHandler, UnfoldListener {

    private final ShellUnfoldProgressProvider mUnfoldProgressProvider;
    private final Transitions mTransitions;
    private final Executor mExecutor;
    private final TransactionPool mTransactionPool;

    @Nullable
    private TransitionFinishCallback mFinishCallback;
    @Nullable
    private IBinder mTransition;

    private final List<TransitionInfo.Change> mAnimatedFullscreenTasks = new ArrayList<>();

    public UnfoldTransitionHandler(ShellUnfoldProgressProvider unfoldProgressProvider,
            TransactionPool transactionPool, Executor executor, Transitions transitions) {
        mUnfoldProgressProvider = unfoldProgressProvider;
        mTransactionPool = transactionPool;
        mExecutor = executor;
        mTransitions = transitions;
    }

    public void init() {
        mTransitions.addHandler(this);
        mUnfoldProgressProvider.addListener(mExecutor, this);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull TransitionFinishCallback finishCallback) {

        if (transition != mTransition) return false;

        startTransaction.apply();

        mAnimatedFullscreenTasks.clear();
        info.getChanges().forEach(change -> {
            final boolean allowedToAnimate = change.getTaskInfo() != null
                    && change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                    && change.getTaskInfo().getActivityType() != ACTIVITY_TYPE_HOME
                    && change.getMode() == TRANSIT_CHANGE;

            if (allowedToAnimate) {
                mAnimatedFullscreenTasks.add(change);
            }
        });

        mFinishCallback = finishCallback;
        mTransition = null;
        return true;
    }

    @Override
    public void onStateChangeProgress(float progress) {
        mAnimatedFullscreenTasks.forEach(change -> {
            final SurfaceControl.Transaction transaction = mTransactionPool.acquire();

            // TODO: this is a placeholder animation, replace with a spec version in the next CLs
            final float testScale = 0.8f + 0.2f * progress;
            transaction.setScale(change.getLeash(), testScale, testScale);

            transaction.apply();
            mTransactionPool.release(transaction);
        });
    }

    @Override
    public void onStateChangeFinished() {
        if (mFinishCallback != null) {
            mFinishCallback.onTransitionFinished(null, null);
            mFinishCallback = null;
            mAnimatedFullscreenTasks.clear();
        }
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (request.getType() == TRANSIT_CHANGE && request.getDisplayChange() != null
                && request.getDisplayChange().isPhysicalDisplayChanged()) {
            mTransition = transition;
            return new WindowContainerTransaction();
        }
        return null;
    }
}
