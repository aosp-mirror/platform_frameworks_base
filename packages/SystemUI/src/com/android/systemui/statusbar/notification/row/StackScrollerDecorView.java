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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;

import java.util.function.Consumer;

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
    private int mAnimationDuration = 260;
    private boolean mContentAnimating;
    private boolean mSecondaryAnimating = false;

    public StackScrollerDecorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContent = findContentView();
        mSecondaryView = findSecondaryView();
        setVisible(false /* visible */, false /* animate */);
        setSecondaryVisible(false /* visible */, false /* animate */, null /* onAnimationEnd */);
        setOutlineProvider(null);
    }

    @Override
    public boolean isTransparent() {
        return true;
    }

    /**
     * Is this view visible. If a view is currently animating to gone, it will
     * return {@code false}.
     */
    public boolean isVisible() {
        return mIsVisible;
    }

    /**
     * Make this view visible. If {@code false} is passed, the view will fade out its content
     * and set the view Visibility to GONE. If only the content should be changed,
     * {@link #setContentVisibleAnimated(boolean)} can be used.
     *
     * @param visible True if the contents should be visible.
     * @param animate True if we should fade to new visibility.
     */
    public void setVisible(boolean visible, boolean animate) {
        if (mIsVisible != visible) {
            mIsVisible = visible;
            if (animate) {
                if (visible) {
                    setVisibility(VISIBLE);
                    setWillBeGone(false);
                    notifyHeightChanged(false /* needsAnimation */);
                } else {
                    setWillBeGone(true);
                }
                setContentVisible(visible, true /* animate */, null /* onAnimationEnded */);
            } else {
                setVisibility(visible ? VISIBLE : GONE);
                setContentVisible(visible, false /* animate */, null /* onAnimationEnded */);
                setWillBeGone(false);
                notifyHeightChanged(false /* needsAnimation */);
            }
        }
    }

    public boolean isContentVisible() {
        return mContentVisible;
    }

    /**
     * Change content visibility to {@code visible}, animated.
     */
    public void setContentVisibleAnimated(boolean visible) {
        setContentVisible(visible, true /* animate */, null /* onAnimationEnded */);
    }

    /**
     * @param visible          True if the contents should be visible.
     * @param animate          True if we should fade to new visibility.
     * @param onAnimationEnded Callback to run after visibility updates, takes a boolean as a
     *                         parameter that represents whether the animation was cancelled.
     */
    public void setContentVisible(boolean visible, boolean animate,
            Consumer<Boolean> onAnimationEnded) {
        if (mContentVisible != visible) {
            mContentAnimating = animate;
            mContentVisible = visible;
            Consumer<Boolean> onAnimationEndedWrapper = (cancelled) -> {
                onContentVisibilityAnimationEnd();
                if (onAnimationEnded != null) {
                    onAnimationEnded.accept(cancelled);
                }
            };
            setViewVisible(mContent, visible, animate, onAnimationEndedWrapper);
        } else if (onAnimationEnded != null) {
            // Execute onAnimationEnded immediately if there's no animation to perform.
            onAnimationEnded.accept(true /* cancelled */);
        }

        if (!mContentAnimating) {
            onContentVisibilityAnimationEnd();
        }
    }

    private void onContentVisibilityAnimationEnd() {
        mContentAnimating = false;
        if (getVisibility() != View.GONE && !mIsVisible) {
            setVisibility(GONE);
            setWillBeGone(false);
            notifyHeightChanged(false /* needsAnimation */);
        }
    }

    protected boolean isSecondaryVisible() {
        return mIsSecondaryVisible;
    }

    /**
     * Set the secondary view of this layout to visible.
     *
     * @param visible          True if the contents should be visible.
     * @param animate          True if we should fade to new visibility.
     * @param onAnimationEnded Callback to run after visibility updates, takes a boolean as a
     *                         parameter that represents whether the animation was cancelled.
     */
    protected void setSecondaryVisible(boolean visible, boolean animate,
            Consumer<Boolean> onAnimationEnded) {
        if (mIsSecondaryVisible != visible) {
            mSecondaryAnimating = animate;
            mIsSecondaryVisible = visible;
            Consumer<Boolean> onAnimationEndedWrapper = (cancelled) -> {
                onContentVisibilityAnimationEnd();
                if (onAnimationEnded != null) {
                    onAnimationEnded.accept(cancelled);
                }
            };
            setViewVisible(mSecondaryView, visible, animate, onAnimationEndedWrapper);
        }

        if (!mSecondaryAnimating) {
            onSecondaryVisibilityAnimationEnd();
        }
    }

    private void onSecondaryVisibilityAnimationEnd() {
        mSecondaryAnimating = false;
        // If we were on screen, become GONE to avoid touches
        if (mSecondaryView == null) return;
        if (getVisibility() != View.GONE
                && mSecondaryView.getVisibility() != View.GONE
                && !mIsSecondaryVisible) {
            mSecondaryView.setVisibility(View.GONE);
        }
    }

    /**
     * Animate a view to a new visibility.
     *
     * @param view             Target view, maybe content view or dismiss view.
     * @param visible          Should it now be visible.
     * @param animate          Should this be done in an animated way.
     * @param onAnimationEnded Callback to run after visibility updates, takes a boolean as a
     *                         parameter that represents whether the animation was cancelled.
     */
    private void setViewVisible(View view, boolean visible,
            boolean animate, Consumer<Boolean> onAnimationEnded) {
        if (view == null) {
            return;
        }

        // Make sure we're visible so animations work
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(View.VISIBLE);
        }

        // cancel any previous animations
        view.animate().cancel();
        float endValue = visible ? 1.0f : 0.0f;
        if (!animate) {
            view.setAlpha(endValue);
            if (onAnimationEnded != null) {
                onAnimationEnded.accept(true);
            }
            return;
        }

        // Animate the view alpha
        Interpolator interpolator = visible ? Interpolators.ALPHA_IN : Interpolators.ALPHA_OUT;
        view.animate()
                .alpha(endValue)
                .setInterpolator(interpolator)
                .setDuration(mAnimationDuration)
                .setListener(new AnimatorListenerAdapter() {
                    boolean mCancelled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        mCancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        onAnimationEnded.accept(mCancelled);
                    }
                });
    }

    @VisibleForTesting
    public void setAnimationDuration(int animationDuration) {
        mAnimationDuration = animationDuration;
    }

    @Override
    public long performRemoveAnimation(long duration, long delay,
            float translationDirection, boolean isHeadsUpAnimation,
            Runnable onStartedRunnable,
            Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener, ClipSide clipSide) {
        // TODO: Use duration
        if (onStartedRunnable != null) {
            onStartedRunnable.run();
        }
        setContentVisible(false, true /* animate */, (cancelled) -> onFinishedRunnable.run());
        return 0;
    }

    @Override
    public void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear) {
        // TODO: use delay and duration
        setContentVisibleAnimated(true);
    }

    @Override
    public void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear,
            Runnable endRunnable) {
        // TODO: use delay and duration
        setContentVisibleAnimated(true);
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
