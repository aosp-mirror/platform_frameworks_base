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

import android.content.Context;
import android.util.AttributeSet;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import com.android.systemui.R;

/**
 * The view representing the separation between important and less important notifications
 */
public class SpeedBumpView extends ExpandableView {

    private final int mSpeedBumpHeight;
    private AlphaOptimizedView mLine;
    private boolean mIsVisible = true;
    private final Interpolator mFastOutSlowInInterpolator;

    public SpeedBumpView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSpeedBumpHeight = getResources()
                .getDimensionPixelSize(R.dimen.speed_bump_height);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_slow_in);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLine = (AlphaOptimizedView) findViewById(R.id.speedbump_line);
    }

    @Override
    protected int getInitialHeight() {
        return mSpeedBumpHeight;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSpeedBumpHeight;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mLine.setPivotX(mLine.getWidth() / 2);
        mLine.setPivotY(mLine.getHeight() / 2);
        setOutlineProvider(null);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, heightMeasureSpec);
        int height = mSpeedBumpHeight;
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
    }

    @Override
    public boolean isTransparent() {
        return true;
    }

    public void performVisibilityAnimation(boolean nowVisible, long delay) {
        animateDivider(nowVisible, delay, null /* onFinishedRunnable */);
    }

    /**
     * Animate the divider to a new visibility.
     *
     * @param nowVisible should it now be visible
     * @param delay the delay after the animation should start
     * @param onFinishedRunnable A runnable which should be run when the animation is
     *        finished.
     */
    public void animateDivider(boolean nowVisible, long delay, Runnable onFinishedRunnable) {
        if (nowVisible != mIsVisible) {
            // Animate dividers
            float endValue = nowVisible ? 1.0f : 0.0f;
            mLine.animate()
                    .alpha(endValue)
                    .setStartDelay(delay)
                    .scaleX(endValue)
                    .scaleY(endValue)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .withEndAction(onFinishedRunnable);
            mIsVisible = nowVisible;
        } else {
            if (onFinishedRunnable != null) {
                onFinishedRunnable.run();
            }
        }
    }

    public void setInvisible() {
        mLine.setAlpha(0.0f);
        mLine.setScaleX(0.0f);
        mLine.setScaleY(0.0f);
        mIsVisible = false;
    }

    @Override
    public void performRemoveAnimation(long duration, float translationDirection,
            Runnable onFinishedRunnable) {
        // TODO: Use duration
        performVisibilityAnimation(false, 0 /* delay */);
    }

    @Override
    public void performAddAnimation(long delay, long duration) {
        // TODO: Use duration
        performVisibilityAnimation(true, delay);
    }
}
