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

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * UDFPS fingerprint drawable that is shown when enrolling
 */
public class UdfpsEnrollDrawable extends UdfpsDrawable {
    private static final String TAG = "UdfpsAnimationEnroll";

    private static final long ANIM_DURATION = 800;
    // 1 + SCALE_MAX is the maximum that the moving target will animate to
    private static final float SCALE_MAX = 0.25f;

    @NonNull private final Drawable mMovingTargetFpIcon;
    @NonNull private final Paint mSensorOutlinePaint;
    @NonNull private final Paint mBlueFill;

    @Nullable private RectF mSensorRect;
    @Nullable private UdfpsEnrollHelper mEnrollHelper;

    // Moving target animator set
    @Nullable AnimatorSet mAnimatorSet;
    // Moving target location
    float mCurrentX;
    float mCurrentY;
    // Moving target size
    float mCurrentScale = 1.f;

    UdfpsEnrollDrawable(@NonNull Context context) {
        super(context);


        mSensorOutlinePaint = new Paint(0 /* flags */);
        mSensorOutlinePaint.setAntiAlias(true);
        mSensorOutlinePaint.setColor(mContext.getColor(R.color.udfps_enroll_icon));
        mSensorOutlinePaint.setStyle(Paint.Style.STROKE);
        mSensorOutlinePaint.setStrokeWidth(2.f);

        mBlueFill = new Paint(0 /* flags */);
        mBlueFill.setAntiAlias(true);
        mBlueFill.setColor(context.getColor(R.color.udfps_moving_target_fill));
        mBlueFill.setStyle(Paint.Style.FILL);

        mMovingTargetFpIcon = context.getResources().getDrawable(R.drawable.ic_fingerprint, null);
        mMovingTargetFpIcon.setTint(Color.WHITE);
        mMovingTargetFpIcon.mutate();

        mFingerprintDrawable.setTint(mContext.getColor(R.color.udfps_enroll_icon));
    }

    void setEnrollHelper(@NonNull UdfpsEnrollHelper helper) {
        mEnrollHelper = helper;
    }

    @Override
    public void onSensorRectUpdated(@NonNull RectF sensorRect) {
        super.onSensorRectUpdated(sensorRect);
        mSensorRect = sensorRect;
    }

    @Override
    protected void updateFingerprintIconBounds(@NonNull Rect bounds) {
        super.updateFingerprintIconBounds(bounds);
        mMovingTargetFpIcon.setBounds(bounds);
        invalidateSelf();
    }

    void onEnrollmentProgress(int remaining, int totalSteps) {
        if (mEnrollHelper != null && !mEnrollHelper.isCenterEnrollmentStage()) {
            if (mAnimatorSet != null && mAnimatorSet.isRunning()) {
                mAnimatorSet.end();
            }

            final PointF point = mEnrollHelper.getNextGuidedEnrollmentPoint();
            if (mCurrentX != point.x || mCurrentY != point.y) {
                final ValueAnimator x = ValueAnimator.ofFloat(mCurrentX, point.x);
                x.addUpdateListener(animation -> {
                    mCurrentX = (float) animation.getAnimatedValue();
                    invalidateSelf();
                });

                final ValueAnimator y = ValueAnimator.ofFloat(mCurrentY, point.y);
                y.addUpdateListener(animation -> {
                    mCurrentY = (float) animation.getAnimatedValue();
                    invalidateSelf();
                });

                final ValueAnimator scale = ValueAnimator.ofFloat(0, (float) Math.PI);
                scale.setDuration(ANIM_DURATION);
                scale.addUpdateListener(animation -> {
                    // Grow then shrink
                    mCurrentScale = 1
                            + SCALE_MAX * (float) Math.sin((float) animation.getAnimatedValue());
                    invalidateSelf();
                });

                mAnimatorSet = new AnimatorSet();

                mAnimatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
                mAnimatorSet.setDuration(ANIM_DURATION);
                mAnimatorSet.playTogether(x, y, scale);
                mAnimatorSet.start();
            }
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (isIlluminationShowing()) {
            return;
        }

        // Draw moving target
        if (mEnrollHelper != null && !mEnrollHelper.isCenterEnrollmentStage()) {
            canvas.save();
            canvas.translate(mCurrentX, mCurrentY);

            if (mSensorRect != null) {
                canvas.scale(mCurrentScale, mCurrentScale,
                        mSensorRect.centerX(), mSensorRect.centerY());
                canvas.drawOval(mSensorRect, mBlueFill);
            }

            mMovingTargetFpIcon.draw(canvas);
            canvas.restore();
        } else {
            if (mSensorRect != null) {
                canvas.drawOval(mSensorRect, mSensorOutlinePaint);
            }
            mFingerprintDrawable.draw(canvas);
            mFingerprintDrawable.setAlpha(mAlpha);
            mSensorOutlinePaint.setAlpha(mAlpha);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        super.setAlpha(alpha);
        mSensorOutlinePaint.setAlpha(alpha);
        mBlueFill.setAlpha(alpha);
        mMovingTargetFpIcon.setAlpha(alpha);
        invalidateSelf();
    }
}
