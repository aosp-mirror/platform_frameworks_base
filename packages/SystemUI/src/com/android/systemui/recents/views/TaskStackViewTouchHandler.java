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
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import com.android.systemui.recents.Console;
import com.android.systemui.recents.Constants;

/* Handles touch events for a TaskStackView. */
class TaskStackViewTouchHandler implements SwipeHelper.Callback {
    static int INACTIVE_POINTER_ID = -1;

    TaskStackView mSv;
    VelocityTracker mVelocityTracker;

    boolean mIsScrolling;

    int mInitialMotionX, mInitialMotionY;
    int mLastMotionX, mLastMotionY;
    int mActivePointerId = INACTIVE_POINTER_ID;
    TaskView mActiveTaskView = null;

    int mTotalScrollMotion;
    int mMinimumVelocity;
    int mMaximumVelocity;
    // The scroll touch slop is used to calculate when we start scrolling
    int mScrollTouchSlop;
    // The page touch slop is used to calculate when we start swiping
    float mPagingTouchSlop;

    SwipeHelper mSwipeHelper;
    boolean mInterceptedBySwipeHelper;

    public TaskStackViewTouchHandler(Context context, TaskStackView sv) {
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mScrollTouchSlop = configuration.getScaledTouchSlop();
        mPagingTouchSlop = configuration.getScaledPagingTouchSlop();
        mSv = sv;


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
        int childCount = mSv.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            TaskView tv = (TaskView) mSv.getChildAt(i);
            if (tv.getVisibility() == View.VISIBLE) {
                if (mSv.isTransformedTouchPointInView(x, y, tv)) {
                    return tv;
                }
            }
        }
        return null;
    }

    /** Touch preprocessing for handling below */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.TouchEvents,
                    "[TaskStackViewTouchHandler|interceptTouchEvent]",
                    Console.motionEventActionToString(ev.getAction()), Console.AnsiBlue);
        }

        // Return early if we have no children
        boolean hasChildren = (mSv.getChildCount() > 0);
        if (!hasChildren) {
            return false;
        }

        // Pass through to swipe helper if we are swiping
        mInterceptedBySwipeHelper = mSwipeHelper.onInterceptTouchEvent(ev);
        if (mInterceptedBySwipeHelper) {
            return true;
        }

        boolean wasScrolling = !mSv.mScroller.isFinished() ||
                (mSv.mScrollAnimator != null && mSv.mScrollAnimator.isRunning());
        int action = ev.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                // Save the touch down info
                mInitialMotionX = mLastMotionX = (int) ev.getX();
                mInitialMotionY = mLastMotionY = (int) ev.getY();
                mActivePointerId = ev.getPointerId(0);
                mActiveTaskView = findViewAtPoint(mLastMotionX, mLastMotionY);
                // Stop the current scroll if it is still flinging
                mSv.abortScroller();
                mSv.abortBoundScrollAnimation();
                // Initialize the velocity tracker
                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(ev);
                // Check if the scroller is finished yet
                mIsScrolling = !mSv.mScroller.isFinished();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INACTIVE_POINTER_ID) break;

                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int y = (int) ev.getY(activePointerIndex);
                int x = (int) ev.getX(activePointerIndex);
                if (Math.abs(y - mInitialMotionY) > mScrollTouchSlop) {
                    // Save the touch move info
                    mIsScrolling = true;
                    // Initialize the velocity tracker if necessary
                    initVelocityTrackerIfNotExists();
                    mVelocityTracker.addMovement(ev);
                    // Disallow parents from intercepting touch events
                    final ViewParent parent = mSv.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    // Enable HW layers
                    mSv.addHwLayersRefCount("stackScroll");
                }

                mLastMotionX = x;
                mLastMotionY = y;
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                // Animate the scroll back if we've cancelled
                mSv.animateBoundScroll();
                // Disable HW layers
                if (mIsScrolling) {
                    mSv.decHwLayersRefCount("stackScroll");
                }
                // Reset the drag state and the velocity tracker
                mIsScrolling = false;
                mActivePointerId = INACTIVE_POINTER_ID;
                mActiveTaskView = null;
                mTotalScrollMotion = 0;
                recycleVelocityTracker();
                break;
            }
        }

        return wasScrolling || mIsScrolling;
    }

    /** Handles touch events once we have intercepted them */
    public boolean onTouchEvent(MotionEvent ev) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.TouchEvents,
                    "[TaskStackViewTouchHandler|touchEvent]",
                    Console.motionEventActionToString(ev.getAction()), Console.AnsiBlue);
        }

        // Short circuit if we have no children
        boolean hasChildren = (mSv.getChildCount() > 0);
        if (!hasChildren) {
            return false;
        }

        // Pass through to swipe helper if we are swiping
        if (mInterceptedBySwipeHelper && mSwipeHelper.onTouchEvent(ev)) {
            return true;
        }

        // Update the velocity tracker
        initVelocityTrackerIfNotExists();
        mVelocityTracker.addMovement(ev);

        int action = ev.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                // Save the touch down info
                mInitialMotionX = mLastMotionX = (int) ev.getX();
                mInitialMotionY = mLastMotionY = (int) ev.getY();
                mActivePointerId = ev.getPointerId(0);
                mActiveTaskView = findViewAtPoint(mLastMotionX, mLastMotionY);
                // Stop the current scroll if it is still flinging
                mSv.abortScroller();
                mSv.abortBoundScrollAnimation();
                // Initialize the velocity tracker
                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(ev);
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
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INACTIVE_POINTER_ID) break;

                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int x = (int) ev.getX(activePointerIndex);
                int y = (int) ev.getY(activePointerIndex);
                int yTotal = Math.abs(y - mInitialMotionY);
                int deltaY = mLastMotionY - y;
                if (!mIsScrolling) {
                    if (yTotal > mScrollTouchSlop) {
                        mIsScrolling = true;
                        // Initialize the velocity tracker
                        initOrResetVelocityTracker();
                        mVelocityTracker.addMovement(ev);
                        // Disallow parents from intercepting touch events
                        final ViewParent parent = mSv.getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                        // Enable HW layers
                        mSv.addHwLayersRefCount("stackScroll");
                    }
                }
                if (mIsScrolling) {
                    int curStackScroll = mSv.getStackScroll();
                    int overScrollAmount = mSv.getScrollAmountOutOfBounds(curStackScroll + deltaY);
                    if (overScrollAmount != 0) {
                        // Bound the overscroll to a fixed amount, and inversely scale the y-movement
                        // relative to how close we are to the max overscroll
                        float maxOverScroll = mSv.mStackAlgorithm.mTaskRect.height() / 3f;
                        deltaY = Math.round(deltaY * (1f - (Math.min(maxOverScroll, overScrollAmount)
                                / maxOverScroll)));
                    }
                    mSv.setStackScroll(curStackScroll + deltaY);
                    if (mSv.isScrollOutOfBounds()) {
                        mVelocityTracker.clear();
                    }
                }
                mLastMotionX = x;
                mLastMotionY = y;
                mTotalScrollMotion += Math.abs(deltaY);
                break;
            }
            case MotionEvent.ACTION_UP: {
                final VelocityTracker velocityTracker = mVelocityTracker;
                velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                int velocity = (int) velocityTracker.getYVelocity(mActivePointerId);

                if (mIsScrolling && (Math.abs(velocity) > mMinimumVelocity)) {
                    // Enable HW layers on the stack
                    mSv.addHwLayersRefCount("flingScroll");
                    // XXX: Make this animation a function of the velocity AND distance
                    int overscrollRange = (int) (Math.min(1f,
                            Math.abs((float) velocity / mMaximumVelocity)) *
                            Constants.Values.TaskStackView.TaskStackOverscrollRange);

                    if (Console.Enabled) {
                        Console.log(Constants.Log.UI.TouchEvents,
                                "[TaskStackViewTouchHandler|fling]",
                                "scroll: " + mSv.getStackScroll() + " velocity: " + velocity +
                                        " maxVelocity: " + mMaximumVelocity +
                                        " overscrollRange: " + overscrollRange,
                                Console.AnsiGreen);
                    }

                    // Fling scroll
                    mSv.mScroller.fling(0, mSv.getStackScroll(),
                            0, -velocity,
                            0, 0,
                            mSv.mMinScroll, mSv.mMaxScroll,
                            0, overscrollRange);
                    // Invalidate to kick off computeScroll
                    mSv.invalidate(mSv.mStackAlgorithm.mStackRect);
                } else if (mSv.isScrollOutOfBounds()) {
                    // Animate the scroll back into bounds
                    // XXX: Make this animation a function of the velocity OR distance
                    mSv.animateBoundScroll();
                }

                if (mIsScrolling) {
                    // Disable HW layers
                    mSv.decHwLayersRefCount("stackScroll");
                }
                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                mTotalScrollMotion = 0;
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
                    mVelocityTracker.clear();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (mIsScrolling) {
                    // Disable HW layers
                    mSv.decHwLayersRefCount("stackScroll");
                }
                if (mSv.isScrollOutOfBounds()) {
                    // Animate the scroll back into bounds
                    // XXX: Make this animation a function of the velocity OR distance
                    mSv.animateBoundScroll();
                }
                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                mTotalScrollMotion = 0;
                recycleVelocityTracker();
                break;
            }
        }
        return true;
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
        // Enable HW layers on that task
        tv.enableHwLayers();
        // Disallow touch events from this task view
        mSv.setTouchOnTaskView(tv, false);
        // Disallow parents from intercepting touch events
        final ViewParent parent = mSv.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    @Override
    public void onSwipeChanged(View v, float delta) {
        // Do nothing
    }

    @Override
    public void onChildDismissed(View v) {
        TaskView tv = (TaskView) v;
        // Disable HW layers on that task
        if (mSv.mHwLayersTrigger.getCount() == 0) {
            tv.disableHwLayers();
        }
        // Re-enable clipping with the stack (we will reuse this view)
        tv.setClipViewInStack(true);
        // Remove the task view from the stack
        mSv.onTaskViewDismissed(tv);
    }

    @Override
    public void onSnapBackCompleted(View v) {
        TaskView tv = (TaskView) v;
        // Disable HW layers on that task
        if (mSv.mHwLayersTrigger.getCount() == 0) {
            tv.disableHwLayers();
        }
        // Re-enable clipping with the stack
        tv.setClipViewInStack(true);
        // Re-enable touch events from this task view
        mSv.setTouchOnTaskView(tv, true);
    }

    @Override
    public void onDragCancelled(View v) {
        // Do nothing
    }
}
