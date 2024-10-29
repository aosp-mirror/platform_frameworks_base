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

import static com.android.systemui.statusbar.notification.NotificationUtils.logKey;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.MathUtils;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.BypassController;
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.SectionProvider;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.AvalancheController;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Global state to track all input states for
 * {@link com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm}.
 */
@SysUISingleton
public class AmbientState implements Dumpable {

    private static final float MAX_PULSE_HEIGHT = 100000f;
    private static final boolean NOTIFICATIONS_HAVE_SHADOWS = false;

    private final SectionProvider mSectionProvider;
    private final BypassController mBypassController;
    private final LargeScreenShadeInterpolator mLargeScreenShadeInterpolator;
    private final AvalancheController mAvalancheController;

    /**
     *  Used to read bouncer states.
     */
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private float mStackTop;
    private float mStackCutoff;
    private float mHeadsUpTop;
    private float mHeadsUpBottom;
    private int mScrollY;
    private float mOverScrollTopAmount;
    private float mOverScrollBottomAmount;
    private boolean mDozing;
    private boolean mHideSensitive;
    private float mStackTranslation;
    private int mLayoutHeight;
    private int mTopPadding;
    private boolean mShadeExpanded;
    private float mMaxHeadsUpTranslation;
    private boolean mClearAllInProgress;
    private int mLayoutMinHeight;
    private int mLayoutMaxHeight;
    private NotificationShelf mShelf;
    private int mZDistanceBetweenElements;
    private int mBaseZHeight;
    private int mContentHeight;
    private ExpandableView mLastVisibleBackgroundChild;
    private float mCurrentScrollVelocity;
    private int mStatusBarState;
    private boolean mShowingStackOnLockscreen;
    private float mLockscreenStackFadeInProgress;
    private float mExpandingVelocity;
    private boolean mPanelTracking;
    private boolean mExpansionChanging;
    private boolean mIsSmallScreen;
    private boolean mPulsing;
    private float mHideAmount;
    private float mPulseHeight = MAX_PULSE_HEIGHT;

    /**
     * The ExpandableNotificationRow that is pulsing, or the one that was pulsing
     * when the device started to transition from AOD to LockScreen.
     */
    private ExpandableNotificationRow mPulsingRow;

    /** Fraction of lockscreen to shade animation (on lockscreen swipe down). */
    private float mFractionToShade;

    /**
     * @param fractionToShade Fraction of lockscreen to shade transition
     */
    public void setFractionToShade(float fractionToShade) {
        mFractionToShade = fractionToShade;
    }

    /**
     * @return fractionToShade Fraction of lockscreen to shade transition
     */
    public float getFractionToShade() {
        return mFractionToShade;
    }

    /** How we much we are sleeping. 1f fully dozing (AOD), 0f fully awake (for all other states) */
    private float mDozeAmount = 0.0f;

    private Runnable mOnPulseHeightChangedListener;
    private ExpandableNotificationRow mTrackedHeadsUpRow;
    private float mAppearFraction;
    private float mOverExpansion;
    private int mStackTopMargin;
    private boolean mUseSplitShade;

    /** Distance of top of notifications panel from top of screen. */
    private float mStackY = 0;

    /** Height of notifications panel interpolated by the expansion fraction. */
    private float mStackHeight = 0;

    /** Fraction of shade expansion. */
    private float mExpansionFraction;

    /** Fraction of QS expansion. 0 when in shade, 1 when in QS. */
    private float mQsExpansionFraction;

    /** Height of the notifications panel when expansion completes. */
    private float mStackEndHeight;

    /** Whether we are swiping up. */
    private boolean mIsSwipingUp;

    /** Whether we are flinging the shade open or closed. */
    private boolean mIsFlinging;

    /**
     * Whether we need to do a fling down after swiping up on lockscreen.
     * True right after we swipe up on lockscreen and have not finished the fling down that follows.
     * False when we stop flinging or leave lockscreen.
     */
    private boolean mIsFlingRequiredAfterLockScreenSwipeUp = false;

    /**
     * Whether the shade is currently closing.
     */
    private boolean mIsClosing;

    @VisibleForTesting
    public boolean isFlingRequiredAfterLockScreenSwipeUp() {
        return mIsFlingRequiredAfterLockScreenSwipeUp;
    }

    @VisibleForTesting
    public void setFlingRequiredAfterLockScreenSwipeUp(boolean value) {
        mIsFlingRequiredAfterLockScreenSwipeUp = value;
    }

    /**
     * @return Height of the available space for the notification content, when the shade
     * expansion completes.
     */
    public float getStackEndHeight() {
        return mStackEndHeight;
    }

    /**
     * @see #getStackEndHeight()
     */
    public void setStackEndHeight(float stackEndHeight) {
        mStackEndHeight = stackEndHeight;
    }

    /**
     * @param stackY Distance of top of notifications panel from top of screen.
     */
    public void setStackY(float stackY) {
        SceneContainerFlag.assertInLegacyMode();
        mStackY = stackY;
    }

    /**
     * @return Distance of top of notifications panel from top of screen.
     */
    public float getStackY() {
        SceneContainerFlag.assertInLegacyMode();
        return mStackY;
    }

    /**
     * @param expansionFraction Fraction of shade expansion.
     */
    public void setExpansionFraction(float expansionFraction) {
        mExpansionFraction = expansionFraction;
    }

    /**
     * @param expansionFraction Fraction of QS expansion.
     */
    public void setQsExpansionFraction(float expansionFraction) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        mQsExpansionFraction = expansionFraction;
    }

    /**
     * @param isSwipingUp Whether we are swiping up.
     */
    public void setSwipingUp(boolean isSwipingUp) {
        SceneContainerFlag.assertInLegacyMode();
        if (!isSwipingUp && mIsSwipingUp) {
            // Just stopped swiping up.
            mIsFlingRequiredAfterLockScreenSwipeUp = true;
        }
        mIsSwipingUp = isSwipingUp;
    }

    /**
     * @return Whether we are swiping up.
     */
    public boolean isSwipingUp() {
        return mIsSwipingUp;
    }

    /**
     * @param isFlinging Whether we are flinging the shade open or closed.
     */
    public void setFlinging(boolean isFlinging) {
        SceneContainerFlag.assertInLegacyMode();
        if (isOnKeyguard() && !isFlinging && mIsFlinging) {
            // Just stopped flinging.
            mIsFlingRequiredAfterLockScreenSwipeUp = false;
        }
        mIsFlinging = isFlinging;
    }

    /**
     * @param useSplitShade True if we are showing split shade.
     */
    public void setUseSplitShade(boolean useSplitShade) {
        mUseSplitShade = useSplitShade;
    }

    /**
     * @return True if we are showing split shade.
     */
    public boolean getUseSplitShade() {
        return mUseSplitShade;
    }

    /**
     * @return Fraction of shade expansion.
     */
    public float getExpansionFraction() {
        return mExpansionFraction;
    }

    /**
     * @return Fraction of QS expansion.
     */
    public float getQsExpansionFraction() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return 0f;
        return mQsExpansionFraction;
    }

    /**
     * @return Height of the notification content returned by {@link #getStackEndHeight()}, but
     * interpolated by the shade expansion fraction.
     */
    public float getInterpolatedStackHeight() {
        return mStackHeight;
    }

    /**
     * @see #getInterpolatedStackHeight()
     */
    public void setInterpolatedStackHeight(float stackHeight) {
        mStackHeight = stackHeight;
    }

    @Inject
    public AmbientState(
            @NonNull Context context,
            @NonNull DumpManager dumpManager,
            @NonNull SectionProvider sectionProvider,
            @NonNull BypassController bypassController,
            @Nullable StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @NonNull LargeScreenShadeInterpolator largeScreenShadeInterpolator,
            AvalancheController avalancheController
    ) {
        mSectionProvider = sectionProvider;
        mBypassController = bypassController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mLargeScreenShadeInterpolator = largeScreenShadeInterpolator;
        mAvalancheController = avalancheController;
        reload(context);
        dumpManager.registerDumpable(this);
    }

    /**
     * Reload the dimens e.g. if the density changed.
     */
    public void reload(Context context) {
        mZDistanceBetweenElements = getZDistanceBetweenElements(context);
        mBaseZHeight = getBaseHeight(mZDistanceBetweenElements);
    }

    String getAvalancheShowingHunKey() {
        // If we don't have a previous showing hun, we don't consider the showing hun as avalanche
        if (isNullAvalancheKey(getAvalanchePreviousHunKey())) return "";
        return mAvalancheController.getShowingHunKey();
    }

    String getAvalanchePreviousHunKey() {
        return mAvalancheController.getPreviousHunKey();
    }

    boolean isNullAvalancheKey(String key) {
        if (key == null || key.isEmpty()) return true;
        return key.equals("HeadsUpEntry null") || key.equals("HeadsUpEntry.mEntry null");
    }

    void setOverExpansion(float overExpansion) {
        mOverExpansion = overExpansion;
    }

    public float getOverExpansion() {
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

    /** Y coordinate in view pixels of the top of the notification stack */
    public float getStackTop() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return 0f;
        return mStackTop;
    }

    /** @see #getStackTop() */
    public void setStackTop(float mStackTop) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        this.mStackTop = mStackTop;
    }

    /**
     * Y coordinate in view pixels above which the bottom of the notification stack / shelf / footer
     * must be.
     */
    public float getStackCutoff() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return 0f;
        return mStackCutoff;
    }

    /** @see #getStackCutoff() */
    public void setStackCutoff(float stackCutoff) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        this.mStackCutoff = stackCutoff;
    }

    /** y coordinate of the top position of a pinned HUN */
    public float getHeadsUpTop() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return 0f;
        return mHeadsUpTop;
    }

    /** @see #getHeadsUpTop() */
    public void setHeadsUpTop(float mHeadsUpTop) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        this.mHeadsUpTop = mHeadsUpTop;
    }

    /** the bottom-most y position where we can draw pinned HUNs  */
    public float getHeadsUpBottom() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return 0f;
        return mHeadsUpBottom;
    }

    /** @see #getHeadsUpBottom() */
    public void setHeadsUpBottom(float headsUpBottom) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        mHeadsUpBottom = headsUpBottom;
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

    public boolean isDozing() {
        return mDozing;
    }

    public boolean isHideSensitive() {
        return mHideSensitive;
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

    public void setLayoutMaxHeight(int maxLayoutHeight) {
        mLayoutMaxHeight = maxLayoutHeight;
    }

    public int getLayoutMaxHeight() {
        return mLayoutMaxHeight;
    }

    public int getTopPadding() {
        SceneContainerFlag.assertInLegacyMode();
        return mTopPadding;
    }

    public void setTopPadding(int topPadding) {
        SceneContainerFlag.assertInLegacyMode();
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
        int height;
        if (SceneContainerFlag.isEnabled()) {
            // TODO(b/192348384): This is probably incorrect as mContentHeight is not up to date.
            //  Consider removing usages of getInnerHeight in flexiglass if possible.
            height = (int) Math.min(mLayoutHeight, mContentHeight) - mTopPadding;
        } else {
            height = (int) Math.max(mLayoutMinHeight,
                    Math.min(mLayoutHeight, mContentHeight) - mTopPadding);
        }
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

    public void setClearAllInProgress(boolean clearAllInProgress) {
        mClearAllInProgress = clearAllInProgress;
    }

    public boolean isClearAllInProgress() {
        return mClearAllInProgress;
    }

    public void setLayoutMinHeight(int layoutMinHeight) {
        SceneContainerFlag.assertInLegacyMode();
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
        SceneContainerFlag.assertInLegacyMode();
        mContentHeight = contentHeight;
    }

    public float getContentHeight() {
        SceneContainerFlag.assertInLegacyMode();
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

    public boolean isShowingStackOnLockscreen() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return false;
        return mShowingStackOnLockscreen;
    }

    public void setShowingStackOnLockscreen(boolean showingStackOnLockscreen) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        mShowingStackOnLockscreen = showingStackOnLockscreen;
    }

    public float getLockscreenStackFadeInProgress() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return 0f;
        return mLockscreenStackFadeInProgress;
    }

    public void setLockscreenStackFadeInProgress(float lockscreenStackFadeInProgress) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        mLockscreenStackFadeInProgress = lockscreenStackFadeInProgress;
    }

    public void setStatusBarState(int statusBarState) {
        if (mStatusBarState != StatusBarState.KEYGUARD) {
            mIsFlingRequiredAfterLockScreenSwipeUp = false;
        }
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
        return mPulsing && entry.isHeadsUpEntry();
    }

    public void setPulsingRow(ExpandableNotificationRow row) {
        mPulsingRow = row;
    }

    /**
     * @param row The row to check
     * @return true if row is the pulsing row when the device started to transition from AOD to lock
     * screen
     */
    public boolean isPulsingRow(ExpandableView row) {
        return mPulsingRow == row;
    }

    public boolean isPanelTracking() {
        return mPanelTracking;
    }

    public boolean isSmallScreen() {
        return mIsSmallScreen;
    }

    public void setSmallScreen(boolean smallScreen) {
        mIsSmallScreen = smallScreen;
    }

    /**
     * @return Whether we need to do a fling down after swiping up on lockscreen.
     */
    public boolean isFlingingAfterSwipeUpOnLockscreen() {
        SceneContainerFlag.assertInLegacyMode();
        return mIsFlinging && mIsFlingRequiredAfterLockScreenSwipeUp;
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

    public float getDozeAmount() {
        return mDozeAmount;
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

    public void setTrackedHeadsUpRow(ExpandableNotificationRow row) {
        mTrackedHeadsUpRow = row;
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

    public void setStackTopMargin(int stackTopMargin) {
        mStackTopMargin = stackTopMargin;
    }

    public int getStackTopMargin() {
        return mStackTopMargin;
    }

    /**
     * Check to see if we are about to show bouncer.
     *
     * @return if bouncer expansion is between 0 and 1.
     */
    public boolean isBouncerInTransit() {
        return mStatusBarKeyguardViewManager != null
                && mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit();
    }

    /**
     * @param isClosing Whether the shade is currently closing.
     */
    public void setIsClosing(boolean isClosing) {
        mIsClosing = isClosing;
    }

    /**
     * @return Whether the shade is currently closing.
     */
    public boolean isClosing() {
        return mIsClosing;
    }

    public LargeScreenShadeInterpolator getLargeScreenShadeInterpolator() {
        return mLargeScreenShadeInterpolator;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("mStackTop=" + mStackTop);
        pw.println("mStackCutoff=" + mStackCutoff);
        pw.println("mHeadsUpTop=" + mHeadsUpTop);
        pw.println("mHeadsUpBottom=" + mHeadsUpBottom);
        pw.println("mTopPadding=" + mTopPadding);
        pw.println("mStackTopMargin=" + mStackTopMargin);
        pw.println("mStackTranslation=" + mStackTranslation);
        pw.println("mLayoutMinHeight=" + mLayoutMinHeight);
        pw.println("mLayoutMaxHeight=" + mLayoutMaxHeight);
        pw.println("mLayoutHeight=" + mLayoutHeight);
        pw.println("mContentHeight=" + mContentHeight);
        pw.println("mHideSensitive=" + mHideSensitive);
        pw.println("mShadeExpanded=" + mShadeExpanded);
        pw.println("mClearAllInProgress=" + mClearAllInProgress);
        pw.println("mStatusBarState=" + StatusBarState.toString(mStatusBarState));
        pw.println("mExpansionChanging=" + mExpansionChanging);
        pw.println("mPanelFullWidth=" + mIsSmallScreen);
        pw.println("mPulsing=" + mPulsing);
        pw.println("mPulseHeight=" + mPulseHeight);
        pw.println("mTrackedHeadsUpRow.key=" + logKey(mTrackedHeadsUpRow));
        pw.println("mMaxHeadsUpTranslation=" + mMaxHeadsUpTranslation);
        pw.println("mDozeAmount=" + mDozeAmount);
        pw.println("mDozing=" + mDozing);
        pw.println("mFractionToShade=" + mFractionToShade);
        pw.println("mHideAmount=" + mHideAmount);
        pw.println("mAppearFraction=" + mAppearFraction);
        pw.println("mExpansionFraction=" + mExpansionFraction);
        pw.println("mQsExpansionFraction=" + mQsExpansionFraction);
        pw.println("mExpandingVelocity=" + mExpandingVelocity);
        pw.println("mOverScrollTopAmount=" + mOverScrollTopAmount);
        pw.println("mOverScrollBottomAmount=" + mOverScrollBottomAmount);
        pw.println("mOverExpansion=" + mOverExpansion);
        pw.println("mStackHeight=" + mStackHeight);
        pw.println("mStackEndHeight=" + mStackEndHeight);
        pw.println("mStackY=" + mStackY);
        pw.println("mScrollY=" + mScrollY);
        pw.println("mCurrentScrollVelocity=" + mCurrentScrollVelocity);
        pw.println("mIsSwipingUp=" + mIsSwipingUp);
        pw.println("mPanelTracking=" + mPanelTracking);
        pw.println("mIsFlinging=" + mIsFlinging);
        pw.println("mIsFlingRequiredAfterLockScreenSwipeUp="
                + mIsFlingRequiredAfterLockScreenSwipeUp);
        pw.println("mZDistanceBetweenElements=" + mZDistanceBetweenElements);
        pw.println("mBaseZHeight=" + mBaseZHeight);
        pw.println("mIsClosing=" + mIsClosing);
    }
}
