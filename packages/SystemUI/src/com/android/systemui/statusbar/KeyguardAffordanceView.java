/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.DisplayListCanvas;
import android.view.RenderNodeAnimator;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.KeyguardAffordanceHelper;

/**
 * An ImageView which does not have overlapping renderings commands and therefore does not need a
 * layer when alpha is changed.
 */
public class KeyguardAffordanceView extends ImageView {

    private static final long CIRCLE_APPEAR_DURATION = 80;
    private static final long CIRCLE_DISAPPEAR_MAX_DURATION = 200;
    private static final long NORMAL_ANIMATION_DURATION = 200;
    public static final float MAX_ICON_SCALE_AMOUNT = 1.5f;
    public static final float MIN_ICON_SCALE_AMOUNT = 0.8f;

    private final int mMinBackgroundRadius;
    private final Paint mCirclePaint;
    private final int mInverseColor;
    private final int mNormalColor;
    private final ArgbEvaluator mColorInterpolator;
    private final FlingAnimationUtils mFlingAnimationUtils;
    private float mCircleRadius;
    private int mCenterX;
    private int mCenterY;
    private ValueAnimator mCircleAnimator;
    private ValueAnimator mAlphaAnimator;
    private ValueAnimator mScaleAnimator;
    private float mCircleStartValue;
    private boolean mCircleWillBeHidden;
    private int[] mTempPoint = new int[2];
    private float mImageScale = 1f;
    private int mCircleColor;
    private boolean mIsLeft;
    private View mPreviewView;
    private float mCircleStartRadius;
    private float mMaxCircleSize;
    private Animator mPreviewClipper;
    private float mRestingAlpha = KeyguardAffordanceHelper.SWIPE_RESTING_ALPHA_AMOUNT;
    private boolean mSupportHardware;
    private boolean mFinishing;
    private boolean mLaunchingAffordance;

    private CanvasProperty<Float> mHwCircleRadius;
    private CanvasProperty<Float> mHwCenterX;
    private CanvasProperty<Float> mHwCenterY;
    private CanvasProperty<Paint> mHwCirclePaint;

    private AnimatorListenerAdapter mClipEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mPreviewClipper = null;
        }
    };
    private AnimatorListenerAdapter mCircleEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mCircleAnimator = null;
        }
    };
    private AnimatorListenerAdapter mScaleEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mScaleAnimator = null;
        }
    };
    private AnimatorListenerAdapter mAlphaEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mAlphaAnimator = null;
        }
    };

    public KeyguardAffordanceView(Context context) {
        this(context, null);
    }

    public KeyguardAffordanceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardAffordanceView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public KeyguardAffordanceView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mCirclePaint = new Paint();
        mCirclePaint.setAntiAlias(true);
        mCircleColor = 0xffffffff;
        mCirclePaint.setColor(mCircleColor);

        mNormalColor = 0xffffffff;
        mInverseColor = 0xff000000;
        mMinBackgroundRadius = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_affordance_min_background_radius);
        mColorInterpolator = new ArgbEvaluator();
        mFlingAnimationUtils = new FlingAnimationUtils(mContext, 0.3f);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mCenterX = getWidth() / 2;
        mCenterY = getHeight() / 2;
        mMaxCircleSize = getMaxCircleSize();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mSupportHardware = canvas.isHardwareAccelerated();
        drawBackgroundCircle(canvas);
        canvas.save();
        canvas.scale(mImageScale, mImageScale, getWidth() / 2, getHeight() / 2);
        super.onDraw(canvas);
        canvas.restore();
    }

    public void setPreviewView(View v) {
        View oldPreviewView = mPreviewView;
        mPreviewView = v;
        if (mPreviewView != null) {
            mPreviewView.setVisibility(mLaunchingAffordance
                    ? oldPreviewView.getVisibility() : INVISIBLE);
        }
    }

    private void updateIconColor() {
        Drawable drawable = getDrawable().mutate();
        float alpha = mCircleRadius / mMinBackgroundRadius;
        alpha = Math.min(1.0f, alpha);
        int color = (int) mColorInterpolator.evaluate(alpha, mNormalColor, mInverseColor);
        drawable.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
    }

    private void drawBackgroundCircle(Canvas canvas) {
        if (mCircleRadius > 0 || mFinishing) {
            if (mFinishing && mSupportHardware && mHwCenterX != null) {
                // Our hardware drawing proparties can be null if the finishing started but we have
                // never drawn before. In that case we are not doing a render thread animation
                // anyway, so we need to use the normal drawing.
                DisplayListCanvas displayListCanvas = (DisplayListCanvas) canvas;
                displayListCanvas.drawCircle(mHwCenterX, mHwCenterY, mHwCircleRadius,
                        mHwCirclePaint);
            } else {
                updateCircleColor();
                canvas.drawCircle(mCenterX, mCenterY, mCircleRadius, mCirclePaint);
            }
        }
    }

    private void updateCircleColor() {
        float fraction = 0.5f + 0.5f * Math.max(0.0f, Math.min(1.0f,
                (mCircleRadius - mMinBackgroundRadius) / (0.5f * mMinBackgroundRadius)));
        if (mPreviewView != null && mPreviewView.getVisibility() == VISIBLE) {
            float finishingFraction = 1 - Math.max(0, mCircleRadius - mCircleStartRadius)
                    / (mMaxCircleSize - mCircleStartRadius);
            fraction *= finishingFraction;
        }
        int color = Color.argb((int) (Color.alpha(mCircleColor) * fraction),
                Color.red(mCircleColor),
                Color.green(mCircleColor), Color.blue(mCircleColor));
        mCirclePaint.setColor(color);
    }

    public void finishAnimation(float velocity, final Runnable mAnimationEndRunnable) {
        cancelAnimator(mCircleAnimator);
        cancelAnimator(mPreviewClipper);
        mFinishing = true;
        mCircleStartRadius = mCircleRadius;
        final float maxCircleSize = getMaxCircleSize();
        Animator animatorToRadius;
        if (mSupportHardware) {
            initHwProperties();
            animatorToRadius = getRtAnimatorToRadius(maxCircleSize);
            startRtAlphaFadeIn();
        } else {
            animatorToRadius = getAnimatorToRadius(maxCircleSize);
        }
        mFlingAnimationUtils.applyDismissing(animatorToRadius, mCircleRadius, maxCircleSize,
                velocity, maxCircleSize);
        animatorToRadius.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimationEndRunnable.run();
                mFinishing = false;
                mCircleRadius = maxCircleSize;
                invalidate();
            }
        });
        animatorToRadius.start();
        setImageAlpha(0, true);
        if (mPreviewView != null) {
            mPreviewView.setVisibility(View.VISIBLE);
            mPreviewClipper = ViewAnimationUtils.createCircularReveal(
                    mPreviewView, getLeft() + mCenterX, getTop() + mCenterY, mCircleRadius,
                    maxCircleSize);
            mFlingAnimationUtils.applyDismissing(mPreviewClipper, mCircleRadius, maxCircleSize,
                    velocity, maxCircleSize);
            mPreviewClipper.addListener(mClipEndListener);
            mPreviewClipper.start();
            if (mSupportHardware) {
                startRtCircleFadeOut(animatorToRadius.getDuration());
            }
        }
    }

    /**
     * Fades in the Circle on the RenderThread. It's used when finishing the circle when it had
     * alpha 0 in the beginning.
     */
    private void startRtAlphaFadeIn() {
        if (mCircleRadius == 0 && mPreviewView == null) {
            Paint modifiedPaint = new Paint(mCirclePaint);
            modifiedPaint.setColor(mCircleColor);
            modifiedPaint.setAlpha(0);
            mHwCirclePaint = CanvasProperty.createPaint(modifiedPaint);
            RenderNodeAnimator animator = new RenderNodeAnimator(mHwCirclePaint,
                    RenderNodeAnimator.PAINT_ALPHA, 255);
            animator.setTarget(this);
            animator.setInterpolator(Interpolators.ALPHA_IN);
            animator.setDuration(250);
            animator.start();
        }
    }

    public void instantFinishAnimation() {
        cancelAnimator(mPreviewClipper);
        if (mPreviewView != null) {
            mPreviewView.setClipBounds(null);
            mPreviewView.setVisibility(View.VISIBLE);
        }
        mCircleRadius = getMaxCircleSize();
        setImageAlpha(0, false);
        invalidate();
    }

    private void startRtCircleFadeOut(long duration) {
        RenderNodeAnimator animator = new RenderNodeAnimator(mHwCirclePaint,
                RenderNodeAnimator.PAINT_ALPHA, 0);
        animator.setDuration(duration);
        animator.setInterpolator(Interpolators.ALPHA_OUT);
        animator.setTarget(this);
        animator.start();
    }

    private Animator getRtAnimatorToRadius(float circleRadius) {
        RenderNodeAnimator animator = new RenderNodeAnimator(mHwCircleRadius, circleRadius);
        animator.setTarget(this);
        return animator;
    }

    private void initHwProperties() {
        mHwCenterX = CanvasProperty.createFloat(mCenterX);
        mHwCenterY = CanvasProperty.createFloat(mCenterY);
        mHwCirclePaint = CanvasProperty.createPaint(mCirclePaint);
        mHwCircleRadius = CanvasProperty.createFloat(mCircleRadius);
    }

    private float getMaxCircleSize() {
        getLocationInWindow(mTempPoint);
        float rootWidth = getRootView().getWidth();
        float width = mTempPoint[0] + mCenterX;
        width = Math.max(rootWidth - width, width);
        float height = mTempPoint[1] + mCenterY;
        return (float) Math.hypot(width, height);
    }

    public void setCircleRadius(float circleRadius) {
        setCircleRadius(circleRadius, false, false);
    }

    public void setCircleRadius(float circleRadius, boolean slowAnimation) {
        setCircleRadius(circleRadius, slowAnimation, false);
    }

    public void setCircleRadiusWithoutAnimation(float circleRadius) {
        cancelAnimator(mCircleAnimator);
        setCircleRadius(circleRadius, false ,true);
    }

    private void setCircleRadius(float circleRadius, boolean slowAnimation, boolean noAnimation) {

        // Check if we need a new animation
        boolean radiusHidden = (mCircleAnimator != null && mCircleWillBeHidden)
                || (mCircleAnimator == null && mCircleRadius == 0.0f);
        boolean nowHidden = circleRadius == 0.0f;
        boolean radiusNeedsAnimation = (radiusHidden != nowHidden) && !noAnimation;
        if (!radiusNeedsAnimation) {
            if (mCircleAnimator == null) {
                mCircleRadius = circleRadius;
                updateIconColor();
                invalidate();
                if (nowHidden) {
                    if (mPreviewView != null) {
                        mPreviewView.setVisibility(View.INVISIBLE);
                    }
                }
            } else if (!mCircleWillBeHidden) {

                // We just update the end value
                float diff = circleRadius - mMinBackgroundRadius;
                PropertyValuesHolder[] values = mCircleAnimator.getValues();
                values[0].setFloatValues(mCircleStartValue + diff, circleRadius);
                mCircleAnimator.setCurrentPlayTime(mCircleAnimator.getCurrentPlayTime());
            }
        } else {
            cancelAnimator(mCircleAnimator);
            cancelAnimator(mPreviewClipper);
            ValueAnimator animator = getAnimatorToRadius(circleRadius);
            Interpolator interpolator = circleRadius == 0.0f
                    ? Interpolators.FAST_OUT_LINEAR_IN
                    : Interpolators.LINEAR_OUT_SLOW_IN;
            animator.setInterpolator(interpolator);
            long duration = 250;
            if (!slowAnimation) {
                float durationFactor = Math.abs(mCircleRadius - circleRadius)
                        / (float) mMinBackgroundRadius;
                duration = (long) (CIRCLE_APPEAR_DURATION * durationFactor);
                duration = Math.min(duration, CIRCLE_DISAPPEAR_MAX_DURATION);
            }
            animator.setDuration(duration);
            animator.start();
            if (mPreviewView != null && mPreviewView.getVisibility() == View.VISIBLE) {
                mPreviewView.setVisibility(View.VISIBLE);
                mPreviewClipper = ViewAnimationUtils.createCircularReveal(
                        mPreviewView, getLeft() + mCenterX, getTop() + mCenterY, mCircleRadius,
                        circleRadius);
                mPreviewClipper.setInterpolator(interpolator);
                mPreviewClipper.setDuration(duration);
                mPreviewClipper.addListener(mClipEndListener);
                mPreviewClipper.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPreviewView.setVisibility(View.INVISIBLE);
                    }
                });
                mPreviewClipper.start();
            }
        }
    }

    private ValueAnimator getAnimatorToRadius(float circleRadius) {
        ValueAnimator animator = ValueAnimator.ofFloat(mCircleRadius, circleRadius);
        mCircleAnimator = animator;
        mCircleStartValue = mCircleRadius;
        mCircleWillBeHidden = circleRadius == 0.0f;
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mCircleRadius = (float) animation.getAnimatedValue();
                updateIconColor();
                invalidate();
            }
        });
        animator.addListener(mCircleEndListener);
        return animator;
    }

    private void cancelAnimator(Animator animator) {
        if (animator != null) {
            animator.cancel();
        }
    }

    public void setImageScale(float imageScale, boolean animate) {
        setImageScale(imageScale, animate, -1, null);
    }

    /**
     * Sets the scale of the containing image
     *
     * @param imageScale The new Scale.
     * @param animate Should an animation be performed
     * @param duration If animate, whats the duration? When -1 we take the default duration
     * @param interpolator If animate, whats the interpolator? When null we take the default
     *                     interpolator.
     */
    public void setImageScale(float imageScale, boolean animate, long duration,
            Interpolator interpolator) {
        cancelAnimator(mScaleAnimator);
        if (!animate) {
            mImageScale = imageScale;
            invalidate();
        } else {
            ValueAnimator animator = ValueAnimator.ofFloat(mImageScale, imageScale);
            mScaleAnimator = animator;
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mImageScale = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            animator.addListener(mScaleEndListener);
            if (interpolator == null) {
                interpolator = imageScale == 0.0f
                        ? Interpolators.FAST_OUT_LINEAR_IN
                        : Interpolators.LINEAR_OUT_SLOW_IN;
            }
            animator.setInterpolator(interpolator);
            if (duration == -1) {
                float durationFactor = Math.abs(mImageScale - imageScale)
                        / (1.0f - MIN_ICON_SCALE_AMOUNT);
                durationFactor = Math.min(1.0f, durationFactor);
                duration = (long) (NORMAL_ANIMATION_DURATION * durationFactor);
            }
            animator.setDuration(duration);
            animator.start();
        }
    }

    public void setRestingAlpha(float alpha) {
        mRestingAlpha = alpha;

        // TODO: Handle the case an animation is playing.
        setImageAlpha(alpha, false);
    }

    public float getRestingAlpha() {
        return mRestingAlpha;
    }

    public void setImageAlpha(float alpha, boolean animate) {
        setImageAlpha(alpha, animate, -1, null, null);
    }

    /**
     * Sets the alpha of the containing image
     *
     * @param alpha The new alpha.
     * @param animate Should an animation be performed
     * @param duration If animate, whats the duration? When -1 we take the default duration
     * @param interpolator If animate, whats the interpolator? When null we take the default
     *                     interpolator.
     */
    public void setImageAlpha(float alpha, boolean animate, long duration,
            Interpolator interpolator, Runnable runnable) {
        cancelAnimator(mAlphaAnimator);
        alpha = mLaunchingAffordance ? 0 : alpha;
        int endAlpha = (int) (alpha * 255);
        final Drawable background = getBackground();
        if (!animate) {
            if (background != null) background.mutate().setAlpha(endAlpha);
            setImageAlpha(endAlpha);
        } else {
            int currentAlpha = getImageAlpha();
            ValueAnimator animator = ValueAnimator.ofInt(currentAlpha, endAlpha);
            mAlphaAnimator = animator;
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    int alpha = (int) animation.getAnimatedValue();
                    if (background != null) background.mutate().setAlpha(alpha);
                    setImageAlpha(alpha);
                }
            });
            animator.addListener(mAlphaEndListener);
            if (interpolator == null) {
                interpolator = alpha == 0.0f
                        ? Interpolators.FAST_OUT_LINEAR_IN
                        : Interpolators.LINEAR_OUT_SLOW_IN;
            }
            animator.setInterpolator(interpolator);
            if (duration == -1) {
                float durationFactor = Math.abs(currentAlpha - endAlpha) / 255f;
                durationFactor = Math.min(1.0f, durationFactor);
                duration = (long) (NORMAL_ANIMATION_DURATION * durationFactor);
            }
            animator.setDuration(duration);
            if (runnable != null) {
                animator.addListener(getEndListener(runnable));
            }
            animator.start();
        }
    }

    private Animator.AnimatorListener getEndListener(final Runnable runnable) {
        return new AnimatorListenerAdapter() {
            boolean mCancelled;
            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCancelled) {
                    runnable.run();
                }
            }
        };
    }

    public float getCircleRadius() {
        return mCircleRadius;
    }

    @Override
    public boolean performClick() {
        if (isClickable()) {
            return super.performClick();
        } else {
            return false;
        }
    }

    public void setLaunchingAffordance(boolean launchingAffordance) {
        mLaunchingAffordance = launchingAffordance;
    }
}
