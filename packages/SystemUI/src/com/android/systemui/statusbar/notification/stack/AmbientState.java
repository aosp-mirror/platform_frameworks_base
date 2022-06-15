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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.stack;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.MathUtils;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.BypassController;
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.SectionProvider;

import javax.inject.Inject;

/**
 * A global state to track all input states for the algorithm.
 */
@SysUISingleton
public class AmbientState {

    private static final float MAX_PULSE_HEIGHT = 100000f;
    private static final boolean NOTIFICATIONS_HAVE_SHADOWS = false;

    private final SectionProvider mSectionProvider;
    private final BypassController mBypassController;
    private int mScrollY;
    private boolean mDimmed;
    private ActivatableNotificationView mActivatedChild;
    private float mOverScrollTopAmount;
    private float mOverScrollBottomAmount;
    private boolean mDozing;
    private boolean mHideSensitive;
    private float mStackTranslation;
    private int mLayoutHeight;
    private int mTopPadding;
    private boolean mShadeExpanded;
    private float mMaxHeadsUpTranslation;
    private boolean mDismissAllInProgress;
    private int mLayoutMinHeight;
    private NotificationShelf mShelf;
    private int mZDistanceBetweenElements;
    private int mBaseZHeight;
    private int mContentHeight;
    private ExpandableView mLastVisibleBackgroundChild;
    private float mCurrentScrollVelocity;
    private int mStatusBarState;
    private float mExpandingVelocity;
    private boolean mPanelTracking;
    private boolean mExpansionChanging;
    private boolean mPanelFullWidth;
    private boolean mPulsing;
    private boolean mUnlockHintRunning;
    private boolean mQsCustomizerShowing;
    private int mIntrinsicPadding;
    private float mHideAmount;
    private boolean mAppearing;
    private float mPulseHeight = MAX_PULSE_HEIGHT;
    private float mDozeAmount = 0.0f;
    private Runnable mOnPulseHeightChangedListener;
    private ExpandableNotificationRow mTrackedHeadsUpRow;
    private float mAppearFraction;
    private boolean mIsShadeOpening;
    private float mOverExpansion;

    /** Distance of top of notifications panel from top of screen. */
    private float mStackY = 0;

    /** Height of notifications panel. */
    private float mStackHeight = 0;

    /** Fraction of shade expansion. */
    private float mExpansionFraction;

    /** Height of the notifications panel without top padding when expansion completes. */
    private float mStackEndHeight;
    private float mTransitionToFullShadeAmount;

    /**
     * @return Height of the notifications panel without top padding when expansion completes.
     */
    public float getStackEndHeight() {
        return mStackEndHeight;
    }

    /**
     * @param stackEndHeight Height of the notifications panel without top padding
     *                       when expansion completes.
     */
    public void setStackEndHeight(float stackEndHeight) {
        mStackEndHeight = stackEndHeight;
    }

    /**
     * @param stackY Distance of top of notifications panel from top of screen.
     */
    public void setStackY(float stackY) {
        mStackY = stackY;
    }

    /**
     * @return Distance of top of notifications panel from top of screen.
     */
    public float getStackY() {
        return mStackY;
    }

    /**
     * @param expansionFraction Fraction of shade expansion.
     */
    public void setExpansionFraction(float expansionFraction) {
        mExpansionFraction = expansionFraction;
    }

    /**
     * @return Fraction of shade expansion.
     */
    public float getExpansionFraction() {
        return mExpansionFraction;
    }

    /**
     * @param stackHeight Height of notifications panel.
     */
    public void setStackHeight(float stackHeight) {
        mStackHeight = stackHeight;
    }

    /**
     * @return Height of notifications panel.
     */
    public float getStackHeight() {
        return mStackHeight;
    }

    /** Tracks the state from AlertingNotificationManager#hasNotifications() */
    private boolean mHasAlertEntries;

    @Inject
    public AmbientState(
            Context context,
            @NonNull SectionProvider sectionProvider,
            @NonNull BypassController bypassController) {
        mSectionProvider = sectionProvider;
        mBypassController = bypassController;
        reload(context);
    }

    /**
     * Reload the dimens e.g. if the density changed.
     */
    public void reload(Context context) {
        mZDistanceBetweenElements = getZDistanceBetweenElements(context);
        mBaseZHeight = getBaseHeight(mZDistanceBetweenElements);
    }

    public void setIsShadeOpening(boolean isOpening) {
        mIsShadeOpening = isOpening;
    }

    public boolean isShadeOpening() {
        return mIsShadeOpening;
    }

    void setOverExpansion(float overExpansion) {
        mOverExpansion = overExpansion;
    }

    float getOverExpansion() {
        return mOverExpansion;
    }

    private static int getZDistanceBetweenElements(Context context) {
        return Math.max(1, context.getResources()
                .getDimensionPixelSize(R.dimen.z_distance_between_notifications));
    }

    private static int getBaseHeight(int zdistanceBetweenElements) {
        return NOTIFICATIONS_HAVE_SHADOWS ? 4 * zdistanceBetweenElements : 0;
    }

    /**
     * @return the launch height for notifications that are launched
     */
    public static int getNotificationLaunchHeight(Context context) {
        int zDistance = getZDistanceBetweenElements(context);
        return NOTIFICATIONS_HAVE_SHADOWS ? 2 * getBaseHeight(zDistance) : 4 * zDistance;
    }

    /**
     * @return the basic Z height on which notifications remain.
     */
    public int getBaseZHeight() {
        return mBaseZHeight;
    }

    /**
     * @return the distance in Z between two overlaying notifications.
     */
    public int getZDistanceBetweenElements() {
        return mZDistanceBetweenElements;
    }

    public int getScrollY() {
        return mScrollY;
    }

    /**
     * Set the new Scroll Y position.
     */
    public void setScrollY(int scrollY) {
        // Because we're dealing with an overscroller, scrollY could sometimes become smaller than
        // 0. However this is only for internal purposes and the scroll position when read
        // should never be smaller than 0, otherwise it can lead to flickers.
        this.mScrollY = Math.max(scrollY, 0);
    }

    /**
     * @param dimmed Whether we are in a dimmed state (on the lockscreen), where the backgrounds are
     *               translucent and everything is scaled back a bit.
     */
    public void setDimmed(boolean dimmed) {
        mDimmed = dimmed;
    }

    /** While dozing, we draw as little as possible, assuming a black background */
    public void setDozing(boolean dozing) {
        mDozing = dozing;
    }

    /** Hide ratio of the status bar **/
    public void setHideAmount(float hidemount) {
        if (hidemount == 1.0f && mHideAmount != hidemount) {
            // Whenever we are fully hidden, let's reset the pulseHeight again
            setPulseHeight(MAX_PULSE_HEIGHT);
        }
        mHideAmount = hidemount;
    }

    /** Returns the hide ratio of the status bar */
    public float getHideAmount() {
        return mHideAmount;
    }

    public void setHideSensitive(boolean hideSensitive) {
        mHideSensitive = hideSensitive;
    }

    /**
     * In dimmed mode, a child can be activated, which happens on the first tap of the double-tap
     * interaction. This child is then scaled normally and its background is fully opaque.
     */
    public void setActivatedChild(ActivatableNotificationView activatedChild) {
        mActivatedChild = activatedChild;
    }

    public boolean isDimmed() {
        // While we are expanding from pulse, we want the notifications not to be dimmed, otherwise
        // you'd see the difference to the pulsing notification
        return mDimmed && !(isPulseExpanding() && mDozeAmount == 1.0f);
    }

    public boolean isDozing() {
        return mDozing;
    }

    public boolean isHideSensitive() {
        return mHideSensitive;
    }

    public ActivatableNotificationView getActivatedChild() {
        return mActivatedChild;
    }

    public void setOverScrollAmount(float amount, boolean onTop) {
        if (onTop) {
            mOverScrollTopAmount = amount;
        } else {
            mOverScrollBottomAmount = amount;
        }
    }

    /**
     * Is bypass currently enabled?
     */
    public boolean isBypassEnabled() {
        return mBypassController.isBypassEnabled();
    }

    public float getOverScrollAmount(boolean top) {
        return top ? mOverScrollTopAmount : mOverScrollBottomAmount;
    }

    public SectionProvider getSectionProvider() {
        return mSectionProvider;
    }

    public float getStackTranslation() {
        return mStackTranslation;
    }

    public void setStackTranslation(float stackTranslation) {
        mStackTranslation = stackTranslation;
    }

    public void setLayoutHeight(int layoutHeight) {
        mLayoutHeight = layoutHeight;
    }

    public float getTopPadding() {
        return mTopPadding;
    }

    public void setTopPadding(int topPadding) {
        mTopPadding = topPadding;
    }

    public int getInnerHeight() {
        return getInnerHeight(false /* ignorePulseHeight */);
    }

    /**
     * @param ignorePulseHeight ignore the pulse height for this request
     * @return the inner height of the algorithm.
     */
    public int getInnerHeight(boolean ignorePulseHeight) {
        if (mDozeAmount == 1.0f && !isPulseExpanding()) {
            return mShelf.getHeight();
        }
        int height = (int) Math.max(mLayoutMinHeight,
                Math.min(mLayoutHeight, mContentHeight) - mTopPadding);
        if (ignorePulseHeight) {
            return height;
        }
        float pulseHeight = Math.min(mPulseHeight, (float) height);
        return (int) MathUtils.lerp(height, pulseHeight, mDozeAmount);
    }

    public boolean isPulseExpanding() {
        return mPulseHeight != MAX_PULSE_HEIGHT && mDozeAmount != 0.0f && mHideAmount != 1.0f;
    }

    public boolean isShadeExpanded() {
        return mShadeExpanded;
    }

    public void setShadeExpanded(boolean shadeExpanded) {
        mShadeExpanded = shadeExpanded;
    }

    public void setMaxHeadsUpTranslation(float maxHeadsUpTranslation) {
        mMaxHeadsUpTranslation = maxHeadsUpTranslation;
    }

    public float getMaxHeadsUpTranslation() {
        return mMaxHeadsUpTranslation;
    }

    public void setDismissAllInProgress(boolean dismissAllInProgress) {
        mDismissAllInProgress = dismissAllInProgress;
    }

    public boolean isDismissAllInProgress() {
        return mDismissAllInProgress;
    }

    public void setLayoutMinHeight(int layoutMinHeight) {
        mLayoutMinHeight = layoutMinHeight;
    }

    public void setShelf(NotificationShelf shelf) {
        mShelf = shelf;
    }

    @Nullable
    public NotificationShelf getShelf() {
        return mShelf;
    }

    public void setContentHeight(int contentHeight) {
        mContentHeight = contentHeight;
    }

    public float getContentHeight() {
        return mContentHeight;
    }

    /**
     * Sets the last visible view of the host layout, that has a background, i.e the very last
     * view in the shade, without the clear all button.
     */
    public void setLastVisibleBackgroundChild(
            ExpandableView lastVisibleBackgroundChild) {
        mLastVisibleBackgroundChild = lastVisibleBackgroundChild;
    }

    public ExpandableView getLastVisibleBackgroundChild() {
        return mLastVisibleBackgroundChild;
    }

    public void setCurrentScrollVelocity(float currentScrollVelocity) {
        mCurrentScrollVelocity = currentScrollVelocity;
    }

    public float getCurrentScrollVelocity() {
        return mCurrentScrollVelocity;
    }

    public boolean isOnKeyguard() {
        return mStatusBarState == StatusBarState.KEYGUARD;
    }

    public void setStatusBarState(int statusBarState) {
        mStatusBarState = statusBarState;
    }

    public void setExpandingVelocity(float expandingVelocity) {
        mExpandingVelocity = expandingVelocity;
    }

    public void setExpansionChanging(boolean expansionChanging) {
        mExpansionChanging = expansionChanging;
    }

    public boolean isExpansionChanging() {
        return mExpansionChanging;
    }

    public float getExpandingVelocity() {
        return mExpandingVelocity;
    }

    public void setPanelTracking(boolean panelTracking) {
        mPanelTracking = panelTracking;
    }

    public boolean hasPulsingNotifications() {
        return mPulsing && mHasAlertEntries;
    }

    public void setPulsing(boolean hasPulsing) {
        mPulsing = hasPulsing;
    }

    /**
     * @return if we're pulsing in general
     */
    public boolean isPulsing() {
        return mPulsing;
    }

    public boolean isPulsing(NotificationEntry entry) {
        return mPulsing && entry.isAlerting();
    }

    public boolean isPanelTracking() {
        return mPanelTracking;
    }

    public boolean isPanelFullWidth() {
        return mPanelFullWidth;
    }

    public void setPanelFullWidth(boolean panelFullWidth) {
        mPanelFullWidth = panelFullWidth;
    }

    public void setUnlockHintRunning(boolean unlockHintRunning) {
        mUnlockHintRunning = unlockHintRunning;
    }

    public boolean isUnlockHintRunning() {
        return mUnlockHintRunning;
    }

    public boolean isQsCustomizerShowing() {
        return mQsCustomizerShowing;
    }

    public void setQsCustomizerShowing(boolean qsCustomizerShowing) {
        mQsCustomizerShowing = qsCustomizerShowing;
    }

    public void setIntrinsicPadding(int intrinsicPadding) {
        mIntrinsicPadding = intrinsicPadding;
    }

    public int getIntrinsicPadding() {
        return mIntrinsicPadding;
    }

    /**
     * @return whether a view is dozing and not pulsing right now
     */
    public boolean isDozingAndNotPulsing(ExpandableView view) {
        if (view instanceof ExpandableNotificationRow) {
            return isDozingAndNotPulsing((ExpandableNotificationRow) view);
        }
        return false;
    }

    /**
     * @return whether a row is dozing and not pulsing right now
     */
    public boolean isDozingAndNotPulsing(ExpandableNotificationRow row) {
        return isDozing() && !isPulsing(row.getEntry());
    }

    /**
     * @return {@code true } when shade is completely hidden: in AOD, ambient display or when
     * bypassing.
     */
    public boolean isFullyHidden() {
        return mHideAmount == 1;
    }

    public boolean isHiddenAtAll() {
        return mHideAmount != 0;
    }

    public void setAppearing(boolean appearing) {
        mAppearing = appearing;
    }

    public boolean isAppearing() {
        return mAppearing;
    }

    public void setPulseHeight(float height) {
        if (height != mPulseHeight) {
            mPulseHeight = height;
            if (mOnPulseHeightChangedListener != null) {
                mOnPulseHeightChangedListener.run();
            }
        }
    }

    public float getPulseHeight() {
        if (mPulseHeight == MAX_PULSE_HEIGHT) {
            // If we're not pulse expanding, the height should be 0
            return 0;
        }
        return mPulseHeight;
    }

    public void setDozeAmount(float dozeAmount) {
        if (dozeAmount != mDozeAmount) {
            mDozeAmount = dozeAmount;
            if (dozeAmount == 0.0f || dozeAmount == 1.0f) {
                // We woke all the way up, let's reset the pulse height
                setPulseHeight(MAX_PULSE_HEIGHT);
            }
        }
    }

    /**
     * Is the device fully awake, which is different from not tark at all when there are pulsing
     * notifications.
     */
    public boolean isFullyAwake() {
        return mDozeAmount == 0.0f;
    }

    public void setOnPulseHeightChangedListener(Runnable onPulseHeightChangedListener) {
        mOnPulseHeightChangedListener = onPulseHeightChangedListener;
    }

    public Runnable getOnPulseHeightChangedListener() {
        return mOnPulseHeightChangedListener;
    }

    public void setTrackedHeadsUpRow(ExpandableNotificationRow row) {
        mTrackedHeadsUpRow = row;
    }

    /**
     * Set the amount of pixels we have currently dragged down if we're transitioning to the full
     * shade. 0.0f means we're not transitioning yet.
     */
    public void setTransitionToFullShadeAmount(float transitionToFullShadeAmount) {
        mTransitionToFullShadeAmount = transitionToFullShadeAmount;
    }

    /**
     * get
     */
    public float getTransitionToFullShadeAmount() {
        return mTransitionToFullShadeAmount;
    }

    /**
     * Returns the currently tracked heads up row, if there is one and it is currently above the
     * shelf (still appearing).
     */
    public ExpandableNotificationRow getTrackedHeadsUpRow() {
        if (mTrackedHeadsUpRow == null || !mTrackedHeadsUpRow.isAboveShelf()) {
            return null;
        }
        return mTrackedHeadsUpRow;
    }

    public void setAppearFraction(float appearFraction) {
        mAppearFraction = appearFraction;
    }

    public float getAppearFraction() {
        return mAppearFraction;
    }

    public void setHasAlertEntries(boolean hasAlertEntries) {
        mHasAlertEntries = hasAlertEntries;
    }
}
