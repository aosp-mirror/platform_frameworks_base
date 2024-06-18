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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static com.android.keyguard.BouncerPanelExpansionCalculator.aboutToShowBouncerProgress;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.util.MathUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.animation.ShadeInterpolation;
import com.android.systemui.flags.Flags;
import com.android.systemui.flags.RefactorFlag;
import com.android.systemui.res.R;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm;
import com.android.systemui.statusbar.notification.stack.ViewState;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.util.DumpUtilsKt;

import java.io.PrintWriter;

/**
 * A notification shelf view that is placed inside the notification scroller. It manages the
 * overflow icons that don't fit into the regular list anymore.
 */
public class NotificationShelf extends ActivatableNotificationView {

    private static final int TAG_CONTINUOUS_CLIPPING = R.id.continuous_clipping_tag;
    private static final String TAG = "NotificationShelf";

    // More extreme version of SLOW_OUT_LINEAR_IN which keeps the icon nearly invisible until after
    // the next icon has translated out of the way, to avoid overlapping.
    private static final Interpolator ICON_ALPHA_INTERPOLATOR =
            new PathInterpolator(0.6f, 0f, 0.6f, 0f);
    private static final SourceType BASE_VALUE = SourceType.from("BaseValue");
    private static final SourceType SHELF_SCROLL = SourceType.from("ShelfScroll");

    private NotificationIconContainer mShelfIcons;
    private boolean mHideBackground;
    private int mStatusBarHeight;
    private boolean mEnableNotificationClipping;
    private AmbientState mAmbientState;
    private int mPaddingBetweenElements;
    private int mNotGoneIndex;
    private boolean mHasItemsInStableShelf;
    private int mScrollFastThreshold;
    private boolean mInteractive;
    private boolean mAnimationsEnabled = true;
    private boolean mShowNotificationShelf;
    private final Rect mClipRect = new Rect();
    private int mIndexOfFirstViewInShelf = -1;
    private float mCornerAnimationDistance;
    private float mActualWidth = -1;
    private int mMaxIconsOnLockscreen;
    private final RefactorFlag mSensitiveRevealAnim =
            RefactorFlag.forView(Flags.SENSITIVE_REVEAL_ANIM);
    private boolean mCanModifyColorOfNotifications;
    private boolean mCanInteract;
    private NotificationStackScrollLayout mHostLayout;
    private NotificationRoundnessManager mRoundnessManager;

    public NotificationShelf(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @VisibleForTesting
    public NotificationShelf(Context context, AttributeSet attrs, boolean showNotificationShelf) {
        super(context, attrs);
        mShowNotificationShelf = showNotificationShelf;
    }

    @Override
    @VisibleForTesting
    public void onFinishInflate() {
        super.onFinishInflate();
        mShelfIcons = findViewById(R.id.content);
        mShelfIcons.setClipChildren(false);
        mShelfIcons.setClipToPadding(false);

        setClipToActualHeight(false);
        setClipChildren(false);
        setClipToPadding(false);
        mShelfIcons.setIsStaticLayout(false);
        requestRoundness(/* top = */ 1f, /* bottom = */ 1f, BASE_VALUE, /* animate = */ false);
        updateResources();
    }

    public void bind(AmbientState ambientState, NotificationStackScrollLayout hostLayout,
            NotificationRoundnessManager roundnessManager) {
        mAmbientState = ambientState;
        mHostLayout = hostLayout;
        mRoundnessManager = roundnessManager;
    }

    private void updateResources() {
        Resources res = getResources();
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        mPaddingBetweenElements = res.getDimensionPixelSize(R.dimen.notification_divider_height);
        mMaxIconsOnLockscreen = res.getInteger(R.integer.max_notif_icons_on_lockscreen);

        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        final int newShelfHeight = res.getDimensionPixelOffset(R.dimen.notification_shelf_height);
        if (newShelfHeight != layoutParams.height) {
            layoutParams.height = newShelfHeight;
            setLayoutParams(layoutParams);
        }

        final int padding = res.getDimensionPixelOffset(R.dimen.shelf_icon_container_padding);
        mShelfIcons.setPadding(padding, 0, padding, 0);
        mScrollFastThreshold = res.getDimensionPixelOffset(R.dimen.scroll_fast_threshold);
        mShowNotificationShelf = res.getBoolean(R.bool.config_showNotificationShelf);
        mCornerAnimationDistance = res.getDimensionPixelSize(
                R.dimen.notification_corner_animation_distance);
        mEnableNotificationClipping = res.getBoolean(R.bool.notification_enable_clipping);

        if (NotificationIconContainerRefactor.isEnabled()) {
            mShelfIcons.setOverrideIconColor(true);
        } else {
            mShelfIcons.setInNotificationIconShelf(true);
        }
        if (!mShowNotificationShelf) {
            setVisibility(GONE);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    protected View getContentView() {
        return mShelfIcons;
    }

    public NotificationIconContainer getShelfIcons() {
        return mShelfIcons;
    }

    @Override
    @NonNull
    public ExpandableViewState createExpandableViewState() {
        return new ShelfState();
    }

    @Override
    public String toString() {
        return super.toString()
                + " (hideBackground=" + mHideBackground
                + " notGoneIndex=" + mNotGoneIndex
                + " hasItemsInStableShelf=" + mHasItemsInStableShelf
                + " interactive=" + mInteractive
                + " animationsEnabled=" + mAnimationsEnabled
                + " showNotificationShelf=" + mShowNotificationShelf
                + " indexOfFirstViewInShelf=" + mIndexOfFirstViewInShelf
                + ')';
    }

    /**
     * Update the state of the shelf.
     */
    public void updateState(StackScrollAlgorithm.StackScrollAlgorithmState algorithmState,
                            AmbientState ambientState) {
        ExpandableView lastView = ambientState.getLastVisibleBackgroundChild();
        ShelfState viewState = (ShelfState) getViewState();
        if (mShowNotificationShelf && lastView != null) {
            ExpandableViewState lastViewState = lastView.getViewState();
            viewState.copyFrom(lastViewState);

            viewState.height = getIntrinsicHeight();
            viewState.setZTranslation(ambientState.getBaseZHeight());
            viewState.clipTopAmount = 0;

            if (ambientState.isExpansionChanging() && !ambientState.isOnKeyguard()) {
                float expansion = ambientState.getExpansionFraction();
                if (ambientState.isBouncerInTransit()) {
                    viewState.setAlpha(aboutToShowBouncerProgress(expansion));
                } else {
                    if (ambientState.isSmallScreen()) {
                        viewState.setAlpha(ShadeInterpolation.getContentAlpha(expansion));
                    } else {
                        LargeScreenShadeInterpolator interpolator =
                                ambientState.getLargeScreenShadeInterpolator();
                        viewState.setAlpha(interpolator.getNotificationContentAlpha(expansion));
                    }
                }
            } else {
                viewState.setAlpha(1f - ambientState.getHideAmount());
            }
            if (!NotificationIconContainerRefactor.isEnabled()) {
                viewState.belowSpeedBump = getSpeedBumpIndex() == 0;
            }
            viewState.hideSensitive = false;
            viewState.setXTranslation(getTranslationX());
            viewState.hasItemsInStableShelf = lastViewState.inShelf;
            viewState.firstViewInShelf = algorithmState.firstViewInShelf;
            if (mNotGoneIndex != -1) {
                viewState.notGoneIndex = Math.min(viewState.notGoneIndex, mNotGoneIndex);
            }

            viewState.hidden = !mAmbientState.isShadeExpanded()
                    || algorithmState.firstViewInShelf == null;

            final int indexOfFirstViewInShelf = algorithmState.visibleChildren.indexOf(
                    algorithmState.firstViewInShelf);

            if (mAmbientState.isExpansionChanging()
                    && algorithmState.firstViewInShelf != null
                    && indexOfFirstViewInShelf > 0) {

                // Show shelf if section before it is showing.
                final ExpandableView viewBeforeShelf = algorithmState.visibleChildren.get(
                        indexOfFirstViewInShelf - 1);
                if (viewBeforeShelf.getViewState().hidden) {
                    viewState.hidden = true;
                }
            }
        } else {
            viewState.hidden = true;
            viewState.location = ExpandableViewState.LOCATION_GONE;
            viewState.hasItemsInStableShelf = false;
        }

        final float stackEnd = ambientState.getStackY() + ambientState.getStackHeight();
        if (mSensitiveRevealAnim.isEnabled() && viewState.hidden) {
            // if the shelf is hidden, position it at the end of the stack (plus the clip
            // padding), such that when it appears animated, it will smoothly move in from the
            // bottom, without jump cutting any notifications
            viewState.setYTranslation(stackEnd + mPaddingBetweenElements);
        } else {
            viewState.setYTranslation(stackEnd - viewState.height);
        }
    }

    private int getSpeedBumpIndex() {
        NotificationIconContainerRefactor.assertInLegacyMode();
        return mHostLayout.getSpeedBumpIndex();
    }

    /**
     * @param fractionToShade Fraction of lockscreen to shade transition
     * @param shortestWidth   Shortest width to use for lockscreen shelf
     */
    @VisibleForTesting
    public void updateActualWidth(float fractionToShade, float shortestWidth) {
        NotificationIconContainerRefactor.assertInLegacyMode();
        final float actualWidth = mAmbientState.isOnKeyguard()
                ? MathUtils.lerp(shortestWidth, getWidth(), fractionToShade)
                : getWidth();
        setBackgroundWidth((int) actualWidth);
        if (mShelfIcons != null) {
            mShelfIcons.setActualLayoutWidth((int) actualWidth);
        }
        mActualWidth = actualWidth;
    }

    private void setActualWidth(float actualWidth) {
        if (NotificationIconContainerRefactor.isUnexpectedlyInLegacyMode()) return;
        setBackgroundWidth((int) actualWidth);
        if (mShelfIcons != null) {
            mShelfIcons.setActualLayoutWidth((int) actualWidth);
        }
        mActualWidth = actualWidth;
    }

    @Override
    public void getBoundsOnScreen(Rect outRect, boolean clipToParent) {
        super.getBoundsOnScreen(outRect, clipToParent);
        final int actualWidth = getActualWidth();
        if (isLayoutRtl()) {
            outRect.left = outRect.right - actualWidth;
        } else {
            outRect.right = outRect.left + actualWidth;
        }
    }

    /**
     * @return Actual width of shelf, accounting for possible ongoing width animation
     */
    public int getActualWidth() {
        return mActualWidth > -1 ? (int) mActualWidth : getWidth();
    }

    /**
     * @param localX Click x from left of screen
     * @param slop   Margin of error within which we count x for valid click
     * @param left   Left of shelf, from left of screen
     * @param right  Right of shelf, from left of screen
     * @return Whether click x was in view
     */
    @VisibleForTesting
    public boolean isXInView(float localX, float slop, float left, float right) {
        return (left - slop) <= localX && localX < (right + slop);
    }

    /**
     * @param localY Click y from top of shelf
     * @param slop   Margin of error within which we count y for valid click
     * @param top    Top of shelf
     * @param bottom Height of shelf
     * @return Whether click y was in view
     */
    @VisibleForTesting
    public boolean isYInView(float localY, float slop, float top, float bottom) {
        return (top - slop) <= localY && localY < (bottom + slop);
    }

    /**
     * @param localX Click x
     * @param localY Click y
     * @param slop   Margin of error for valid click
     * @return Whether this click was on the visible (non-clipped) part of the shelf
     */
    @Override
    public boolean pointInView(float localX, float localY, float slop) {
        final float containerWidth = getWidth();
        final float shelfWidth = getActualWidth();

        final float left = isLayoutRtl() ? containerWidth - shelfWidth : 0;
        final float right = isLayoutRtl() ? containerWidth : shelfWidth;

        final float top = mClipTopAmount;
        final float bottom = getActualHeight();

        return isXInView(localX, slop, left, right)
                && isYInView(localY, slop, top, bottom);
    }

    @Override
    public void updateBackgroundColors() {
        super.updateBackgroundColors();
        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logEvent("Shelf.updateBackgroundColors()",
                    "normalBgColor=" + hexColorString(getNormalBgColor())
                            + " background=" + mBackgroundNormal.toDumpString());
        }
    }

    /**
     * Update the shelf appearance based on the other notifications around it. This transforms
     * the icons from the notification area into the shelf.
     */
    public void updateAppearance() {
        // If the shelf should not be shown, then there is no need to update anything.
        if (!mShowNotificationShelf) {
            return;
        }
        mShelfIcons.resetViewStates();
        float shelfStart = getTranslationY();
        float numViewsInShelf = 0.0f;
        View lastChild = mAmbientState.getLastVisibleBackgroundChild();
        mNotGoneIndex = -1;
        //  find the first view that doesn't overlap with the shelf
        int notGoneIndex = 0;
        int colorOfViewBeforeLast = NO_COLOR;
        boolean backgroundForceHidden = false;
        if (mHideBackground && !((ShelfState) getViewState()).hasItemsInStableShelf) {
            backgroundForceHidden = true;
        }
        int colorTwoBefore = NO_COLOR;
        int previousColor = NO_COLOR;
        float transitionAmount = 0.0f;
        float currentScrollVelocity = mAmbientState.getCurrentScrollVelocity();
        boolean scrollingFast = currentScrollVelocity > mScrollFastThreshold
                || (mAmbientState.isExpansionChanging()
                && Math.abs(mAmbientState.getExpandingVelocity()) > mScrollFastThreshold);
        boolean expandingAnimated = mAmbientState.isExpansionChanging()
                && !mAmbientState.isPanelTracking();
        int baseZHeight = mAmbientState.getBaseZHeight();
        int clipTopAmount = 0;

        for (int i = 0; i < getHostLayoutChildCount(); i++) {
            ExpandableView child = getHostLayoutChildAt(i);
            if (!child.needsClippingToShelf() || child.getVisibility() == GONE) {
                continue;
            }
            float notificationClipEnd;
            boolean aboveShelf = ViewState.getFinalTranslationZ(child) > baseZHeight
                    || child.isPinned();
            boolean isLastChild = child == lastChild;
            final float viewStart = child.getTranslationY();
            final float shelfClipStart = getTranslationY() - mPaddingBetweenElements;
            final float inShelfAmount = getAmountInShelf(i, child, scrollingFast,
                    expandingAnimated, isLastChild, shelfClipStart);

            // TODO(b/172289889) scale mPaddingBetweenElements with expansion amount
            if ((!mSensitiveRevealAnim.isEnabled() && ((isLastChild && !child.isInShelf())
                    || backgroundForceHidden)) || aboveShelf) {
                notificationClipEnd = shelfStart + getIntrinsicHeight();
            } else {
                notificationClipEnd = shelfStart - mPaddingBetweenElements;
            }
            int clipTop = updateNotificationClipHeight(child, notificationClipEnd, notGoneIndex);
            clipTopAmount = Math.max(clipTop, clipTopAmount);

            // If the current row is an ExpandableNotificationRow, update its color, roundedness,
            // and icon state.
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow expandableRow = (ExpandableNotificationRow) child;
                numViewsInShelf += inShelfAmount;
                int ownColorUntinted = expandableRow.getBackgroundColorWithoutTint();
                if (viewStart >= shelfStart && mNotGoneIndex == -1) {
                    mNotGoneIndex = notGoneIndex;
                    setTintColor(previousColor);
                    setOverrideTintColor(colorTwoBefore, transitionAmount);

                } else if (mNotGoneIndex == -1) {
                    colorTwoBefore = previousColor;
                    transitionAmount = inShelfAmount;
                }
                // We don't want to modify the color if the notification is hun'd
                if (isLastChild && canModifyColorOfNotifications()) {
                    if (colorOfViewBeforeLast == NO_COLOR) {
                        colorOfViewBeforeLast = ownColorUntinted;
                    }
                    expandableRow.setOverrideTintColor(colorOfViewBeforeLast, inShelfAmount);
                } else {
                    colorOfViewBeforeLast = ownColorUntinted;
                    expandableRow.setOverrideTintColor(NO_COLOR, 0 /* overrideAmount */);
                }
                if (notGoneIndex != 0 || !aboveShelf) {
                    expandableRow.setAboveShelf(false);
                }

                previousColor = ownColorUntinted;
                notGoneIndex++;
            }

            if (child instanceof ActivatableNotificationView) {
                ActivatableNotificationView anv = (ActivatableNotificationView) child;
                // Because we show whole notifications on the lockscreen, the bottom notification is
                // always "just about to enter the shelf" by normal scrolling rules.  This is fine
                // if the shelf is visible, but if the shelf is hidden, it causes incorrect curling.
                // notificationClipEnd handles the discrepancy between a visible and hidden shelf,
                // so we use that when on the keyguard (and while animating away) to reduce curling.
                final float keyguardSafeShelfStart = !mSensitiveRevealAnim.isEnabled()
                        && mAmbientState.isOnKeyguard() ? notificationClipEnd : shelfStart;
                updateCornerRoundnessOnScroll(anv, viewStart, keyguardSafeShelfStart);
            }
        }

        clipTransientViews();

        setClipTopAmount(clipTopAmount);

        boolean isHidden = getViewState().hidden
                || clipTopAmount >= getIntrinsicHeight()
                || !mShowNotificationShelf
                || numViewsInShelf < 1f;

        final float fractionToShade = Interpolators.STANDARD.getInterpolation(
                mAmbientState.getFractionToShade());

        if (NotificationIconContainerRefactor.isEnabled()) {
            if (mAmbientState.isOnKeyguard()) {
                float numViews = MathUtils.min(numViewsInShelf, mMaxIconsOnLockscreen + 1);
                float shortestWidth = mShelfIcons.calculateWidthFor(numViews);
                float actualWidth = MathUtils.lerp(shortestWidth, getWidth(), fractionToShade);
                setActualWidth(actualWidth);
            } else {
                setActualWidth(getWidth());
            }
        } else {
            final float shortestWidth = mShelfIcons.calculateWidthFor(numViewsInShelf);
            updateActualWidth(fractionToShade, shortestWidth);
        }

        // TODO(b/172289889) transition last icon in shelf to notification icon and vice versa.
        setVisibility(isHidden ? View.INVISIBLE : View.VISIBLE);
        if (!NotificationIconContainerRefactor.isEnabled()) {
            mShelfIcons.setSpeedBumpIndex(getSpeedBumpIndex());
        }
        mShelfIcons.calculateIconXTranslations();
        mShelfIcons.applyIconStates();
        for (int i = 0; i < getHostLayoutChildCount(); i++) {
            View child = getHostLayoutChildAt(i);
            if (!(child instanceof ExpandableNotificationRow)
                    || child.getVisibility() == GONE) {
                continue;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            updateContinuousClipping(row);
        }
        boolean hideBackground = isHidden;
        setHideBackground(hideBackground);
        if (mNotGoneIndex == -1) {
            mNotGoneIndex = notGoneIndex;
        }
    }

    private ExpandableView getHostLayoutChildAt(int index) {
        return (ExpandableView) mHostLayout.getChildAt(index);
    }

    private int getHostLayoutChildCount() {
        return mHostLayout.getChildCount();
    }

    private boolean canModifyColorOfNotifications() {
        return mCanModifyColorOfNotifications && mAmbientState.isShadeExpanded();
    }

    private void updateCornerRoundnessOnScroll(
            ActivatableNotificationView anv,
            float viewStart,
            float shelfStart) {

        final boolean isUnlockedHeadsUp = !mAmbientState.isOnKeyguard()
                && !mAmbientState.isShadeExpanded()
                && anv instanceof ExpandableNotificationRow
                && anv.isHeadsUp();

        final boolean isHunGoingToShade = mAmbientState.isShadeExpanded()
                && anv == mAmbientState.getTrackedHeadsUpRow();

        final boolean shouldUpdateCornerRoundness = viewStart < shelfStart
                && !isViewAffectedBySwipe(anv)
                && !isUnlockedHeadsUp
                && !isHunGoingToShade
                && !anv.isAboveShelf()
                && !mAmbientState.isPulsing()
                && !mAmbientState.isDozing();

        if (!shouldUpdateCornerRoundness) {
            return;
        }

        final float viewEnd = viewStart + anv.getActualHeight();
        final float cornerAnimationDistance = mCornerAnimationDistance
                * mAmbientState.getExpansionFraction();
        final float cornerAnimationTop = shelfStart - cornerAnimationDistance;

        final float topValue;
        if (viewStart >= cornerAnimationTop) {
            // Round top corners within animation bounds
            topValue = MathUtils.saturate(
                    (viewStart - cornerAnimationTop) / cornerAnimationDistance);
        } else {
            // Fast scroll skips frames and leaves corners with unfinished rounding.
            // Reset top and bottom corners outside of animation bounds.
            topValue = 0f;
        }
        anv.requestTopRoundness(topValue, SHELF_SCROLL, /* animate = */ false);

        final float bottomValue;
        if (viewEnd >= cornerAnimationTop) {
            // Round bottom corners within animation bounds
            bottomValue = MathUtils.saturate(
                    (viewEnd - cornerAnimationTop) / cornerAnimationDistance);
        } else {
            // Fast scroll skips frames and leaves corners with unfinished rounding.
            // Reset top and bottom corners outside of animation bounds.
            bottomValue = 0f;
        }
        anv.requestBottomRoundness(bottomValue, SHELF_SCROLL, /* animate = */ false);
    }

    private boolean isViewAffectedBySwipe(ExpandableView expandableView) {
        return mRoundnessManager.isViewAffectedBySwipe(expandableView);
    }

    /**
     * Clips transient views to the top of the shelf - Transient views are only used for
     * disappearing views/animations and need to be clipped correctly by the shelf to ensure they
     * don't show underneath the notification stack when something is animating and the user
     * swipes quickly.
     */
    private void clipTransientViews() {
        for (int i = 0; i < getHostLayoutTransientViewCount(); i++) {
            View transientView = getHostLayoutTransientView(i);
            if (transientView instanceof ExpandableView) {
                ExpandableView transientExpandableView = (ExpandableView) transientView;
                updateNotificationClipHeight(transientExpandableView, getTranslationY(), -1);
            }
        }
    }

    private View getHostLayoutTransientView(int index) {
        return mHostLayout.getTransientView(index);
    }

    private int getHostLayoutTransientViewCount() {
        return mHostLayout.getTransientViewCount();
    }

    private void updateIconClipAmount(ExpandableNotificationRow row) {
        float maxTop = row.getTranslationY();
        if (getClipTopAmount() != 0) {
            // if the shelf is clipped, lets make sure we also clip the icon
            maxTop = Math.max(maxTop, getTranslationY() + getClipTopAmount());
        }
        StatusBarIconView icon = row.getEntry().getIcons().getShelfIcon();
        float shelfIconPosition = getTranslationY() + icon.getTop() + icon.getTranslationY();
        if (shelfIconPosition < maxTop && !mAmbientState.isFullyHidden()) {
            int top = (int) (maxTop - shelfIconPosition);
            Rect clipRect = new Rect(0, top, icon.getWidth(), Math.max(top, icon.getHeight()));
            icon.setClipBounds(clipRect);
        } else {
            icon.setClipBounds(null);
        }
    }

    private void updateContinuousClipping(final ExpandableNotificationRow row) {
        StatusBarIconView icon = row.getEntry().getIcons().getShelfIcon();
        boolean needsContinuousClipping = ViewState.isAnimatingY(icon) && !mAmbientState.isDozing();
        boolean isContinuousClipping = icon.getTag(TAG_CONTINUOUS_CLIPPING) != null;
        if (needsContinuousClipping && !isContinuousClipping) {
            final ViewTreeObserver observer = icon.getViewTreeObserver();
            ViewTreeObserver.OnPreDrawListener predrawListener =
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            boolean animatingY = ViewState.isAnimatingY(icon);
                            if (!animatingY) {
                                if (observer.isAlive()) {
                                    observer.removeOnPreDrawListener(this);
                                }
                                icon.setTag(TAG_CONTINUOUS_CLIPPING, null);
                                return true;
                            }
                            updateIconClipAmount(row);
                            return true;
                        }
                    };
            observer.addOnPreDrawListener(predrawListener);
            icon.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    if (v == icon) {
                        if (observer.isAlive()) {
                            observer.removeOnPreDrawListener(predrawListener);
                        }
                        icon.setTag(TAG_CONTINUOUS_CLIPPING, null);
                    }
                }
            });
            icon.setTag(TAG_CONTINUOUS_CLIPPING, predrawListener);
        }
    }

    /**
     * Update the clipping of this view.
     *
     * @return the amount that our own top should be clipped
     */
    private int updateNotificationClipHeight(ExpandableView view,
                                             float notificationClipEnd, int childIndex) {
        float viewEnd = view.getTranslationY() + view.getActualHeight();
        boolean isPinned = (view.isPinned() || view.isHeadsUpAnimatingAway())
                && !mAmbientState.isDozingAndNotPulsing(view);
        boolean shouldClipOwnTop;
        if (mAmbientState.isPulseExpanding()) {
            shouldClipOwnTop = childIndex == 0;
        } else {
            shouldClipOwnTop = view.showingPulsing();
        }
        if (!isPinned) {
            if (viewEnd > notificationClipEnd && !shouldClipOwnTop) {
                int clipBottomAmount =
                        mEnableNotificationClipping ? (int) (viewEnd - notificationClipEnd) : 0;
                view.setClipBottomAmount(clipBottomAmount);
            } else {
                view.setClipBottomAmount(0);
            }
        }
        if (shouldClipOwnTop) {
            return (int) (viewEnd - getTranslationY());
        } else {
            return 0;
        }
    }

    @Override
    public void setFakeShadowIntensity(float shadowIntensity, float outlineAlpha, int shadowYEnd,
                                       int outlineTranslation) {
        if (!mHasItemsInStableShelf) {
            shadowIntensity = 0.0f;
        }
        super.setFakeShadowIntensity(shadowIntensity, outlineAlpha, shadowYEnd, outlineTranslation);
    }

    /**
     * @param i                 Index of the view in the host layout.
     * @param view              The current ExpandableView.
     * @param scrollingFast     Whether we are scrolling fast.
     * @param expandingAnimated Whether we are expanding a notification.
     * @param isLastChild       Whether this is the last view.
     * @param shelfClipStart    The point at which notifications start getting clipped by the shelf.
     * @return The amount how much this notification is in the shelf.
     * 0f is not in shelf. 1f is completely in shelf.
     */
    @VisibleForTesting
    public float getAmountInShelf(
            int i,
            ExpandableView view,
            boolean scrollingFast,
            boolean expandingAnimated,
            boolean isLastChild,
            float shelfClipStart
    ) {

        // Let's calculate how much the view is in the shelf
        float viewStart = view.getTranslationY();
        int fullHeight = view.getActualHeight() + mPaddingBetweenElements;
        float iconTransformStart = calculateIconTransformationStart(view);

        // Let's make sure the transform distance is
        // at most to the icon (relevant for conversations)
        float transformDistance = Math.min(
                viewStart + fullHeight - iconTransformStart,
                getIntrinsicHeight());

        if (isLastChild) {
            fullHeight = Math.min(fullHeight, view.getMinHeight() - getIntrinsicHeight());
            transformDistance = Math.min(
                    transformDistance,
                    view.getMinHeight() - getIntrinsicHeight());
        }

        float viewEnd = viewStart + fullHeight;
        float fullTransitionAmount = 0.0f;
        float iconTransitionAmount = 0.0f;

        // Don't animate shelf icons during shade expansion.
        if (mAmbientState.isExpansionChanging() && !mAmbientState.isOnKeyguard()) {
            // TODO(b/172289889) handle icon placement for notification that is clipped by the shelf
            if (mIndexOfFirstViewInShelf != -1 && i >= mIndexOfFirstViewInShelf) {
                fullTransitionAmount = 1f;
                iconTransitionAmount = 1f;
            }

        } else if (viewEnd >= shelfClipStart
                && (mAmbientState.isShadeExpanded()
                || (!view.isPinned() && !view.isHeadsUpAnimatingAway()))) {

            if (viewStart < shelfClipStart && Math.abs(viewStart - shelfClipStart) > 0.001f) {
                // Partially clipped by shelf.
                float fullAmount = (shelfClipStart - viewStart) / fullHeight;
                fullAmount = Math.min(1.0f, fullAmount);
                fullTransitionAmount = 1.0f - fullAmount;
                if (isLastChild) {
                    // Reduce icon transform distance to completely fade in shelf icon
                    // by the time the notification icon fades out, and vice versa
                    iconTransitionAmount = (shelfClipStart - viewStart)
                            / (iconTransformStart - viewStart);
                } else {
                    iconTransitionAmount = (shelfClipStart - iconTransformStart)
                            / transformDistance;
                }
                iconTransitionAmount = MathUtils.constrain(iconTransitionAmount, 0.0f, 1.0f);
                iconTransitionAmount = 1.0f - iconTransitionAmount;
            } else {
                // Fully in shelf.
                fullTransitionAmount = 1.0f;
                iconTransitionAmount = 1.0f;
            }
        }
        updateIconPositioning(view, iconTransitionAmount,
                scrollingFast, expandingAnimated, isLastChild);
        return fullTransitionAmount;
    }

    /**
     * @return the location where the transformation into the shelf should start.
     */
    private float calculateIconTransformationStart(ExpandableView view) {
        View target = view.getShelfTransformationTarget();
        if (target == null) {
            return view.getTranslationY();
        }
        float start = view.getTranslationY() + view.getRelativeTopPadding(target);

        // Let's not start the transformation right at the icon but by the padding before it.
        start -= view.getShelfIcon().getTop();
        return start;
    }

    private void updateIconPositioning(
            ExpandableView view,
            float iconTransitionAmount,
            boolean scrollingFast,
            boolean expandingAnimated,
            boolean isLastChild
    ) {
        StatusBarIconView icon = view.getShelfIcon();
        NotificationIconContainer.IconState iconState = getIconState(icon);
        if (iconState == null) {
            return;
        }
        boolean clampInShelf = iconTransitionAmount > 0.5f || isTargetClipped(view);
        float clampedAmount = clampInShelf ? 1.0f : 0.0f;
        if (iconTransitionAmount == clampedAmount) {
            iconState.noAnimations = (scrollingFast || expandingAnimated) && !isLastChild;
        }
        if (!isLastChild
                && (scrollingFast || (expandingAnimated && !ViewState.isAnimatingY(icon)))) {
            iconState.cancelAnimations(icon);
            iconState.noAnimations = true;
        }
        float transitionAmount;
        if (mAmbientState.isHiddenAtAll() && !view.isInShelf()) {
            transitionAmount = mAmbientState.isFullyHidden() ? 1 : 0;
        } else {
            transitionAmount = iconTransitionAmount;
            iconState.needsCannedAnimation = iconState.clampedAppearAmount != clampedAmount;
        }
        iconState.clampedAppearAmount = clampedAmount;
        setIconTransformationAmount(view, transitionAmount);
    }

    private boolean isTargetClipped(ExpandableView view) {
        View target = view.getShelfTransformationTarget();
        if (target == null) {
            return false;
        }
        // We should never clip the target, let's instead put it into the shelf!
        float endOfTarget = view.getTranslationY()
                + view.getContentTranslation()
                + view.getRelativeTopPadding(target)
                + target.getHeight();
        return endOfTarget >= getTranslationY() - mPaddingBetweenElements;
    }

    private void setIconTransformationAmount(ExpandableView view, float transitionAmount) {
        if (!(view instanceof ExpandableNotificationRow)) {
            return;
        }
        ExpandableNotificationRow row = (ExpandableNotificationRow) view;
        StatusBarIconView icon = row.getShelfIcon();
        NotificationIconContainer.IconState iconState = getIconState(icon);
        if (iconState == null) {
            return;
        }
        iconState.setAlpha(ICON_ALPHA_INTERPOLATOR.getInterpolation(transitionAmount));
        boolean isAppearing = row.isDrawingAppearAnimation() && !row.isInShelf();
        iconState.hidden = isAppearing
                || (view instanceof ExpandableNotificationRow
                && ((ExpandableNotificationRow) view).isLowPriority()
                && mShelfIcons.areIconsOverflowing())
                || (transitionAmount == 0.0f && !iconState.isAnimating(icon))
                || row.isAboveShelf()
                || row.showingPulsing()
                || row.getTranslationZ() > mAmbientState.getBaseZHeight();

        iconState.iconAppearAmount = iconState.hidden ? 0f : transitionAmount;

        // Fade in icons at shelf start
        // This is important for conversation icons, which are badged and need x reset
        iconState.setXTranslation(mShelfIcons.getActualPaddingStart());

        final boolean stayingInShelf = row.isInShelf() && !row.isTransformingIntoShelf();
        if (stayingInShelf) {
            iconState.iconAppearAmount = 1.0f;
            iconState.setAlpha(1.0f);
            iconState.hidden = false;
        }
        int backgroundColor = getBackgroundColorWithoutTint();
        int shelfColor = icon.getContrastedStaticDrawableColor(backgroundColor);
        if (row.isShowingIcon() && shelfColor != StatusBarIconView.NO_COLOR) {
            int iconColor = row.getOriginalIconColor();
            shelfColor = NotificationUtils.interpolateColors(iconColor, shelfColor,
                    iconState.iconAppearAmount);
        }
        iconState.iconColor = shelfColor;
    }

    private NotificationIconContainer.IconState getIconState(StatusBarIconView icon) {
        if (mShelfIcons == null) {
            return null;
        }
        return mShelfIcons.getIconState(icon);
    }

    @Override
    public boolean hasNoContentHeight() {
        return true;
    }

    private void setHideBackground(boolean hideBackground) {
        if (mHideBackground != hideBackground) {
            mHideBackground = hideBackground;
            updateOutline();
        }
    }

    @Override
    protected boolean needsOutline() {
        return !mHideBackground && super.needsOutline();
    }


    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // we always want to clip to our sides, such that nothing can draw outside of these bounds
        int height = getResources().getDisplayMetrics().heightPixels;
        mClipRect.set(0, -height, getWidth(), height);
        if (mShelfIcons != null) {
            mShelfIcons.setClipBounds(mClipRect);
        }
    }

    /**
     * @return the index of the notification at which the shelf visually resides
     */
    public int getNotGoneIndex() {
        return mNotGoneIndex;
    }

    private void setHasItemsInStableShelf(boolean hasItemsInStableShelf) {
        if (mHasItemsInStableShelf != hasItemsInStableShelf) {
            mHasItemsInStableShelf = hasItemsInStableShelf;
            updateInteractiveness();
        }
    }

    private void updateInteractiveness() {
        mInteractive = mCanInteract && mHasItemsInStableShelf;
        setClickable(mInteractive);
        setFocusable(mInteractive);
        setImportantForAccessibility(mInteractive ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    @Override
    protected boolean isInteractive() {
        return mInteractive;
    }

    public void setAnimationsEnabled(boolean enabled) {
        mAnimationsEnabled = enabled;
        if (!enabled) {
            // we need to wait with enabling the animations until the first frame has passed
            mShelfIcons.setAnimationsEnabled(false);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;  // Shelf only uses alpha for transitions where the difference can't be seen.
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mInteractive) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            AccessibilityNodeInfo.AccessibilityAction unlock
                    = new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    getContext().getString(R.string.accessibility_overflow_action));
            info.addAction(unlock);
        }
    }

    @Override
    public boolean needsClippingToShelf() {
        return false;
    }

    public void setCanModifyColorOfNotifications(boolean canModifyColorOfNotifications) {
        mCanModifyColorOfNotifications = canModifyColorOfNotifications;
    }

    public void setCanInteract(boolean canInteract) {
        mCanInteract = canInteract;
        updateInteractiveness();
    }

    public void setIndexOfFirstViewInShelf(ExpandableView firstViewInShelf) {
        mIndexOfFirstViewInShelf = getIndexOfViewInHostLayout(firstViewInShelf);
    }

    private int getIndexOfViewInHostLayout(ExpandableView child) {
        return mHostLayout.indexOfChild(child);
    }

    public void requestRoundnessResetFor(ExpandableView child) {
        child.requestRoundnessReset(SHELF_SCROLL);
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        super.dump(pw, args);
        if (DUMP_VERBOSE) {
            DumpUtilsKt.withIncreasedIndent(pw, () -> {
                pw.println("mActualWidth: " + mActualWidth);
                pw.println("mStatusBarHeight: " + mStatusBarHeight);
            });
        }
    }

    public class ShelfState extends ExpandableViewState {
        private boolean hasItemsInStableShelf;
        private ExpandableView firstViewInShelf;

        @Override
        public void applyToView(View view) {
            if (!mShowNotificationShelf) {
                return;
            }
            super.applyToView(view);
            setIndexOfFirstViewInShelf(firstViewInShelf);
            updateAppearance();
            setHasItemsInStableShelf(hasItemsInStableShelf);
            mShelfIcons.setAnimationsEnabled(mAnimationsEnabled);
        }

        @Override
        public void animateTo(View view, AnimationProperties properties) {
            if (!mShowNotificationShelf) {
                return;
            }
            super.animateTo(view, properties);
            setIndexOfFirstViewInShelf(firstViewInShelf);
            updateAppearance();
            setHasItemsInStableShelf(hasItemsInStableShelf);
            mShelfIcons.setAnimationsEnabled(mAnimationsEnabled);
        }
    }
}
