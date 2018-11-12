/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_RIGHT;

import static com.android.systemui.recents.OverviewProxyService.DEBUG_OVERVIEW_PROXY;
import static com.android.systemui.recents.OverviewProxyService.TAG_OPS;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_BACK;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_DEAD_ZONE;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_HOME;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_NONE;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_OVERVIEW;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;

import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.R;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.system.NavigationBarCompat;

import java.io.PrintWriter;

/**
 * Class to detect gestures on the navigation bar and implement quick scrub.
 * Note that the variables in this class horizontal and vertical represents horizontal always
 * aligned with along the navigation bar).
 */
public class QuickStepController implements GestureHelper {

    private static final String TAG = "QuickStepController";

    /** Experiment to swipe home button left to execute a back key press */
    private static final String HIDE_BACK_BUTTON_PROP = "quickstepcontroller_hideback";
    private static final long BACK_BUTTON_FADE_IN_ALPHA = 150;

    /** When the home-swipe-back gesture is disallowed, make it harder to pull */
    private static final float DISALLOW_GESTURE_DAMPING_FACTOR = 0.16f;

    private static final int ACTION_SWIPE_UP_INDEX = 0;
    private static final int ACTION_SWIPE_DOWN_INDEX = 1;
    private static final int ACTION_SWIPE_LEFT_INDEX = 2;
    private static final int ACTION_SWIPE_RIGHT_INDEX = 3;
    private static final int MAX_GESTURES = 4;

    private NavigationBarView mNavigationBarView;

    private boolean mAllowGestureDetection;
    private boolean mNotificationsVisibleOnDown;
    private int mTouchDownX;
    private int mTouchDownY;
    private boolean mDragHPositive;
    private boolean mDragVPositive;
    private boolean mIsRTL;
    private int mNavBarPosition;
    private float mDarkIntensity;
    private ViewPropertyAnimator mDragBtnAnimator;
    private ButtonDispatcher mHitTarget;
    private boolean mIsInScreenPinning;
    private boolean mGestureHorizontalDragsButton;
    private boolean mGestureVerticalDragsButton;
    private boolean mGestureTrackPositive;

    private NavigationGestureAction mCurrentAction;
    private NavigationGestureAction[] mGestureActions = new NavigationGestureAction[MAX_GESTURES];

    private final OverviewProxyService mOverviewEventSender;
    private final int mHomeBackGestureDragLimit;
    private final Context mContext;
    private final StatusBar mStatusBar;
    private final Matrix mTransformGlobalMatrix = new Matrix();
    private final Matrix mTransformLocalMatrix = new Matrix();

    public QuickStepController(Context context) {
        final Resources res = context.getResources();
        mContext = context;
        mStatusBar = SysUiServiceProvider.getComponent(context, StatusBar.class);
        mOverviewEventSender = Dependency.get(OverviewProxyService.class);
        mHomeBackGestureDragLimit =
                res.getDimensionPixelSize(R.dimen.nav_home_back_gesture_drag_limit);
    }

    public void setComponents(NavigationBarView navigationBarView) {
        mNavigationBarView = navigationBarView;

        mNavigationBarView.getBackButton().setVisibility(shouldhideBackButton(mContext)
                ? View.GONE
                : View.VISIBLE);
    }

    /**
     * Set each gesture an action. After set the gestures triggered will run the actions attached.
     * @param swipeUpAction action after swiping up
     * @param swipeDownAction action after swiping down
     * @param swipeLeftAction action after swiping left
     * @param swipeRightAction action after swiping right
     */
    public void setGestureActions(@Nullable NavigationGestureAction swipeUpAction,
            @Nullable NavigationGestureAction swipeDownAction,
            @Nullable NavigationGestureAction swipeLeftAction,
            @Nullable NavigationGestureAction swipeRightAction) {
        mGestureActions[ACTION_SWIPE_UP_INDEX] = swipeUpAction;
        mGestureActions[ACTION_SWIPE_DOWN_INDEX] = swipeDownAction;
        mGestureActions[ACTION_SWIPE_LEFT_INDEX] = swipeLeftAction;
        mGestureActions[ACTION_SWIPE_RIGHT_INDEX] = swipeRightAction;

        // Set the current state to all actions
        for (NavigationGestureAction action: mGestureActions) {
            if (action != null) {
                action.setBarState(true, mNavBarPosition, mDragHPositive, mDragVPositive);
                action.onDarkIntensityChange(mDarkIntensity);
            }
        }
    }

    /**
     * @return true if we want to intercept touch events for quick scrub and prevent proxying the
     *         event to the overview service.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mStatusBar.isKeyguardShowing()) {
            // Disallow any handling when the keyguard is showing
            return false;
        }
        return handleTouchEvent(event);
    }

    /**
     * @return true if we want to handle touch events for quick scrub or if down event (that will
     *         get consumed and ignored). No events will be proxied to the overview service.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mStatusBar.isKeyguardShowing()) {
            // Disallow any handling when the keyguard is showing
            return false;
        }

        // The same down event was just sent on intercept and therefore can be ignored here
        final boolean ignoreProxyDownEvent = event.getAction() == MotionEvent.ACTION_DOWN
                && mOverviewEventSender.getProxy() != null;
        return ignoreProxyDownEvent || handleTouchEvent(event);
    }

    private boolean handleTouchEvent(MotionEvent event) {
        final boolean deadZoneConsumed =
                mNavigationBarView.getDownHitTarget() == HIT_TARGET_DEAD_ZONE;

        // Requires proxy and an active gesture or able to perform any gesture to continue
        if (mOverviewEventSender.getProxy() == null
                || (mCurrentAction == null && !canPerformAnyAction())) {
            return deadZoneConsumed;
        }
        mNavigationBarView.requestUnbufferedDispatch(event);

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int x = (int) event.getX();
                int y = (int) event.getY();
                mIsInScreenPinning = mNavigationBarView.inScreenPinning();

                for (NavigationGestureAction gestureAction: mGestureActions) {
                    if (gestureAction != null) {
                        gestureAction.reset();
                    }
                }

                // Valid buttons to drag over
                switch (mNavigationBarView.getDownHitTarget()) {
                    case HIT_TARGET_BACK:
                        mHitTarget = mNavigationBarView.getBackButton();
                        break;
                    case HIT_TARGET_HOME:
                        mHitTarget = mNavigationBarView.getHomeButton();
                        break;
                    case HIT_TARGET_OVERVIEW:
                        mHitTarget = mNavigationBarView.getRecentsButton();
                        break;
                    default:
                        mHitTarget = null;
                        break;
                }
                if (mHitTarget != null) {
                    // Pre-emptively delay the touch feedback for the button that we just touched
                    mHitTarget.setDelayTouchFeedback(true);
                }
                mTouchDownX = x;
                mTouchDownY = y;
                mGestureHorizontalDragsButton = false;
                mGestureVerticalDragsButton = false;
                mTransformGlobalMatrix.set(Matrix.IDENTITY_MATRIX);
                mTransformLocalMatrix.set(Matrix.IDENTITY_MATRIX);
                mNavigationBarView.transformMatrixToGlobal(mTransformGlobalMatrix);
                mNavigationBarView.transformMatrixToLocal(mTransformLocalMatrix);
                mAllowGestureDetection = true;
                mNotificationsVisibleOnDown = !mNavigationBarView.isNotificationsFullyCollapsed();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!mAllowGestureDetection) {
                    break;
                }
                int x = (int) event.getX();
                int y = (int) event.getY();
                int xDiff = Math.abs(x - mTouchDownX);
                int yDiff = Math.abs(y - mTouchDownY);

                boolean exceededSwipeHorizontalTouchSlop, exceededSwipeVerticalTouchSlop;
                int posH, touchDownH, posV, touchDownV;

                if (isNavBarVertical()) {
                    exceededSwipeHorizontalTouchSlop =
                            yDiff > NavigationBarCompat.getQuickScrubTouchSlopPx() && yDiff > xDiff;
                    exceededSwipeVerticalTouchSlop =
                            xDiff > NavigationBarCompat.getQuickStepTouchSlopPx() && xDiff > yDiff;
                    posH = y;
                    touchDownH = mTouchDownY;
                    posV = x;
                    touchDownV = mTouchDownX;
                } else {
                    exceededSwipeHorizontalTouchSlop =
                            xDiff > NavigationBarCompat.getQuickScrubTouchSlopPx() && xDiff > yDiff;
                    exceededSwipeVerticalTouchSlop =
                            yDiff > NavigationBarCompat.getQuickStepTouchSlopPx() && yDiff > xDiff;
                    posH = x;
                    touchDownH = mTouchDownX;
                    posV = y;
                    touchDownV = mTouchDownY;
                }

                if (mCurrentAction != null) {
                    // Gesture started, provide positions to the current action
                    mCurrentAction.onGestureMove(x, y);
                } else {
                    // Detect gesture and try to execute an action, only one can run at a time
                    if (exceededSwipeVerticalTouchSlop) {
                        if (mDragVPositive ? (posV < touchDownV) : (posV > touchDownV)) {
                            // Swiping up gesture
                            tryToStartGesture(mGestureActions[ACTION_SWIPE_UP_INDEX],
                                    false /* alignedWithNavBar */, false /* positiveDirection */,
                                    event);
                        } else {
                            // Swiping down gesture
                            tryToStartGesture(mGestureActions[ACTION_SWIPE_DOWN_INDEX],
                                    false /* alignedWithNavBar */, true /* positiveDirection */,
                                    event);
                        }
                    } else if (exceededSwipeHorizontalTouchSlop) {
                        if (mDragHPositive ? (posH < touchDownH) : (posH > touchDownH)) {
                            // Swiping left (ltr) gesture
                            tryToStartGesture(mGestureActions[ACTION_SWIPE_LEFT_INDEX],
                                    true /* alignedWithNavBar */, false /* positiveDirection */,
                                    event);
                        } else {
                            // Swiping right (ltr) gesture
                            tryToStartGesture(mGestureActions[ACTION_SWIPE_RIGHT_INDEX],
                                    true /* alignedWithNavBar */, true /* positiveDirection */,
                                    event);
                        }
                    }
                }

                handleDragHitTarget(mGestureHorizontalDragsButton ? posH : posV,
                        mGestureHorizontalDragsButton ? touchDownH : touchDownV);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mCurrentAction != null) {
                    mCurrentAction.endGesture();
                    mCurrentAction = null;
                }

                // Return the hit target back to its original position
                if (mHitTarget != null) {
                    final View button = mHitTarget.getCurrentView();
                    if (mGestureHorizontalDragsButton || mGestureVerticalDragsButton) {
                        mDragBtnAnimator = button.animate().setDuration(BACK_BUTTON_FADE_IN_ALPHA)
                                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
                        if (mGestureVerticalDragsButton ^ isNavBarVertical()) {
                            mDragBtnAnimator.translationY(0);
                        } else {
                            mDragBtnAnimator.translationX(0);
                        }
                        mDragBtnAnimator.start();
                    }
                }
                break;
        }

        if (shouldProxyEvents(action)) {
            proxyMotionEvents(event);
        }
        return mCurrentAction != null || deadZoneConsumed;
    }

    private void handleDragHitTarget(int position, int touchDown) {
        // Drag the hit target if gesture action requires it
        if (mHitTarget != null && (mGestureVerticalDragsButton || mGestureHorizontalDragsButton)) {
            final View button = mHitTarget.getCurrentView();
            if (mDragBtnAnimator != null) {
                mDragBtnAnimator.cancel();
                mDragBtnAnimator = null;
            }

            int diff = position - touchDown;
            // If dragging the incorrect direction after starting gesture or unable to
            // execute tried action, then move the button but dampen its distance
            if (mCurrentAction == null || (mGestureTrackPositive ? diff < 0 : diff > 0)) {
                diff *= DISALLOW_GESTURE_DAMPING_FACTOR;
            } else if (Math.abs(diff) > mHomeBackGestureDragLimit) {
                // Once the user drags the button past a certain limit, the distance will
                // lessen as the button dampens that it was pulled too far
                float distanceAfterDragLimit = (Math.abs(diff) - mHomeBackGestureDragLimit)
                        * DISALLOW_GESTURE_DAMPING_FACTOR;
                diff = (int) (distanceAfterDragLimit + mHomeBackGestureDragLimit);
                if (!mGestureTrackPositive) {
                    diff *= -1;
                }
            }
            if (mGestureVerticalDragsButton ^ isNavBarVertical()) {
                button.setTranslationY(diff);
            } else {
                button.setTranslationX(diff);
            }
        }
    }

    private boolean shouldProxyEvents(int action) {
        final boolean actionValid = (mCurrentAction == null
                || (mGestureActions[ACTION_SWIPE_UP_INDEX] != null
                        && mGestureActions[ACTION_SWIPE_UP_INDEX].isActive()));
        if (actionValid && !mIsInScreenPinning) {
            // Allow down, cancel and up events, move and other events are passed if notifications
            // are not showing and disabled gestures (such as long press) are not executed
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    return true;
                default:
                    return !mNotificationsVisibleOnDown && mAllowGestureDetection;
            }
        }
        return false;
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (mCurrentAction != null) {
            mCurrentAction.onDraw(canvas);
        }
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (NavigationGestureAction action: mGestureActions) {
            if (action != null) {
                action.onLayout(changed, left, top, right, bottom);
            }
        }
    }

    @Override
    public void onDarkIntensityChange(float intensity) {
        final float oldIntensity = mDarkIntensity;
        mDarkIntensity = intensity;

        // When in quick scrub, invalidate gradient if changing intensity from black to white and
        // vice-versa
        if (mCurrentAction != null && mNavigationBarView.isQuickScrubEnabled()
                && Math.round(intensity) != Math.round(oldIntensity)) {
            mCurrentAction.onDarkIntensityChange(mDarkIntensity);
        }
        mNavigationBarView.invalidate();
    }

    @Override
    public void setBarState(boolean isRTL, int navBarPosition) {
        final boolean changed = (mIsRTL != isRTL) || (mNavBarPosition != navBarPosition);
        mIsRTL = isRTL;
        mNavBarPosition = navBarPosition;

        // Determine the drag directions depending on location of nav bar
        switch (navBarPosition) {
            case NAV_BAR_LEFT:
                mDragHPositive = !isRTL;
                mDragVPositive = false;
                break;
            case NAV_BAR_RIGHT:
                mDragHPositive = isRTL;
                mDragVPositive = true;
                break;
            case NAV_BAR_BOTTOM:
                mDragHPositive = !isRTL;
                mDragVPositive = true;
                break;
        }

        for (NavigationGestureAction action: mGestureActions) {
            if (action != null) {
                action.setBarState(changed, mNavBarPosition, mDragHPositive, mDragVPositive);
            }
        }
    }

    @Override
    public void onNavigationButtonLongPress(View v) {
        mAllowGestureDetection = false;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("QuickStepController {");
        pw.print("    "); pw.println("mAllowGestureDetection=" + mAllowGestureDetection);
        pw.print("    "); pw.println("mNotificationsVisibleOnDown=" + mNotificationsVisibleOnDown);
        pw.print("    "); pw.println("mNavBarPosition=" + mNavBarPosition);
        pw.print("    "); pw.println("mIsRTL=" + mIsRTL);
        pw.print("    "); pw.println("mIsInScreenPinning=" + mIsInScreenPinning);
        pw.println("}");
    }

    public NavigationGestureAction getCurrentAction() {
        return mCurrentAction;
    }

    private void tryToStartGesture(NavigationGestureAction action, boolean alignedWithNavBar,
            boolean positiveDirection, MotionEvent event) {
        if (action == null) {
            return;
        }
        if (mIsInScreenPinning) {
            mNavigationBarView.showPinningEscapeToast();
            mAllowGestureDetection = false;
            return;
        }

        // Start new action from gesture if is able to start and depending on notifications
        // visibility and starting touch down target. If the action is enabled, then also check if
        // can perform the action so that if action requires the button to be dragged, then the
        // gesture will have a large dampening factor and prevent action from running.
        final boolean validHitTarget = action.requiresTouchDownHitTarget() == HIT_TARGET_NONE
                || action.requiresTouchDownHitTarget() == mNavigationBarView.getDownHitTarget();
        if (mCurrentAction == null && validHitTarget && action.isEnabled()
                && (!mNotificationsVisibleOnDown || action.canRunWhenNotificationsShowing())) {
            if (action.canPerformAction()) {
                mCurrentAction = action;
                event.transform(mTransformGlobalMatrix);
                action.startGesture(event);
                event.transform(mTransformLocalMatrix);
            }

            // Handle direction of the hit target drag from the axis that started the gesture
            if (action.requiresDragWithHitTarget()) {
                if (alignedWithNavBar) {
                    mGestureHorizontalDragsButton = true;
                    mGestureVerticalDragsButton = false;
                    if (positiveDirection) {
                        mGestureTrackPositive = mDragHPositive;
                    }
                } else {
                    mGestureVerticalDragsButton = true;
                    mGestureHorizontalDragsButton = false;
                    if (positiveDirection) {
                        mGestureTrackPositive = mDragVPositive;
                    }
                }
            }

            if (mHitTarget != null) {
                mHitTarget.abortCurrentGesture();
            }
        }
    }

    private boolean canPerformAnyAction() {
        for (NavigationGestureAction action: mGestureActions) {
            if (action != null && action.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    private boolean proxyMotionEvents(MotionEvent event) {
        final IOverviewProxy overviewProxy = mOverviewEventSender.getProxy();
        event.transform(mTransformGlobalMatrix);
        try {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                overviewProxy.onPreMotionEvent(mNavigationBarView.getDownHitTarget());
            }
            overviewProxy.onMotionEvent(event);
            if (DEBUG_OVERVIEW_PROXY) {
                Log.d(TAG_OPS, "Send MotionEvent: " + event.toString());
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Callback failed", e);
        } finally {
            event.transform(mTransformLocalMatrix);
        }
        return false;
    }

    protected boolean isNavBarVertical() {
        return mNavBarPosition == NAV_BAR_LEFT || mNavBarPosition == NAV_BAR_RIGHT;
    }

    static boolean getBoolGlobalSetting(Context context, String key) {
        return Settings.Global.getInt(context.getContentResolver(), key, 0) != 0;
    }

    public static boolean shouldhideBackButton(Context context) {
        return getBoolGlobalSetting(context, HIDE_BACK_BUTTON_PROP);
    }
}
