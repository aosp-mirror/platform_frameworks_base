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
    private boolean mSecondaryAnimating;
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
        performVisibilityAnimation(nowVisible, null /* onFinishedRunnable */);
    }

    public void performVisibilityAnimation(boolean nowVisible, Runnable onFinishedRunnable) {
        boolean oldVisible = isVisible();
        animateText(mContent, nowVisible, oldVisible, new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mAnimating = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mAnimating = false;
                    mIsVisible = nowVisible;
                    if (onFinishedRunnable != null) {
                        onFinishedRunnable.run();
                    }
                }
            });
    }

    public void performSecondaryVisibilityAnimation(boolean nowVisible) {
        performSecondaryVisibilityAnimation(nowVisible, null /* onFinishedRunnable */);
    }

    public void performSecondaryVisibilityAnimation(boolean nowVisible,
            Runnable onFinishedRunnable) {
        boolean oldVisible = isSecondaryVisible();
        animateText(mSecondaryView, nowVisible, oldVisible, new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mSecondaryAnimating = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mSecondaryAnimating = false;
                    mIsSecondaryVisible = nowVisible;
                    if (onFinishedRunnable != null) {
                        onFinishedRunnable.run();
                    }
                }
            });
    }

    /**
     * Check whether the secondary view is visible or not.<p/>
     *
     * @see #isVisible()
     */
    public boolean isSecondaryVisible() {
        return mSecondaryView != null && (mIsSecondaryVisible ^ mSecondaryAnimating);
    }

    /**
     * Check whether the whole view is visible or not.<p/>
     * The view is considered visible if it matches one of following:
     * <ul>
     *   <li> It's visible and there is no ongoing animation. </li>
     *   <li> It's not visible but is animating, thus being eventually visible. </li>
     * </ul>
     */
    public boolean isVisible() {
        return mIsVisible ^ mAnimating;
    }

    void setDuration(int duration) {
        mDuration = duration;
    }

    /**
     * Animate the text to a new visibility.
     *
     * @param view Target view, maybe content view or dissmiss view
     * @param nowVisible Should it now be visible
     * @param oldVisible Is it visible currently
     * @param listener A listener that doing flag settings or other actions
     */
    private void animateText(View view, boolean nowVisible, boolean oldVisible,
        AnimatorListenerAdapter listener) {
        if (view == null) {
            return;
        }

        if (nowVisible != oldVisible) {
            // Animate text
            float endValue = nowVisible ? 1.0f : 0.0f;
            Interpolator interpolator;
            if (nowVisible) {
                interpolator = Interpolators.ALPHA_IN;
            } else {
                interpolator = Interpolators.ALPHA_OUT;
            }
            view.animate()
                    .alpha(endValue)
                    .setInterpolator(interpolator)
                    .setDuration(mDuration)
                    .setListener(listener);
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
