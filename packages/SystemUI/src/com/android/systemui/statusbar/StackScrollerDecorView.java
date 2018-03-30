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

import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.systemui.Interpolators;

/**
 * A common base class for all views in the notification stack scroller which don't have a
 * background.
 */
public abstract class StackScrollerDecorView extends ExpandableView {

    protected View mContent;
    protected View mSecondaryView;
    private boolean mIsVisible;
    private boolean mIsSecondaryVisible;
    private boolean mAnimating;
    private int mDuration = 260;

    public StackScrollerDecorView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContent = findContentView();
        mSecondaryView = findSecondaryView();
        setInvisible();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        setOutlineProvider(null);
    }

    @Override
    public boolean isTransparent() {
        return true;
    }

    public void performVisibilityAnimation(boolean nowVisible) {
        animateText(mContent, nowVisible, null /* onFinishedRunnable */);
        mIsVisible = nowVisible;
    }

    public void performVisibilityAnimation(boolean nowVisible, Runnable onFinishedRunnable) {
        animateText(mContent, nowVisible, onFinishedRunnable);
        mIsVisible = nowVisible;
    }

    public void performSecondaryVisibilityAnimation(boolean nowVisible) {
        performSecondaryVisibilityAnimation(nowVisible, null /* onFinishedRunnable */);
    }

    public void performSecondaryVisibilityAnimation(boolean nowVisible,
            Runnable onFinishedRunnable) {
        animateText(mSecondaryView, nowVisible, onFinishedRunnable);
        mIsSecondaryVisible = nowVisible;
    }

    public boolean isSecondaryVisible() {
        return mSecondaryView != null && (mIsSecondaryVisible || mAnimating);
    }

    public boolean isVisible() {
        return mIsVisible || mAnimating;
    }

    void setDuration(int duration) {
        mDuration = duration;
    }

    /**
     * Animate the text to a new visibility.
     *
     * @param nowVisible should it now be visible
     * @param onFinishedRunnable A runnable which should be run when the animation is
     *        finished.
     */
    private void animateText(View view, boolean nowVisible, final Runnable onFinishedRunnable) {
        if (view == null) {
            return;
        }
        if (nowVisible != mIsVisible) {
            // Animate text
            float endValue = nowVisible ? 1.0f : 0.0f;
            Interpolator interpolator;
            if (nowVisible) {
                interpolator = Interpolators.ALPHA_IN;
            } else {
                interpolator = Interpolators.ALPHA_OUT;
            }
            mAnimating = true;
            view.animate()
                    .alpha(endValue)
                    .setInterpolator(interpolator)
                    .setDuration(mDuration)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mAnimating = false;
                            if (onFinishedRunnable != null) {
                                onFinishedRunnable.run();
                            }
                        }
                    });
        } else {
            if (onFinishedRunnable != null) {
                onFinishedRunnable.run();
            }
        }
    }

    public void setInvisible() {
        mContent.setAlpha(0.0f);
        if (mSecondaryView != null) {
            mSecondaryView.setAlpha(0.0f);
        }
        mIsVisible = false;
        mIsSecondaryVisible = false;
    }

    @Override
    public void performRemoveAnimation(long duration, long delay,
            float translationDirection, boolean isHeadsUpAnimation, float endLocation,
            Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener) {
        // TODO: Use duration
        performVisibilityAnimation(false);
    }

    @Override
    public void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear) {
        // TODO: use delay and duration
        performVisibilityAnimation(true);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void cancelAnimation() {
        mContent.animate().cancel();
        if (mSecondaryView != null) {
            mSecondaryView.animate().cancel();
        }
    }

    protected abstract View findContentView();

    /**
     * Returns a view that might not always appear while the main content view is still visible.
     */
    protected abstract View findSecondaryView();
}
