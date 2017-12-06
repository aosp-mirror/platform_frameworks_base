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
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.FloatProperty;
import android.util.Log;
import android.util.MutableFloat;
import android.util.Property;
import android.view.ViewConfiguration;
import android.view.ViewDebug;
import android.widget.OverScroller;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.views.lowram.TaskStackLowRamLayoutAlgorithm;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.io.PrintWriter;

/* The scrolling logic for a TaskStackView */
public class TaskStackViewScroller {

    private static final String TAG = "TaskStackViewScroller";
    private static final boolean DEBUG = false;

    public interface TaskStackViewScrollerCallbacks {
        void onStackScrollChanged(float prevScroll, float curScroll, AnimationProps animation);
    }

    /**
     * A Property wrapper around the <code>stackScroll</code> functionality handled by the
     * {@link #setStackScroll(float)} and
     * {@link #getStackScroll()} methods.
     */
    private static final Property<TaskStackViewScroller, Float> STACK_SCROLL =
            new FloatProperty<TaskStackViewScroller>("stackScroll") {
                @Override
                public void setValue(TaskStackViewScroller object, float value) {
                    object.setStackScroll(value);
                }

                @Override
                public Float get(TaskStackViewScroller object) {
                    return object.getStackScroll();
                }
            };

    Context mContext;
    TaskStackLayoutAlgorithm mLayoutAlgorithm;
    TaskStackViewScrollerCallbacks mCb;

    @ViewDebug.ExportedProperty(category="recents")
    float mStackScrollP;
    @ViewDebug.ExportedProperty(category="recents")
    float mLastDeltaP = 0f;
    float mFlingDownScrollP;
    int mFlingDownY;

    OverScroller mScroller;
    ObjectAnimator mScrollAnimator;
    float mFinalAnimatedScroll;

    final FlingAnimationUtils mFlingAnimationUtils;

    public TaskStackViewScroller(Context context, TaskStackViewScrollerCallbacks cb,
            TaskStackLayoutAlgorithm layoutAlgorithm) {
        mContext = context;
        mCb = cb;
        mScroller = new OverScroller(context);
        if (Recents.getConfiguration().isLowRamDevice) {
            mScroller.setFriction(0.06f);
        }
        mLayoutAlgorithm = layoutAlgorithm;
        mFlingAnimationUtils = new FlingAnimationUtils(context, 0.3f);
    }

    /** Resets the task scroller. */
    void reset() {
        mStackScrollP = 0f;
        mLastDeltaP = 0f;
    }

    void resetDeltaScroll() {
        mLastDeltaP = 0f;
    }

    /** Gets the current stack scroll */
    public float getStackScroll() {
        return mStackScrollP;
    }

    /**
     * Sets the current stack scroll immediately.
     */
    public void setStackScroll(float s) {
        setStackScroll(s, AnimationProps.IMMEDIATE);
    }

    /**
     * Sets the current stack scroll immediately, and returns the difference between the target
     * scroll and the actual scroll after accounting for the effect on the focus state.
     */
    public float setDeltaStackScroll(float downP, float deltaP) {
        float targetScroll = downP + deltaP;
        float newScroll = mLayoutAlgorithm.updateFocusStateOnScroll(downP + mLastDeltaP, targetScroll,
                mStackScrollP);
        setStackScroll(newScroll, AnimationProps.IMMEDIATE);
        mLastDeltaP = deltaP;
        return newScroll - targetScroll;
    }

    /**
     * Sets the current stack scroll, but indicates to the callback the preferred animation to
     * update to this new scroll.
     */
    public void setStackScroll(float newScroll, AnimationProps animation) {
        float prevScroll = mStackScrollP;
        mStackScrollP = newScroll;
        if (mCb != null) {
            mCb.onStackScrollChanged(prevScroll, mStackScrollP, animation);
        }
    }

    /**
     * Sets the current stack scroll to the initial state when you first enter recents.
     * @return whether the stack progress changed.
     */
    public boolean setStackScrollToInitialState() {
        float prevScroll = mStackScrollP;
        setStackScroll(mLayoutAlgorithm.mInitialScrollP);
        return Float.compare(prevScroll, mStackScrollP) != 0;
    }

    /**
     * Starts a fling that is coordinated with the {@link TaskStackViewTouchHandler}.
     */
    public void fling(float downScrollP, int downY, int y, int velY, int minY, int maxY,
            int overscroll) {
        if (DEBUG) {
            Log.d(TAG, "fling: " + downScrollP + ", downY: " + downY + ", y: " + y +
                    ", velY: " + velY + ", minY: " + minY + ", maxY: " + maxY);
        }
        mFlingDownScrollP = downScrollP;
        mFlingDownY = downY;
        mScroller.fling(0, y, 0, velY, 0, 0, minY, maxY, 0, overscroll);
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
        return Utilities.clamp(scroll, mLayoutAlgorithm.mMinScrollP, mLayoutAlgorithm.mMaxScrollP);
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

    /**
     * Scrolls the closest task and snaps into place. Only used in recents for low ram devices.
     * @param velocity of scroll
     */
    void scrollToClosestTask(int velocity) {
        float stackScroll = getStackScroll();

        // Skip if not in low ram layout and if the scroll is out of min and max bounds
        if (!Recents.getConfiguration().isLowRamDevice || stackScroll < mLayoutAlgorithm.mMinScrollP
                || stackScroll > mLayoutAlgorithm.mMaxScrollP) {
            return;
        }
        TaskStackLowRamLayoutAlgorithm algorithm = mLayoutAlgorithm.mTaskStackLowRamLayoutAlgorithm;

        float flingThreshold = ViewConfiguration.get(mContext).getScaledMinimumFlingVelocity();
        if (Math.abs(velocity) > flingThreshold) {
            int minY = algorithm.percentageToScroll(mLayoutAlgorithm.mMinScrollP);
            int maxY = algorithm.percentageToScroll(mLayoutAlgorithm.mMaxScrollP);

            // Calculate the fling and snap to closest task from final y position, computeScroll()
            // never runs when cancelled with animateScroll() and the overscroll is not calculated
            // here
            fling(0 /* downScrollP */, 0 /* downY */, algorithm.percentageToScroll(stackScroll),
                    -velocity, minY, maxY, 0 /* overscroll */);
            float pos = algorithm.scrollToPercentage(mScroller.getFinalY());

            float newScrollP = algorithm.getClosestTaskP(pos, mLayoutAlgorithm.mNumStackTasks,
                    velocity);
            ValueAnimator animator = ObjectAnimator.ofFloat(stackScroll, newScrollP);
            mFlingAnimationUtils.apply(animator, algorithm.percentageToScroll(stackScroll),
                    algorithm.percentageToScroll(newScrollP), velocity);
            animateScroll(newScrollP, (int) animator.getDuration(), animator.getInterpolator(),
                    null /* postRunnable */);
        } else {
            float newScrollP = algorithm.getClosestTaskP(stackScroll,
                    mLayoutAlgorithm.mNumStackTasks, velocity);
            animateScroll(newScrollP, 300, Interpolators.ACCELERATE_DECELERATE,
                    null /* postRunnable */);
        }
    }

    /** Animates the stack scroll into bounds */
    ObjectAnimator animateBoundScroll() {
        // TODO: Take duration for snap back
        float curScroll = getStackScroll();
        float newScroll = getBoundedStackScroll(curScroll);
        if (Float.compare(newScroll, curScroll) != 0) {
            // Start a new scroll animation
            animateScroll(newScroll, null /* postScrollRunnable */);
        }
        return mScrollAnimator;
    }

    /** Animates the stack scroll */
    void animateScroll(float newScroll, final Runnable postRunnable) {
        int duration = mContext.getResources().getInteger(
                R.integer.recents_animate_task_stack_scroll_duration);
        animateScroll(newScroll, duration, postRunnable);
    }

    /** Animates the stack scroll */
    void animateScroll(float newScroll, int duration, final Runnable postRunnable) {
        animateScroll(newScroll, duration, Interpolators.LINEAR_OUT_SLOW_IN, postRunnable);
    }

    /** Animates the stack scroll with time interpolator */
    void animateScroll(float newScroll, int duration, TimeInterpolator interpolator,
            final Runnable postRunnable) {
        ObjectAnimator an = ObjectAnimator.ofFloat(this, STACK_SCROLL, getStackScroll(), newScroll);
        an.setDuration(duration);
        an.setInterpolator(interpolator);
        animateScroll(newScroll, an, postRunnable);
    }

    /** Animates the stack scroll with animator */
    private void animateScroll(float newScroll, ObjectAnimator animator,
            final Runnable postRunnable) {
        // Finish any current scrolling animations
        if (mScrollAnimator != null && mScrollAnimator.isRunning()) {
            setStackScroll(mFinalAnimatedScroll);
            mScroller.forceFinished(true);
        }
        stopScroller();
        stopBoundScrollAnimation();

        if (Float.compare(mStackScrollP, newScroll) != 0) {
            mFinalAnimatedScroll = newScroll;
            mScrollAnimator = animator;
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
        } else {
            if (postRunnable != null) {
                postRunnable.run();
            }
        }
    }

    /** Aborts any current stack scrolls */
    void stopBoundScrollAnimation() {
        Utilities.cancelAnimationWithoutCallbacks(mScrollAnimator);
    }

    /**** OverScroller ****/

    /** Called from the view draw, computes the next scroll. */
    boolean computeScroll() {
        if (mScroller.computeScrollOffset()) {
            float deltaP = mLayoutAlgorithm.getDeltaPForY(mFlingDownY, mScroller.getCurrY());
            mFlingDownScrollP += setDeltaStackScroll(mFlingDownScrollP, deltaP);
            if (DEBUG) {
                Log.d(TAG, "computeScroll: " + (mFlingDownScrollP + deltaP));
            }
            return true;
        }
        return false;
    }

    /** Returns whether the overscroller is scrolling. */
    boolean isScrolling() {
        return !mScroller.isFinished();
    }

    float getScrollVelocity() {
        return mScroller.getCurrVelocity();
    }

    /** Stops the scroller and any current fling. */
    void stopScroller() {
        if (!mScroller.isFinished()) {
            mScroller.abortAnimation();
        }
    }

    public void dump(String prefix, PrintWriter writer) {
        writer.print(prefix); writer.print(TAG);
        writer.print(" stackScroll:"); writer.print(mStackScrollP);
        writer.println();
    }
}