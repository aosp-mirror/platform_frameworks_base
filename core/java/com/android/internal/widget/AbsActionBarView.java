/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * limitations under the License.
 */
package com.android.internal.widget;

import com.android.internal.R;
import com.android.internal.view.menu.ActionMenuPresenter;
import com.android.internal.view.menu.ActionMenuView;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

public abstract class AbsActionBarView extends ViewGroup {
    protected ActionMenuView mMenuView;
    protected ActionMenuPresenter mActionMenuPresenter;
    protected ActionBarContainer mSplitView;
    protected boolean mSplitActionBar;
    protected boolean mSplitWhenNarrow;
    protected int mContentHeight;

    protected Animator mVisibilityAnim;
    protected final VisibilityAnimListener mVisAnimListener = new VisibilityAnimListener();

    private static final TimeInterpolator sAlphaInterpolator = new DecelerateInterpolator();

    private static final int FADE_DURATION = 200;

    public AbsActionBarView(Context context) {
        super(context);
    }

    public AbsActionBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AbsActionBarView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Action bar can change size on configuration changes.
        // Reread the desired height from the theme-specified style.
        TypedArray a = getContext().obtainStyledAttributes(null, R.styleable.ActionBar,
                com.android.internal.R.attr.actionBarStyle, 0);
        setContentHeight(a.getLayoutDimension(R.styleable.ActionBar_height, 0));
        a.recycle();
        if (mSplitWhenNarrow) {
            setSplitActionBar(getContext().getResources().getBoolean(
                    com.android.internal.R.bool.split_action_bar_is_narrow));
        }
        if (mActionMenuPresenter != null) {
            mActionMenuPresenter.onConfigurationChanged(newConfig);
        }
    }

    /**
     * Sets whether the bar should be split right now, no questions asked.
     * @param split true if the bar should split
     */
    public void setSplitActionBar(boolean split) {
        mSplitActionBar = split;
    }

    /**
     * Sets whether the bar should split if we enter a narrow screen configuration.
     * @param splitWhenNarrow true if the bar should check to split after a config change
     */
    public void setSplitWhenNarrow(boolean splitWhenNarrow) {
        mSplitWhenNarrow = splitWhenNarrow;
    }

    public void setContentHeight(int height) {
        mContentHeight = height;
        requestLayout();
    }

    public int getContentHeight() {
        return mContentHeight;
    }

    public void setSplitView(ActionBarContainer splitView) {
        mSplitView = splitView;
    }

    /**
     * @return Current visibility or if animating, the visibility being animated to.
     */
    public int getAnimatedVisibility() {
        if (mVisibilityAnim != null) {
            return mVisAnimListener.mFinalVisibility;
        }
        return getVisibility();
    }

    public void animateToVisibility(int visibility) {
        if (mVisibilityAnim != null) {
            mVisibilityAnim.cancel();
        }
        if (visibility == VISIBLE) {
            if (getVisibility() != VISIBLE) {
                setAlpha(0);
                if (mSplitView != null && mMenuView != null) {
                    mMenuView.setAlpha(0);
                }
            }
            ObjectAnimator anim = ObjectAnimator.ofFloat(this, "alpha", 1);
            anim.setDuration(FADE_DURATION);
            anim.setInterpolator(sAlphaInterpolator);
            if (mSplitView != null && mMenuView != null) {
                AnimatorSet set = new AnimatorSet();
                ObjectAnimator splitAnim = ObjectAnimator.ofFloat(mMenuView, "alpha", 1);
                splitAnim.setDuration(FADE_DURATION);
                set.addListener(mVisAnimListener.withFinalVisibility(visibility));
                set.play(anim).with(splitAnim);
                set.start();
            } else {
                anim.addListener(mVisAnimListener.withFinalVisibility(visibility));
                anim.start();
            }
        } else {
            ObjectAnimator anim = ObjectAnimator.ofFloat(this, "alpha", 0);
            anim.setDuration(FADE_DURATION);
            anim.setInterpolator(sAlphaInterpolator);
            if (mSplitView != null && mMenuView != null) {
                AnimatorSet set = new AnimatorSet();
                ObjectAnimator splitAnim = ObjectAnimator.ofFloat(mMenuView, "alpha", 0);
                splitAnim.setDuration(FADE_DURATION);
                set.addListener(mVisAnimListener.withFinalVisibility(visibility));
                set.play(anim).with(splitAnim);
                set.start();
            } else {
                anim.addListener(mVisAnimListener.withFinalVisibility(visibility));
                anim.start();
            }
        }
    }

    @Override
    public void setVisibility(int visibility) {
        if (visibility != getVisibility()) {
            if (mVisibilityAnim != null) {
                mVisibilityAnim.end();
            }
            super.setVisibility(visibility);
        }
    }

    public boolean showOverflowMenu() {
        if (mActionMenuPresenter != null) {
            return mActionMenuPresenter.showOverflowMenu();
        }
        return false;
    }

    public void postShowOverflowMenu() {
        post(new Runnable() {
            public void run() {
                showOverflowMenu();
            }
        });
    }

    public boolean hideOverflowMenu() {
        if (mActionMenuPresenter != null) {
            return mActionMenuPresenter.hideOverflowMenu();
        }
        return false;
    }

    public boolean isOverflowMenuShowing() {
        if (mActionMenuPresenter != null) {
            return mActionMenuPresenter.isOverflowMenuShowing();
        }
        return false;
    }

    public boolean isOverflowReserved() {
        return mActionMenuPresenter != null && mActionMenuPresenter.isOverflowReserved();
    }

    public void dismissPopupMenus() {
        if (mActionMenuPresenter != null) {
            mActionMenuPresenter.dismissPopupMenus();
        }
    }

    protected int measureChildView(View child, int availableWidth, int childSpecHeight,
            int spacing) {
        child.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                childSpecHeight);

        availableWidth -= child.getMeasuredWidth();
        availableWidth -= spacing;

        return Math.max(0, availableWidth);
    }

    protected int positionChild(View child, int x, int y, int contentHeight) {
        int childWidth = child.getMeasuredWidth();
        int childHeight = child.getMeasuredHeight();
        int childTop = y + (contentHeight - childHeight) / 2;

        child.layout(x, childTop, x + childWidth, childTop + childHeight);

        return childWidth;
    }

    protected int positionChildInverse(View child, int x, int y, int contentHeight) {
        int childWidth = child.getMeasuredWidth();
        int childHeight = child.getMeasuredHeight();
        int childTop = y + (contentHeight - childHeight) / 2;

        child.layout(x - childWidth, childTop, x, childTop + childHeight);

        return childWidth;
    }

    protected class VisibilityAnimListener implements Animator.AnimatorListener {
        private boolean mCanceled = false;
        int mFinalVisibility;

        public VisibilityAnimListener withFinalVisibility(int visibility) {
            mFinalVisibility = visibility;
            return this;
        }

        @Override
        public void onAnimationStart(Animator animation) {
            setVisibility(VISIBLE);
            mVisibilityAnim = animation;
            mCanceled = false;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (mCanceled) return;

            mVisibilityAnim = null;
            setVisibility(mFinalVisibility);
            if (mSplitView != null && mMenuView != null) {
                mMenuView.setVisibility(mFinalVisibility);
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mCanceled = true;
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }
    }
}
