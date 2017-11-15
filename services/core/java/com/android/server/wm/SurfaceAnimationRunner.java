/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.view.Choreographer.CALLBACK_TRAVERSAL;
import static android.view.Choreographer.getSfInstance;

import android.animation.AnimationHandler;
import android.animation.AnimationHandler.AnimationFrameCallbackProvider;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.server.AnimationThread;
import com.android.server.wm.LocalAnimationAdapter.AnimationSpec;

/**
 * Class to run animations without holding the window manager lock.
 */
class SurfaceAnimationRunner {

    private final Object mLock = new Object();

    @VisibleForTesting
    Choreographer mChoreographer;

    private final Runnable mApplyTransactionRunnable = this::applyTransaction;
    private final AnimationHandler mAnimationHandler;
    private final Transaction mFrameTransaction;
    private boolean mApplyScheduled;

    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<SurfaceControl, RunningAnimation> mPendingAnimations = new ArrayMap<>();

    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<SurfaceControl, ValueAnimator> mRunningAnimations = new ArrayMap<>();

    SurfaceAnimationRunner() {
        this(null /* callbackProvider */, new Transaction());
    }

    @VisibleForTesting
    SurfaceAnimationRunner(@Nullable AnimationFrameCallbackProvider callbackProvider,
            Transaction frameTransaction) {
        SurfaceAnimationThread.getHandler().runWithScissors(() -> mChoreographer = getSfInstance(),
                0 /* timeout */);
        mFrameTransaction = frameTransaction;
        mAnimationHandler = new AnimationHandler();
        mAnimationHandler.setProvider(callbackProvider != null
                ? callbackProvider
                : new SfVsyncFrameCallbackProvider(mChoreographer));
    }

    void startAnimation(AnimationSpec a, SurfaceControl animationLeash, Transaction t,
            Runnable finishCallback) {
        synchronized (mLock) {
            final RunningAnimation runningAnim = new RunningAnimation(a, animationLeash,
                    finishCallback);
            mPendingAnimations.put(animationLeash, runningAnim);
            mChoreographer.postFrameCallback(this::stepAnimation);

            // Some animations (e.g. move animations) require the initial transform to be applied
            // immediately.
            applyTransformation(runningAnim, t, 0 /* currentPlayTime */);
        }
    }

    void onAnimationCancelled(SurfaceControl leash) {
        synchronized (mLock) {
            if (mPendingAnimations.containsKey(leash)) {
                mPendingAnimations.remove(leash);
                return;
            }
            final ValueAnimator anim = mRunningAnimations.get(leash);
            if (anim != null) {
                mRunningAnimations.remove(leash);
                SurfaceAnimationThread.getHandler().post(() -> {
                    anim.cancel();
                    applyTransaction();
                });
            }
        }
    }

    private void startPendingAnimationsLocked() {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            startAnimationLocked(mPendingAnimations.valueAt(i));
        }
        mPendingAnimations.clear();
    }

    private void startAnimationLocked(RunningAnimation a) {
        final ValueAnimator result = new SfValueAnimator();

        // Animation length is already expected to be scaled.
        result.overrideDurationScale(1.0f);
        result.setDuration(a.mAnimSpec.getDuration());
        result.addUpdateListener(animation -> {
            applyTransformation(a, mFrameTransaction, result.getCurrentPlayTime());

            // Transaction will be applied in the commit phase.
            scheduleApplyTransaction();
        });
        result.addListener(new AnimatorListenerAdapter() {

            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                mFrameTransaction.show(a.mLeash);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                synchronized (mLock) {
                    mRunningAnimations.remove(a.mLeash);
                }
                if (!mCancelled) {
                    // Post on other thread that we can push final state without jank.
                    AnimationThread.getHandler().post(a.mFinishCallback);
                }
            }
        });
        result.start();
        mRunningAnimations.put(a.mLeash, result);
    }

    private void applyTransformation(RunningAnimation a, Transaction t, long currentPlayTime) {
        a.mAnimSpec.apply(t, a.mLeash, currentPlayTime);
    }

    private void stepAnimation(long frameTimeNanos) {
        synchronized (mLock) {
            startPendingAnimationsLocked();
        }
    }

    private void scheduleApplyTransaction() {
        if (!mApplyScheduled) {
            mChoreographer.postCallback(CALLBACK_TRAVERSAL, mApplyTransactionRunnable,
                    null /* token */);
            mApplyScheduled = true;
        }
    }

    private void applyTransaction() {
        mFrameTransaction.apply();
        mApplyScheduled = false;
    }

    private static final class RunningAnimation {
        final AnimationSpec mAnimSpec;
        final SurfaceControl mLeash;
        final Runnable mFinishCallback;

        RunningAnimation(AnimationSpec animSpec, SurfaceControl leash, Runnable finishCallback) {
            mAnimSpec = animSpec;
            mLeash = leash;
            mFinishCallback = finishCallback;
        }
    }

    /**
     * Value animator that uses sf-vsync signal to tick.
     */
    private class SfValueAnimator extends ValueAnimator {

        SfValueAnimator() {
            setFloatValues(0f, 1f);
        }

        @Override
        public AnimationHandler getAnimationHandler() {
            return mAnimationHandler;
        }
    }
}
