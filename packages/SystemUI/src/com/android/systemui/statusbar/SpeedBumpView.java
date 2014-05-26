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
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.TextView;
import com.android.systemui.R;

/**
 * The view representing the separation between important and less important notifications
 */
public class SpeedBumpView extends ExpandableView implements View.OnClickListener {

    private final int mCollapsedHeight;
    private final int mDotsHeight;
    private final int mTextPaddingInset;
    private SpeedBumpDotsLayout mDots;
    private View mLineLeft;
    private View mLineRight;
    private boolean mIsExpanded;
    private boolean mDividerVisible = true;
    private ValueAnimator mCurrentAnimator;
    private final Interpolator mFastOutSlowInInterpolator;
    private float mCenterX;
    private TextView mExplanationText;
    private boolean mExplanationTextVisible = false;
    private AnimatorListenerAdapter mHideExplanationListener = new AnimatorListenerAdapter() {
        private boolean mCancelled;

        @Override
        public void onAnimationEnd(Animator animation) {
            if (!mCancelled) {
                mExplanationText.setVisibility(View.INVISIBLE);
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mCancelled = true;
        }

        @Override
        public void onAnimationStart(Animator animation) {
            mCancelled = false;
        }
    };
    private Animator.AnimatorListener mAnimationFinishedListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mCurrentAnimator = null;
        }
    };

    public SpeedBumpView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mCollapsedHeight = getResources()
                .getDimensionPixelSize(R.dimen.speed_bump_height_collapsed);
        mTextPaddingInset = getResources().getDimensionPixelSize(
                R.dimen.speed_bump_text_padding_inset);
        mDotsHeight = getResources().getDimensionPixelSize(R.dimen.speed_bump_dots_height);
        setOnClickListener(this);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_slow_in);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDots = (SpeedBumpDotsLayout) findViewById(R.id.speed_bump_dots_layout);
        mLineLeft = findViewById(R.id.speedbump_line_left);
        mLineRight = findViewById(R.id.speedbump_line_right);
        mExplanationText = (TextView) findViewById(R.id.speed_bump_text);
        resetExplanationText();

    }

    @Override
    protected int getInitialHeight() {
        return mCollapsedHeight;
    }

    @Override
    public int getIntrinsicHeight() {
        if (mCurrentAnimator != null) {
            // expand animation is running
            return getActualHeight();
        }
        return mIsExpanded ? getHeight() : mCollapsedHeight;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        Outline outline = new Outline();
        mCenterX = getWidth() / 2;
        float centerY = getHeight() / 2;
        // TODO: hide outline better
        // Temporary workaround to hide outline on a transparent view
        int outlineLeft = (int) (mCenterX - getResources().getDisplayMetrics().densityDpi * 8);
        int outlineTop = (int) (centerY - mDotsHeight / 2);
        outline.setOval(outlineLeft, outlineTop, outlineLeft + mDotsHeight,
                outlineTop + mDotsHeight);
        setOutline(outline);
        mLineLeft.setPivotX(mLineLeft.getWidth());
        mLineLeft.setPivotY(mLineLeft.getHeight() / 2);
        mLineRight.setPivotX(0);
        mLineRight.setPivotY(mLineRight.getHeight() / 2);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, heightMeasureSpec);
        int height = mCollapsedHeight + mExplanationText.getMeasuredHeight() - mTextPaddingInset;
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
    }

    @Override
    public void onClick(View v) {
        if (mCurrentAnimator != null) {
            return;
        }
        int startValue = mIsExpanded ? getMaxHeight() : mCollapsedHeight;
        int endValue = mIsExpanded ? mCollapsedHeight : getMaxHeight();
        mCurrentAnimator = ValueAnimator.ofInt(startValue, endValue);
        mCurrentAnimator.setInterpolator(mFastOutSlowInInterpolator);
        mCurrentAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setActualHeight((int) animation.getAnimatedValue());
            }
        });
        mCurrentAnimator.addListener(mAnimationFinishedListener);
        mCurrentAnimator.start();
        mIsExpanded = !mIsExpanded;
        mDots.performDotClickAnimation();
        animateExplanationTextInternal(mIsExpanded);
    }

    private void animateExplanationTextInternal(boolean visible) {
        if (mExplanationTextVisible != visible) {
            float translationY = 0.0f;
            float scale = 0.5f;
            float alpha = 0.0f;
            boolean needsHideListener = true;
            if (visible) {
                mExplanationText.setVisibility(VISIBLE);
                translationY = mDots.getBottom() - mTextPaddingInset;
                scale = 1.0f;
                alpha = 1.0f;
                needsHideListener = false;
            }
            mExplanationText.animate().setInterpolator(mFastOutSlowInInterpolator)
                    .alpha(alpha)
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationY(translationY)
                    .setListener(needsHideListener ? mHideExplanationListener : null)
                    .withLayer();
            mExplanationTextVisible = visible;
        }
    }

    @Override
    public boolean isTransparent() {
        return true;
    }

    public void performVisibilityAnimation(boolean nowVisible) {
        animateDivider(nowVisible, null /* onFinishedRunnable */);

        // Animate explanation Text
        if (mIsExpanded) {
            animateExplanationTextInternal(nowVisible);
        }
    }

    /**
     * Animate the divider to a new visibility.
     *
     * @param nowVisible should it now be visible
     * @param onFinishedRunnable A runnable which should be run when the animation is
     *        finished.
     */
    public void animateDivider(boolean nowVisible, Runnable onFinishedRunnable) {
        if (nowVisible != mDividerVisible) {
            // Animate dividers
            float endValue = nowVisible ? 1.0f : 0.0f;
            float endTranslationXLeft = nowVisible ? 0.0f : mCenterX - mLineLeft.getRight();
            float endTranslationXRight = nowVisible ? 0.0f : mCenterX - mLineRight.getLeft();
            mLineLeft.animate()
                    .alpha(endValue)
                    .withLayer()
                    .scaleX(endValue)
                    .scaleY(endValue)
                    .translationX(endTranslationXLeft)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .withEndAction(onFinishedRunnable);
            mLineRight.animate()
                    .alpha(endValue)
                    .withLayer()
                    .scaleX(endValue)
                    .scaleY(endValue)
                    .translationX(endTranslationXRight)
                    .setInterpolator(mFastOutSlowInInterpolator);

            // Animate dots
            mDots.performVisibilityAnimation(nowVisible);
            mDividerVisible = nowVisible;
        } else {
            if (onFinishedRunnable != null) {
                onFinishedRunnable.run();
            }
        }
    }

    public void setInvisible() {
        float endTranslationXLeft = mCenterX - mLineLeft.getRight();
        float endTranslationXRight = mCenterX - mLineRight.getLeft();
        mLineLeft.setAlpha(0.0f);
        mLineLeft.setScaleX(0.0f);
        mLineLeft.setScaleY(0.0f);
        mLineLeft.setTranslationX(endTranslationXLeft);
        mLineRight.setAlpha(0.0f);
        mLineRight.setScaleX(0.0f);
        mLineRight.setScaleY(0.0f);
        mLineRight.setTranslationX(endTranslationXRight);
        mDots.setInvisible();
        resetExplanationText();

        mDividerVisible = false;
    }

    public void collapse() {
        if (mIsExpanded) {
            setActualHeight(mCollapsedHeight);
            mIsExpanded = false;
        }
        resetExplanationText();
    }

    public void animateExplanationText(boolean nowVisible) {
        if (mIsExpanded) {
            animateExplanationTextInternal(nowVisible);
        }
    }

    @Override
    public void performRemoveAnimation(float translationDirection, Runnable onFinishedRunnable) {
        performVisibilityAnimation(false);
    }

    @Override
    public void performAddAnimation(long delay) {
        performVisibilityAnimation(true);
    }

    private void resetExplanationText() {
        mExplanationText.setTranslationY(0);
        mExplanationText.setVisibility(INVISIBLE);
        mExplanationText.setAlpha(0.0f);
        mExplanationText.setScaleX(0.5f);
        mExplanationText.setScaleY(0.5f);
        mExplanationTextVisible = false;
    }

    public boolean isExpanded() {
        return mIsExpanded;
    }
}
