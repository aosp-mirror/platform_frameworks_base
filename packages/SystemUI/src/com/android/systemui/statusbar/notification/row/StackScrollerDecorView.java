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

package com.android.systemui.statusbar.notification.row;

import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.animation.Interpolators;

/**
 * A common base class for all views in the notification stack scroller which don't have a
 * background.
 */
public abstract class StackScrollerDecorView extends ExpandableView {

    protected View mContent;
    protected View mSecondaryView;
    private boolean mIsVisible = true;
    private boolean mContentVisible = true;
    private boolean mIsSecondaryVisible = true;
    private int mDuration = 260;
    private boolean mContentAnimating;
    private final Runnable mContentVisibilityEndRunnable = () -> {
        mContentAnimating = false;
        if (getVisibility() != View.GONE && !mIsVisible) {
            setVisibility(GONE);
            setWillBeGone(false);
            notifyHeightChanged(false /* needsAnimation */);
        }
    };

    private boolean mSecondaryAnimating = false;
    private final Runnable mSecondaryVisibilityEndRunnable = () -> {
        mSecondaryAnimating = false;
        // If we were on screen, become GONE to avoid touches
        if (mSecondaryView == null) return;
        if (getVisibility() != View.GONE
                && mSecondaryView.getVisibility() != View.GONE
                && !mIsSecondaryVisible) {
            mSecondaryView.setVisibility(View.GONE);
        }
    };

    public StackScrollerDecorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContent = findContentView();
        mSecondaryView = findSecondaryView();
        setVisible(false /* nowVisible */, false /* animate */);
        setSecondaryVisible(false /* nowVisible */, false /* animate */);
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

    /**
     * Set the content of this view to be visible in an animated way.
     *
     * @param contentVisible True if the content should be visible or false if it should be hidden.
     */
    public void setContentVisible(boolean contentVisible) {
        setContentVisible(contentVisible, true /* animate */);
    }
    /**
     * Set the content of this view to be visible.
     * @param contentVisible True if the content should be visible or false if it should be hidden.
     * @param animate Should an animation be performed.
     */
    private void setContentVisible(boolean contentVisible, boolean animate) {
        if (mContentVisible != contentVisible) {
            mContentAnimating = animate;
            mContentVisible = contentVisible;
            setViewVisible(mContent, contentVisible, animate, mContentVisibilityEndRunnable);
        }

        if (!mContentAnimating) {
            mContentVisibilityEndRunnable.run();
        }
    }

    public boolean isContentVisible() {
        return mContentVisible;
    }

    /**
     * Make this view visible. If {@code false} is passed, the view will fade out it's content
     * and set the view Visibility to GONE. If only the content should be changed
     * {@link #setContentVisible(boolean)} can be used.
     *
     * @param nowVisible should the view be visible
     * @param animate should the change be animated.
     */
    public void setVisible(boolean nowVisible, boolean animate) {
        if (mIsVisible != nowVisible) {
            mIsVisible = nowVisible;
            if (animate) {
                if (nowVisible) {
                    setVisibility(VISIBLE);
                    setWillBeGone(false);
                    notifyHeightChanged(false /* needsAnimation */);
                } else {
                    setWillBeGone(true);
                }
                setContentVisible(nowVisible, true /* animate */);
            } else {
                setVisibility(nowVisible ? VISIBLE : GONE);
                setContentVisible(nowVisible, false /* animate */);
                setWillBeGone(false);
                notifyHeightChanged(false /* needsAnimation */);
            }
        }
    }

    /**
     * Set the secondary view of this layout to visible.
     *
     * @param nowVisible should the secondary view be visible
     * @param animate should the change be animated
     */
    public void setSecondaryVisible(boolean nowVisible, boolean animate) {
        if (mIsSecondaryVisible != nowVisible) {
            mSecondaryAnimating = animate;
            mIsSecondaryVisible = nowVisible;
            setViewVisible(mSecondaryView, nowVisible, animate, mSecondaryVisibilityEndRunnable);
        }

        if (!mSecondaryAnimating) {
            mSecondaryVisibilityEndRunnable.run();
        }
    }

    @VisibleForTesting
    boolean isSecondaryVisible() {
        return mIsSecondaryVisible;
    }

    /**
     * Is this view visible. If a view is currently animating to gone, it will
     * return {@code false}.
     */
    public boolean isVisible() {
        return mIsVisible;
    }

    void setDuration(int duration) {
        mDuration = duration;
    }

    /**
     * Animate a view to a new visibility.
     * @param view Target view, maybe content view or dismiss view.
     * @param nowVisible Should it now be visible.
     * @param animate Should this be done in an animated way.
     * @param endRunnable A runnable that is run when the animation is done.
     */
    private void setViewVisible(View view, boolean nowVisible,
            boolean animate, Runnable endRunnable) {
        if (view == null) {
            return;
        }

        // Make sure we're visible so animations work
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(View.VISIBLE);
        }

        // cancel any previous animations
        view.animate().cancel();
        float endValue = nowVisible ? 1.0f : 0.0f;
        if (!animate) {
            view.setAlpha(endValue);
            if (endRunnable != null) {
                endRunnable.run();
            }
            return;
        }

        // Animate the view alpha
        Interpolator interpolator = nowVisible ? Interpolators.ALPHA_IN : Interpolators.ALPHA_OUT;
        view.animate()
                .alpha(endValue)
                .setInterpolator(interpolator)
                .setDuration(mDuration)
                .withEndAction(endRunnable);
    }

    @Override
    public long performRemoveAnimation(long duration, long delay,
            float translationDirection, boolean isHeadsUpAnimation, float endLocation,
            Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener) {
        // TODO: Use duration
        setContentVisible(false);
        return 0;
    }

    @Override
    public void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear) {
        // TODO: use delay and duration
        setContentVisible(true);
    }

    @Override
    public boolean needsClippingToShelf() {
        return false;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    protected abstract View findContentView();

    /**
     * Returns a view that might not always appear while the main content view is still visible.
     */
    protected abstract View findSecondaryView();
}
