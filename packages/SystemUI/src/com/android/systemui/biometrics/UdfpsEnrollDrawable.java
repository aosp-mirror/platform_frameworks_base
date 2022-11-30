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

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * UDFPS fingerprint drawable that is shown when enrolling
 */
public class UdfpsEnrollDrawable extends UdfpsDrawable {
    private static final String TAG = "UdfpsAnimationEnroll";

    private static final long TARGET_ANIM_DURATION_LONG = 800L;
    private static final long TARGET_ANIM_DURATION_SHORT = 600L;
    // 1 + SCALE_MAX is the maximum that the moving target will animate to
    private static final float SCALE_MAX = 0.25f;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @NonNull private final Drawable mMovingTargetFpIcon;
    @NonNull private final Paint mSensorOutlinePaint;
    @NonNull private final Paint mBlueFill;

    @Nullable private RectF mSensorRect;
    @Nullable private UdfpsEnrollHelper mEnrollHelper;

    // Moving target animator set
    @Nullable AnimatorSet mTargetAnimatorSet;
    // Moving target location
    float mCurrentX;
    float mCurrentY;
    // Moving target size
    float mCurrentScale = 1.f;

    @NonNull private final Animator.AnimatorListener mTargetAnimListener;

    private boolean mShouldShowTipHint = false;
    private boolean mShouldShowEdgeHint = false;

    private int mEnrollIcon;
    private int mMovingTargetFill;

    UdfpsEnrollDrawable(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context);

        loadResources(context, attrs);
        mSensorOutlinePaint = new Paint(0 /* flags */);
        mSensorOutlinePaint.setAntiAlias(true);
        mSensorOutlinePaint.setColor(mMovingTargetFill);
        mSensorOutlinePaint.setStyle(Paint.Style.FILL);

        mBlueFill = new Paint(0 /* flags */);
        mBlueFill.setAntiAlias(true);
        mBlueFill.setColor(mMovingTargetFill);
        mBlueFill.setStyle(Paint.Style.FILL);

        mMovingTargetFpIcon = context.getResources()
                .getDrawable(R.drawable.ic_kg_fingerprint, null);
        mMovingTargetFpIcon.setTint(mEnrollIcon);
        mMovingTargetFpIcon.mutate();

        getFingerprintDrawable().setTint(mEnrollIcon);

        mTargetAnimListener = new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {}

            @Override
            public void onAnimationEnd(Animator animation) {
                updateTipHintVisibility();
            }

            @Override
            public void onAnimationCancel(Animator animation) {}

            @Override
            public void onAnimationRepeat(Animator animation) {}
        };
    }

    void loadResources(Context context, @Nullable AttributeSet attrs) {
        final TypedArray ta = context.obtainStyledAttributes(attrs,
                R.styleable.BiometricsEnrollView, R.attr.biometricsEnrollStyle,
                R.style.BiometricsEnrollStyle);
        mEnrollIcon = ta.getColor(R.styleable.BiometricsEnrollView_biometricsEnrollIcon, 0);
        mMovingTargetFill = ta.getColor(
                R.styleable.BiometricsEnrollView_biometricsMovingTargetFill, 0);
        ta.recycle();
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
        if (mEnrollHelper == null) {
            return;
        }

        if (!mEnrollHelper.isCenterEnrollmentStage()) {
            if (mTargetAnimatorSet != null && mTargetAnimatorSet.isRunning()) {
                mTargetAnimatorSet.end();
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

                final boolean isMovingToCenter = point.x == 0f && point.y == 0f;
                final long duration = isMovingToCenter
                        ? TARGET_ANIM_DURATION_SHORT
                        : TARGET_ANIM_DURATION_LONG;

                final ValueAnimator scale = ValueAnimator.ofFloat(0, (float) Math.PI);
                scale.setDuration(duration);
                scale.addUpdateListener(animation -> {
                    // Grow then shrink
                    mCurrentScale = 1
                            + SCALE_MAX * (float) Math.sin((float) animation.getAnimatedValue());
                    invalidateSelf();
                });

                mTargetAnimatorSet = new AnimatorSet();

                mTargetAnimatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
                mTargetAnimatorSet.setDuration(duration);
                mTargetAnimatorSet.addListener(mTargetAnimListener);
                mTargetAnimatorSet.playTogether(x, y, scale);
                mTargetAnimatorSet.start();
            } else {
                updateTipHintVisibility();
            }
        } else {
            updateTipHintVisibility();
        }

        updateEdgeHintVisibility();
    }

    private void updateTipHintVisibility() {
        final boolean shouldShow = mEnrollHelper != null && mEnrollHelper.isTipEnrollmentStage();
        // With the new update, we will git rid of most of this code, and instead
        // we will change the fingerprint icon.
        if (mShouldShowTipHint == shouldShow) {
            return;
        }
        mShouldShowTipHint = shouldShow;
    }

    private void updateEdgeHintVisibility() {
        final boolean shouldShow = mEnrollHelper != null && mEnrollHelper.isEdgeEnrollmentStage();
        if (mShouldShowEdgeHint == shouldShow) {
            return;
        }
        mShouldShowEdgeHint = shouldShow;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (isDisplayConfigured()) {
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
            getFingerprintDrawable().draw(canvas);
            getFingerprintDrawable().setAlpha(getAlpha());
            mSensorOutlinePaint.setAlpha(getAlpha());
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
