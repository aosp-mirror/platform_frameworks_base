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

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsConfiguration;

import java.util.List;

/* Handles touch events for a TaskStackView. */
class TaskStackViewTouchHandler implements SwipeHelper.Callback {
    static int INACTIVE_POINTER_ID = -1;

    RecentsConfiguration mConfig;
    TaskStackView mSv;
    TaskStackViewScroller mScroller;
    VelocityTracker mVelocityTracker;

    boolean mIsScrolling;

    float mInitialP;
    float mLastP;
    float mTotalPMotion;
    int mInitialMotionX, mInitialMotionY;
    int mLastMotionX, mLastMotionY;
    int mActivePointerId = INACTIVE_POINTER_ID;
    TaskView mActiveTaskView = null;

    int mMinimumVelocity;
    int mMaximumVelocity;
    // The scroll touch slop is used to calculate when we start scrolling
    int mScrollTouchSlop;
    // The page touch slop is used to calculate when we start swiping
    float mPagingTouchSlop;
    // Used to calculate when a tap is outside a task view rectangle.
    final int mWindowTouchSlop;

    SwipeHelper mSwipeHelper;
    boolean mInterceptedBySwipeHelper;

    public TaskStackViewTouchHandler(Context context, TaskStackView sv,
            RecentsConfiguration config, TaskStackViewScroller scroller) {
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mScrollTouchSlop = configuration.getScaledTouchSlop();
        mPagingTouchSlop = configuration.getScaledPagingTouchSlop();
        mWindowTouchSlop = configuration.getScaledWindowTouchSlop();
        mSv = sv;
        mScroller = scroller;
        mConfig = config;

        float densityScale = context.getResources().getDisplayMetrics().density;
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, densityScale, mPagingTouchSlop);
        mSwipeHelper.setMinAlpha(1f);
    }

    /** Velocity tracker helpers */
    void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }
    void initVelocityTrackerIfNotExists() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }
    void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /** Returns the view at the specified coordinates */
    TaskView findViewAtPoint(int x, int y) {
        List<TaskView> taskViews = mSv.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            if (tv.getVisibility() == View.VISIBLE) {
                if (mSv.isTransformedTouchPointInView(x, y, tv)) {
                    return tv;
                }
            }
        }
        return null;
    }

    /** Constructs a simulated motion event for the current stack scroll. */
    MotionEvent createMotionEventForStackScroll(MotionEvent ev) {
        MotionEvent pev = MotionEvent.obtainNoHistory(ev);
        pev.setLocation(0, mScroller.progressToScrollRange(mScroller.getStackScroll()));
        return pev;
    }

    /** Touch preprocessing for handling below */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Return early if we have no children
        boolean hasTaskViews = (mSv.getTaskViews().size() > 0);
        if (!hasTaskViews) {
            return false;
        }

        int action = ev.getAction();
        if (mConfig.multiStackEnabled) {
            // Check if we are within the bounds of the stack view contents
            if ((action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                if (!mSv.getTouchableRegion().contains((int) ev.getX(), (int) ev.getY())) {
                    return false;
                }
            }
        }

        // Pass through to swipe helper if we are swiping
        mInterceptedBySwipeHelper = mSwipeHelper.onInterceptTouchEvent(ev);
        if (mInterceptedBySwipeHelper) {
            return true;
        }

        boolean wasScrolling = mScroller.isScrolling() ||
                (mScroller.mScrollAnimator != null && mScroller.mScrollAnimator.isRunning());
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                // Save the touch down info
                mInitialMotionX = mLastMotionX = (int) ev.getX();
                mInitialMotionY = mLastMotionY = (int) ev.getY();
                mInitialP = mLastP = mSv.mLayoutAlgorithm.screenYToCurveProgress(mLastMotionY);
                mActivePointerId = ev.getPointerId(0);
                mActiveTaskView = findViewAtPoint(mLastMotionX, mLastMotionY);
                // Stop the current scroll if it is still flinging
                mScroller.stopScroller();
                mScroller.stopBoundScrollAnimation();
                // Initialize the velocity tracker
                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(createMotionEventForStackScroll(ev));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INACTIVE_POINTER_ID) break;

                // Initialize the velocity tracker if necessary
                initVelocityTrackerIfNotExists();
                mVelocityTracker.addMovement(createMotionEventForStackScroll(ev));

                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int y = (int) ev.getY(activePointerIndex);
                int x = (int) ev.getX(activePointerIndex);
                if (Math.abs(y - mInitialMotionY) > mScrollTouchSlop) {
                    // Save the touch move info
                    mIsScrolling = true;
                    // Disallow parents from intercepting touch events
                    final ViewParent parent = mSv.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }

                mLastMotionX = x;
                mLastMotionY = y;
                mLastP = mSv.mLayoutAlgorithm.screenYToCurveProgress(mLastMotionY);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                // Animate the scroll back if we've cancelled
                mScroller.animateBoundScroll();
                // Reset the drag state and the velocity tracker
                mIsScrolling = false;
                mActivePointerId = INACTIVE_POINTER_ID;
                mActiveTaskView = null;
                mTotalPMotion = 0;
                recycleVelocityTracker();
                break;
            }
        }

        return wasScrolling || mIsScrolling;
    }

    /** Handles touch events once we have intercepted them */
    public boolean onTouchEvent(MotionEvent ev) {
        // Short circuit if we have no children
        boolean hasTaskViews = (mSv.getTaskViews().size() > 0);
        if (!hasTaskViews) {
            return false;
        }

        int action = ev.getAction();
        if (mConfig.multiStackEnabled) {
            // Check if we are within the bounds of the stack view contents
            if ((action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                if (!mSv.getTouchableRegion().contains((int) ev.getX(), (int) ev.getY())) {
                    return false;
                }
            }
        }

        // Pass through to swipe helper if we are swiping
        if (mInterceptedBySwipeHelper && mSwipeHelper.onTouchEvent(ev)) {
            return true;
        }

        // Update the velocity tracker
        initVelocityTrackerIfNotExists();

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                // Save the touch down info
                mInitialMotionX = mLastMotionX = (int) ev.getX();
                mInitialMotionY = mLastMotionY = (int) ev.getY();
                mInitialP = mLastP = mSv.mLayoutAlgorithm.screenYToCurveProgress(mLastMotionY);
                mActivePointerId = ev.getPointerId(0);
                mActiveTaskView = findViewAtPoint(mLastMotionX, mLastMotionY);
                // Stop the current scroll if it is still flinging
                mScroller.stopScroller();
                mScroller.stopBoundScrollAnimation();
                // Initialize the velocity tracker
                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(createMotionEventForStackScroll(ev));
                // Disallow parents from intercepting touch events
                final ViewParent parent = mSv.getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int index = ev.getActionIndex();
                mActivePointerId = ev.getPointerId(index);
                mLastMotionX = (int) ev.getX(index);
                mLastMotionY = (int) ev.getY(index);
                mLastP = mSv.mLayoutAlgorithm.screenYToCurveProgress(mLastMotionY);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INACTIVE_POINTER_ID) break;

                mVelocityTracker.addMovement(createMotionEventForStackScroll(ev));

                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int x = (int) ev.getX(activePointerIndex);
                int y = (int) ev.getY(activePointerIndex);
                int yTotal = Math.abs(y - mInitialMotionY);
                float curP = mSv.mLayoutAlgorithm.screenYToCurveProgress(y);
                float deltaP = mLastP - curP;
                if (!mIsScrolling) {
                    if (yTotal > mScrollTouchSlop) {
                        mIsScrolling = true;
                        // Disallow parents from intercepting touch events
                        final ViewParent parent = mSv.getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (mIsScrolling) {
                    float curStackScroll = mScroller.getStackScroll();
                    float overScrollAmount = mScroller.getScrollAmountOutOfBounds(curStackScroll + deltaP);
                    if (Float.compare(overScrollAmount, 0f) != 0) {
                        // Bound the overscroll to a fixed amount, and inversely scale the y-movement
                        // relative to how close we are to the max overscroll
                        float maxOverScroll = mConfig.taskStackOverscrollPct;
                        deltaP *= (1f - (Math.min(maxOverScroll, overScrollAmount)
                                / maxOverScroll));
                    }
                    mScroller.setStackScroll(curStackScroll + deltaP);
                }
                mLastMotionX = x;
                mLastMotionY = y;
                mLastP = mSv.mLayoutAlgorithm.screenYToCurveProgress(mLastMotionY);
                mTotalPMotion += Math.abs(deltaP);
                break;
            }
            case MotionEvent.ACTION_UP: {
                mVelocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                int velocity = (int) mVelocityTracker.getYVelocity(mActivePointerId);
                if (mIsScrolling && (Math.abs(velocity) > mMinimumVelocity)) {
                    float overscrollRangePct = Math.abs((float) velocity / mMaximumVelocity);
                    int overscrollRange = (int) (Math.min(1f, overscrollRangePct) *
                            (Constants.Values.TaskStackView.TaskStackMaxOverscrollRange -
                                    Constants.Values.TaskStackView.TaskStackMinOverscrollRange));
                    mScroller.mScroller.fling(0,
                            mScroller.progressToScrollRange(mScroller.getStackScroll()),
                            0, velocity,
                            0, 0,
                            mScroller.progressToScrollRange(mSv.mLayoutAlgorithm.mMinScrollP),
                            mScroller.progressToScrollRange(mSv.mLayoutAlgorithm.mMaxScrollP),
                            0, Constants.Values.TaskStackView.TaskStackMinOverscrollRange +
                                    overscrollRange);
                    // Invalidate to kick off computeScroll
                    mSv.invalidate();
                } else if (mIsScrolling && mScroller.isScrollOutOfBounds()) {
                    // Animate the scroll back into bounds
                    mScroller.animateBoundScroll();
                } else if (mActiveTaskView == null) {
                    // This tap didn't start on a task.
                    maybeHideRecentsFromBackgroundTap((int) ev.getX(), (int) ev.getY());
                }

                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                mTotalPMotion = 0;
                recycleVelocityTracker();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndex);
                if (pointerId == mActivePointerId) {
                    // Select a new active pointer id and reset the motion state
                    final int newPointerIndex = (pointerIndex == 0) ? 1 : 0;
                    mActivePointerId = ev.getPointerId(newPointerIndex);
                    mLastMotionX = (int) ev.getX(newPointerIndex);
                    mLastMotionY = (int) ev.getY(newPointerIndex);
                    mLastP = mSv.mLayoutAlgorithm.screenYToCurveProgress(mLastMotionY);
                    mVelocityTracker.clear();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (mScroller.isScrollOutOfBounds()) {
                    // Animate the scroll back into bounds
                    mScroller.animateBoundScroll();
                }
                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                mTotalPMotion = 0;
                recycleVelocityTracker();
                break;
            }
        }
        return true;
    }

    /** Hides recents if the up event at (x, y) is a tap on the background area. */
    void maybeHideRecentsFromBackgroundTap(int x, int y) {
        // Ignore the up event if it's too far from its start position. The user might have been
        // trying to scroll or swipe.
        int dx = Math.abs(mInitialMotionX - x);
        int dy = Math.abs(mInitialMotionY - y);
        if (dx > mScrollTouchSlop || dy > mScrollTouchSlop) {
            return;
        }

        // Shift the tap position toward the center of the task stack and check to see if it would
        // have hit a view. The user might have tried to tap on a task and missed slightly.
        int shiftedX = x;
        if (x > mSv.getTouchableRegion().centerX()) {
            shiftedX -= mWindowTouchSlop;
        } else {
            shiftedX += mWindowTouchSlop;
        }
        if (findViewAtPoint(shiftedX, y) != null) {
            return;
        }

        // The user intentionally tapped on the background, which is like a tap on the "desktop".
        // Hide recents and transition to the launcher.
        Recents recents = Recents.getInstanceAndStartIfNeeded(mSv.getContext());
        recents.hideRecents(false /* altTab */, true /* homeKey */);
    }

    /** Handles generic motion events */
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & InputDevice.SOURCE_CLASS_POINTER) ==
                InputDevice.SOURCE_CLASS_POINTER) {
            int action = ev.getAction();
            switch (action & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_SCROLL:
                    // Find the front most task and scroll the next task to the front
                    float vScroll = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (vScroll > 0) {
                        if (mSv.ensureFocusedTask(true)) {
                            mSv.focusNextTask(true, false);
                        }
                    } else {
                        if (mSv.ensureFocusedTask(true)) {
                            mSv.focusNextTask(false, false);
                        }
                    }
                    return true;
            }
        }
        return false;
    }

    /**** SwipeHelper Implementation ****/

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        return findViewAtPoint((int) ev.getX(), (int) ev.getY());
    }

    @Override
    public boolean canChildBeDismissed(View v) {
        return true;
    }

    @Override
    public void onBeginDrag(View v) {
        TaskView tv = (TaskView) v;
        // Disable clipping with the stack while we are swiping
        tv.setClipViewInStack(false);
        // Disallow touch events from this task view
        tv.setTouchEnabled(false);
        // Disallow parents from intercepting touch events
        final ViewParent parent = mSv.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        // Fade out the dismiss button
        mSv.hideDismissAllButton(null);
    }

    @Override
    public void onSwipeChanged(View v, float delta) {
        // Do nothing
    }

    @Override
    public void onChildDismissed(View v) {
        TaskView tv = (TaskView) v;
        // Re-enable clipping with the stack (we will reuse this view)
        tv.setClipViewInStack(true);
        // Re-enable touch events from this task view
        tv.setTouchEnabled(true);
        // Remove the task view from the stack
        mSv.onTaskViewDismissed(tv);
        // Keep track of deletions by keyboard
        MetricsLogger.histogram(tv.getContext(), "overview_task_dismissed_source",
                Constants.Metrics.DismissSourceSwipeGesture);
    }

    @Override
    public void onSnapBackCompleted(View v) {
        TaskView tv = (TaskView) v;
        // Re-enable clipping with the stack
        tv.setClipViewInStack(true);
        // Re-enable touch events from this task view
        tv.setTouchEnabled(true);
        // Restore the dismiss button
        mSv.showDismissAllButton();
    }

    @Override
    public void onDragCancelled(View v) {
        // Do nothing
    }
}
