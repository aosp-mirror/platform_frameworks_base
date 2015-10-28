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
import android.content.res.Resources;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.ui.DismissTaskViewEvent;

import java.util.List;

/* Handles touch events for a TaskStackView. */
class TaskStackViewTouchHandler implements SwipeHelper.Callback {

    private static final String TAG = "TaskStackViewTouchHandler";
    private static final boolean DEBUG = true;

    private static int INACTIVE_POINTER_ID = -1;

    Context mContext;
    TaskStackView mSv;
    TaskStackViewScroller mScroller;
    VelocityTracker mVelocityTracker;

    boolean mIsScrolling;
    float mDownScrollP;
    int mDownX, mDownY;
    int mActivePointerId = INACTIVE_POINTER_ID;
    int mOverscrollSize;
    TaskView mActiveTaskView = null;

    int mMinimumVelocity;
    int mMaximumVelocity;
    // The scroll touch slop is used to calculate when we start scrolling
    int mScrollTouchSlop;
    // Used to calculate when a tap is outside a task view rectangle.
    final int mWindowTouchSlop;

    SwipeHelper mSwipeHelper;
    boolean mInterceptedBySwipeHelper;

    public TaskStackViewTouchHandler(Context context, TaskStackView sv,
            TaskStackViewScroller scroller) {
        Resources res = context.getResources();
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mContext = context;
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mScrollTouchSlop = configuration.getScaledTouchSlop();
        mWindowTouchSlop = configuration.getScaledWindowTouchSlop();
        mSv = sv;
        mScroller = scroller;

        float densityScale = res.getDisplayMetrics().density;
        mOverscrollSize = res.getDimensionPixelSize(R.dimen.recents_stack_overscroll);
        mSwipeHelper = new SwipeHelper(context, SwipeHelper.X, this, densityScale,
                configuration.getScaledPagingTouchSlop());
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

    /** Touch preprocessing for handling below */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Pass through to swipe helper if we are swiping
        mInterceptedBySwipeHelper = mSwipeHelper.onInterceptTouchEvent(ev);
        if (mInterceptedBySwipeHelper) {
            return true;
        }

        return handleTouchEvent(ev);
    }

    /** Handles touch events once we have intercepted them */
    public boolean onTouchEvent(MotionEvent ev) {
        // Pass through to swipe helper if we are swiping
        if (mInterceptedBySwipeHelper && mSwipeHelper.onTouchEvent(ev)) {
            return true;
        }

        handleTouchEvent(ev);
        return true;
    }

    private boolean handleTouchEvent(MotionEvent ev) {
        // Short circuit if we have no children
        if (mSv.getTaskViews().size() == 0) {
            return false;
        }

        TaskStackViewLayoutAlgorithm layoutAlgorithm = mSv.mLayoutAlgorithm;
        int action = ev.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                // Save the touch down info
                mDownX = (int) ev.getX();
                mDownY = (int) ev.getY();
                mDownScrollP = mScroller.getStackScroll();
                mActivePointerId = ev.getPointerId(0);
                mActiveTaskView = findViewAtPoint(mDownX, mDownY);

                // Stop the current scroll if it is still flinging
                mScroller.stopScroller();
                mScroller.stopBoundScrollAnimation();

                // Initialize the velocity tracker
                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(ev);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int index = ev.getActionIndex();
                mDownX = (int) ev.getX();
                mDownY = (int) ev.getY();
                mDownScrollP = mScroller.getStackScroll();
                mActivePointerId = ev.getPointerId(index);
                mVelocityTracker.addMovement(ev);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int y = (int) ev.getY(activePointerIndex);
                if (!mIsScrolling) {
                    if (Math.abs(y - mDownY) > mScrollTouchSlop) {
                        mIsScrolling = true;

                        // Disallow parents from intercepting touch events
                        final ViewParent parent = mSv.getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (mIsScrolling) {
                    // If we just move linearly on the screen, then that would map to 1/arclength
                    // of the curve, so just move the scroll proportional to that
                    float deltaP = layoutAlgorithm.getDeltaPForY(mDownY, y);
                    float curScrollP = mDownScrollP + deltaP;
                    mScroller.setStackScroll(curScrollP);
                }

                mVelocityTracker.addMovement(ev);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndex);
                if (pointerId == mActivePointerId) {
                    // Select a new active pointer id and reset the motion state
                    final int newPointerIndex = (pointerIndex == 0) ? 1 : 0;
                    mActivePointerId = ev.getPointerId(newPointerIndex);
                }
                mVelocityTracker.addMovement(ev);
                break;
            }
            case MotionEvent.ACTION_UP: {
                mVelocityTracker.addMovement(ev);
                mVelocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int y = (int) ev.getY(activePointerIndex);
                int velocity = (int) mVelocityTracker.getYVelocity(mActivePointerId);
                if (mIsScrolling) {
                    if (mScroller.isScrollOutOfBounds()) {
                        // Animate the scroll back into bounds
                        mScroller.animateBoundScroll();
                    } else if (Math.abs(velocity) > mMinimumVelocity) {
                        float deltaP = layoutAlgorithm.getDeltaPForY(mDownY, y);
                        float curScrollP = mDownScrollP + deltaP;
                        float downToCurY = mDownY + layoutAlgorithm.getYForDeltaP(mDownScrollP,
                                curScrollP);
                        float downToMinY = mDownY + layoutAlgorithm.getYForDeltaP(mDownScrollP,
                                layoutAlgorithm.mMaxScrollP);
                        float downToMaxY = mDownY + layoutAlgorithm.getYForDeltaP(mDownScrollP,
                                layoutAlgorithm.mMinScrollP);
                        mScroller.fling(mDownScrollP, mDownY, (int) downToCurY, velocity,
                                (int) downToMinY, (int) downToMaxY, mOverscrollSize);
                        mSv.invalidate();
                    }
                } else if (mActiveTaskView == null) {
                    // This tap didn't start on a task.
                    maybeHideRecentsFromBackgroundTap((int) ev.getX(), (int) ev.getY());
                }

                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                recycleVelocityTracker();
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (mScroller.isScrollOutOfBounds()) {
                    // Animate the scroll back into bounds
                    mScroller.animateBoundScroll();
                }
                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                recycleVelocityTracker();
                break;
            }
        }
        return mIsScrolling;
    }

    /** Hides recents if the up event at (x, y) is a tap on the background area. */
    void maybeHideRecentsFromBackgroundTap(int x, int y) {
        // Ignore the up event if it's too far from its start position. The user might have been
        // trying to scroll or swipe.
        int dx = Math.abs(mDownX - x);
        int dy = Math.abs(mDownY - y);
        if (dx > mScrollTouchSlop || dy > mScrollTouchSlop) {
            return;
        }

        // Shift the tap position toward the center of the task stack and check to see if it would
        // have hit a view. The user might have tried to tap on a task and missed slightly.
        int shiftedX = x;
        if (x > (mSv.getRight() - mSv.getLeft()) / 2) {
            shiftedX -= mWindowTouchSlop;
        } else {
            shiftedX += mWindowTouchSlop;
        }
        if (findViewAtPoint(shiftedX, y) != null) {
            return;
        }

        // The user intentionally tapped on the background, which is like a tap on the "desktop".
        // Hide recents and transition to the launcher.
        EventBus.getDefault().send(new HideRecentsEvent(false, true));
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
                        mSv.setRelativeFocusedTask(true, false /* animated */);
                    } else {
                        mSv.setRelativeFocusedTask(false, false /* animated */);
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
        if (v instanceof TaskView) {
            return !((TaskView) v).getTask().isFreeformTask();
        }
        return true;
    }

    @Override
    public void onBeginDrag(View v) {
        TaskView tv = (TaskView) v;
        mSwipeHelper.setSnapBackTranslationX(tv.getTranslationX());
        // Disable clipping with the stack while we are swiping
        tv.setClipViewInStack(false);
        // Disallow touch events from this task view
        tv.setTouchEnabled(false);
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
        // Re-enable clipping with the stack (we will reuse this view)
        tv.setClipViewInStack(true);
        // Re-enable touch events from this task view
        tv.setTouchEnabled(true);
        // Remove the task view from the stack
        EventBus.getDefault().send(new DismissTaskViewEvent(tv.getTask(), tv));
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
    }

    @Override
    public void onDragCancelled(View v) {
        // Do nothing
    }
}
