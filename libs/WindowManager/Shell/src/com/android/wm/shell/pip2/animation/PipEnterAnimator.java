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
import android.graphics.PointF;
import android.graphics.Rect;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.common.pip.PipUtils;
import com.android.wm.shell.pip2.PipSurfaceTransactionHelper;
import com.android.wm.shell.shared.animation.Interpolators;

/**
 * Animator that handles bounds animations for entering PIP.
 */
public class PipEnterAnimator extends ValueAnimator
        implements ValueAnimator.AnimatorUpdateListener, ValueAnimator.AnimatorListener {
    @NonNull private final SurfaceControl mLeash;
    private final SurfaceControl.Transaction mStartTransaction;
    private final SurfaceControl.Transaction mFinishTransaction;

    // Bounds updated by the evaluator as animator is running.
    private final Rect mAnimatedRect = new Rect();

    private final RectEvaluator mRectEvaluator;
    private final Rect mEndBounds = new Rect();
    @Nullable private final Rect mSourceRectHint;
    private final @Surface.Rotation int mRotation;
    @Nullable private Runnable mAnimationStartCallback;
    @Nullable private Runnable mAnimationEndCallback;

    private final PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;

    // Internal state representing initial transform - cached to avoid recalculation.
    private final PointF mInitScale = new PointF();
    private final PointF mInitPos = new PointF();
    private final Rect mInitCrop = new Rect();

    public PipEnterAnimator(Context context,
            @NonNull SurfaceControl leash,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            @NonNull Rect endBounds,
            @Nullable Rect sourceRectHint,
            @Surface.Rotation int rotation) {
        mLeash = leash;
        mStartTransaction = startTransaction;
        mFinishTransaction = finishTransaction;
        mRectEvaluator = new RectEvaluator(mAnimatedRect);
        mEndBounds.set(endBounds);
        mSourceRectHint = sourceRectHint != null ? new Rect(sourceRectHint) : null;
        mRotation = rotation;
        mSurfaceControlTransactionFactory =
                new PipSurfaceTransactionHelper.VsyncSurfaceControlTransactionFactory();

        final int enterAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipEnterAnimationDuration);
        setDuration(enterAnimationDuration);
        setFloatValues(0f, 1f);
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
            onEnterAnimationUpdate(mInitScale, mInitPos, mInitCrop,
                    0f /* fraction */, mStartTransaction);
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
        onEnterAnimationUpdate(mInitScale, mInitPos, mInitCrop, fraction, tx);
        tx.apply();
    }

    private void onEnterAnimationUpdate(PointF initScale, PointF initPos, Rect initCrop,
            float fraction, SurfaceControl.Transaction tx) {
        float scaleX = 1 + (initScale.x - 1) * (1 - fraction);
        float scaleY = 1 + (initScale.y - 1) * (1 - fraction);
        tx.setScale(mLeash, scaleX, scaleY);

        float posX = initPos.x + (mEndBounds.left - initPos.x) * fraction;
        float posY = initPos.y + (mEndBounds.top - initPos.y) * fraction;
        tx.setPosition(mLeash, posX, posY);

        Rect endCrop = new Rect(mEndBounds);
        endCrop.offsetTo(0, 0);
        mRectEvaluator.evaluate(fraction, initCrop, endCrop);
        tx.setCrop(mLeash, mAnimatedRect);
    }

    // no-ops

    @Override
    public void onAnimationCancel(@NonNull Animator animation) {}

    @Override
    public void onAnimationRepeat(@NonNull Animator animation) {}

    /**
     * Caches the initial transform relevant values for the bounds enter animation.
     *
     * Since enter PiP makes use of a config-at-end transition, initial transform needs to be
     * calculated differently from generic transitions.
     * @param pipChange PiP change received as a transition target.
     */
    public void setEnterStartState(@NonNull TransitionInfo.Change pipChange) {
        PipUtils.calcStartTransform(pipChange, mInitScale, mInitPos, mInitCrop);
    }
}
