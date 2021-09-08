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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.R;

/**
 * UDFPS fingerprint drawable that is shown when enrolling
 */
public class UdfpsEnrollDrawable extends UdfpsDrawable {
    private static final String TAG = "UdfpsAnimationEnroll";

    private static final long HINT_COLOR_ANIM_DELAY_MS = 233L;
    private static final long HINT_COLOR_ANIM_DURATION_MS = 517L;
    private static final long HINT_WIDTH_ANIM_DURATION_MS = 233L;
    private static final long TARGET_ANIM_DURATION_LONG = 800L;
    private static final long TARGET_ANIM_DURATION_SHORT = 600L;
    // 1 + SCALE_MAX is the maximum that the moving target will animate to
    private static final float SCALE_MAX = 0.25f;

    private static final float HINT_PADDING_DP = 10f;
    private static final float HINT_MAX_WIDTH_DP = 6f;
    private static final float HINT_ANGLE = 40f;

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

    @ColorInt private final int mHintColorFaded;
    @ColorInt private final int mHintColorHighlight;
    private final float mHintMaxWidthPx;
    private final float mHintPaddingPx;

    @NonNull private final Animator.AnimatorListener mTargetAnimListener;

    private boolean mShouldShowTipHint = false;
    @NonNull private final Paint mTipHintPaint;
    @Nullable private AnimatorSet mTipHintAnimatorSet;
    @Nullable private ValueAnimator mTipHintColorAnimator;
    @Nullable private ValueAnimator mTipHintWidthAnimator;
    @NonNull private final ValueAnimator.AnimatorUpdateListener mTipHintColorUpdateListener;
    @NonNull private final ValueAnimator.AnimatorUpdateListener mTipHintWidthUpdateListener;
    @NonNull private final Animator.AnimatorListener mTipHintPulseListener;

    private boolean mShouldShowEdgeHint = false;
    @NonNull private final Paint mEdgeHintPaint;
    @Nullable private AnimatorSet mEdgeHintAnimatorSet;
    @Nullable private ValueAnimator mEdgeHintColorAnimator;
    @Nullable private ValueAnimator mEdgeHintWidthAnimator;
    @NonNull private final ValueAnimator.AnimatorUpdateListener mEdgeHintColorUpdateListener;
    @NonNull private final ValueAnimator.AnimatorUpdateListener mEdgeHintWidthUpdateListener;
    @NonNull private final Animator.AnimatorListener mEdgeHintPulseListener;

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

        mHintColorFaded = getHintColorFaded(context);
        mHintColorHighlight = context.getColor(R.color.udfps_enroll_progress);
        mHintMaxWidthPx = Utils.dpToPixels(context, HINT_MAX_WIDTH_DP);
        mHintPaddingPx = Utils.dpToPixels(context, HINT_PADDING_DP);

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

        mTipHintPaint = new Paint(0 /* flags */);
        mTipHintPaint.setAntiAlias(true);
        mTipHintPaint.setColor(mHintColorFaded);
        mTipHintPaint.setStyle(Paint.Style.STROKE);
        mTipHintPaint.setStrokeCap(Paint.Cap.ROUND);
        mTipHintPaint.setStrokeWidth(0f);
        mTipHintColorUpdateListener = animation -> {
            mTipHintPaint.setColor((int) animation.getAnimatedValue());
            invalidateSelf();
        };
        mTipHintWidthUpdateListener = animation -> {
            mTipHintPaint.setStrokeWidth((float) animation.getAnimatedValue());
            invalidateSelf();
        };
        mTipHintPulseListener = new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {}

            @Override
            public void onAnimationEnd(Animator animation) {
                mHandler.postDelayed(() -> {
                    mTipHintColorAnimator =
                            ValueAnimator.ofArgb(mTipHintPaint.getColor(), mHintColorFaded);
                    mTipHintColorAnimator.setInterpolator(new LinearInterpolator());
                    mTipHintColorAnimator.setDuration(HINT_COLOR_ANIM_DURATION_MS);
                    mTipHintColorAnimator.addUpdateListener(mTipHintColorUpdateListener);
                    mTipHintColorAnimator.start();
                }, HINT_COLOR_ANIM_DELAY_MS);
            }

            @Override
            public void onAnimationCancel(Animator animation) {}

            @Override
            public void onAnimationRepeat(Animator animation) {}
        };

        mEdgeHintPaint = new Paint(0 /* flags */);
        mEdgeHintPaint.setAntiAlias(true);
        mEdgeHintPaint.setColor(mHintColorFaded);
        mEdgeHintPaint.setStyle(Paint.Style.STROKE);
        mEdgeHintPaint.setStrokeCap(Paint.Cap.ROUND);
        mEdgeHintPaint.setStrokeWidth(0f);
        mEdgeHintColorUpdateListener = animation -> {
            mEdgeHintPaint.setColor((int) animation.getAnimatedValue());
            invalidateSelf();
        };
        mEdgeHintWidthUpdateListener = animation -> {
            mEdgeHintPaint.setStrokeWidth((float) animation.getAnimatedValue());
            invalidateSelf();
        };
        mEdgeHintPulseListener = new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {}

            @Override
            public void onAnimationEnd(Animator animation) {
                mHandler.postDelayed(() -> {
                    mEdgeHintColorAnimator =
                            ValueAnimator.ofArgb(mEdgeHintPaint.getColor(), mHintColorFaded);
                    mEdgeHintColorAnimator.setInterpolator(new LinearInterpolator());
                    mEdgeHintColorAnimator.setDuration(HINT_COLOR_ANIM_DURATION_MS);
                    mEdgeHintColorAnimator.addUpdateListener(mEdgeHintColorUpdateListener);
                    mEdgeHintColorAnimator.start();
                }, HINT_COLOR_ANIM_DELAY_MS);
            }

            @Override
            public void onAnimationCancel(Animator animation) {}

            @Override
            public void onAnimationRepeat(Animator animation) {}
        };
    }

    @ColorInt
    private static int getHintColorFaded(@NonNull Context context) {
        final TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.disabledAlpha, tv, true);
        final int alpha = (int) (tv.getFloat() * 255f);

        final int[] attrs = new int[] {android.R.attr.colorControlNormal};
        final TypedArray ta = context.obtainStyledAttributes(attrs);
        try {
            @ColorInt final int color = ta.getColor(0, context.getColor(R.color.white_disabled));
            return ColorUtils.setAlphaComponent(color, alpha);
        } finally {
            ta.recycle();
        }
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
        if (mShouldShowTipHint == shouldShow) {
            return;
        }
        mShouldShowTipHint = shouldShow;

        if (mTipHintWidthAnimator != null && mTipHintWidthAnimator.isRunning()) {
            mTipHintWidthAnimator.cancel();
        }

        final float targetWidth = shouldShow ? mHintMaxWidthPx : 0f;
        mTipHintWidthAnimator = ValueAnimator.ofFloat(mTipHintPaint.getStrokeWidth(), targetWidth);
        mTipHintWidthAnimator.setDuration(HINT_WIDTH_ANIM_DURATION_MS);
        mTipHintWidthAnimator.addUpdateListener(mTipHintWidthUpdateListener);

        if (shouldShow) {
            startTipHintPulseAnimation();
        } else {
            mTipHintWidthAnimator.start();
        }
    }

    private void updateEdgeHintVisibility() {
        final boolean shouldShow = mEnrollHelper != null && mEnrollHelper.isEdgeEnrollmentStage();
        if (mShouldShowEdgeHint == shouldShow) {
            return;
        }
        mShouldShowEdgeHint = shouldShow;

        if (mEdgeHintWidthAnimator != null && mEdgeHintWidthAnimator.isRunning()) {
            mEdgeHintWidthAnimator.cancel();
        }

        final float targetWidth = shouldShow ? mHintMaxWidthPx : 0f;
        mEdgeHintWidthAnimator =
                ValueAnimator.ofFloat(mEdgeHintPaint.getStrokeWidth(), targetWidth);
        mEdgeHintWidthAnimator.setDuration(HINT_WIDTH_ANIM_DURATION_MS);
        mEdgeHintWidthAnimator.addUpdateListener(mEdgeHintWidthUpdateListener);

        if (shouldShow) {
            startEdgeHintPulseAnimation();
        } else {
            mEdgeHintWidthAnimator.start();
        }
    }

    private void startTipHintPulseAnimation() {
        mHandler.removeCallbacksAndMessages(null);
        if (mTipHintAnimatorSet != null && mTipHintAnimatorSet.isRunning()) {
            mTipHintAnimatorSet.cancel();
        }
        if (mTipHintColorAnimator != null && mTipHintColorAnimator.isRunning()) {
            mTipHintColorAnimator.cancel();
        }

        mTipHintColorAnimator = ValueAnimator.ofArgb(mTipHintPaint.getColor(), mHintColorHighlight);
        mTipHintColorAnimator.setDuration(HINT_WIDTH_ANIM_DURATION_MS);
        mTipHintColorAnimator.addUpdateListener(mTipHintColorUpdateListener);
        mTipHintColorAnimator.addListener(mTipHintPulseListener);

        mTipHintAnimatorSet = new AnimatorSet();
        mTipHintAnimatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        mTipHintAnimatorSet.playTogether(mTipHintColorAnimator, mTipHintWidthAnimator);
        mTipHintAnimatorSet.start();
    }

    private void startEdgeHintPulseAnimation() {
        mHandler.removeCallbacksAndMessages(null);
        if (mEdgeHintAnimatorSet != null && mEdgeHintAnimatorSet.isRunning()) {
            mEdgeHintAnimatorSet.cancel();
        }
        if (mEdgeHintColorAnimator != null && mEdgeHintColorAnimator.isRunning()) {
            mEdgeHintColorAnimator.cancel();
        }

        mEdgeHintColorAnimator =
                ValueAnimator.ofArgb(mEdgeHintPaint.getColor(), mHintColorHighlight);
        mEdgeHintColorAnimator.setDuration(HINT_WIDTH_ANIM_DURATION_MS);
        mEdgeHintColorAnimator.addUpdateListener(mEdgeHintColorUpdateListener);
        mEdgeHintColorAnimator.addListener(mEdgeHintPulseListener);

        mEdgeHintAnimatorSet = new AnimatorSet();
        mEdgeHintAnimatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        mEdgeHintAnimatorSet.playTogether(mEdgeHintColorAnimator, mEdgeHintWidthAnimator);
        mEdgeHintAnimatorSet.start();
    }

    private boolean isTipHintVisible() {
        return mTipHintPaint.getStrokeWidth() > 0f;
    }

    private boolean isEdgeHintVisible() {
        return mEdgeHintPaint.getStrokeWidth() > 0f;
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

        // Draw the finger tip or edges hint.
        if (isTipHintVisible() || isEdgeHintVisible()) {
            canvas.save();

            // Make arcs start from the top, rather than the right.
            canvas.rotate(-90f, mSensorRect.centerX(), mSensorRect.centerY());

            final float halfSensorHeight = Math.abs(mSensorRect.bottom - mSensorRect.top) / 2f;
            final float halfSensorWidth = Math.abs(mSensorRect.right - mSensorRect.left) / 2f;
            final float hintXOffset = halfSensorWidth + mHintPaddingPx;
            final float hintYOffset = halfSensorHeight + mHintPaddingPx;

            if (isTipHintVisible()) {
                canvas.drawArc(
                        mSensorRect.centerX() - hintXOffset,
                        mSensorRect.centerY() - hintYOffset,
                        mSensorRect.centerX() + hintXOffset,
                        mSensorRect.centerY() + hintYOffset,
                        -HINT_ANGLE / 2f,
                        HINT_ANGLE,
                        false /* useCenter */,
                        mTipHintPaint);
            }

            if (isEdgeHintVisible()) {
                // Draw right edge hint.
                canvas.rotate(-90f, mSensorRect.centerX(), mSensorRect.centerY());
                canvas.drawArc(
                        mSensorRect.centerX() - hintXOffset,
                        mSensorRect.centerY() - hintYOffset,
                        mSensorRect.centerX() + hintXOffset,
                        mSensorRect.centerY() + hintYOffset,
                        -HINT_ANGLE / 2f,
                        HINT_ANGLE,
                        false /* useCenter */,
                        mEdgeHintPaint);

                // Draw left edge hint.
                canvas.rotate(180f, mSensorRect.centerX(), mSensorRect.centerY());
                canvas.drawArc(
                        mSensorRect.centerX() - hintXOffset,
                        mSensorRect.centerY() - hintYOffset,
                        mSensorRect.centerX() + hintXOffset,
                        mSensorRect.centerY() + hintYOffset,
                        -HINT_ANGLE / 2f,
                        HINT_ANGLE,
                        false /* useCenter */,
                        mEdgeHintPaint);
            }

            canvas.restore();
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
