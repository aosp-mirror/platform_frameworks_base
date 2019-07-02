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
 * limitations under the Licen
 */


package com.android.systemui.statusbar.notification.stack;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.SwipeHelper;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;

class NotificationSwipeHelper extends SwipeHelper
        implements NotificationSwipeActionHelper {
    @VisibleForTesting
    protected static final long COVER_MENU_DELAY = 4000;
    private static final String TAG = "NotificationSwipeHelper";
    private final Runnable mFalsingCheck;
    private View mTranslatingParentView;
    private View mMenuExposedView;
    private final NotificationCallback mCallback;
    private final NotificationMenuRowPlugin.OnMenuEventListener mMenuListener;

    private static final long SWIPE_MENU_TIMING = 200;

    private NotificationMenuRowPlugin mCurrMenuRow;
    private boolean mIsExpanded;
    private boolean mPulsing;

    public NotificationSwipeHelper(int swipeDirection, NotificationCallback callback,
            Context context, NotificationMenuRowPlugin.OnMenuEventListener menuListener) {
        super(swipeDirection, callback, context);
        mMenuListener = menuListener;
        mCallback = callback;
        mFalsingCheck = new Runnable() {
            @Override
            public void run() {
                resetExposedMenuView(true /* animate */, true /* force */);
            }
        };
    }

    public View getTranslatingParentView() {
        return mTranslatingParentView;
    }

    public void clearTranslatingParentView() { setTranslatingParentView(null); }

    @VisibleForTesting
    protected void setTranslatingParentView(View view) { mTranslatingParentView = view; };

    public void setExposedMenuView(View view) {
        mMenuExposedView = view;
    }

    public void clearExposedMenuView() { setExposedMenuView(null); }

    public void clearCurrentMenuRow() { setCurrentMenuRow(null); }

    public View getExposedMenuView() {
        return mMenuExposedView;
    }

    public void setCurrentMenuRow(NotificationMenuRowPlugin menuRow) {
        mCurrMenuRow = menuRow;
    }

    public NotificationMenuRowPlugin getCurrentMenuRow() {  return mCurrMenuRow; }

    @VisibleForTesting
    protected Handler getHandler() { return mHandler; }

    @VisibleForTesting
    protected Runnable getFalsingCheck() {
        return mFalsingCheck;
    }

    public void setIsExpanded(boolean isExpanded) {
        mIsExpanded = isExpanded;
    }

    @Override
    protected void onChildSnappedBack(View animView, float targetLeft) {
        if (mCurrMenuRow != null && targetLeft == 0) {
            mCurrMenuRow.resetMenu();
            clearCurrentMenuRow();
        }
    }

    @Override
    public void onDownUpdate(View currView, MotionEvent ev) {
        mTranslatingParentView = currView;
        NotificationMenuRowPlugin menuRow = getCurrentMenuRow();
        if (menuRow != null) {
            menuRow.onTouchStart();
        }
        clearCurrentMenuRow();
        getHandler().removeCallbacks(getFalsingCheck());

        // Slide back any notifications that might be showing a menu
        resetExposedMenuView(true /* animate */, false /* force */);

        if (currView instanceof ExpandableNotificationRow) {
            initializeRow((ExpandableNotificationRow) currView);
        }
    }

    @VisibleForTesting
    protected void initializeRow(ExpandableNotificationRow row) {
        if (row.getEntry().hasFinishedInitialization()) {
            mCurrMenuRow = row.createMenu();
            if (mCurrMenuRow != null) {
                mCurrMenuRow.setMenuClickListener(mMenuListener);
                mCurrMenuRow.onTouchStart();
            }
        }
    }

    private boolean swipedEnoughToShowMenu(NotificationMenuRowPlugin menuRow) {
        return !swipedFarEnough() && menuRow.isSwipedEnoughToShowMenu();
    }

    @Override
    public void onMoveUpdate(View view, MotionEvent ev, float translation, float delta) {
        getHandler().removeCallbacks(getFalsingCheck());
        NotificationMenuRowPlugin menuRow = getCurrentMenuRow();
        if (menuRow != null) {
            menuRow.onTouchMove(delta);
        }
    }

    @Override
    public boolean handleUpEvent(MotionEvent ev, View animView, float velocity,
            float translation) {
        NotificationMenuRowPlugin menuRow = getCurrentMenuRow();
        if (menuRow != null) {
            menuRow.onTouchEnd();
            handleMenuRowSwipe(ev, animView, velocity, menuRow);
            return true;
        }
        return false;
    }

    @VisibleForTesting
    protected void handleMenuRowSwipe(MotionEvent ev, View animView, float velocity,
            NotificationMenuRowPlugin menuRow) {
        if (!menuRow.shouldShowMenu()) {
            // If the menu should not be shown, then there is no need to check if the a swipe
            // should result in a snapping to the menu. As a result, just check if the swipe
            // was enough to dismiss the notification.
            if (isDismissGesture(ev)) {
                dismiss(animView, velocity);
            } else {
                snapClosed(animView, velocity);
                menuRow.onSnapClosed();
            }
            return;
        }

        if (menuRow.isSnappedAndOnSameSide()) {
            // Menu was snapped to previously and we're on the same side
            handleSwipeFromOpenState(ev, animView, velocity, menuRow);
        } else {
            // Menu has not been snapped, or was snapped previously but is now on
            // the opposite side.
            handleSwipeFromClosedState(ev, animView, velocity, menuRow);
        }
    }

    private void handleSwipeFromClosedState(MotionEvent ev, View animView, float velocity,
            NotificationMenuRowPlugin menuRow) {
        boolean isDismissGesture = isDismissGesture(ev);
        final boolean gestureTowardsMenu = menuRow.isTowardsMenu(velocity);
        final boolean gestureFastEnough = getEscapeVelocity() <= Math.abs(velocity);

        final double timeForGesture = ev.getEventTime() - ev.getDownTime();
        final boolean showMenuForSlowOnGoing = !menuRow.canBeDismissed()
                && timeForGesture >= SWIPE_MENU_TIMING;

        boolean isNonDismissGestureTowardsMenu = gestureTowardsMenu && !isDismissGesture;
        boolean isSlowSwipe = !gestureFastEnough || showMenuForSlowOnGoing;
        boolean slowSwipedFarEnough = swipedEnoughToShowMenu(menuRow) && isSlowSwipe;
        boolean isFastNonDismissGesture =
                gestureFastEnough && !gestureTowardsMenu && !isDismissGesture;
        boolean isAbleToShowMenu = menuRow.shouldShowGutsOnSnapOpen()
                || mIsExpanded && !mPulsing;
        boolean isMenuRevealingGestureAwayFromMenu = slowSwipedFarEnough
                || (isFastNonDismissGesture && isAbleToShowMenu);
        int menuSnapTarget = menuRow.getMenuSnapTarget();
        boolean isNonFalseMenuRevealingGesture =
                !isFalseGesture(ev) && isMenuRevealingGestureAwayFromMenu;
        if ((isNonDismissGestureTowardsMenu || isNonFalseMenuRevealingGesture)
                && menuSnapTarget != 0) {
            // Menu has not been snapped to previously and this is menu revealing gesture
            snapOpen(animView, menuSnapTarget, velocity);
            menuRow.onSnapOpen();
        } else if (isDismissGesture(ev) && !gestureTowardsMenu) {
            dismiss(animView, velocity);
            menuRow.onDismiss();
        } else {
            snapClosed(animView, velocity);
            menuRow.onSnapClosed();
        }
    }

    private void handleSwipeFromOpenState(MotionEvent ev, View animView, float velocity,
            NotificationMenuRowPlugin menuRow) {
        boolean isDismissGesture = isDismissGesture(ev);

        final boolean withinSnapMenuThreshold =
                menuRow.isWithinSnapMenuThreshold();

        if (withinSnapMenuThreshold && !isDismissGesture) {
            // Haven't moved enough to unsnap from the menu
            menuRow.onSnapOpen();
            snapOpen(animView, menuRow.getMenuSnapTarget(), velocity);
        } else if (isDismissGesture && !menuRow.shouldSnapBack()) {
            // Only dismiss if we're not moving towards the menu
            dismiss(animView, velocity);
            menuRow.onDismiss();
        } else {
            snapClosed(animView, velocity);
            menuRow.onSnapClosed();
        }
    }

    @Override
    public void dismissChild(final View view, float velocity,
            boolean useAccelerateInterpolator) {
        superDismissChild(view, velocity, useAccelerateInterpolator);
        if (mCallback.shouldDismissQuickly()) {
            // We don't want to quick-dismiss when it's a heads up as this might lead to closing
            // of the panel early.
            mCallback.handleChildViewDismissed(view);
        }
        mCallback.onDismiss();
        handleMenuCoveredOrDismissed();
    }

    @VisibleForTesting
    protected void superDismissChild(final View view, float velocity, boolean useAccelerateInterpolator) {
        super.dismissChild(view, velocity, useAccelerateInterpolator);
    }

    @VisibleForTesting
    protected void superSnapChild(final View animView, final float targetLeft, float velocity) {
        super.snapChild(animView, targetLeft, velocity);
    }

    @Override
    public void snapChild(final View animView, final float targetLeft, float velocity) {
        superSnapChild(animView, targetLeft, velocity);
        mCallback.onDragCancelled(animView);
        if (targetLeft == 0) {
            handleMenuCoveredOrDismissed();
        }
    }

    @Override
    public void snooze(StatusBarNotification sbn, SnoozeOption snoozeOption) {
        mCallback.onSnooze(sbn, snoozeOption);
    }

    @VisibleForTesting
    protected void handleMenuCoveredOrDismissed() {
        View exposedMenuView = getExposedMenuView();
        if (exposedMenuView != null && exposedMenuView == mTranslatingParentView) {
            clearExposedMenuView();
        }
    }

    @VisibleForTesting
    protected Animator superGetViewTranslationAnimator(View v, float target,
            ValueAnimator.AnimatorUpdateListener listener) {
        return super.getViewTranslationAnimator(v, target, listener);
    }

    @Override
    public Animator getViewTranslationAnimator(View v, float target,
            ValueAnimator.AnimatorUpdateListener listener) {
        if (v instanceof ExpandableNotificationRow) {
            return ((ExpandableNotificationRow) v).getTranslateViewAnimator(target, listener);
        } else {
            return superGetViewTranslationAnimator(v, target, listener);
        }
    }

    @Override
    public void setTranslation(View v, float translate) {
        if (v instanceof ExpandableNotificationRow) {
            ((ExpandableNotificationRow) v).setTranslation(translate);
        }
    }

    @Override
    public float getTranslation(View v) {
        if (v instanceof ExpandableNotificationRow) {
            return ((ExpandableNotificationRow) v).getTranslation();
        }
        else {
            return 0f;
        }
    }

    @Override
    public boolean swipedFastEnough(float translation, float viewSize) {
        return swipedFastEnough();
    }

    @Override
    @VisibleForTesting
    protected boolean swipedFastEnough() {
        return super.swipedFastEnough();
    }

    @Override
    public boolean swipedFarEnough(float translation, float viewSize) {
        return swipedFarEnough();
    }

    @Override
    @VisibleForTesting
    protected boolean swipedFarEnough() {
        return super.swipedFarEnough();
    }

    @Override
    public void dismiss(View animView, float velocity) {
        dismissChild(animView, velocity,
                !swipedFastEnough() /* useAccelerateInterpolator */);
    }

    @Override
    public void snapOpen(View animView, int targetLeft, float velocity) {
        snapChild(animView, targetLeft, velocity);
    }

    @VisibleForTesting
    protected void snapClosed(View animView, float velocity) {
        snapChild(animView, 0, velocity);
    }

    @Override
    @VisibleForTesting
    protected float getEscapeVelocity() {
        return super.getEscapeVelocity();
    }

    @Override
    public float getMinDismissVelocity() {
        return getEscapeVelocity();
    }

    public void onMenuShown(View animView) {
        setExposedMenuView(getTranslatingParentView());
        mCallback.onDragCancelled(animView);
        Handler handler = getHandler();

        // If we're on the lockscreen we want to false this.
        if (mCallback.isAntiFalsingNeeded()) {
            handler.removeCallbacks(getFalsingCheck());
            handler.postDelayed(getFalsingCheck(), COVER_MENU_DELAY);
        }
    }

    @VisibleForTesting
    protected boolean shouldResetMenu(boolean force) {
        if (mMenuExposedView == null
                || (!force && mMenuExposedView == mTranslatingParentView)) {
            // If no menu is showing or it's showing for this view we do nothing.
            return false;
        }
        return true;
    }

    public void resetExposedMenuView(boolean animate, boolean force) {
        if (!shouldResetMenu(force)) {
            return;
        }
        final View prevMenuExposedView = getExposedMenuView();
        if (animate) {
            Animator anim = getViewTranslationAnimator(prevMenuExposedView,
                    0 /* leftTarget */, null /* updateListener */);
            if (anim != null) {
                anim.start();
            }
        } else if (prevMenuExposedView instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) prevMenuExposedView;
            if (!row.isRemoved()) {
                row.resetTranslation();
            }
        }
        clearExposedMenuView();
    }

    public static boolean isTouchInView(MotionEvent ev, View view) {
        if (view == null) {
            return false;
        }
        final int height = (view instanceof ExpandableView)
                ? ((ExpandableView) view).getActualHeight()
                : view.getHeight();
        final int rx = (int) ev.getRawX();
        final int ry = (int) ev.getRawY();
        int[] temp = new int[2];
        view.getLocationOnScreen(temp);
        final int x = temp[0];
        final int y = temp[1];
        Rect rect = new Rect(x, y, x + view.getWidth(), y + height);
        boolean ret = rect.contains(rx, ry);
        return ret;
    }

    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
    }

    public interface NotificationCallback extends SwipeHelper.Callback{
        /**
         * @return if the view should be dismissed as soon as the touch is released, otherwise its
         *         removed when the animation finishes.
         */
        boolean shouldDismissQuickly();

        void handleChildViewDismissed(View view);

        void onSnooze(StatusBarNotification sbn, SnoozeOption snoozeOption);

        void onDismiss();
    }
}
