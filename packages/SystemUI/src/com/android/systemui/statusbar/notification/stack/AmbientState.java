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

import android.annotation.Nullable;
import android.content.Context;
import android.util.MathUtils;
import android.view.View;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.AmbientPulseManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;

import java.util.ArrayList;
import java.util.List;

/**
 * A global state to track all input states for the algorithm.
 */
public class AmbientState {

    private static final int NO_SECTION_BOUNDARY = -1;
    private static final float MAX_PULSE_HEIGHT = 100000f;

    private ArrayList<ExpandableView> mDraggedViews = new ArrayList<>();
    private int mScrollY;
    private int mAnchorViewIndex;
    private int mAnchorViewY;
    private boolean mDimmed;
    private ActivatableNotificationView mActivatedChild;
    private float mOverScrollTopAmount;
    private float mOverScrollBottomAmount;
    private int mSpeedBumpIndex = -1;
    private final List<Integer> mSectionBoundaryIndices = new ArrayList<>();
    private boolean mDark;
    private boolean mHideSensitive;
    private AmbientPulseManager mAmbientPulseManager = Dependency.get(AmbientPulseManager.class);
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
    private int mMaxLayoutHeight;
    private ActivatableNotificationView mLastVisibleBackgroundChild;
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
    private int mExpandAnimationTopChange;
    private ExpandableNotificationRow mExpandingNotification;
    private float mDarkAmount;
    private boolean mAppearing;
    private float mPulseHeight = MAX_PULSE_HEIGHT;
    private float mDozeAmount = 0.0f;

    public AmbientState(Context context) {
        mSectionBoundaryIndices.add(NO_SECTION_BOUNDARY);
        reload(context);
    }

    /**
     * Reload the dimens e.g. if the density changed.
     */
    public void reload(Context context) {
        mZDistanceBetweenElements = getZDistanceBetweenElements(context);
        mBaseZHeight = getBaseHeight(mZDistanceBetweenElements);
    }

    private static int getZDistanceBetweenElements(Context context) {
        return Math.max(1, context.getResources()
                .getDimensionPixelSize(R.dimen.z_distance_between_notifications));
    }

    private static int getBaseHeight(int zdistanceBetweenElements) {
        return 4 * zdistanceBetweenElements;
    }

    /**
     * @return the launch height for notifications that are launched
     */
    public static int getNotificationLaunchHeight(Context context) {
        int zDistance = getZDistanceBetweenElements(context);
        return getBaseHeight(zDistance) * 2;
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

    public void setScrollY(int scrollY) {
        this.mScrollY = scrollY;
    }

    /**
     * Index of the child view whose Y position on screen is returned by {@link #getAnchorViewY()}.
     * Other views are laid out outwards from this view in both directions.
     */
    public int getAnchorViewIndex() {
        return mAnchorViewIndex;
    }

    public void setAnchorViewIndex(int anchorViewIndex) {
        mAnchorViewIndex = anchorViewIndex;
    }

    /** Current Y position of the view at {@link #getAnchorViewIndex()}. */
    public int getAnchorViewY() {
        return mAnchorViewY;
    }

    public void setAnchorViewY(int anchorViewY) {
        mAnchorViewY = anchorViewY;
    }

    /** Call when dragging begins. */
    public void onBeginDrag(ExpandableView view) {
        mDraggedViews.add(view);
    }

    public void onDragFinished(View view) {
        mDraggedViews.remove(view);
    }

    public ArrayList<ExpandableView> getDraggedViews() {
        return mDraggedViews;
    }

    /**
     * @param dimmed Whether we are in a dimmed state (on the lockscreen), where the backgrounds are
     *               translucent and everything is scaled back a bit.
     */
    public void setDimmed(boolean dimmed) {
        mDimmed = dimmed;
    }

    /** In dark mode, we draw as little as possible, assuming a black background */
    public void setDark(boolean dark) {
        mDark = dark;
    }

    /** Dark ratio of the status bar **/
    public void setDarkAmount(float darkAmount) {
        if (darkAmount == 1.0f && mDarkAmount != darkAmount) {
            // Whenever we are fully dark, let's reset the pulseHeight again
            mPulseHeight = MAX_PULSE_HEIGHT;
        }
        mDarkAmount = darkAmount;
    }

    /** Returns the dark ratio of the status bar */
    public float getDarkAmount() {
        return mDarkAmount;
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
        return mDimmed;
    }

    public boolean isDark() {
        return mDark;
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

    public float getOverScrollAmount(boolean top) {
        return top ? mOverScrollTopAmount : mOverScrollBottomAmount;
    }

    public int getSpeedBumpIndex() {
        return mSpeedBumpIndex;
    }

    public void setSpeedBumpIndex(int shelfIndex) {
        mSpeedBumpIndex = shelfIndex;
    }

    /**
     * Returns the index of the boundary between two sections, where the first section is at index
     * {@code boundaryNum}.
     */
    public int getSectionBoundaryIndex(int boundaryNum) {
        return mSectionBoundaryIndices.get(boundaryNum);
    }

    /** Returns true if the item at {@code index} is directly below a section boundary. */
    public boolean beginsNewSection(int index) {
        return mSectionBoundaryIndices.contains(index);
    }

    /**
     * Sets the index of the boundary between the section at {@code boundaryNum} and the following
     * section to {@code boundaryIndex}.
     */
    public void setSectionBoundaryIndex(int boundaryNum, int boundaryIndex) {
        mSectionBoundaryIndices.set(boundaryNum, boundaryIndex);
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
        int height = Math.max(mLayoutMinHeight,
                Math.min(mLayoutHeight, mMaxLayoutHeight) - mTopPadding);
        if (ignorePulseHeight) {
            return height;
        }
        float pulseHeight = Math.min(mPulseHeight, (float) height);
        return (int) MathUtils.lerp(height, pulseHeight, mDozeAmount);
    }

    public boolean isPulseExpanding() {
        return mPulseHeight != MAX_PULSE_HEIGHT && mDozeAmount != 0.0f && mDarkAmount != 1.0f;
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

    public void setLayoutMaxHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
    }

    /**
     * Sets the last visible view of the host layout, that has a background, i.e the very last
     * view in the shade, without the clear all button.
     */
    public void setLastVisibleBackgroundChild(
            ActivatableNotificationView lastVisibleBackgroundChild) {
        mLastVisibleBackgroundChild = lastVisibleBackgroundChild;
    }

    public ActivatableNotificationView getLastVisibleBackgroundChild() {
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
        return mPulsing && mAmbientPulseManager != null
                && mAmbientPulseManager.hasNotifications();
    }

    public void setPulsing(boolean hasPulsing) {
        mPulsing = hasPulsing;
    }

    public boolean isPulsing(NotificationEntry entry) {
        if (!mPulsing || mAmbientPulseManager == null) {
            return false;
        }
        return mAmbientPulseManager.isAlerting(entry.key);
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
        return isDark() && !isPulsing(row.getEntry());
    }

    public void setExpandAnimationTopChange(int expandAnimationTopChange) {
        mExpandAnimationTopChange = expandAnimationTopChange;
    }

    public void setExpandingNotification(ExpandableNotificationRow row) {
        mExpandingNotification = row;
    }

    public ExpandableNotificationRow getExpandingNotification() {
        return mExpandingNotification;
    }

    public int getExpandAnimationTopChange() {
        return mExpandAnimationTopChange;
    }

    /**
     * @return {@code true } when shade is completely dark: in AOD or ambient display.
     */
    public boolean isFullyDark() {
        return mDarkAmount == 1;
    }

    public boolean isDarkAtAll() {
        return mDarkAmount != 0;
    }

    public void setAppearing(boolean appearing) {
        mAppearing = appearing;
    }

    public boolean isAppearing() {
        return mAppearing;
    }

    public void setPulseHeight(float height) {
        mPulseHeight = height;
    }

    public void setDozeAmount(float dozeAmount) {
        if (dozeAmount != mDozeAmount) {
            mDozeAmount = dozeAmount;
            if (dozeAmount == 0.0f || dozeAmount == 1.0f) {
                // We woke all the way up, let's reset the pulse height
                mPulseHeight = MAX_PULSE_HEIGHT;
            }
        }
    }
}
