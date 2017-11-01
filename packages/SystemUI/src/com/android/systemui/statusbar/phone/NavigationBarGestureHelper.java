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

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.DividerSnapAlgorithm.SnapTarget;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.tuner.TunerService;

import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_TOP;

/**
 * Class to detect gestures on the navigation bar.
 */
public class NavigationBarGestureHelper extends GestureDetector.SimpleOnGestureListener
        implements TunerService.Tunable, GestureHelper {

    private static final String KEY_DOCK_WINDOW_GESTURE = "overview_nav_bar_gesture";
    /**
     * When dragging from the navigation bar, we drag in recents.
     */
    public static final int DRAG_MODE_NONE = -1;

    /**
     * When dragging from the navigation bar, we drag in recents.
     */
    public static final int DRAG_MODE_RECENTS = 0;

    /**
     * When dragging from the navigation bar, we drag the divider.
     */
    public static final int DRAG_MODE_DIVIDER = 1;

    private RecentsComponent mRecentsComponent;
    private Divider mDivider;
    private Context mContext;
    private NavigationBarView mNavigationBarView;
    private boolean mIsVertical;
    private boolean mIsRTL;

    private final GestureDetector mTaskSwitcherDetector;
    private final int mScrollTouchSlop;
    private final int mMinFlingVelocity;
    private int mTouchDownX;
    private int mTouchDownY;
    private boolean mDownOnRecents;
    private VelocityTracker mVelocityTracker;

    private boolean mDockWindowEnabled;
    private boolean mDockWindowTouchSlopExceeded;
    private int mDragMode;

    public NavigationBarGestureHelper(Context context) {
        mContext = context;
        ViewConfiguration configuration = ViewConfiguration.get(context);
        Resources r = context.getResources();
        mScrollTouchSlop = r.getDimensionPixelSize(R.dimen.navigation_bar_min_swipe_distance);
        mMinFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        mTaskSwitcherDetector = new GestureDetector(context, this);
        Dependency.get(TunerService.class).addTunable(this, KEY_DOCK_WINDOW_GESTURE);
    }

    public void destroy() {
        Dependency.get(TunerService.class).removeTunable(this);
    }

    public void setComponents(RecentsComponent recentsComponent, Divider divider,
            NavigationBarView navigationBarView) {
        mRecentsComponent = recentsComponent;
        mDivider = divider;
        mNavigationBarView = navigationBarView;
    }

    public void setBarState(boolean isVertical, boolean isRTL) {
        mIsVertical = isVertical;
        mIsRTL = isRTL;
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        // If we move more than a fixed amount, then start capturing for the
        // task switcher detector
        mTaskSwitcherDetector.onTouchEvent(event);
        int action = event.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                mTouchDownX = (int) event.getX();
                mTouchDownY = (int) event.getY();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int x = (int) event.getX();
                int y = (int) event.getY();
                int xDiff = Math.abs(x - mTouchDownX);
                int yDiff = Math.abs(y - mTouchDownY);
                boolean exceededTouchSlop = !mIsVertical
                        ? xDiff > mScrollTouchSlop && xDiff > yDiff
                        : yDiff > mScrollTouchSlop && yDiff > xDiff;
                if (exceededTouchSlop) {
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                break;
        }
        return mDockWindowEnabled && interceptDockWindowEvent(event);
    }

    private boolean interceptDockWindowEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleDragActionDownEvent(event);
                break;
            case MotionEvent.ACTION_MOVE:
                return handleDragActionMoveEvent(event);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handleDragActionUpEvent(event);
                break;
        }
        return false;
    }

    private boolean handleDockWindowEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleDragActionDownEvent(event);
                break;
            case MotionEvent.ACTION_MOVE:
                handleDragActionMoveEvent(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handleDragActionUpEvent(event);
                break;
        }
        return true;
    }

    private void handleDragActionDownEvent(MotionEvent event) {
        mVelocityTracker = VelocityTracker.obtain();
        mVelocityTracker.addMovement(event);
        mDockWindowTouchSlopExceeded = false;
        mTouchDownX = (int) event.getX();
        mTouchDownY = (int) event.getY();

        if (mNavigationBarView != null) {
            View recentsButton = mNavigationBarView.getRecentsButton().getCurrentView();
            if (recentsButton != null) {
                mDownOnRecents = mTouchDownX >= recentsButton.getLeft()
                        && mTouchDownX <= recentsButton.getRight()
                        && mTouchDownY >= recentsButton.getTop()
                        && mTouchDownY <= recentsButton.getBottom();
            } else {
                mDownOnRecents = false;
            }
        }
    }

    private boolean handleDragActionMoveEvent(MotionEvent event) {
        mVelocityTracker.addMovement(event);
        int x = (int) event.getX();
        int y = (int) event.getY();
        int xDiff = Math.abs(x - mTouchDownX);
        int yDiff = Math.abs(y - mTouchDownY);
        if (mDivider == null || mRecentsComponent == null) {
            return false;
        }
        if (!mDockWindowTouchSlopExceeded) {
            boolean touchSlopExceeded = !mIsVertical
                    ? yDiff > mScrollTouchSlop && yDiff > xDiff
                    : xDiff > mScrollTouchSlop && xDiff > yDiff;
            if (mDownOnRecents && touchSlopExceeded
                    && mDivider.getView().getWindowManagerProxy().getDockSide() == DOCKED_INVALID) {
                Rect initialBounds = null;
                int dragMode = calculateDragMode();
                int createMode = ActivityManager.DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
                if (dragMode == DRAG_MODE_DIVIDER) {
                    initialBounds = new Rect();
                    mDivider.getView().calculateBoundsForPosition(mIsVertical
                                    ? (int) event.getRawX()
                                    : (int) event.getRawY(),
                            mDivider.getView().isHorizontalDivision()
                                    ? DOCKED_TOP
                                    : DOCKED_LEFT,
                            initialBounds);
                } else if (dragMode == DRAG_MODE_RECENTS && mTouchDownX
                        < mContext.getResources().getDisplayMetrics().widthPixels / 2) {
                    createMode = ActivityManager.DOCKED_STACK_CREATE_MODE_BOTTOM_OR_RIGHT;
                }
                boolean docked = mRecentsComponent.dockTopTask(dragMode, createMode, initialBounds,
                        MetricsEvent.ACTION_WINDOW_DOCK_SWIPE);
                if (docked) {
                    mDragMode = dragMode;
                    if (mDragMode == DRAG_MODE_DIVIDER) {
                        mDivider.getView().startDragging(false /* animate */, true /* touching*/);
                    }
                    mDockWindowTouchSlopExceeded = true;
                    return true;
                }
            }
        } else {
            if (mDragMode == DRAG_MODE_DIVIDER) {
                int position = !mIsVertical ? (int) event.getRawY() : (int) event.getRawX();
                SnapTarget snapTarget = mDivider.getView().getSnapAlgorithm()
                        .calculateSnapTarget(position, 0f /* velocity */, false /* hardDismiss */);
                mDivider.getView().resizeStack(position, snapTarget.position, snapTarget);
            } else if (mDragMode == DRAG_MODE_RECENTS) {
                mRecentsComponent.onDraggingInRecents(event.getRawY());
            }
        }
        return false;
    }

    private void handleDragActionUpEvent(MotionEvent event) {
        mVelocityTracker.addMovement(event);
        mVelocityTracker.computeCurrentVelocity(1000);
        if (mDockWindowTouchSlopExceeded && mDivider != null && mRecentsComponent != null) {
            if (mDragMode == DRAG_MODE_DIVIDER) {
                mDivider.getView().stopDragging(mIsVertical
                                ? (int) event.getRawX()
                                : (int) event.getRawY(),
                        mIsVertical
                                ? mVelocityTracker.getXVelocity()
                                : mVelocityTracker.getYVelocity(),
                        true /* avoidDismissStart */, false /* logMetrics */);
            } else if (mDragMode == DRAG_MODE_RECENTS) {
                mRecentsComponent.onDraggingInRecentsEnded(mVelocityTracker.getYVelocity());
            }
        }
        mVelocityTracker.recycle();
        mVelocityTracker = null;
    }

    private int calculateDragMode() {
        if (mIsVertical && !mDivider.getView().isHorizontalDivision()) {
            return DRAG_MODE_DIVIDER;
        }
        if (!mIsVertical && mDivider.getView().isHorizontalDivision()) {
            return DRAG_MODE_DIVIDER;
        }
        return DRAG_MODE_RECENTS;
    }

    public boolean onTouchEvent(MotionEvent event) {
        boolean result = mTaskSwitcherDetector.onTouchEvent(event);
        if (mDockWindowEnabled) {
            result |= handleDockWindowEvent(event);
        }
        return result;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        float absVelX = Math.abs(velocityX);
        float absVelY = Math.abs(velocityY);
        boolean isValidFling = absVelX > mMinFlingVelocity &&
                mIsVertical ? (absVelY > absVelX) : (absVelX > absVelY);
        if (isValidFling && mRecentsComponent != null) {
            boolean showNext;
            if (!mIsRTL) {
                showNext = mIsVertical ? (velocityY < 0) : (velocityX < 0);
            } else {
                // In RTL, vertical is still the same, but horizontal is flipped
                showNext = mIsVertical ? (velocityY < 0) : (velocityX > 0);
            }
            if (showNext) {
                mRecentsComponent.showNextAffiliatedTask();
            } else {
                mRecentsComponent.showPrevAffiliatedTask();
            }
        }
        return true;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case KEY_DOCK_WINDOW_GESTURE:
                mDockWindowEnabled = newValue != null && (Integer.parseInt(newValue) != 0);
                break;
        }
    }
}
