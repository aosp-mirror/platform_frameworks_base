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
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.widget.OverScroller;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.Utilities;

/* The scrolling logic for a TaskStackView */
public class TaskStackViewScroller {
    public interface TaskStackViewScrollerCallbacks {
        public void onScrollChanged(float p);
    }

    RecentsConfiguration mConfig;
    TaskStackViewLayoutAlgorithm mLayoutAlgorithm;
    TaskStackViewScrollerCallbacks mCb;

    float mStackScrollP;

    OverScroller mScroller;
    ObjectAnimator mScrollAnimator;
    float mFinalAnimatedScroll;

    public TaskStackViewScroller(Context context, RecentsConfiguration config, TaskStackViewLayoutAlgorithm layoutAlgorithm) {
        mConfig = config;
        mScroller = new OverScroller(context);
        mLayoutAlgorithm = layoutAlgorithm;
        setStackScroll(getStackScroll());
    }

    /** Resets the task scroller. */
    void reset() {
        mStackScrollP = 0f;
    }

    /** Sets the callbacks */
    void setCallbacks(TaskStackViewScrollerCallbacks cb) {
        mCb = cb;
    }

    /** Gets the current stack scroll */
    public float getStackScroll() {
        return mStackScrollP;
    }

    /** Sets the current stack scroll */
    public void setStackScroll(float s) {
        mStackScrollP = s;
        if (mCb != null) {
            mCb.onScrollChanged(mStackScrollP);
        }
    }

    /** Sets the current stack scroll without calling the callback. */
    void setStackScrollRaw(float s) {
        mStackScrollP = s;
    }

    /**
     * Sets the current stack scroll to the initial state when you first enter recents.
     * @return whether the stack progress changed.
     */
    public boolean setStackScrollToInitialState() {
        float prevStackScrollP = mStackScrollP;
        setStackScroll(getBoundedStackScroll(mLayoutAlgorithm.mInitialScrollP));
        return Float.compare(prevStackScrollP, mStackScrollP) != 0;
    }

    /** Bounds the current scroll if necessary */
    public boolean boundScroll() {
        float curScroll = getStackScroll();
        float newScroll = getBoundedStackScroll(curScroll);
        if (Float.compare(newScroll, curScroll) != 0) {
            setStackScroll(newScroll);
            return true;
        }
        return false;
    }

    /** Returns the bounded stack scroll */
    float getBoundedStackScroll(float scroll) {
        return Math.max(mLayoutAlgorithm.mMinScrollP, Math.min(mLayoutAlgorithm.mMaxScrollP, scroll));
    }

    /** Returns the amount that the absolute value of how much the scroll is out of bounds. */
    float getScrollAmountOutOfBounds(float scroll) {
        if (scroll < mLayoutAlgorithm.mMinScrollP) {
            return Math.abs(scroll - mLayoutAlgorithm.mMinScrollP);
        } else if (scroll > mLayoutAlgorithm.mMaxScrollP) {
            return Math.abs(scroll - mLayoutAlgorithm.mMaxScrollP);
        }
        return 0f;
    }

    /** Returns whether the specified scroll is out of bounds */
    boolean isScrollOutOfBounds() {
        return Float.compare(getScrollAmountOutOfBounds(mStackScrollP), 0f) != 0;
    }

    /** Animates the stack scroll into bounds */
    ObjectAnimator animateBoundScroll() {
        float curScroll = getStackScroll();
        float newScroll = getBoundedStackScroll(curScroll);
        if (Float.compare(newScroll, curScroll) != 0) {
            // Start a new scroll animation
            animateScroll(curScroll, newScroll, null);
        }
        return mScrollAnimator;
    }

    /** Animates the stack scroll */
    void animateScroll(float curScroll, float newScroll, final Runnable postRunnable) {
        // Finish any current scrolling animations
        if (mScrollAnimator != null && mScrollAnimator.isRunning()) {
            setStackScroll(mFinalAnimatedScroll);
            mScroller.startScroll(0, progressToScrollRange(mFinalAnimatedScroll), 0, 0, 0);
        }
        stopScroller();
        stopBoundScrollAnimation();

        mFinalAnimatedScroll = newScroll;
        mScrollAnimator = ObjectAnimator.ofFloat(this, "stackScroll", curScroll, newScroll);
        mScrollAnimator.setDuration(mConfig.taskStackScrollDuration);
        mScrollAnimator.setInterpolator(mConfig.linearOutSlowInInterpolator);
        mScrollAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setStackScroll((Float) animation.getAnimatedValue());
            }
        });
        mScrollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (postRunnable != null) {
                    postRunnable.run();
                }
                mScrollAnimator.removeAllListeners();
            }
        });
        mScrollAnimator.start();
    }

    /** Aborts any current stack scrolls */
    void stopBoundScrollAnimation() {
        Utilities.cancelAnimationWithoutCallbacks(mScrollAnimator);
    }

    /**** OverScroller ****/

    int progressToScrollRange(float p) {
        return (int) (p * mLayoutAlgorithm.mStackVisibleRect.height());
    }

    float scrollRangeToProgress(int s) {
        return (float) s / mLayoutAlgorithm.mStackVisibleRect.height();
    }

    /** Called from the view draw, computes the next scroll. */
    boolean computeScroll() {
        if (mScroller.computeScrollOffset()) {
            float scroll = scrollRangeToProgress(mScroller.getCurrY());
            setStackScrollRaw(scroll);
            if (mCb != null) {
                mCb.onScrollChanged(scroll);
            }
            return true;
        }
        return false;
    }

    /** Returns whether the overscroller is scrolling. */
    boolean isScrolling() {
        return !mScroller.isFinished();
    }

    /** Stops the scroller and any current fling. */
    void stopScroller() {
        if (!mScroller.isFinished()) {
            mScroller.abortAnimation();
        }
    }
}