/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static com.android.systemui.SwipeHelper.SWIPED_FAR_ENOUGH_SIZE_FRACTION;

import java.util.ArrayList;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.statusbar.NotificationGuts.GutsContent;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.service.notification.StatusBarNotification;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;

public class NotificationMenuRow implements NotificationMenuRowPlugin, View.OnClickListener,
        ExpandableNotificationRow.LayoutListener {

    private static final boolean DEBUG = false;
    private static final String TAG = "swipe";

    private static final int ICON_ALPHA_ANIM_DURATION = 200;
    private static final long SHOW_MENU_DELAY = 60;
    private static final long SWIPE_MENU_TIMING = 200;

    // Notification must be swiped at least this fraction of a single menu item to show menu
    private static final float SWIPED_FAR_ENOUGH_MENU_FRACTION = 0.25f;
    private static final float SWIPED_FAR_ENOUGH_MENU_UNCLEARABLE_FRACTION = 0.15f;

    // When the menu is displayed, the notification must be swiped within this fraction of a single
    // menu item to snap back to menu (else it will cover the menu or it'll be dismissed)
    private static final float SWIPED_BACK_ENOUGH_TO_COVER_FRACTION = 0.2f;

    private ExpandableNotificationRow mParent;

    private Context mContext;
    private FrameLayout mMenuContainer;
    private MenuItem mInfoItem;
    private MenuItem mAppOpsItem;
    private MenuItem mSnoozeItem;
    private ArrayList<MenuItem> mMenuItems;
    private OnMenuEventListener mMenuListener;

    private ValueAnimator mFadeAnimator;
    private boolean mAnimating;
    private boolean mMenuFadedIn;

    private boolean mOnLeft;
    private boolean mIconsPlaced;

    private boolean mDismissing;
    private boolean mSnapping;
    private float mTranslation;

    private int[] mIconLocation = new int[2];
    private int[] mParentLocation = new int[2];

    private float mHorizSpaceForIcon = -1;
    private int mVertSpaceForIcons = -1;
    private int mIconPadding = -1;
    private int mSidePadding;

    private float mAlpha = 0f;
    private float mPrevX;

    private CheckForDrag mCheckForDrag;
    private Handler mHandler;

    private boolean mMenuSnappedTo;
    private boolean mMenuSnappedOnLeft;
    private boolean mShouldShowMenu;

    private NotificationSwipeActionHelper mSwipeHelper;
    private boolean mIsUserTouching;

    public NotificationMenuRow(Context context) {
        mContext = context;
        mShouldShowMenu = context.getResources().getBoolean(R.bool.config_showNotificationGear);
        mHandler = new Handler(Looper.getMainLooper());
        mMenuItems = new ArrayList<>();
    }

    @Override
    public ArrayList<MenuItem> getMenuItems(Context context) {
        return mMenuItems;
    }

    @Override
    public MenuItem getLongpressMenuItem(Context context) {
        return mInfoItem;
    }

    @Override
    public MenuItem getAppOpsMenuItem(Context context) {
        return mAppOpsItem;
    }

    @Override
    public MenuItem getSnoozeMenuItem(Context context) {
        return mSnoozeItem;
    }

    @Override
    public void setSwipeActionHelper(NotificationSwipeActionHelper helper) {
        mSwipeHelper = helper;
    }

    @Override
    public void setMenuClickListener(OnMenuEventListener listener) {
        mMenuListener = listener;
    }

    @Override
    public void createMenu(ViewGroup parent, StatusBarNotification sbn) {
        mParent = (ExpandableNotificationRow) parent;
        createMenuViews(true /* resetState */);
    }

    @Override
    public boolean isMenuVisible() {
        return mAlpha > 0;
    }

    @Override
    public View getMenuView() {
        return mMenuContainer;
    }

    @Override
    public void resetMenu() {
        resetState(true);
    }

    @Override
    public void onNotificationUpdated(StatusBarNotification sbn) {
        if (mMenuContainer == null) {
            // Menu hasn't been created yet, no need to do anything.
            return;
        }
        createMenuViews(!isMenuVisible() /* resetState */);
    }

    @Override
    public void onConfigurationChanged() {
        mParent.setLayoutListener(this);
    }

    @Override
    public void onLayout() {
        mIconsPlaced = false; // Force icons to be re-placed
        setMenuLocation();
        mParent.removeListener();
    }

    private void createMenuViews(boolean resetState) {
        final Resources res = mContext.getResources();
        mHorizSpaceForIcon = res.getDimensionPixelSize(R.dimen.notification_menu_icon_size);
        mVertSpaceForIcons = res.getDimensionPixelSize(R.dimen.notification_min_height);
        mMenuItems.clear();
        // Construct the menu items based on the notification
        if (mParent != null && mParent.getStatusBarNotification() != null) {
            int flags = mParent.getStatusBarNotification().getNotification().flags;
            boolean isForeground = (flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
            if (!isForeground) {
                // Only show snooze for non-foreground notifications
                mSnoozeItem = createSnoozeItem(mContext);
                mMenuItems.add(mSnoozeItem);
            }
        }
        mInfoItem = createInfoItem(mContext);
        mMenuItems.add(mInfoItem);

        mAppOpsItem = createAppOpsItem(mContext);
        mMenuItems.add(mAppOpsItem);

        // Construct the menu views
        if (mMenuContainer != null) {
            mMenuContainer.removeAllViews();
        } else {
            mMenuContainer = new FrameLayout(mContext);
        }
        for (int i = 0; i < mMenuItems.size(); i++) {
            addMenuView(mMenuItems.get(i), mMenuContainer);
        }
        if (resetState) {
            resetState(false /* notify */);
        } else {
            mIconsPlaced = false;
            setMenuLocation();
            if (!mIsUserTouching) {
                // If the # of items showing changed we need to update the snap position
                showMenu(mParent, mOnLeft ? getSpaceForMenu() : -getSpaceForMenu(),
                        0 /* velocity */);
            }
        }
    }

    private void resetState(boolean notify) {
        setMenuAlpha(0f);
        mIconsPlaced = false;
        mMenuFadedIn = false;
        mAnimating = false;
        mSnapping = false;
        mDismissing = false;
        mMenuSnappedTo = false;
        setMenuLocation();
        if (mMenuListener != null && notify) {
            mMenuListener.onMenuReset(mParent);
        }
    }

    @Override
    public boolean onTouchEvent(View view, MotionEvent ev, float velocity) {
        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mSnapping = false;
                if (mFadeAnimator != null) {
                    mFadeAnimator.cancel();
                }
                mHandler.removeCallbacks(mCheckForDrag);
                mCheckForDrag = null;
                mPrevX = ev.getRawX();
                mIsUserTouching = true;
                break;

            case MotionEvent.ACTION_MOVE:
                mSnapping = false;
                float diffX = ev.getRawX() - mPrevX;
                mPrevX = ev.getRawX();
                if (!isTowardsMenu(diffX) && isMenuLocationChange()) {
                    // Don't consider it "snapped" if location has changed.
                    mMenuSnappedTo = false;

                    // Changed directions, make sure we check to fade in icon again.
                    if (!mHandler.hasCallbacks(mCheckForDrag)) {
                        // No check scheduled, set null to schedule a new one.
                        mCheckForDrag = null;
                    } else {
                        // Check scheduled, reset alpha and update location; check will fade it in
                        setMenuAlpha(0f);
                        setMenuLocation();
                    }
                }
                if (mShouldShowMenu
                        && !NotificationStackScrollLayout.isPinnedHeadsUp(view)
                        && !mParent.areGutsExposed()
                        && !mParent.isDark()
                        && (mCheckForDrag == null || !mHandler.hasCallbacks(mCheckForDrag))) {
                    // Only show the menu if we're not a heads up view and guts aren't exposed.
                    mCheckForDrag = new CheckForDrag();
                    mHandler.postDelayed(mCheckForDrag, SHOW_MENU_DELAY);
                }
                break;

            case MotionEvent.ACTION_UP:
                mIsUserTouching = false;
                return handleUpEvent(ev, view, velocity);
            case MotionEvent.ACTION_CANCEL:
                mIsUserTouching = false;
                cancelDrag();
                return false;
        }
        return false;
    }

    private boolean handleUpEvent(MotionEvent ev, View animView, float velocity) {
        // If the menu should not be shown, then there is no need to check if the a swipe
        // should result in a snapping to the menu. As a result, just check if the swipe
        // was enough to dismiss the notification.
        if (!mShouldShowMenu) {
            if (mSwipeHelper.isDismissGesture(ev)) {
                dismiss(animView, velocity);
            } else {
                snapBack(animView, velocity);
            }
            return true;
        }

        final boolean gestureTowardsMenu = isTowardsMenu(velocity);
        final boolean gestureFastEnough =
                mSwipeHelper.getMinDismissVelocity() <= Math.abs(velocity);
        final boolean gestureFarEnough =
                mSwipeHelper.swipedFarEnough(mTranslation, mParent.getWidth());
        final double timeForGesture = ev.getEventTime() - ev.getDownTime();
        final boolean showMenuForSlowOnGoing = !mParent.canViewBeDismissed()
                && timeForGesture >= SWIPE_MENU_TIMING;
        final float menuSnapTarget = mOnLeft ? getSpaceForMenu() : -getSpaceForMenu();

        if (DEBUG) {
            Log.d(TAG, "mTranslation= " + mTranslation
                    + " mAlpha= " + mAlpha
                    + " velocity= " + velocity
                    + " mMenuSnappedTo= " + mMenuSnappedTo
                    + " mMenuSnappedOnLeft= " + mMenuSnappedOnLeft
                    + " mOnLeft= " + mOnLeft
                    + " minDismissVel= " + mSwipeHelper.getMinDismissVelocity()
                    + " isDismissGesture= " + mSwipeHelper.isDismissGesture(ev)
                    + " gestureTowardsMenu= " + gestureTowardsMenu
                    + " gestureFastEnough= " + gestureFastEnough
                    + " gestureFarEnough= " + gestureFarEnough);
        }

        if (mMenuSnappedTo && isMenuVisible() && mMenuSnappedOnLeft == mOnLeft) {
            // Menu was snapped to previously and we're on the same side, figure out if
            // we should stick to the menu, snap back into place, or dismiss
            final float maximumSwipeDistance = mHorizSpaceForIcon
                    * SWIPED_BACK_ENOUGH_TO_COVER_FRACTION;
            final float targetLeft = getSpaceForMenu() - maximumSwipeDistance;
            final float targetRight = mParent.getWidth() * SWIPED_FAR_ENOUGH_SIZE_FRACTION;
            boolean withinSnapMenuThreshold = mOnLeft
                    ? mTranslation > targetLeft && mTranslation < targetRight
                    : mTranslation < -targetLeft && mTranslation > -targetRight;
            boolean shouldSnapTo = mOnLeft ? mTranslation < targetLeft : mTranslation > -targetLeft;
            if (DEBUG) {
                Log.d(TAG, "   withinSnapMenuThreshold= " + withinSnapMenuThreshold
                        + "   shouldSnapTo= " + shouldSnapTo
                        + "   targetLeft= " + targetLeft
                        + "   targetRight= " + targetRight);
            }
            if (withinSnapMenuThreshold && !mSwipeHelper.isDismissGesture(ev)) {
                // Haven't moved enough to unsnap from the menu
                showMenu(animView, menuSnapTarget, velocity);
            } else if (mSwipeHelper.isDismissGesture(ev) && !shouldSnapTo) {
                // Only dismiss if we're not moving towards the menu
                dismiss(animView, velocity);
            } else {
                snapBack(animView, velocity);
            }
        } else if (!mSwipeHelper.isFalseGesture(ev)
                && (swipedEnoughToShowMenu() && (!gestureFastEnough || showMenuForSlowOnGoing))
                || (gestureTowardsMenu && !mSwipeHelper.isDismissGesture(ev))) {
            // Menu has not been snapped to previously and this is menu revealing gesture
            showMenu(animView, menuSnapTarget, velocity);
        } else if (mSwipeHelper.isDismissGesture(ev) && !gestureTowardsMenu) {
            dismiss(animView, velocity);
        } else {
            snapBack(animView, velocity);
        }
        return true;
    }

    private void showMenu(View animView, float targetLeft, float velocity) {
        mMenuSnappedTo = true;
        mMenuSnappedOnLeft = mOnLeft;
        mMenuListener.onMenuShown(animView);
        mSwipeHelper.snap(animView, targetLeft, velocity);
    }

    private void snapBack(View animView, float velocity) {
        cancelDrag();
        mMenuSnappedTo = false;
        mSnapping = true;
        mSwipeHelper.snap(animView, 0 /* leftTarget */, velocity);
    }

    private void dismiss(View animView, float velocity) {
        cancelDrag();
        mMenuSnappedTo = false;
        mDismissing = true;
        mSwipeHelper.dismiss(animView, velocity);
    }

    private void cancelDrag() {
        if (mFadeAnimator != null) {
            mFadeAnimator.cancel();
        }
        mHandler.removeCallbacks(mCheckForDrag);
    }

    /**
     * @return whether the notification has been translated enough to show the menu and not enough
     *         to be dismissed.
     */
    private boolean swipedEnoughToShowMenu() {
        final float multiplier = mParent.canViewBeDismissed()
                ? SWIPED_FAR_ENOUGH_MENU_FRACTION
                : SWIPED_FAR_ENOUGH_MENU_UNCLEARABLE_FRACTION;
        final float minimumSwipeDistance = mHorizSpaceForIcon * multiplier;
        return !mSwipeHelper.swipedFarEnough(0, 0) && isMenuVisible()
                && (mOnLeft ? mTranslation > minimumSwipeDistance
                        : mTranslation < -minimumSwipeDistance);
    }

    /**
     * Returns whether the gesture is towards the menu location or not.
     */
    private boolean isTowardsMenu(float movement) {
        return isMenuVisible()
                && ((mOnLeft && movement <= 0)
                        || (!mOnLeft && movement >= 0));
    }

    @Override
    public void setAppName(String appName) {
        if (appName == null) {
            return;
        }
        Resources res = mContext.getResources();
        final int count = mMenuItems.size();
        for (int i = 0; i < count; i++) {
            MenuItem item = mMenuItems.get(i);
            String description = String.format(
                    res.getString(R.string.notification_menu_accessibility),
                    appName, item.getContentDescription());
            View menuView = item.getMenuView();
            if (menuView != null) {
                menuView.setContentDescription(description);
            }
        }
    }

    @Override
    public void onHeightUpdate() {
        if (mParent == null || mMenuItems.size() == 0 || mMenuContainer == null) {
            return;
        }
        int parentHeight = mParent.getActualHeight();
        float translationY;
        if (parentHeight < mVertSpaceForIcons) {
            translationY = (parentHeight / 2) - (mHorizSpaceForIcon / 2);
        } else {
            translationY = (mVertSpaceForIcons - mHorizSpaceForIcon) / 2;
        }
        mMenuContainer.setTranslationY(translationY);
    }

    @Override
    public void onTranslationUpdate(float translation) {
        mTranslation = translation;
        if (mAnimating || !mMenuFadedIn) {
            // Don't adjust when animating, or if the menu hasn't been shown yet.
            return;
        }
        final float fadeThreshold = mParent.getWidth() * 0.3f;
        final float absTrans = Math.abs(translation);
        float desiredAlpha = 0;
        if (absTrans == 0) {
            desiredAlpha = 0;
        } else if (absTrans <= fadeThreshold) {
            desiredAlpha = 1;
        } else {
            desiredAlpha = 1 - ((absTrans - fadeThreshold) / (mParent.getWidth() - fadeThreshold));
        }
        setMenuAlpha(desiredAlpha);
    }

    @Override
    public void onClick(View v) {
        if (mMenuListener == null) {
            // Nothing to do
            return;
        }
        v.getLocationOnScreen(mIconLocation);
        mParent.getLocationOnScreen(mParentLocation);
        final int centerX = (int) (mHorizSpaceForIcon / 2);
        final int centerY = v.getHeight() / 2;
        final int x = mIconLocation[0] - mParentLocation[0] + centerX;
        final int y = mIconLocation[1] - mParentLocation[1] + centerY;
        final int index = mMenuContainer.indexOfChild(v);
        mMenuListener.onMenuClicked(mParent, x, y, mMenuItems.get(index));
    }

    private boolean isMenuLocationChange() {
        boolean onLeft = mTranslation > mIconPadding;
        boolean onRight = mTranslation < -mIconPadding;
        if ((mOnLeft && onRight) || (!mOnLeft && onLeft)) {
            return true;
        }
        return false;
    }

    private void setMenuLocation() {
        boolean showOnLeft = mTranslation > 0;
        if ((mIconsPlaced && showOnLeft == mOnLeft) || mSnapping || mMenuContainer == null
                || !mMenuContainer.isAttachedToWindow()) {
            // Do nothing
            return;
        }
        final int count = mMenuContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            final View v = mMenuContainer.getChildAt(i);
            final float left = i * mHorizSpaceForIcon;
            final float right = mParent.getWidth() - (mHorizSpaceForIcon * (i + 1));
            v.setX(showOnLeft ? left : right);
        }
        mOnLeft = showOnLeft;
        mIconsPlaced = true;
    }

    private void setMenuAlpha(float alpha) {
        mAlpha = alpha;
        if (mMenuContainer == null) {
            return;
        }
        if (alpha == 0) {
            mMenuFadedIn = false; // Can fade in again once it's gone.
            mMenuContainer.setVisibility(View.INVISIBLE);
        } else {
            mMenuContainer.setVisibility(View.VISIBLE);
        }
        final int count = mMenuContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            mMenuContainer.getChildAt(i).setAlpha(mAlpha);
        }
    }

    /**
     * Returns the horizontal space in pixels required to display the menu.
     */
    private float getSpaceForMenu() {
        return mHorizSpaceForIcon * mMenuContainer.getChildCount();
    }

    private final class CheckForDrag implements Runnable {
        @Override
        public void run() {
            final float absTransX = Math.abs(mTranslation);
            final float bounceBackToMenuWidth = getSpaceForMenu();
            final float notiThreshold = mParent.getWidth() * 0.4f;
            if ((!isMenuVisible() || isMenuLocationChange())
                    && absTransX >= bounceBackToMenuWidth * 0.4
                    && absTransX < notiThreshold) {
                fadeInMenu(notiThreshold);
            }
        }
    }

    private void fadeInMenu(final float notiThreshold) {
        if (mDismissing || mAnimating) {
            return;
        }
        if (isMenuLocationChange()) {
            setMenuAlpha(0f);
        }
        final float transX = mTranslation;
        final boolean fromLeft = mTranslation > 0;
        setMenuLocation();
        mFadeAnimator = ValueAnimator.ofFloat(mAlpha, 1);
        mFadeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final float absTrans = Math.abs(transX);

                boolean pastMenu = (fromLeft && transX <= notiThreshold)
                        || (!fromLeft && absTrans <= notiThreshold);
                if (pastMenu && !mMenuFadedIn) {
                    setMenuAlpha((float) animation.getAnimatedValue());
                }
            }
        });
        mFadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mAnimating = true;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // TODO should animate back to 0f from current alpha
                setMenuAlpha(0f);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimating = false;
                mMenuFadedIn = mAlpha == 1;
            }
        });
        mFadeAnimator.setInterpolator(Interpolators.ALPHA_IN);
        mFadeAnimator.setDuration(ICON_ALPHA_ANIM_DURATION);
        mFadeAnimator.start();
    }

    @Override
    public void setMenuItems(ArrayList<MenuItem> items) {
        // Do nothing we use our own for now.
        // TODO -- handle / allow custom menu items!
    }

    public static MenuItem createSnoozeItem(Context context) {
        Resources res = context.getResources();
        NotificationSnooze content = (NotificationSnooze) LayoutInflater.from(context)
                .inflate(R.layout.notification_snooze, null, false);
        String snoozeDescription = res.getString(R.string.notification_menu_snooze_description);
        MenuItem snooze = new NotificationMenuItem(context, snoozeDescription, content,
                R.drawable.ic_snooze);
        return snooze;
    }

    public static MenuItem createInfoItem(Context context) {
        Resources res = context.getResources();
        String infoDescription = res.getString(R.string.notification_menu_gear_description);
        NotificationInfo infoContent = (NotificationInfo) LayoutInflater.from(context).inflate(
                R.layout.notification_info, null, false);
        MenuItem info = new NotificationMenuItem(context, infoDescription, infoContent,
                R.drawable.ic_settings);
        return info;
    }

    public static MenuItem createAppOpsItem(Context context) {
        AppOpsInfo appOpsContent = (AppOpsInfo) LayoutInflater.from(context).inflate(
                R.layout.app_ops_info, null, false);
        MenuItem info = new NotificationMenuItem(context, null, appOpsContent,
                -1 /*don't show in slow swipe menu */);
        return info;
    }

    private void addMenuView(MenuItem item, ViewGroup parent) {
        View menuView = item.getMenuView();
        if (menuView != null) {
            parent.addView(menuView);
            menuView.setOnClickListener(this);
            FrameLayout.LayoutParams lp = (LayoutParams) menuView.getLayoutParams();
            lp.width = (int) mHorizSpaceForIcon;
            lp.height = (int) mHorizSpaceForIcon;
            menuView.setLayoutParams(lp);
        }
    }

    public static class NotificationMenuItem implements MenuItem {
        View mMenuView;
        GutsContent mGutsContent;
        String mContentDescription;

        /**
         * Add a new 'guts' panel. If iconResId < 0 it will not appear in the slow swipe menu
         * but can still be exposed via other affordances.
         */
        public NotificationMenuItem(Context context, String s, GutsContent content, int iconResId) {
            Resources res = context.getResources();
            int padding = res.getDimensionPixelSize(R.dimen.notification_menu_icon_padding);
            int tint = res.getColor(R.color.notification_gear_color);
            if (iconResId >= 0) {
                AlphaOptimizedImageView iv = new AlphaOptimizedImageView(context);
                iv.setPadding(padding, padding, padding, padding);
                Drawable icon = context.getResources().getDrawable(iconResId);
                iv.setImageDrawable(icon);
                iv.setColorFilter(tint);
                iv.setAlpha(1f);
                mMenuView = iv;
            }
            mContentDescription = s;
            mGutsContent = content;
        }

        @Override
        @Nullable
        public View getMenuView() {
            return mMenuView;
        }

        @Override
        public View getGutsView() {
            return mGutsContent.getContentView();
        }

        @Override
        public String getContentDescription() {
            return mContentDescription;
        }
    }
}
