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
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * UDFPS enrollment progress bar.
 */
public class UdfpsEnrollProgressBarDrawable extends Drawable {

    private static final String TAG = "UdfpsEnrollProgressBarDrawable";

    private static final float PROGRESS_BAR_THICKNESS_DP = 12;

    @NonNull private final Context mContext;
    @NonNull private final Paint mBackgroundCirclePaint;
    @NonNull private final Paint mProgressPaint;

    @Nullable private ValueAnimator mProgressAnimator;
    private float mProgress;
    private int mRotation; // After last step, rotate the progress bar once
    private boolean mLastStepAcquired;

    public UdfpsEnrollProgressBarDrawable(@NonNull Context context) {
        mContext = context;

        mBackgroundCirclePaint = new Paint();
        mBackgroundCirclePaint.setStrokeWidth(Utils.dpToPixels(context, PROGRESS_BAR_THICKNESS_DP));
        mBackgroundCirclePaint.setColor(context.getColor(R.color.white_disabled));
        mBackgroundCirclePaint.setAntiAlias(true);
        mBackgroundCirclePaint.setStyle(Paint.Style.STROKE);

        // Background circle color + alpha
        TypedArray tc = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorControlNormal});
        int tintColor = tc.getColor(0, mBackgroundCirclePaint.getColor());
        mBackgroundCirclePaint.setColor(tintColor);
        tc.recycle();
        TypedValue alpha = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.disabledAlpha, alpha, true);
        mBackgroundCirclePaint.setAlpha((int) (alpha.getFloat() * 255));

        // Progress should not be color extracted
        mProgressPaint = new Paint();
        mProgressPaint.setStrokeWidth(Utils.dpToPixels(context, PROGRESS_BAR_THICKNESS_DP));
        mProgressPaint.setColor(context.getColor(R.color.udfps_enroll_progress));
        mProgressPaint.setAntiAlias(true);
        mProgressPaint.setStyle(Paint.Style.STROKE);
        mProgressPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setEnrollmentProgress(int remaining, int totalSteps) {
        // Add one so that the first steps actually changes progress, but also so that the last
        // step ends at 1.0
        final float progress = (totalSteps - remaining + 1) / (float) (totalSteps + 1);
        setEnrollmentProgress(progress);
    }

    private void setEnrollmentProgress(float progress) {
        if (mLastStepAcquired) {
            return;
        }

        long animationDuration = 150;

        if (progress == 1.f) {
            animationDuration = 400;
            final ValueAnimator rotationAnimator = ValueAnimator.ofInt(0, 400);
            rotationAnimator.setDuration(animationDuration);
            rotationAnimator.addUpdateListener(animation -> {
                Log.d(TAG, "Rotation: " + mRotation);
                mRotation = (int) animation.getAnimatedValue();
                invalidateSelf();
            });
            rotationAnimator.start();
        }

        if (mProgressAnimator != null && mProgressAnimator.isRunning()) {
            mProgressAnimator.cancel();
        }

        mProgressAnimator = ValueAnimator.ofFloat(mProgress, progress);
        mProgressAnimator.setDuration(animationDuration);
        mProgressAnimator.addUpdateListener(animation -> {
            mProgress = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
        mProgressAnimator.start();
    }

    void onLastStepAcquired() {
        setEnrollmentProgress(1.f);
        mLastStepAcquired = true;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.save();

        // Progress starts from the top, instead of the right
        canvas.rotate(-90 + mRotation, getBounds().centerX(), getBounds().centerY());

        // Progress bar "background track"
        final float halfPaddingPx = Utils.dpToPixels(mContext, PROGRESS_BAR_THICKNESS_DP) / 2;
        canvas.drawArc(halfPaddingPx,
                halfPaddingPx,
                getBounds().right - halfPaddingPx,
                getBounds().bottom - halfPaddingPx,
                0,
                360,
                false,
                mBackgroundCirclePaint
        );

        final float progress = 360.f * mProgress;
        // Progress
        canvas.drawArc(halfPaddingPx,
                halfPaddingPx,
                getBounds().right - halfPaddingPx,
                getBounds().bottom - halfPaddingPx,
                0,
                progress,
                false,
                mProgressPaint
        );

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {

    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return 0;
    }
}
