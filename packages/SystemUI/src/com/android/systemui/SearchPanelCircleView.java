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

package com.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.util.ArrayList;

public class SearchPanelCircleView extends FrameLayout {

    private final int mCircleMinSize;
    private final int mBaseMargin;
    private final int mStaticOffset;
    private final Paint mBackgroundPaint = new Paint();
    private final Paint mRipplePaint = new Paint();
    private final Rect mCircleRect = new Rect();
    private final Rect mStaticRect = new Rect();
    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mAppearInterpolator;
    private final Interpolator mDisappearInterpolator;

    private boolean mClipToOutline;
    private final int mMaxElevation;
    private boolean mAnimatingOut;
    private float mOutlineAlpha;
    private float mOffset;
    private float mCircleSize;
    private boolean mHorizontal;
    private boolean mCircleHidden;
    private ImageView mLogo;
    private boolean mDraggedFarEnough;
    private boolean mOffsetAnimatingIn;
    private float mCircleAnimationEndValue;
    private ArrayList<Ripple> mRipples = new ArrayList<Ripple>();

    private ValueAnimator mOffsetAnimator;
    private ValueAnimator mCircleAnimator;
    private ValueAnimator mFadeOutAnimator;
    private ValueAnimator.AnimatorUpdateListener mCircleUpdateListener
            = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            applyCircleSize((float) animation.getAnimatedValue());
            updateElevation();
        }
    };
    private AnimatorListenerAdapter mClearAnimatorListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mCircleAnimator = null;
        }
    };
    private ValueAnimator.AnimatorUpdateListener mOffsetUpdateListener
            = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            setOffset((float) animation.getAnimatedValue());
        }
    };


    public SearchPanelCircleView(Context context) {
        this(context, null);
    }

    public SearchPanelCircleView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SearchPanelCircleView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public SearchPanelCircleView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (mCircleSize > 0.0f) {
                    outline.setOval(mCircleRect);
                } else {
                    outline.setEmpty();
                }
                outline.setAlpha(mOutlineAlpha);
            }
        });
        setWillNotDraw(false);
        mCircleMinSize = context.getResources().getDimensionPixelSize(
                R.dimen.search_panel_circle_size);
        mBaseMargin = context.getResources().getDimensionPixelSize(
                R.dimen.search_panel_circle_base_margin);
        mStaticOffset = context.getResources().getDimensionPixelSize(
                R.dimen.search_panel_circle_travel_distance);
        mMaxElevation = context.getResources().getDimensionPixelSize(
                R.dimen.search_panel_circle_elevation);
        mAppearInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.linear_out_slow_in);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_slow_in);
        mDisappearInterpolator = AnimationUtils.loadInterpolator(mContext,
                android.R.interpolator.fast_out_linear_in);
        mBackgroundPaint.setAntiAlias(true);
        mBackgroundPaint.setColor(getResources().getColor(R.color.search_panel_circle_color));
        mRipplePaint.setColor(getResources().getColor(R.color.search_panel_ripple_color));
        mRipplePaint.setAntiAlias(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBackground(canvas);
        drawRipples(canvas);
    }

    private void drawRipples(Canvas canvas) {
        for (int i = 0; i < mRipples.size(); i++) {
            Ripple ripple = mRipples.get(i);
            ripple.draw(canvas);
        }
    }

    private void drawBackground(Canvas canvas) {
        canvas.drawCircle(mCircleRect.centerX(), mCircleRect.centerY(), mCircleSize / 2,
                mBackgroundPaint);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLogo = (ImageView) findViewById(R.id.search_logo);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mLogo.layout(0, 0, mLogo.getMeasuredWidth(), mLogo.getMeasuredHeight());
        if (changed) {
            updateCircleRect(mStaticRect, mStaticOffset, true);
        }
    }

    public void setCircleSize(float circleSize) {
        setCircleSize(circleSize, false, null, 0, null);
    }

    public void setCircleSize(float circleSize, boolean animated, final Runnable endRunnable,
            int startDelay, Interpolator interpolator) {
        boolean isAnimating = mCircleAnimator != null;
        boolean animationPending = isAnimating && !mCircleAnimator.isRunning();
        boolean animatingOut = isAnimating && mCircleAnimationEndValue == 0;
        if (animated || animationPending || animatingOut) {
            if (isAnimating) {
                if (circleSize == mCircleAnimationEndValue) {
                    return;
                }
                mCircleAnimator.cancel();
            }
            mCircleAnimator = ValueAnimator.ofFloat(mCircleSize, circleSize);
            mCircleAnimator.addUpdateListener(mCircleUpdateListener);
            mCircleAnimator.addListener(mClearAnimatorListener);
            mCircleAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (endRunnable != null) {
                        endRunnable.run();
                    }
                }
            });
            Interpolator desiredInterpolator = interpolator != null ? interpolator
                    : circleSize == 0 ? mDisappearInterpolator : mAppearInterpolator;
            mCircleAnimator.setInterpolator(desiredInterpolator);
            mCircleAnimator.setDuration(300);
            mCircleAnimator.setStartDelay(startDelay);
            mCircleAnimator.start();
            mCircleAnimationEndValue = circleSize;
        } else {
            if (isAnimating) {
                float diff = circleSize - mCircleAnimationEndValue;
                PropertyValuesHolder[] values = mCircleAnimator.getValues();
                values[0].setFloatValues(diff, circleSize);
                mCircleAnimator.setCurrentPlayTime(mCircleAnimator.getCurrentPlayTime());
                mCircleAnimationEndValue = circleSize;
            } else {
                applyCircleSize(circleSize);
                updateElevation();
            }
        }
    }

    private void applyCircleSize(float circleSize) {
        mCircleSize = circleSize;
        updateLayout();
    }

    private void updateElevation() {
        float t = (mStaticOffset - mOffset) / (float) mStaticOffset;
        t = 1.0f - Math.max(t, 0.0f);
        float offset = t * mMaxElevation;
        setElevation(offset);
    }

    /**
     * Sets the offset to the edge of the screen. By default this not not animated.
     *
     * @param offset The offset to apply.
     */
    public void setOffset(float offset) {
        setOffset(offset, false, 0, null, null);
    }

    /**
     * Sets the offset to the edge of the screen.
     *
     * @param offset The offset to apply.
     * @param animate Whether an animation should be performed.
     * @param startDelay The desired start delay if animated.
     * @param interpolator The desired interpolator if animated. If null,
     *                     a default interpolator will be taken designed for appearing or
     *                     disappearing.
     * @param endRunnable The end runnable which should be executed when the animation is finished.
     */
    private void setOffset(float offset, boolean animate, int startDelay,
            Interpolator interpolator, final Runnable endRunnable) {
        if (!animate) {
            mOffset = offset;
            updateLayout();
            if (endRunnable != null) {
                endRunnable.run();
            }
        } else {
            if (mOffsetAnimator != null) {
                mOffsetAnimator.removeAllListeners();
                mOffsetAnimator.cancel();
            }
            mOffsetAnimator = ValueAnimator.ofFloat(mOffset, offset);
            mOffsetAnimator.addUpdateListener(mOffsetUpdateListener);
            mOffsetAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mOffsetAnimator = null;
                    if (endRunnable != null) {
                        endRunnable.run();
                    }
                }
            });
            Interpolator desiredInterpolator = interpolator != null ?
                    interpolator : offset == 0 ? mDisappearInterpolator : mAppearInterpolator;
            mOffsetAnimator.setInterpolator(desiredInterpolator);
            mOffsetAnimator.setStartDelay(startDelay);
            mOffsetAnimator.setDuration(300);
            mOffsetAnimator.start();
            mOffsetAnimatingIn = offset != 0;
        }
    }

    private void updateLayout() {
        updateCircleRect();
        updateLogo();
        invalidateOutline();
        invalidate();
        updateClipping();
    }

    private void updateClipping() {
        boolean clip = mCircleSize < mCircleMinSize || !mRipples.isEmpty();
        if (clip != mClipToOutline) {
            setClipToOutline(clip);
            mClipToOutline = clip;
        }
    }

    private void updateLogo() {
        boolean exitAnimationRunning = mFadeOutAnimator != null;
        Rect rect = exitAnimationRunning ? mCircleRect : mStaticRect;
        float translationX = (rect.left + rect.right) / 2.0f - mLogo.getWidth() / 2.0f;
        float translationY = (rect.top + rect.bottom) / 2.0f - mLogo.getHeight() / 2.0f;
        float t = (mStaticOffset - mOffset) / (float) mStaticOffset;
        if (!exitAnimationRunning) {
            if (mHorizontal) {
                translationX += t * mStaticOffset * 0.3f;
            } else {
                translationY += t * mStaticOffset * 0.3f;
            }
            float alpha = 1.0f-t;
            alpha = Math.max((alpha - 0.5f) * 2.0f, 0);
            mLogo.setAlpha(alpha);
        } else {
            translationY += (mOffset - mStaticOffset) / 2;
        }
        mLogo.setTranslationX(translationX);
        mLogo.setTranslationY(translationY);
    }

    private void updateCircleRect() {
        updateCircleRect(mCircleRect, mOffset, false);
    }

    private void updateCircleRect(Rect rect, float offset, boolean useStaticSize) {
        int left, top;
        float circleSize = useStaticSize ? mCircleMinSize : mCircleSize;
        if (mHorizontal) {
            left = (int) (getWidth() - circleSize / 2 - mBaseMargin - offset);
            top = (int) ((getHeight() - circleSize) / 2);
        } else {
            left = (int) (getWidth() - circleSize) / 2;
            top = (int) (getHeight() - circleSize / 2 - mBaseMargin - offset);
        }
        rect.set(left, top, (int) (left + circleSize), (int) (top + circleSize));
    }

    public void setHorizontal(boolean horizontal) {
        mHorizontal = horizontal;
        updateCircleRect(mStaticRect, mStaticOffset, true);
        updateLayout();
    }

    public void setDragDistance(float distance) {
        if (!mAnimatingOut && (!mCircleHidden || mDraggedFarEnough)) {
            float circleSize = mCircleMinSize + rubberband(distance);
            setCircleSize(circleSize);
        }

    }

    private float rubberband(float diff) {
        return (float) Math.pow(Math.abs(diff), 0.6f);
    }

    public void startAbortAnimation(Runnable endRunnable) {
        if (mAnimatingOut) {
            if (endRunnable != null) {
                endRunnable.run();
            }
            return;
        }
        setCircleSize(0, true, null, 0, null);
        setOffset(0, true, 0, null, endRunnable);
        mCircleHidden = true;
    }

    public void startEnterAnimation() {
        if (mAnimatingOut) {
            return;
        }
        applyCircleSize(0);
        setOffset(0);
        setCircleSize(mCircleMinSize, true, null, 50, null);
        setOffset(mStaticOffset, true, 50, null, null);
        mCircleHidden = false;
    }


    public void startExitAnimation(final Runnable endRunnable) {
        if (!mHorizontal) {
            float offset = getHeight() / 2.0f;
            setOffset(offset - mBaseMargin, true, 50, mFastOutSlowInInterpolator, null);
            float xMax = getWidth() / 2;
            float yMax = getHeight() / 2;
            float maxRadius = (float) Math.ceil(Math.hypot(xMax, yMax) * 2);
            setCircleSize(maxRadius, true, null, 50, mFastOutSlowInInterpolator);
            performExitFadeOutAnimation(50, 300, endRunnable);
        } else {

            // when in landscape, we don't wan't the animation as it interferes with the general
            // rotation animation to the homescreen.
            endRunnable.run();
        }
    }

    private void performExitFadeOutAnimation(int startDelay, int duration,
            final Runnable endRunnable) {
        mFadeOutAnimator = ValueAnimator.ofFloat(mBackgroundPaint.getAlpha() / 255.0f, 0.0f);

        // Linear since we are animating multiple values
        mFadeOutAnimator.setInterpolator(new LinearInterpolator());
        mFadeOutAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float animatedFraction = animation.getAnimatedFraction();
                float logoValue = animatedFraction > 0.5f ? 1.0f : animatedFraction / 0.5f;
                logoValue = PhoneStatusBar.ALPHA_OUT.getInterpolation(1.0f - logoValue);
                float backgroundValue = animatedFraction < 0.2f ? 0.0f :
                        PhoneStatusBar.ALPHA_OUT.getInterpolation((animatedFraction - 0.2f) / 0.8f);
                backgroundValue = 1.0f - backgroundValue;
                mBackgroundPaint.setAlpha((int) (backgroundValue * 255));
                mOutlineAlpha = backgroundValue;
                mLogo.setAlpha(logoValue);
                invalidateOutline();
                invalidate();
            }
        });
        mFadeOutAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (endRunnable != null) {
                    endRunnable.run();
                }
                mLogo.setAlpha(1.0f);
                mBackgroundPaint.setAlpha(255);
                mOutlineAlpha = 1.0f;
                mFadeOutAnimator = null;
            }
        });
        mFadeOutAnimator.setStartDelay(startDelay);
        mFadeOutAnimator.setDuration(duration);
        mFadeOutAnimator.start();
    }

    public void setDraggedFarEnough(boolean farEnough) {
        if (farEnough != mDraggedFarEnough) {
            if (farEnough) {
                if (mCircleHidden) {
                    startEnterAnimation();
                }
                if (mOffsetAnimator == null) {
                    addRipple();
                } else {
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            addRipple();
                        }
                    }, 100);
                }
            } else {
                startAbortAnimation(null);
            }
            mDraggedFarEnough = farEnough;
        }

    }

    private void addRipple() {
        if (mRipples.size() > 1) {
            // we only want 2 ripples at the time
            return;
        }
        float xInterpolation, yInterpolation;
        if (mHorizontal) {
            xInterpolation = 0.75f;
            yInterpolation = 0.5f;
        } else {
            xInterpolation = 0.5f;
            yInterpolation = 0.75f;
        }
        float circleCenterX = mStaticRect.left * (1.0f - xInterpolation)
                + mStaticRect.right * xInterpolation;
        float circleCenterY = mStaticRect.top * (1.0f - yInterpolation)
                + mStaticRect.bottom * yInterpolation;
        float radius = Math.max(mCircleSize, mCircleMinSize * 1.25f) * 0.75f;
        Ripple ripple = new Ripple(circleCenterX, circleCenterY, radius);
        ripple.start();
    }

    public void reset() {
        mDraggedFarEnough = false;
        mAnimatingOut = false;
        mCircleHidden = true;
        mClipToOutline = false;
        if (mFadeOutAnimator != null) {
            mFadeOutAnimator.cancel();
        }
        mBackgroundPaint.setAlpha(255);
        mOutlineAlpha = 1.0f;
    }

    /**
     * Check if an animation is currently running
     *
     * @param enterAnimation Is the animating queried the enter animation.
     */
    public boolean isAnimationRunning(boolean enterAnimation) {
        return mOffsetAnimator != null && (enterAnimation == mOffsetAnimatingIn);
    }

    public void performOnAnimationFinished(final Runnable runnable) {
        if (mOffsetAnimator != null) {
            mOffsetAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (runnable != null) {
                        runnable.run();
                    }
                }
            });
        } else {
            if (runnable != null) {
                runnable.run();
            }
        }
    }

    public void setAnimatingOut(boolean animatingOut) {
        mAnimatingOut = animatingOut;
    }

    /**
     * @return Whether the circle is currently launching to the search activity or aborting the
     * interaction
     */
    public boolean isAnimatingOut() {
        return mAnimatingOut;
    }

    @Override
    public boolean hasOverlappingRendering() {
        // not really true but it's ok during an animation, as it's never permanent
        return false;
    }

    private class Ripple {
        float x;
        float y;
        float radius;
        float endRadius;
        float alpha;

        Ripple(float x, float y, float endRadius) {
            this.x = x;
            this.y = y;
            this.endRadius = endRadius;
        }

        void start() {
            ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);

            // Linear since we are animating multiple values
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    alpha = 1.0f - animation.getAnimatedFraction();
                    alpha = mDisappearInterpolator.getInterpolation(alpha);
                    radius = mAppearInterpolator.getInterpolation(animation.getAnimatedFraction());
                    radius *= endRadius;
                    invalidate();
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mRipples.remove(Ripple.this);
                    updateClipping();
                }

                public void onAnimationStart(Animator animation) {
                    mRipples.add(Ripple.this);
                    updateClipping();
                }
            });
            animator.setDuration(400);
            animator.start();
        }

        public void draw(Canvas canvas) {
            mRipplePaint.setAlpha((int) (alpha * 255));
            canvas.drawCircle(x, y, radius, mRipplePaint);
        }
    }

}
