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

import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_TOP;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.DividerSnapAlgorithm.SnapTarget;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.tuner.TunerService;

/**
 * Class to detect gestures on the navigation bar.
 */
public class NavigationBarGestureHelper implements TunerService.Tunable, GestureHelper {

    private static final String TAG = "NavBarGestureHelper";
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

    private final QuickStepController mQuickStepController;
    private final int mScrollTouchSlop;
    private final StatusBar mStatusBar;
    private int mTouchDownX;
    private int mTouchDownY;
    private boolean mDownOnRecents;
    private VelocityTracker mVelocityTracker;
    private boolean mIsInScreenPinning;
    private boolean mNotificationsVisibleOnDown;

    private boolean mDockWindowEnabled;
    private boolean mDockWindowTouchSlopExceeded;
    private int mDragMode;

    public NavigationBarGestureHelper(Context context) {
        mContext = context;
        mStatusBar = SysUiServiceProvider.getComponent(context, StatusBar.class);
        Resources r = context.getResources();
        mScrollTouchSlop = r.getDimensionPixelSize(R.dimen.navigation_bar_min_swipe_distance);
        mQuickStepController = new QuickStepController(context);
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
        mQuickStepController.setComponents(mNavigationBarView);
    }

    public void setBarState(boolean isVertical, boolean isRTL) {
        mIsVertical = isVertical;
        mQuickStepController.setBarState(isVertical, isRTL);
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mIsInScreenPinning = mNavigationBarView.inScreenPinning();
            mNotificationsVisibleOnDown = !mStatusBar.isPresenterFullyCollapsed();
        }
        if (!canHandleGestures()) {
            return false;
        }
        boolean result = mQuickStepController.onInterceptTouchEvent(event);
        if (mDockWindowEnabled) {
            result |= interceptDockWindowEvent(event);
        }
        return result;
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!canHandleGestures()) {
            return false;
        }
        boolean result = mQuickStepController.onTouchEvent(event);
        if (mDockWindowEnabled) {
            result |= handleDockWindowEvent(event);
        }
        return result;
    }

    public void onDraw(Canvas canvas) {
        mQuickStepController.onDraw(canvas);
    }

    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mQuickStepController.onLayout(changed, left, top, right, bottom);
    }

    public void onDarkIntensityChange(float intensity) {
        mQuickStepController.onDarkIntensityChange(intensity);
    }

    public void onNavigationButtonLongPress(View v) {
        mQuickStepController.onNavigationButtonLongPress(v);
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
                int createMode = ActivityManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
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
                    createMode = ActivityManager.SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT;
                }
                boolean docked = mRecentsComponent.splitPrimaryTask(dragMode, createMode,
                        initialBounds, MetricsEvent.ACTION_WINDOW_DOCK_SWIPE);
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

    private boolean canHandleGestures() {
        return !mIsInScreenPinning && !mStatusBar.isKeyguardShowing()
                && !mNotificationsVisibleOnDown;
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

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case KEY_DOCK_WINDOW_GESTURE:
                mDockWindowEnabled = newValue != null && (Integer.parseInt(newValue) != 0);
                break;
        }
    }
}
