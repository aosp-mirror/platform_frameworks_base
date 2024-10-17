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
import android.content.Context;
import android.graphics.Rect;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;
import com.android.wm.shell.shared.animation.Interpolators;

/**
 * Animator that handles bounds animations for exit-via-expanding PIP.
 */
public class PipExpandAnimator extends ValueAnimator
        implements ValueAnimator.AnimatorUpdateListener, ValueAnimator.AnimatorListener {
    @NonNull
    private final SurfaceControl mLeash;
    private final SurfaceControl.Transaction mStartTransaction;
    private final SurfaceControl.Transaction mFinishTransaction;
    private final @Surface.Rotation int mRotation;

    // optional callbacks for tracking animation start and end
    @Nullable
    private Runnable mAnimationStartCallback;
    @Nullable private Runnable mAnimationEndCallback;

    private final Rect mBaseBounds = new Rect();
    private final Rect mStartBounds = new Rect();
    private final Rect mEndBounds = new Rect();

    @Nullable private final Rect mSourceRectHint;
    private final Rect mSourceRectHintInsets = new Rect();
    private final Rect mZeroInsets = new Rect(0, 0, 0, 0);

    // Bounds updated by the evaluator as animator is running.
    private final Rect mAnimatedRect = new Rect();

    private final PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;
    private final RectEvaluator mRectEvaluator;
    private final RectEvaluator mInsetEvaluator;
    private final PipSurfaceTransactionHelper mPipSurfaceTransactionHelper;

    public PipExpandAnimator(Context context,
            @NonNull SurfaceControl leash,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            @NonNull Rect baseBounds,
            @NonNull Rect startBounds,
            @NonNull Rect endBounds,
            @Nullable Rect sourceRectHint,
            @Surface.Rotation int rotation) {
        mLeash = leash;
        mStartTransaction = startTransaction;
        mFinishTransaction = finishTransaction;
        mBaseBounds.set(baseBounds);
        mStartBounds.set(startBounds);
        mAnimatedRect.set(startBounds);
        mEndBounds.set(endBounds);
        mRectEvaluator = new RectEvaluator(mAnimatedRect);
        mInsetEvaluator = new RectEvaluator(new Rect());
        mPipSurfaceTransactionHelper = new PipSurfaceTransactionHelper(context);
        mRotation = rotation;

        mSourceRectHint = sourceRectHint != null ? new Rect(sourceRectHint) : null;
        if (mSourceRectHint != null) {
            mSourceRectHintInsets.set(
                    mSourceRectHint.left - mBaseBounds.left,
                    mSourceRectHint.top - mBaseBounds.top,
                    mBaseBounds.right - mSourceRectHint.right,
                    mBaseBounds.bottom - mSourceRectHint.bottom
            );
        }

        mSurfaceControlTransactionFactory =
                new PipSurfaceTransactionHelper.VsyncSurfaceControlTransactionFactory();

        final int enterAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipEnterAnimationDuration);
        setDuration(enterAnimationDuration);

        setObjectValues(startBounds, endBounds);
        setEvaluator(mRectEvaluator);
        setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
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
        if (mFinishTransaction != null) {
            // finishTransaction might override some state (eg. corner radii) so we want to
            // manually set the state to the end of the animation
            mPipSurfaceTransactionHelper.scaleAndCrop(mFinishTransaction, mLeash, mSourceRectHint,
                            mBaseBounds, mAnimatedRect, getInsets(1f),
                            false /* isInPipDirection */, 1f)
                    .round(mFinishTransaction, mLeash, false /* applyCornerRadius */)
                    .shadow(mFinishTransaction, mLeash, false /* applyCornerRadius */);
        }
        if (mAnimationEndCallback != null) {
            mAnimationEndCallback.run();
        }
    }

    @Override
    public void onAnimationUpdate(@NonNull ValueAnimator animation) {
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        final float fraction = getAnimatedFraction();

        // TODO (b/350801661): implement fixed rotation

        Rect insets = getInsets(fraction);
        mPipSurfaceTransactionHelper.scaleAndCrop(tx, mLeash, mSourceRectHint,
                        mBaseBounds, mAnimatedRect, insets, false /* isInPipDirection */, fraction)
                .round(tx, mLeash, false /* applyCornerRadius */)
                .shadow(tx, mLeash, false /* applyCornerRadius */);
        tx.apply();
    }

    private Rect getInsets(float fraction) {
        final Rect startInsets = mSourceRectHintInsets;
        final Rect endInsets = mZeroInsets;
        return mInsetEvaluator.evaluate(fraction, startInsets, endInsets);
    }

    // no-ops

    @Override
    public void onAnimationCancel(@NonNull Animator animation) {}

    @Override
    public void onAnimationRepeat(@NonNull Animator animation) {}
}
