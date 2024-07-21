/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.pip2.animation;

import android.animation.Animator;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.content.Context;
import android.graphics.Rect;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Animator that handles bounds animations for entering / exiting PIP.
 */
public class PipEnterExitAnimator extends ValueAnimator
        implements ValueAnimator.AnimatorUpdateListener, ValueAnimator.AnimatorListener {
    @IntDef(prefix = {"BOUNDS_"}, value = {
            BOUNDS_ENTER,
            BOUNDS_EXIT
    })

    @Retention(RetentionPolicy.SOURCE)
    public @interface BOUNDS {}

    public static final int BOUNDS_ENTER = 0;
    public static final int BOUNDS_EXIT = 1;

    @NonNull private final SurfaceControl mLeash;
    private final SurfaceControl.Transaction mStartTransaction;
    private final int mEnterAnimationDuration;
    private final @BOUNDS int mDirection;

    // optional callbacks for tracking animation start and end
    @Nullable private Runnable mAnimationStartCallback;
    @Nullable private Runnable mAnimationEndCallback;

    private final Rect mBaseBounds = new Rect();
    private final Rect mStartBounds = new Rect();
    private final Rect mEndBounds = new Rect();

    // Bounds updated by the evaluator as animator is running.
    private final Rect mAnimatedRect = new Rect();

    private final PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;
    private final RectEvaluator mRectEvaluator;
    private final PipSurfaceTransactionHelper mPipSurfaceTransactionHelper;

    public PipEnterExitAnimator(Context context,
            @NonNull SurfaceControl leash,
            SurfaceControl.Transaction startTransaction,
            @NonNull Rect baseBounds,
            @NonNull Rect startBounds,
            @NonNull Rect endBounds,
            @BOUNDS int direction) {
        mLeash = leash;
        mStartTransaction = startTransaction;
        mBaseBounds.set(baseBounds);
        mStartBounds.set(startBounds);
        mAnimatedRect.set(startBounds);
        mEndBounds.set(endBounds);
        mRectEvaluator = new RectEvaluator(mAnimatedRect);
        mPipSurfaceTransactionHelper = new PipSurfaceTransactionHelper(context);
        mDirection = direction;

        mSurfaceControlTransactionFactory =
                new PipSurfaceTransactionHelper.VsyncSurfaceControlTransactionFactory();
        mEnterAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipEnterAnimationDuration);

        setDuration(mEnterAnimationDuration);
        setEvaluator(mRectEvaluator);
        addListener(this);
        addUpdateListener(this);
    }

    public void setAnimationStartCallback(@NonNull Runnable runnable) {
        mAnimationStartCallback = runnable;
    }

    public void setAnimationEndCallback(@NonNull Runnable runnable) {
        mAnimationEndCallback = runnable;
    }

    @Override
    public void onAnimationStart(@NonNull Animator animation) {
        if (mAnimationStartCallback != null) {
            mAnimationStartCallback.run();
        }
        if (mStartTransaction != null) {
            mStartTransaction.apply();
        }
    }

    @Override
    public void onAnimationEnd(@NonNull Animator animation) {
        if (mAnimationEndCallback != null) {
            mAnimationEndCallback.run();
        }
    }

    @Override
    public void onAnimationUpdate(@NonNull ValueAnimator animation) {
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        final float fraction = getAnimatedFraction();
        // TODO (b/350801661): implement fixed rotation

        mPipSurfaceTransactionHelper.scaleAndCrop(tx, mLeash, null,
                mBaseBounds, mAnimatedRect, null, isInPipDirection(), fraction)
                .round(tx, mLeash, isInPipDirection())
                .shadow(tx, mLeash, isInPipDirection());
        tx.apply();
    }

    private boolean isInPipDirection() {
        return mDirection == BOUNDS_ENTER;
    }

    // no-ops

    @Override
    public void onAnimationCancel(@NonNull Animator animation) {}

    @Override
    public void onAnimationRepeat(@NonNull Animator animation) {}
}
