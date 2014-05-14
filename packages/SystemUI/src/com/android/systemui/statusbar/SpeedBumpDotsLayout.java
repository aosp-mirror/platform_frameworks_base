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

import android.animation.TimeAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import com.android.systemui.R;

/**
 * A layout with a certain number of dots which are integrated in the
 * {@link com.android.systemui.statusbar.SpeedBumpView}
 */
public class SpeedBumpDotsLayout extends ViewGroup {

    private static final float DOT_CLICK_ANIMATION_LENGTH = 300;
    private final int mDotSize;
    private final SpeedBumpDotsAlgorithm mAlgorithm = new SpeedBumpDotsAlgorithm(getContext());
    private final SpeedBumpDotsState mCurrentState = new SpeedBumpDotsState(this);
    private boolean mIsCurrentlyVisible = true;
    private final ValueAnimator mClickAnimator;
    private float mAnimationProgress;
    private ValueAnimator.AnimatorUpdateListener mClickUpdateListener
            = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mAnimationProgress = animation.getAnimatedFraction();
            updateChildren();
        }
    };

    public SpeedBumpDotsLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDotSize = getResources().getDimensionPixelSize(R.dimen.speed_bump_dots_height);
        createDots(context, attrs);
        mClickAnimator = TimeAnimator.ofFloat(0, DOT_CLICK_ANIMATION_LENGTH);
        mClickAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mClickAnimator.addUpdateListener(mClickUpdateListener);
    }

    private void createDots(Context context, AttributeSet attrs) {
        SpeedBumpDotView blueDot = new SpeedBumpDotView(context, attrs);
        blueDot.setColor(getResources().getColor(R.color.speed_bump_dot_blue));
        addView(blueDot);

        SpeedBumpDotView redDot = new SpeedBumpDotView(context, attrs);
        redDot.setColor(getResources().getColor(R.color.speed_bump_dot_red));
        addView(redDot);

        SpeedBumpDotView yellowDot = new SpeedBumpDotView(context, attrs);
        yellowDot.setColor(getResources().getColor(R.color.speed_bump_dot_yellow));
        addView(yellowDot);

        SpeedBumpDotView greenDot = new SpeedBumpDotView(context, attrs);
        greenDot.setColor(getResources().getColor(R.color.speed_bump_dot_green));
        addView(greenDot);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int childWidthSpec = MeasureSpec.makeMeasureSpec(mDotSize,
                MeasureSpec.getMode(widthMeasureSpec));
        int childHeightSpec = MeasureSpec.makeMeasureSpec(mDotSize,
                MeasureSpec.getMode(heightMeasureSpec));
        measureChildren(childWidthSpec, childHeightSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            child.layout(0, 0, mDotSize, mDotSize);
        }
        if (changed) {
            updateChildren();
        }
    }

    private void updateChildren() {
        mAlgorithm.getState(mCurrentState);
        mCurrentState.apply();
    }

    public void performVisibilityAnimation(boolean visible) {
        if (mClickAnimator.isRunning()) {
            mClickAnimator.cancel();
        }
        mIsCurrentlyVisible = visible;
        mAlgorithm.getState(mCurrentState);
        mCurrentState.animateToState();
    }

    public void setInvisible() {
        mIsCurrentlyVisible = false;
        mAlgorithm.getState(mCurrentState);
        mCurrentState.apply();
    }

    public boolean isCurrentlyVisible() {
        return mIsCurrentlyVisible;
    }

    public void performDotClickAnimation() {
        if (mClickAnimator.isRunning()) {
            // don't perform an animation if it's running already
            return;
        }
        mClickAnimator.start();
    }


    public float getAnimationProgress() {
        return mAnimationProgress;
    }
}
