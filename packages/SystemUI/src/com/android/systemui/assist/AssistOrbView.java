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

package com.android.systemui.assist;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

public class AssistOrbView extends FrameLayout {

    private final int mCircleMinSize;
    private final int mBaseMargin;
    private final int mStaticOffset;
    private final Paint mBackgroundPaint = new Paint();
    private final Rect mCircleRect = new Rect();
    private final Rect mStaticRect = new Rect();
    private final Interpolator mOvershootInterpolator = new OvershootInterpolator();

    private boolean mClipToOutline;
    private final int mMaxElevation;
    private float mOutlineAlpha;
    private float mOffset;
    private float mCircleSize;
    private ImageView mLogo;
    private float mCircleAnimationEndValue;

    private ValueAnimator mOffsetAnimator;
    private ValueAnimator mCircleAnimator;

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
            mOffset = (float) animation.getAnimatedValue();
            updateLayout();
        }
    };


    public AssistOrbView(Context context) {
        this(context, null);
    }

    public AssistOrbView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AssistOrbView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public AssistOrbView(Context context, AttributeSet attrs, int defStyleAttr,
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
                R.dimen.assist_orb_size);
        mBaseMargin = context.getResources().getDimensionPixelSize(
                R.dimen.assist_orb_base_margin);
        mStaticOffset = context.getResources().getDimensionPixelSize(
                R.dimen.assist_orb_travel_distance);
        mMaxElevation = context.getResources().getDimensionPixelSize(
                R.dimen.assist_orb_elevation);
        mBackgroundPaint.setAntiAlias(true);
        mBackgroundPaint.setColor(getResources().getColor(R.color.assist_orb_color));
    }

    public ImageView getLogo() {
        return mLogo;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBackground(canvas);
    }

    private void drawBackground(Canvas canvas) {
        canvas.drawCircle(mCircleRect.centerX(), mCircleRect.centerY(), mCircleSize / 2,
                mBackgroundPaint);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLogo = findViewById(R.id.search_logo);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mLogo.layout(0, 0, mLogo.getMeasuredWidth(), mLogo.getMeasuredHeight());
        if (changed) {
            updateCircleRect(mStaticRect, mStaticOffset, true);
        }
    }

    public void animateCircleSize(float circleSize, long duration,
            long startDelay, Interpolator interpolator) {
        if (circleSize == mCircleAnimationEndValue) {
            return;
        }
        if (mCircleAnimator != null) {
            mCircleAnimator.cancel();
        }
        mCircleAnimator = ValueAnimator.ofFloat(mCircleSize, circleSize);
        mCircleAnimator.addUpdateListener(mCircleUpdateListener);
        mCircleAnimator.addListener(mClearAnimatorListener);
        mCircleAnimator.setInterpolator(interpolator);
        mCircleAnimator.setDuration(duration);
        mCircleAnimator.setStartDelay(startDelay);
        mCircleAnimator.start();
        mCircleAnimationEndValue = circleSize;
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
     * Animates the offset to the edge of the screen.
     *
     * @param offset The offset to apply.
     * @param startDelay The desired start delay if animated.
     *
     * @param interpolator The desired interpolator if animated. If null,
     *                     a default interpolator will be taken designed for appearing or
     *                     disappearing.
     */
    private void animateOffset(float offset, long duration, long startDelay,
            Interpolator interpolator) {
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
            }
        });
        mOffsetAnimator.setInterpolator(interpolator);
        mOffsetAnimator.setStartDelay(startDelay);
        mOffsetAnimator.setDuration(duration);
        mOffsetAnimator.start();
    }

    private void updateLayout() {
        updateCircleRect();
        updateLogo();
        invalidateOutline();
        invalidate();
        updateClipping();
    }

    private void updateClipping() {
        boolean clip = mCircleSize < mCircleMinSize;
        if (clip != mClipToOutline) {
            setClipToOutline(clip);
            mClipToOutline = clip;
        }
    }

    private void updateLogo() {
        float translationX = (mCircleRect.left + mCircleRect.right) / 2.0f - mLogo.getWidth() / 2.0f;
        float translationY = (mCircleRect.top + mCircleRect.bottom) / 2.0f
                - mLogo.getHeight() / 2.0f - mCircleMinSize / 7f;
        float t = (mStaticOffset - mOffset) / (float) mStaticOffset;
        translationY += t * mStaticOffset * 0.1f;
        float alpha = 1.0f-t;
        alpha = Math.max((alpha - 0.5f) * 2.0f, 0);
        mLogo.setImageAlpha((int) (alpha * 255));
        mLogo.setTranslationX(translationX);
        mLogo.setTranslationY(translationY);
    }

    private void updateCircleRect() {
        updateCircleRect(mCircleRect, mOffset, false);
    }

    private void updateCircleRect(Rect rect, float offset, boolean useStaticSize) {
        int left, top;
        float circleSize = useStaticSize ? mCircleMinSize : mCircleSize;
        left = (int) (getWidth() - circleSize) / 2;
        top = (int) (getHeight() - circleSize / 2 - mBaseMargin - offset);
        rect.set(left, top, (int) (left + circleSize), (int) (top + circleSize));
    }

    public void startExitAnimation(long delay) {
        animateCircleSize(0, 200, delay, Interpolators.FAST_OUT_LINEAR_IN);
        animateOffset(0, 200, delay, Interpolators.FAST_OUT_LINEAR_IN);
    }

    public void startEnterAnimation() {
        applyCircleSize(0);
        post(new Runnable() {
            @Override
            public void run() {
                animateCircleSize(mCircleMinSize, 300, 0 /* delay */, mOvershootInterpolator);
                animateOffset(mStaticOffset, 400, 0 /* delay */, Interpolators.LINEAR_OUT_SLOW_IN);
            }
        });
    }

    public void reset() {
        mClipToOutline = false;
        mBackgroundPaint.setAlpha(255);
        mOutlineAlpha = 1.0f;
    }

    @Override
    public boolean hasOverlappingRendering() {
        // not really true but it's ok during an animation, as it's never permanent
        return false;
    }
}
