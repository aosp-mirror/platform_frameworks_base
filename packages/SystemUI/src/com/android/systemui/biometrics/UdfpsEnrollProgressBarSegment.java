/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * A single segment of the UDFPS enrollment progress bar.
 */
public class UdfpsEnrollProgressBarSegment {
    private static final String TAG = "UdfpsProgressBarSegment";

    private static final long PROGRESS_ANIMATION_DURATION_MS = 400L;
    private static final long OVER_SWEEP_ANIMATION_DELAY_MS = 200L;
    private static final long OVER_SWEEP_ANIMATION_DURATION_MS = 200L;

    private static final float STROKE_WIDTH_DP = 12f;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @NonNull private final Rect mBounds;
    @NonNull private final Runnable mInvalidateRunnable;
    private final float mStartAngle;
    private final float mSweepAngle;
    private final float mMaxOverSweepAngle;
    private final float mStrokeWidthPx;

    @NonNull private final Paint mBackgroundPaint;
    @NonNull private final Paint mProgressPaint;

    private boolean mIsFilledOrFilling = false;

    private float mProgress = 0f;
    @Nullable private ValueAnimator mProgressAnimator;
    @NonNull private final ValueAnimator.AnimatorUpdateListener mProgressUpdateListener;

    private float mOverSweepAngle = 0f;
    @Nullable private ValueAnimator mOverSweepAnimator;
    @Nullable private ValueAnimator mOverSweepReverseAnimator;
    @NonNull private final ValueAnimator.AnimatorUpdateListener mOverSweepUpdateListener;
    @NonNull private final Runnable mOverSweepAnimationRunnable;

    public UdfpsEnrollProgressBarSegment(@NonNull Context context, @NonNull Rect bounds,
            float startAngle, float sweepAngle, float maxOverSweepAngle,
            @NonNull Runnable invalidateRunnable) {

        mBounds = bounds;
        mInvalidateRunnable = invalidateRunnable;
        mStartAngle = startAngle;
        mSweepAngle = sweepAngle;
        mMaxOverSweepAngle = maxOverSweepAngle;
        mStrokeWidthPx = Utils.dpToPixels(context, STROKE_WIDTH_DP);

        mBackgroundPaint = new Paint();
        mBackgroundPaint.setStrokeWidth(mStrokeWidthPx);
        mBackgroundPaint.setColor(context.getColor(R.color.white_disabled));
        mBackgroundPaint.setAntiAlias(true);
        mBackgroundPaint.setStyle(Paint.Style.STROKE);
        mBackgroundPaint.setStrokeCap(Paint.Cap.ROUND);

        // Background paint color + alpha
        final int[] attrs = new int[] {android.R.attr.colorControlNormal};
        final TypedArray ta = context.obtainStyledAttributes(attrs);
        @ColorInt final int tintColor = ta.getColor(0, mBackgroundPaint.getColor());
        mBackgroundPaint.setColor(tintColor);
        ta.recycle();
        TypedValue alpha = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.disabledAlpha, alpha, true);
        mBackgroundPaint.setAlpha((int) (alpha.getFloat() * 255f));

        // Progress should not be color extracted
        mProgressPaint = new Paint();
        mProgressPaint.setStrokeWidth(mStrokeWidthPx);
        mProgressPaint.setColor(context.getColor(R.color.udfps_enroll_progress));
        mProgressPaint.setAntiAlias(true);
        mProgressPaint.setStyle(Paint.Style.STROKE);
        mProgressPaint.setStrokeCap(Paint.Cap.ROUND);

        mProgressUpdateListener = animation -> {
            mProgress = (float) animation.getAnimatedValue();
            mInvalidateRunnable.run();
        };

        mOverSweepUpdateListener = animation -> {
            mOverSweepAngle = (float) animation.getAnimatedValue();
            mInvalidateRunnable.run();
        };
        mOverSweepAnimationRunnable = () -> {
            if (mOverSweepAnimator != null && mOverSweepAnimator.isRunning()) {
                mOverSweepAnimator.cancel();
            }
            mOverSweepAnimator = ValueAnimator.ofFloat(mOverSweepAngle, mMaxOverSweepAngle);
            mOverSweepAnimator.setDuration(OVER_SWEEP_ANIMATION_DURATION_MS);
            mOverSweepAnimator.addUpdateListener(mOverSweepUpdateListener);
            mOverSweepAnimator.start();
        };
    }

    /**
     * Draws this segment to the given canvas.
     */
    public void draw(@NonNull Canvas canvas) {
        Log.d(TAG, "draw: mProgress = " + mProgress);

        final float halfPaddingPx = mStrokeWidthPx / 2f;

        if (mProgress < 1f) {
            // Draw the unfilled background color of the segment.
            canvas.drawArc(
                    halfPaddingPx,
                    halfPaddingPx,
                    mBounds.right - halfPaddingPx,
                    mBounds.bottom - halfPaddingPx,
                    mStartAngle,
                    mSweepAngle,
                    false /* useCenter */,
                    mBackgroundPaint);
        }

        if (mProgress > 0f) {
            // Draw the filled progress portion of the segment.
            canvas.drawArc(
                    halfPaddingPx,
                    halfPaddingPx,
                    mBounds.right - halfPaddingPx,
                    mBounds.bottom - halfPaddingPx,
                    mStartAngle,
                    mSweepAngle * mProgress + mOverSweepAngle,
                    false /* useCenter */,
                    mProgressPaint);
        }
    }

    /**
     * @return Whether this segment is filled or in the process of being filled.
     */
    public boolean isFilledOrFilling() {
        return mIsFilledOrFilling;
    }

    /**
     * Updates the fill progress of this segment, animating if necessary.
     *
     * @param progress The new fill progress, in the range [0, 1].
     */
    public void updateProgress(float progress) {
        updateProgress(progress, PROGRESS_ANIMATION_DURATION_MS);
    }

    private void updateProgress(float progress, long animationDurationMs) {
        Log.d(TAG, "updateProgress: progress = " + progress
                + ", duration = " + animationDurationMs);

        if (mProgress == progress) {
            Log.d(TAG, "updateProgress skipped: progress == mProgress");
            return;
        }

        mIsFilledOrFilling = progress >= 1f;

        if (mProgressAnimator != null && mProgressAnimator.isRunning()) {
            mProgressAnimator.cancel();
        }

        mProgressAnimator = ValueAnimator.ofFloat(mProgress, progress);
        mProgressAnimator.setDuration(animationDurationMs);
        mProgressAnimator.addUpdateListener(mProgressUpdateListener);
        mProgressAnimator.start();
    }

    /**
     * Queues and runs the completion animation for this segment.
     */
    public void startCompletionAnimation() {
        final boolean hasCallback = mHandler.hasCallbacks(mOverSweepAnimationRunnable);
        if (hasCallback || mOverSweepAngle >= mMaxOverSweepAngle) {
            Log.d(TAG, "startCompletionAnimation skipped: hasCallback = " + hasCallback
                    + ", mOverSweepAngle = " + mOverSweepAngle);
            return;
        }

        Log.d(TAG, "startCompletionAnimation: mProgress = " + mProgress
                + ", mOverSweepAngle = " + mOverSweepAngle);

        // Reset sweep angle back to zero if the animation is being rolled back.
        if (mOverSweepReverseAnimator != null && mOverSweepReverseAnimator.isRunning()) {
            mOverSweepReverseAnimator.cancel();
            mOverSweepAngle = 0f;
        }

        // Start filling the segment if it isn't already.
        if (mProgress < 1f) {
            updateProgress(1f, OVER_SWEEP_ANIMATION_DELAY_MS);
        }

        // Queue the animation to run after fill completes.
        mHandler.postDelayed(mOverSweepAnimationRunnable, OVER_SWEEP_ANIMATION_DELAY_MS);
    }

    /**
     * Cancels (and reverses, if necessary) a queued or running completion animation.
     */
    public void cancelCompletionAnimation() {
        Log.d(TAG, "cancelCompletionAnimation: mProgress = " + mProgress
                + ", mOverSweepAngle = " + mOverSweepAngle);

        // Cancel the animation if it's queued or running.
        mHandler.removeCallbacks(mOverSweepAnimationRunnable);
        if (mOverSweepAnimator != null && mOverSweepAnimator.isRunning()) {
            mOverSweepAnimator.cancel();
        }

        // Roll back the animation if it has at least partially run.
        if (mOverSweepAngle > 0f) {
            if (mOverSweepReverseAnimator != null && mOverSweepReverseAnimator.isRunning()) {
                mOverSweepReverseAnimator.cancel();
            }

            final float completion = mOverSweepAngle / mMaxOverSweepAngle;
            final long proratedDuration = (long) (OVER_SWEEP_ANIMATION_DURATION_MS * completion);
            mOverSweepReverseAnimator = ValueAnimator.ofFloat(mOverSweepAngle, 0f);
            mOverSweepReverseAnimator.setDuration(proratedDuration);
            mOverSweepReverseAnimator.addUpdateListener(mOverSweepUpdateListener);
            mOverSweepReverseAnimator.start();
        }
    }
}
