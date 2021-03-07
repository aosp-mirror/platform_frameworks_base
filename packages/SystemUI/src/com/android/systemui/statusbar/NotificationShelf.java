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

import static com.android.systemui.Interpolators.FAST_OUT_SLOW_IN_REVERSE;
import static com.android.systemui.statusbar.phone.NotificationIconContainer.IconState.NO_VALUE;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.ViewState;
import com.android.systemui.statusbar.phone.NotificationIconContainer;

/**
 * A notification shelf view that is placed inside the notification scroller. It manages the
 * overflow icons that don't fit into the regular list anymore.
 */
public class NotificationShelf extends ActivatableNotificationView implements
        View.OnLayoutChangeListener, StateListener {

    private static final boolean USE_ANIMATIONS_WHEN_OPENING =
            SystemProperties.getBoolean("debug.icon_opening_animations", true);
    private static final boolean ICON_ANMATIONS_WHILE_SCROLLING
            = SystemProperties.getBoolean("debug.icon_scroll_animations", true);
    private static final int TAG_CONTINUOUS_CLIPPING = R.id.continuous_clipping_tag;
    private static final String TAG = "NotificationShelf";

    private NotificationIconContainer mShelfIcons;
    private int[] mTmp = new int[2];
    private boolean mHideBackground;
    private int mIconAppearTopPadding;
    private float mHiddenShelfIconSize;
    private int mStatusBarHeight;
    private AmbientState mAmbientState;
    private NotificationStackScrollLayoutController mHostLayoutController;
    private int mMaxLayoutHeight;
    private int mPaddingBetweenElements;
    private int mNotGoneIndex;
    private boolean mHasItemsInStableShelf;
    private NotificationIconContainer mCollapsedIcons;
    private int mScrollFastThreshold;
    private int mIconSize;
    private int mStatusBarState;
    private int mRelativeOffset;
    private boolean mInteractive;
    private float mOpenedAmount;
    private boolean mNoAnimationsInThisFrame;
    private boolean mAnimationsEnabled = true;
    private boolean mShowNotificationShelf;
    private float mFirstElementRoundness;
    private Rect mClipRect = new Rect();
    private int mCutoutHeight;
    private int mGapHeight;
    private NotificationShelfController mController;

    public NotificationShelf(Context context, AttributeSet attrs) {
        super(context, attrs);
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
        setBottomRoundness(1.0f, false /* animate */);

        // Setting this to first in section to get the clipping to the top roundness correct. This
        // value determines the way we are clipping to the top roundness of the overall shade
        setFirstInSection(true);
        initDimens();
    }

    public void bind(AmbientState ambientState,
            NotificationStackScrollLayoutController hostLayoutController) {
        mAmbientState = ambientState;
        mHostLayoutController = hostLayoutController;
    }

    private void initDimens() {
        Resources res = getResources();
        mIconAppearTopPadding = res.getDimensionPixelSize(R.dimen.notification_icon_appear_padding);
        mStatusBarHeight = res.getDimensionPixelOffset(R.dimen.status_bar_height);
        mPaddingBetweenElements = res.getDimensionPixelSize(R.dimen.notification_divider_height);

        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.height = res.getDimensionPixelOffset(R.dimen.notification_shelf_height);
        setLayoutParams(layoutParams);

        int padding = res.getDimensionPixelOffset(R.dimen.shelf_icon_container_padding);
        mShelfIcons.setPadding(padding, 0, padding, 0);
        mScrollFastThreshold = res.getDimensionPixelOffset(R.dimen.scroll_fast_threshold);
        mShowNotificationShelf = res.getBoolean(R.bool.config_showNotificationShelf);
        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);
        mHiddenShelfIconSize = res.getDimensionPixelOffset(R.dimen.hidden_shelf_icon_size);
        mGapHeight = res.getDimensionPixelSize(R.dimen.qs_notification_padding);

        if (!mShowNotificationShelf) {
            setVisibility(GONE);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initDimens();
    }

    @Override
    protected View getContentView() {
        return mShelfIcons;
    }

    public NotificationIconContainer getShelfIcons() {
        return mShelfIcons;
    }

    @Override
    public ExpandableViewState createExpandableViewState() {
        return new ShelfState();
    }

    /** Update the state of the shelf. */
    public void updateState(AmbientState ambientState) {
        ExpandableView lastView = ambientState.getLastVisibleBackgroundChild();
        ShelfState viewState = (ShelfState) getViewState();
        if (mShowNotificationShelf && lastView != null) {
            float maxShelfEnd = ambientState.getInnerHeight() + ambientState.getTopPadding()
                    + ambientState.getStackTranslation();
            ExpandableViewState lastViewState = lastView.getViewState();
            float viewEnd = lastViewState.yTranslation + lastViewState.height;
            viewState.copyFrom(lastViewState);
            viewState.height = getIntrinsicHeight();

            viewState.yTranslation = Math.max(Math.min(viewEnd, maxShelfEnd) - viewState.height,
                    getFullyClosedTranslation());
            viewState.zTranslation = ambientState.getBaseZHeight();
            // For the small display size, it's not enough to make the icon not covered by
            // the top cutout so the denominator add the height of cutout.
            // Totally, (getIntrinsicHeight() * 2 + mCutoutHeight) should be smaller then
            // mAmbientState.getTopPadding().
            float openedAmount = (viewState.yTranslation - getFullyClosedTranslation())
                    / (getIntrinsicHeight() * 2 + mCutoutHeight);
            openedAmount = Math.min(1.0f, openedAmount);
            viewState.openedAmount = openedAmount;
            viewState.clipTopAmount = 0;
            viewState.alpha = 1;
            viewState.belowSpeedBump = mHostLayoutController.getSpeedBumpIndex() == 0;
            viewState.hideSensitive = false;
            viewState.xTranslation = getTranslationX();
            if (mNotGoneIndex != -1) {
                viewState.notGoneIndex = Math.min(viewState.notGoneIndex, mNotGoneIndex);
            }
            viewState.hasItemsInStableShelf = lastViewState.inShelf;
            viewState.hidden = !mAmbientState.isShadeExpanded()
                    || mAmbientState.isQsCustomizerShowing();
        } else {
            viewState.hidden = true;
            viewState.location = ExpandableViewState.LOCATION_GONE;
            viewState.hasItemsInStableShelf = false;
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
        float interpolationStart = mMaxLayoutHeight - getIntrinsicHeight() * 2;
        float expandAmount = 0.0f;
        if (shelfStart >= interpolationStart) {
            expandAmount = (shelfStart - interpolationStart) / getIntrinsicHeight();
            expandAmount = Math.min(1.0f, expandAmount);
        }
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
        boolean scrolling = currentScrollVelocity > 0;
        boolean expandingAnimated = mAmbientState.isExpansionChanging()
                && !mAmbientState.isPanelTracking();
        int baseZHeight = mAmbientState.getBaseZHeight();
        int backgroundTop = 0;
        int clipTopAmount = 0;
        float firstElementRoundness = 0.0f;
        ActivatableNotificationView previousAnv = null;

        for (int i = 0; i < mHostLayoutController.getChildCount(); i++) {
            ExpandableView child = (ExpandableView) mHostLayoutController.getChildAt(i);

            if (!child.needsClippingToShelf() || child.getVisibility() == GONE) {
                continue;
            }

            float notificationClipEnd;
            boolean aboveShelf = ViewState.getFinalTranslationZ(child) > baseZHeight
                    || child.isPinned();
            boolean isLastChild = child == lastChild;
            float rowTranslationY = child.getTranslationY();
            if ((isLastChild && !child.isInShelf()) || aboveShelf || backgroundForceHidden) {
                notificationClipEnd = shelfStart + getIntrinsicHeight();
            } else {
                notificationClipEnd = shelfStart - mPaddingBetweenElements;
            }
            int clipTop = updateNotificationClipHeight(child, notificationClipEnd, notGoneIndex);
            clipTopAmount = Math.max(clipTop, clipTopAmount);


            float inShelfAmount = updateShelfTransformation(child, expandAmount, scrolling,
                    scrollingFast, expandingAnimated, isLastChild);
            // If the current row is an ExpandableNotificationRow, update its color, roundedness,
            // and icon state.
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow expandableRow = (ExpandableNotificationRow) child;
                numViewsInShelf += inShelfAmount;
                int ownColorUntinted = expandableRow.getBackgroundColorWithoutTint();
                if (rowTranslationY >= shelfStart && mNotGoneIndex == -1) {
                    mNotGoneIndex = notGoneIndex;
                    setTintColor(previousColor);
                    setOverrideTintColor(colorTwoBefore, transitionAmount);

                } else if (mNotGoneIndex == -1) {
                    colorTwoBefore = previousColor;
                    transitionAmount = inShelfAmount;
                }
                // We don't want to modify the color if the notification is hun'd
                if (isLastChild && mController.canModifyColorOfNotifications()) {
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
                if (notGoneIndex == 0) {
                    StatusBarIconView icon = expandableRow.getEntry().getIcons().getShelfIcon();
                    NotificationIconContainer.IconState iconState = getIconState(icon);
                    // The icon state might be null in rare cases where the notification is actually
                    // added to the layout, but not to the shelf. An example are replied messages,
                    // since they don't show up on AOD
                    if (iconState != null && iconState.clampedAppearAmount == 1.0f) {
                        // only if the first icon is fully in the shelf we want to clip to it!
                        backgroundTop = (int) (child.getTranslationY() - getTranslationY());
                        firstElementRoundness = expandableRow.getCurrentTopRoundness();
                    }
                }

                previousColor = ownColorUntinted;
                notGoneIndex++;
            }

            if (child instanceof ActivatableNotificationView) {
                ActivatableNotificationView anv =
                        (ActivatableNotificationView) child;
                if (anv.isFirstInSection() && previousAnv != null
                        && previousAnv.isLastInSection()) {
                    // If the top of the shelf is between the view before a gap and the view after a
                    // gap then we need to adjust the shelf's top roundness.
                    float distanceToGapBottom = child.getTranslationY() - getTranslationY();
                    float distanceToGapTop = getTranslationY()
                            - (previousAnv.getTranslationY() + previousAnv.getActualHeight());
                    if (distanceToGapTop > 0) {
                        // We interpolate our top roundness so that it's fully rounded if we're at
                        // the bottom of the gap, and not rounded at all if we're at the top of the
                        // gap (directly up against the bottom of previousAnv)
                        // Then we apply the same roundness to the bottom of previousAnv so that the
                        // corners join together as the shelf approaches previousAnv.
                        firstElementRoundness = (float) Math.min(1.0,
                                distanceToGapTop / mGapHeight);
                        previousAnv.setBottomRoundness(firstElementRoundness,
                                false /* don't animate */);
                        backgroundTop = (int) distanceToGapBottom;
                    }
                }
                previousAnv = anv;
            }
        }
        clipTransientViews();

        setClipTopAmount(clipTopAmount);
        boolean isHidden = getViewState().hidden || clipTopAmount >= getIntrinsicHeight();
        if (mShowNotificationShelf) {
            setVisibility(isHidden ? View.INVISIBLE : View.VISIBLE);
        }
        setBackgroundTop(backgroundTop);
        setFirstElementRoundness(firstElementRoundness);
        mShelfIcons.setSpeedBumpIndex(mHostLayoutController.getSpeedBumpIndex());
        mShelfIcons.calculateIconTranslations();
        mShelfIcons.applyIconStates();
        for (int i = 0; i < mHostLayoutController.getChildCount(); i++) {
            View child = mHostLayoutController.getChildAt(i);
            if (!(child instanceof ExpandableNotificationRow)
                    || child.getVisibility() == GONE) {
                continue;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            updateIconClipAmount(row);
            updateContinuousClipping(row);
        }
        boolean hideBackground = numViewsInShelf < 1.0f;
        setHideBackground(hideBackground || backgroundForceHidden);
        if (mNotGoneIndex == -1) {
            mNotGoneIndex = notGoneIndex;
        }
    }

    /**
     * Clips transient views to the top of the shelf - Transient views are only used for
     * disappearing views/animations and need to be clipped correctly by the shelf to ensure they
     * don't show underneath the notification stack when something is animating and the user
     * swipes quickly.
     */
    private void clipTransientViews() {
        for (int i = 0; i < mHostLayoutController.getTransientViewCount(); i++) {
            View transientView = mHostLayoutController.getTransientView(i);
            if (transientView instanceof ExpandableView) {
                ExpandableView transientExpandableView = (ExpandableView) transientView;
                updateNotificationClipHeight(transientExpandableView, getTranslationY(), -1);
            }
        }
    }

    private void setFirstElementRoundness(float firstElementRoundness) {
        if (mFirstElementRoundness != firstElementRoundness) {
            mFirstElementRoundness = firstElementRoundness;
            setTopRoundness(firstElementRoundness, false /* animate */);
        }
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
        if (viewEnd > notificationClipEnd && !shouldClipOwnTop
                && (mAmbientState.isShadeExpanded() || !isPinned)) {
            int clipBottomAmount = (int) (viewEnd - notificationClipEnd);
            if (isPinned) {
                clipBottomAmount = Math.min(view.getIntrinsicHeight() - view.getCollapsedHeight(),
                        clipBottomAmount);
            }
            view.setClipBottomAmount(clipBottomAmount);
        } else {
            view.setClipBottomAmount(0);
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
     * @return the amount how much this notification is in the shelf
     */
    private float updateShelfTransformation(ExpandableView view, float expandAmount,
            boolean scrolling, boolean scrollingFast, boolean expandingAnimated,
            boolean isLastChild) {
        StatusBarIconView icon = view.getShelfIcon();
        NotificationIconContainer.IconState iconState = getIconState(icon);

        // Let calculate how much the view is in the shelf
        float viewStart = view.getTranslationY();
        int fullHeight = view.getActualHeight() + mPaddingBetweenElements;
        float iconTransformStart = calculateIconTransformationStart(view);

        float transformDistance = getIntrinsicHeight() * 1.5f;
        transformDistance *= NotificationUtils.interpolate(1.f, 1.5f, expandAmount);
        transformDistance = Math.min(transformDistance, fullHeight);

        // Let's make sure the transform distance is
        // at most to the icon (relevant for conversations)
        transformDistance = Math.min(viewStart + fullHeight - iconTransformStart,
                transformDistance);

        if (isLastChild) {
            fullHeight = Math.min(fullHeight, view.getMinHeight() - getIntrinsicHeight());
            transformDistance = Math.min(transformDistance, view.getMinHeight()
                    - getIntrinsicHeight());
        }
        float viewEnd = viewStart + fullHeight;
        handleCustomTransformHeight(view, expandingAnimated, iconState);

        float fullTransitionAmount;
        float iconTransitionAmount;
        float contentTransformationAmount;
        float shelfStart = getTranslationY();
        boolean fullyInOrOut = true;
        if (viewEnd >= shelfStart && (!mAmbientState.isUnlockHintRunning() || view.isInShelf())
                && (mAmbientState.isShadeExpanded()
                        || (!view.isPinned() && !view.isHeadsUpAnimatingAway()))) {
            if (viewStart < shelfStart) {
                if (iconState != null && iconState.hasCustomTransformHeight()) {
                    fullHeight = iconState.customTransformHeight;
                    transformDistance = iconState.customTransformHeight;
                }

                float fullAmount = (shelfStart - viewStart) / fullHeight;
                fullAmount = Math.min(1.0f, fullAmount);
                float interpolatedAmount =  Interpolators.ACCELERATE_DECELERATE.getInterpolation(
                        fullAmount);
                interpolatedAmount = NotificationUtils.interpolate(
                        interpolatedAmount, fullAmount, expandAmount);
                fullTransitionAmount = 1.0f - interpolatedAmount;

                if (isLastChild) {
                    // Reduce icon transform distance to completely fade in shelf icon
                    // by the time the notification icon fades out, and vice versa
                    iconTransitionAmount = (shelfStart - viewStart)
                            / (iconTransformStart - viewStart);
                } else {
                    iconTransitionAmount = (shelfStart - iconTransformStart) / transformDistance;
                }
                iconTransitionAmount = MathUtils.constrain(iconTransitionAmount, 0.0f, 1.0f);
                iconTransitionAmount = 1.0f - iconTransitionAmount;
                fullyInOrOut = false;
            } else {
                fullTransitionAmount = 1.0f;
                iconTransitionAmount = 1.0f;
            }

            // Transforming the content
            contentTransformationAmount = (shelfStart - viewStart) / transformDistance;
            contentTransformationAmount = Math.min(1.0f, contentTransformationAmount);
            contentTransformationAmount = 1.0f - contentTransformationAmount;
        } else {
            fullTransitionAmount = 0.0f;
            iconTransitionAmount = 0.0f;
            contentTransformationAmount = 0.0f;
        }
        if (iconState != null && fullyInOrOut && !expandingAnimated && iconState.isLastExpandIcon) {
            iconState.isLastExpandIcon = false;
            iconState.customTransformHeight = NO_VALUE;
        }

        // Update the content transformation amount
        if (view.isAboveShelf() || view.showingPulsing()
                || (!isLastChild && iconState != null && !iconState.translateContent)) {
            contentTransformationAmount = 0.0f;
        }
        view.setContentTransformationAmount(contentTransformationAmount, isLastChild);

        // Update the positioning of the icon
        updateIconPositioning(view, iconTransitionAmount, fullTransitionAmount,
                transformDistance, scrolling, scrollingFast, expandingAnimated, isLastChild);

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

    private void handleCustomTransformHeight(ExpandableView view, boolean expandingAnimated,
            NotificationIconContainer.IconState iconState) {
        if (iconState != null && expandingAnimated && mAmbientState.getScrollY() == 0
                && !mAmbientState.isOnKeyguard() && !iconState.isLastExpandIcon) {
            // We are expanding animated. Because we switch to a linear interpolation in this case,
            // the last icon may be stuck in between the shelf position and the notification
            // position, which looks pretty bad. We therefore optimize this case by applying a
            // shorter transition such that the icon is either fully in the notification or we clamp
            // it into the shelf if it's close enough.
            // We need to persist this, since after the expansion, the behavior should still be the
            // same.
            float position = mAmbientState.getIntrinsicPadding()
                    + mHostLayoutController.getPositionInLinearLayout(view);
            int maxShelfStart = mMaxLayoutHeight - getIntrinsicHeight();
            if (position < maxShelfStart && position + view.getIntrinsicHeight() >= maxShelfStart
                    && view.getTranslationY() < position) {
                iconState.isLastExpandIcon = true;
                iconState.customTransformHeight = NO_VALUE;
                // Let's check if we're close enough to snap into the shelf
                boolean forceInShelf = mMaxLayoutHeight - getIntrinsicHeight() - position
                        < getIntrinsicHeight();
                if (!forceInShelf) {
                    // We are overlapping the shelf but not enough, so the icon needs to be
                    // repositioned
                    iconState.customTransformHeight = (int) (mMaxLayoutHeight
                            - getIntrinsicHeight() - position);
                }
            }
        }
    }

    private void updateIconPositioning(ExpandableView view, float iconTransitionAmount,
            float fullTransitionAmount, float iconTransformDistance, boolean scrolling,
            boolean scrollingFast, boolean expandingAnimated, boolean isLastChild) {
        StatusBarIconView icon = view.getShelfIcon();
        NotificationIconContainer.IconState iconState = getIconState(icon);
        if (iconState == null) {
            return;
        }
        boolean forceInShelf =
                iconState.isLastExpandIcon && !iconState.hasCustomTransformHeight();
        boolean clampInShelf = iconTransitionAmount > 0.5f || isTargetClipped(view);
        float clampedAmount = clampInShelf ? 1.0f : 0.0f;
        if (iconTransitionAmount == clampedAmount) {
            iconState.noAnimations = (scrollingFast || expandingAnimated) && !forceInShelf;
            iconState.useFullTransitionAmount = iconState.noAnimations
                    || (!ICON_ANMATIONS_WHILE_SCROLLING && iconTransitionAmount == 0.0f
                    && scrolling);
            iconState.useLinearTransitionAmount = !ICON_ANMATIONS_WHILE_SCROLLING
                    && iconTransitionAmount == 0.0f && !mAmbientState.isExpansionChanging();
            iconState.translateContent = mMaxLayoutHeight - getTranslationY()
                    - getIntrinsicHeight() > 0;
        }
        if (!forceInShelf && (scrollingFast || (expandingAnimated
                && iconState.useFullTransitionAmount && !ViewState.isAnimatingY(icon)))) {
            iconState.cancelAnimations(icon);
            iconState.useFullTransitionAmount = true;
            iconState.noAnimations = true;
        }
        if (iconState.hasCustomTransformHeight()) {
            iconState.useFullTransitionAmount = true;
        }
        if (iconState.isLastExpandIcon) {
            iconState.translateContent = false;
        }
        float transitionAmount;
        if (mAmbientState.isHiddenAtAll() && !view.isInShelf()) {
            transitionAmount = mAmbientState.isFullyHidden() ? 1 : 0;
        } else if (isLastChild || !USE_ANIMATIONS_WHEN_OPENING
                || iconState.useFullTransitionAmount
                || iconState.useLinearTransitionAmount) {
            transitionAmount = iconTransitionAmount;
        } else {
            transitionAmount = iconTransitionAmount;
            iconState.needsCannedAnimation = iconState.clampedAppearAmount != clampedAmount
                    && !mNoAnimationsInThisFrame;
        }
        iconState.iconAppearAmount = !USE_ANIMATIONS_WHEN_OPENING
                || iconState.useFullTransitionAmount
                ? fullTransitionAmount
                : transitionAmount;
        iconState.clampedAppearAmount = clampedAmount;
        setIconTransformationAmount(view, transitionAmount, isLastChild);
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

    private void setIconTransformationAmount(ExpandableView view,
            float transitionAmount,
            boolean isLastChild) {
        if (!(view instanceof ExpandableNotificationRow)) {
            return;
        }
        ExpandableNotificationRow row = (ExpandableNotificationRow) view;

        StatusBarIconView icon = row.getShelfIcon();
        NotificationIconContainer.IconState iconState = getIconState(icon);
        View rowIcon = row.getShelfTransformationTarget();

        // Let's resolve the relative positions of the icons
        int iconStartPadding;
        if (rowIcon != null) {
            iconStartPadding = row.getRelativeStartPadding(rowIcon);
        } else {
            iconStartPadding = 0;
        }
        boolean stayingInShelf = row.isInShelf() && !row.isTransformingIntoShelf();

        // Get the icon correctly positioned in X
        // Even in RTL it's the left, since we're inverting the location in post
        float shelfIconPositionX = icon.getLeft();
        shelfIconPositionX += (1.0f - icon.getIconScale()) * icon.getWidth() / 2.0f;
        float iconXTranslation = NotificationUtils.interpolate(
                iconStartPadding - shelfIconPositionX,
                mShelfIcons.getActualPaddingStart(),
                transitionAmount);

        // Let's handle the case that there's no Icon
        boolean noIcon = !row.isShowingIcon();
        if (noIcon) {
            // The view currently doesn't have an icon, lets transform it in!
            iconXTranslation = mShelfIcons.getActualPaddingStart();
        }
        if (iconState != null) {
            iconState.hidden = transitionAmount == 0.0f && !iconState.isAnimating(icon);
            boolean isAppearing = row.isDrawingAppearAnimation() && !row.isInShelf();
            if (isAppearing) {
                iconState.hidden = true;
                iconState.iconAppearAmount = 0.0f;
            }
            iconState.alpha = transitionAmount;
            iconState.xTranslation = iconXTranslation;
            if (stayingInShelf) {
                iconState.iconAppearAmount = 1.0f;
                iconState.alpha = 1.0f;
                iconState.scaleX = 1.0f;
                iconState.scaleY = 1.0f;
                iconState.hidden = false;
            }
            if (row.isAboveShelf()
                    || row.showingPulsing()
                    || (!row.isInShelf() && (isLastChild && row.areGutsExposed()
                    || row.getTranslationZ() > mAmbientState.getBaseZHeight()))) {
                iconState.hidden = true;
            }
            int backgroundColor = getBackgroundColorWithoutTint();
            int shelfColor = icon.getContrastedStaticDrawableColor(backgroundColor);
            if (!noIcon && shelfColor != StatusBarIconView.NO_COLOR) {
                int iconColor = row.getOriginalIconColor();
                shelfColor = NotificationUtils.interpolateColors(iconColor, shelfColor,
                        iconState.iconAppearAmount);
            }
            iconState.iconColor = shelfColor;
        }
    }

    private NotificationIconContainer.IconState getIconState(StatusBarIconView icon) {
        return mShelfIcons.getIconState(icon);
    }

    private float getFullyClosedTranslation() {
        return - (getIntrinsicHeight() - mStatusBarHeight) / 2;
    }

    @Override
    public boolean hasNoContentHeight() {
        return true;
    }

    private void setHideBackground(boolean hideBackground) {
        if (mHideBackground != hideBackground) {
            mHideBackground = hideBackground;
            updateBackground();
            updateOutline();
        }
    }

    @Override
    protected boolean needsOutline() {
        return !mHideBackground && super.needsOutline();
    }

    @Override
    protected boolean shouldHideBackground() {
        return super.shouldHideBackground() || mHideBackground;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateRelativeOffset();

        // we always want to clip to our sides, such that nothing can draw outside of these bounds
        int height = getResources().getDisplayMetrics().heightPixels;
        mClipRect.set(0, -height, getWidth(), height);
        mShelfIcons.setClipBounds(mClipRect);
    }

    private void updateRelativeOffset() {
        mCollapsedIcons.getLocationOnScreen(mTmp);
        mRelativeOffset = mTmp[0];
        getLocationOnScreen(mTmp);
        mRelativeOffset -= mTmp[0];
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        WindowInsets ret = super.onApplyWindowInsets(insets);

        // NotificationShelf drag from the status bar and the status bar dock on the top
        // of the display for current design so just focus on the top of ScreenDecorations.
        // In landscape or multiple window split mode, the NotificationShelf still drag from
        // the top and the physical notch/cutout goes to the right, left, or both side of the
        // display so it doesn't matter for the NotificationSelf in landscape.
        DisplayCutout displayCutout = insets.getDisplayCutout();
        mCutoutHeight = displayCutout == null || displayCutout.getSafeInsetTop() < 0
                ? 0 : displayCutout.getSafeInsetTop();

        return ret;
    }

    private void setOpenedAmount(float openedAmount) {
        mNoAnimationsInThisFrame = openedAmount == 1.0f && mOpenedAmount == 0.0f;
        mOpenedAmount = openedAmount;
        if (!mAmbientState.isPanelFullWidth() || mAmbientState.isDozing()) {
            // We don't do a transformation at all, lets just assume we are fully opened
            openedAmount = 1.0f;
        }
        int start = mRelativeOffset;
        if (isLayoutRtl()) {
            start = getWidth() - start - mCollapsedIcons.getWidth();
        }
        int width = (int) NotificationUtils.interpolate(
                start + mCollapsedIcons.getFinalTranslationX(),
                mShelfIcons.getWidth(),
                FAST_OUT_SLOW_IN_REVERSE.getInterpolation(openedAmount));
        mShelfIcons.setActualLayoutWidth(width);
        boolean hasOverflow = mCollapsedIcons.hasOverflow();
        int collapsedPadding = mCollapsedIcons.getPaddingEnd();
        if (!hasOverflow) {
            // we have to ensure that adding the low priority notification won't lead to an
            // overflow
            collapsedPadding -= mCollapsedIcons.getNoOverflowExtraPadding();
        } else {
            // Partial overflow padding will fill enough space to add extra dots
            collapsedPadding -= mCollapsedIcons.getPartialOverflowExtraPadding();
        }
        float padding = NotificationUtils.interpolate(collapsedPadding,
                mShelfIcons.getPaddingEnd(),
                openedAmount);
        mShelfIcons.setActualPaddingEnd(padding);
        float paddingStart = NotificationUtils.interpolate(start,
                mShelfIcons.getPaddingStart(), openedAmount);
        mShelfIcons.setActualPaddingStart(paddingStart);
        mShelfIcons.setOpenedAmount(openedAmount);
    }

    public void setMaxLayoutHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
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

    /**
     * @return whether the shelf has any icons in it when a potential animation has finished, i.e
     *         if the current state would be applied right now
     */
    public boolean hasItemsInStableShelf() {
        return mHasItemsInStableShelf;
    }

    public void setCollapsedIcons(NotificationIconContainer collapsedIcons) {
        mCollapsedIcons = collapsedIcons;
        mCollapsedIcons.addOnLayoutChangeListener(this);
    }

    @Override
    public void onStateChanged(int newState) {
        mStatusBarState = newState;
        updateInteractiveness();
    }

    private void updateInteractiveness() {
        mInteractive = mStatusBarState == StatusBarState.KEYGUARD && mHasItemsInStableShelf;
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
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        updateRelativeOffset();
    }

    @Override
    public boolean needsClippingToShelf() {
        return false;
    }

    public void setController(NotificationShelfController notificationShelfController) {
        mController = notificationShelfController;
    }

    private class ShelfState extends ExpandableViewState {
        private float openedAmount;
        private boolean hasItemsInStableShelf;

        @Override
        public void applyToView(View view) {
            if (!mShowNotificationShelf) {
                return;
            }

            super.applyToView(view);
            setOpenedAmount(openedAmount);
            updateAppearance();
            setHasItemsInStableShelf(hasItemsInStableShelf);
            mShelfIcons.setAnimationsEnabled(mAnimationsEnabled);
        }

        @Override
        public void animateTo(View child, AnimationProperties properties) {
            if (!mShowNotificationShelf) {
                return;
            }

            super.animateTo(child, properties);
            setOpenedAmount(openedAmount);
            updateAppearance();
            setHasItemsInStableShelf(hasItemsInStableShelf);
            mShelfIcons.setAnimationsEnabled(mAnimationsEnabled);
        }
    }
}
