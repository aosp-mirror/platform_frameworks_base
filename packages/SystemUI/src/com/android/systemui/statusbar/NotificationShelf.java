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

import static com.android.systemui.statusbar.phone.NotificationIconContainer.IconState.NO_VALUE;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.stack.AmbientState;
import com.android.systemui.statusbar.stack.AnimationProperties;
import com.android.systemui.statusbar.stack.ExpandableViewState;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.stack.StackScrollState;
import com.android.systemui.statusbar.stack.ViewState;

/**
 * A notification shelf view that is placed inside the notification scroller. It manages the
 * overflow icons that don't fit into the regular list anymore.
 */
public class NotificationShelf extends ActivatableNotificationView implements
        View.OnLayoutChangeListener {

    public static final boolean SHOW_AMBIENT_ICONS = true;
    private static final boolean USE_ANIMATIONS_WHEN_OPENING =
            SystemProperties.getBoolean("debug.icon_opening_animations", true);
    private static final boolean ICON_ANMATIONS_WHILE_SCROLLING
            = SystemProperties.getBoolean("debug.icon_scroll_animations", true);
    private static final int TAG_CONTINUOUS_CLIPPING = R.id.continuous_clipping_tag;
    private static final String TAG = "NotificationShelf";
    private static final long SHELF_IN_TRANSLATION_DURATION = 200;

    private boolean mDark;
    private NotificationIconContainer mShelfIcons;
    private ShelfState mShelfState;
    private int[] mTmp = new int[2];
    private boolean mHideBackground;
    private int mIconAppearTopPadding;
    private int mShelfAppearTranslation;
    private int mStatusBarHeight;
    private int mStatusBarPaddingStart;
    private AmbientState mAmbientState;
    private NotificationStackScrollLayout mHostLayout;
    private int mMaxLayoutHeight;
    private int mPaddingBetweenElements;
    private int mNotGoneIndex;
    private boolean mHasItemsInStableShelf;
    private NotificationIconContainer mCollapsedIcons;
    private int mScrollFastThreshold;
    private int mIconSize;
    private int mStatusBarState;
    private float mMaxShelfEnd;
    private int mRelativeOffset;
    private boolean mInteractive;
    private float mOpenedAmount;
    private boolean mNoAnimationsInThisFrame;
    private boolean mAnimationsEnabled = true;
    private boolean mShowNotificationShelf;
    private float mFirstElementRoundness;
    private Rect mClipRect = new Rect();

    public NotificationShelf(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mShelfIcons = findViewById(R.id.content);
        mShelfIcons.setClipChildren(false);
        mShelfIcons.setClipToPadding(false);

        setClipToActualHeight(false);
        setClipChildren(false);
        setClipToPadding(false);
        mShelfIcons.setIsStaticLayout(false);
        mShelfState = new ShelfState();
        setBottomRoundness(1.0f, false /* animate */);
        initDimens();
    }

    public void bind(AmbientState ambientState, NotificationStackScrollLayout hostLayout) {
        mAmbientState = ambientState;
        mHostLayout = hostLayout;
    }

    private void initDimens() {
        Resources res = getResources();
        mIconAppearTopPadding = res.getDimensionPixelSize(R.dimen.notification_icon_appear_padding);
        mStatusBarHeight = res.getDimensionPixelOffset(R.dimen.status_bar_height);
        mStatusBarPaddingStart = res.getDimensionPixelOffset(R.dimen.status_bar_padding_start);
        mPaddingBetweenElements = res.getDimensionPixelSize(R.dimen.notification_divider_height);
        mShelfAppearTranslation = res.getDimensionPixelSize(R.dimen.shelf_appear_translation);

        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.height = res.getDimensionPixelOffset(R.dimen.notification_shelf_height);
        setLayoutParams(layoutParams);

        int padding = res.getDimensionPixelOffset(R.dimen.shelf_icon_container_padding);
        mShelfIcons.setPadding(padding, 0, padding, 0);
        mScrollFastThreshold = res.getDimensionPixelOffset(R.dimen.scroll_fast_threshold);
        mShowNotificationShelf = res.getBoolean(R.bool.config_showNotificationShelf);
        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);

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
    public void setDark(boolean dark, boolean fade, long delay) {
        super.setDark(dark, fade, delay);
        if (mDark == dark) return;
        mDark = dark;
        mShelfIcons.setDark(dark, fade, delay);
        updateInteractiveness();
    }

    public void fadeInTranslating() {
        mShelfIcons.setTranslationY(-mShelfAppearTranslation);
        mShelfIcons.setAlpha(0);
        mShelfIcons.animate()
                .setInterpolator(Interpolators.DECELERATE_QUINT)
                .translationY(0)
                .setDuration(SHELF_IN_TRANSLATION_DURATION)
                .start();
        mShelfIcons.animate()
                .alpha(1)
                .setInterpolator(Interpolators.LINEAR)
                .setDuration(SHELF_IN_TRANSLATION_DURATION)
                .start();
    }

    @Override
    protected View getContentView() {
        return mShelfIcons;
    }

    public NotificationIconContainer getShelfIcons() {
        return mShelfIcons;
    }

    @Override
    public ExpandableViewState createNewViewState(StackScrollState stackScrollState) {
        return mShelfState;
    }

    public void updateState(StackScrollState resultState,
            AmbientState ambientState) {
        View lastView = ambientState.getLastVisibleBackgroundChild();
        if (mShowNotificationShelf && lastView != null) {
            float maxShelfEnd = ambientState.getInnerHeight() + ambientState.getTopPadding()
                    + ambientState.getStackTranslation();
            ExpandableViewState lastViewState = resultState.getViewStateForView(lastView);
            float viewEnd = lastViewState.yTranslation + lastViewState.height;
            mShelfState.copyFrom(lastViewState);
            mShelfState.height = getIntrinsicHeight();

            float awakenTranslation = Math.max(Math.min(viewEnd, maxShelfEnd) - mShelfState.height,
                    getFullyClosedTranslation());
            float darkTranslation = mAmbientState.getDarkTopPadding();
            float yRatio = mAmbientState.hasPulsingNotifications() ?
                    0 : mAmbientState.getDarkAmount();
            mShelfState.yTranslation = MathUtils.lerp(awakenTranslation, darkTranslation, yRatio);
            mShelfState.zTranslation = ambientState.getBaseZHeight();
            float openedAmount = (mShelfState.yTranslation - getFullyClosedTranslation())
                    / (getIntrinsicHeight() * 2);
            openedAmount = Math.min(1.0f, openedAmount);
            mShelfState.openedAmount = openedAmount;
            mShelfState.clipTopAmount = 0;
            mShelfState.alpha = mAmbientState.hasPulsingNotifications() ? 0 : 1;
            mShelfState.belowSpeedBump = mAmbientState.getSpeedBumpIndex() == 0;
            mShelfState.shadowAlpha = 1.0f;
            mShelfState.hideSensitive = false;
            mShelfState.xTranslation = getTranslationX();
            if (mNotGoneIndex != -1) {
                mShelfState.notGoneIndex = Math.min(mShelfState.notGoneIndex, mNotGoneIndex);
            }
            mShelfState.hasItemsInStableShelf = lastViewState.inShelf;
            mShelfState.hidden = !mAmbientState.isShadeExpanded()
                    || mAmbientState.isQsCustomizerShowing();
            mShelfState.maxShelfEnd = maxShelfEnd;
        } else {
            mShelfState.hidden = true;
            mShelfState.location = ExpandableViewState.LOCATION_GONE;
            mShelfState.hasItemsInStableShelf = false;
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
        if (mHideBackground && !mShelfState.hasItemsInStableShelf) {
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
        float firstElementRoundness = 0.0f;

        for (int i = 0; i < mHostLayout.getChildCount(); i++) {
            ExpandableView child = (ExpandableView) mHostLayout.getChildAt(i);

            if (!(child instanceof ExpandableNotificationRow)
                    || child.getVisibility() == GONE) {
                continue;
            }

            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            float notificationClipEnd;
            boolean aboveShelf = ViewState.getFinalTranslationZ(row) > baseZHeight
                    || row.isPinned();
            boolean isLastChild = child == lastChild;
            float rowTranslationY = row.getTranslationY();
            if ((isLastChild && !child.isInShelf()) || aboveShelf || backgroundForceHidden) {
                notificationClipEnd = shelfStart + getIntrinsicHeight();
            } else {
                notificationClipEnd = shelfStart - mPaddingBetweenElements;
                float height = notificationClipEnd - rowTranslationY;
                if (!row.isBelowSpeedBump() && height <= getNotificationMergeSize()) {
                    // We want the gap to close when we reached the minimum size and only shrink
                    // before
                    notificationClipEnd = Math.min(shelfStart,
                            rowTranslationY + getNotificationMergeSize());
                }
            }
            updateNotificationClipHeight(row, notificationClipEnd);
            float inShelfAmount = updateIconAppearance(row, expandAmount, scrolling, scrollingFast,
                    expandingAnimated, isLastChild);
            numViewsInShelf += inShelfAmount;
            int ownColorUntinted = row.getBackgroundColorWithoutTint();
            if (rowTranslationY >= shelfStart && mNotGoneIndex == -1) {
                mNotGoneIndex = notGoneIndex;
                setTintColor(previousColor);
                setOverrideTintColor(colorTwoBefore, transitionAmount);

            } else if (mNotGoneIndex == -1) {
                colorTwoBefore = previousColor;
                transitionAmount = inShelfAmount;
            }
            if (isLastChild) {
                if (colorOfViewBeforeLast == NO_COLOR) {
                    colorOfViewBeforeLast = ownColorUntinted;
                }
                row.setOverrideTintColor(colorOfViewBeforeLast, inShelfAmount);
            } else {
                colorOfViewBeforeLast = ownColorUntinted;
                row.setOverrideTintColor(NO_COLOR, 0 /* overrideAmount */);
            }
            if (notGoneIndex != 0 || !aboveShelf) {
                row.setAboveShelf(false);
            }
            if (notGoneIndex == 0) {
                StatusBarIconView icon = row.getEntry().expandedIcon;
                NotificationIconContainer.IconState iconState = getIconState(icon);
                if (iconState != null && iconState.clampedAppearAmount == 1.0f) {
                    // only if the first icon is fully in the shelf we want to clip to it!
                    backgroundTop = (int) (row.getTranslationY() - getTranslationY());
                    firstElementRoundness = row.getCurrentTopRoundness();
                } else if (iconState == null) {
                    Log.wtf(TAG, "iconState is null. ExpandedIcon: " + row.getEntry().expandedIcon
                            + (row.getEntry().expandedIcon != null
                            ? "\n icon parent: " + row.getEntry().expandedIcon.getParent() : "")
                            + " \n number of notifications: " + mHostLayout.getChildCount() );
                }
            }
            notGoneIndex++;
            previousColor = ownColorUntinted;
        }

        clipTransientViews();

        setBackgroundTop(backgroundTop);
        setFirstElementRoundness(firstElementRoundness);
        mShelfIcons.setSpeedBumpIndex(mAmbientState.getSpeedBumpIndex());
        mShelfIcons.calculateIconTranslations();
        mShelfIcons.applyIconStates();
        for (int i = 0; i < mHostLayout.getChildCount(); i++) {
            View child = mHostLayout.getChildAt(i);
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
        for (int i = 0; i < mHostLayout.getTransientViewCount(); i++) {
            View transientView = mHostLayout.getTransientView(i);
            if (transientView instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow transientRow = (ExpandableNotificationRow) transientView;
                updateNotificationClipHeight(transientRow, getTranslationY());
            } else {
                Log.e(TAG, "NotificationShelf.clipTransientViews(): "
                        + "Trying to clip non-row transient view");
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
        StatusBarIconView icon = row.getEntry().expandedIcon;
        float shelfIconPosition = getTranslationY() + icon.getTop() + icon.getTranslationY();
        if (shelfIconPosition < maxTop && !mAmbientState.isDark()) {
            int top = (int) (maxTop - shelfIconPosition);
            Rect clipRect = new Rect(0, top, icon.getWidth(), Math.max(top, icon.getHeight()));
            icon.setClipBounds(clipRect);
        } else {
            icon.setClipBounds(null);
        }
    }

    private void updateContinuousClipping(final ExpandableNotificationRow row) {
        StatusBarIconView icon = row.getEntry().expandedIcon;
        boolean needsContinuousClipping = ViewState.isAnimatingY(icon) && !mAmbientState.isDark();
        boolean isContinuousClipping = icon.getTag(TAG_CONTINUOUS_CLIPPING) != null;
        if (needsContinuousClipping && !isContinuousClipping) {
            final ViewTreeObserver observer = icon.getViewTreeObserver();
            ViewTreeObserver.OnPreDrawListener predrawListener =
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            boolean animatingY = ViewState.isAnimatingY(icon);
                            if (!animatingY) {
                                observer.removeOnPreDrawListener(this);
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
                        observer.removeOnPreDrawListener(predrawListener);
                        icon.setTag(TAG_CONTINUOUS_CLIPPING, null);
                    }
                }
            });
            icon.setTag(TAG_CONTINUOUS_CLIPPING, predrawListener);
        }
    }

    private void updateNotificationClipHeight(ExpandableNotificationRow row,
            float notificationClipEnd) {
        float viewEnd = row.getTranslationY() + row.getActualHeight();
        boolean isPinned = (row.isPinned() || row.isHeadsUpAnimatingAway())
                && !mAmbientState.isDozingAndNotPulsing(row);
        if (viewEnd > notificationClipEnd
                && (mAmbientState.isShadeExpanded() || !isPinned)) {
            int clipBottomAmount = (int) (viewEnd - notificationClipEnd);
            if (isPinned) {
                clipBottomAmount = Math.min(row.getIntrinsicHeight() - row.getCollapsedHeight(),
                        clipBottomAmount);
            }
            row.setClipBottomAmount(clipBottomAmount);
        } else {
            row.setClipBottomAmount(0);
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
     * @return the icon amount how much this notification is in the shelf;
     */
    private float updateIconAppearance(ExpandableNotificationRow row, float expandAmount,
            boolean scrolling, boolean scrollingFast, boolean expandingAnimated,
            boolean isLastChild) {
        StatusBarIconView icon = row.getEntry().expandedIcon;
        NotificationIconContainer.IconState iconState = getIconState(icon);
        if (iconState == null) {
            return 0.0f;
        }

        // Let calculate how much the view is in the shelf
        float viewStart = row.getTranslationY();
        int fullHeight = row.getActualHeight() + mPaddingBetweenElements;
        float iconTransformDistance = getIntrinsicHeight() * 1.5f;
        iconTransformDistance *= NotificationUtils.interpolate(1.f, 1.5f, expandAmount);
        iconTransformDistance = Math.min(iconTransformDistance, fullHeight);
        if (isLastChild) {
            fullHeight = Math.min(fullHeight, row.getMinHeight() - getIntrinsicHeight());
            iconTransformDistance = Math.min(iconTransformDistance, row.getMinHeight()
                    - getIntrinsicHeight());
        }
        float viewEnd = viewStart + fullHeight;
        if (expandingAnimated && mAmbientState.getScrollY() == 0
                && !mAmbientState.isOnKeyguard() && !iconState.isLastExpandIcon) {
            // We are expanding animated. Because we switch to a linear interpolation in this case,
            // the last icon may be stuck in between the shelf position and the notification
            // position, which looks pretty bad. We therefore optimize this case by applying a
            // shorter transition such that the icon is either fully in the notification or we clamp
            // it into the shelf if it's close enough.
            // We need to persist this, since after the expansion, the behavior should still be the
            // same.
            float position = mAmbientState.getIntrinsicPadding()
                    + mHostLayout.getPositionInLinearLayout(row);
            int maxShelfStart = mMaxLayoutHeight - getIntrinsicHeight();
            if (position < maxShelfStart && position + row.getIntrinsicHeight() >= maxShelfStart
                    && row.getTranslationY() < position) {
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
        float fullTransitionAmount;
        float iconTransitionAmount;
        float shelfStart = getTranslationY();
        if (iconState.hasCustomTransformHeight()) {
            fullHeight = iconState.customTransformHeight;
            iconTransformDistance = iconState.customTransformHeight;
        }
        boolean fullyInOrOut = true;
        if (viewEnd >= shelfStart && (!mAmbientState.isUnlockHintRunning() || row.isInShelf())
                && (mAmbientState.isShadeExpanded()
                        || (!row.isPinned() && !row.isHeadsUpAnimatingAway()))) {
            if (viewStart < shelfStart) {
                float fullAmount = (shelfStart - viewStart) / fullHeight;
                fullAmount = Math.min(1.0f, fullAmount);
                float interpolatedAmount =  Interpolators.ACCELERATE_DECELERATE.getInterpolation(
                        fullAmount);
                interpolatedAmount = NotificationUtils.interpolate(
                        interpolatedAmount, fullAmount, expandAmount);
                fullTransitionAmount = 1.0f - interpolatedAmount;

                iconTransitionAmount = (shelfStart - viewStart) / iconTransformDistance;
                iconTransitionAmount = Math.min(1.0f, iconTransitionAmount);
                iconTransitionAmount = 1.0f - iconTransitionAmount;
                fullyInOrOut = false;
            } else {
                fullTransitionAmount = 1.0f;
                iconTransitionAmount = 1.0f;
            }
        } else {
            fullTransitionAmount = 0.0f;
            iconTransitionAmount = 0.0f;
        }
        if (fullyInOrOut && !expandingAnimated && iconState.isLastExpandIcon) {
            iconState.isLastExpandIcon = false;
            iconState.customTransformHeight = NO_VALUE;
        }
        updateIconPositioning(row, iconTransitionAmount, fullTransitionAmount,
                iconTransformDistance, scrolling, scrollingFast, expandingAnimated, isLastChild);
        return fullTransitionAmount;
    }

    private void updateIconPositioning(ExpandableNotificationRow row, float iconTransitionAmount,
            float fullTransitionAmount, float iconTransformDistance, boolean scrolling,
            boolean scrollingFast, boolean expandingAnimated, boolean isLastChild) {
        StatusBarIconView icon = row.getEntry().expandedIcon;
        NotificationIconContainer.IconState iconState = getIconState(icon);
        if (iconState == null) {
            return;
        }
        boolean forceInShelf = iconState.isLastExpandIcon && !iconState.hasCustomTransformHeight();
        float clampedAmount = iconTransitionAmount > 0.5f ? 1.0f : 0.0f;
        if (clampedAmount == fullTransitionAmount) {
            iconState.noAnimations = (scrollingFast || expandingAnimated) && !forceInShelf;
            iconState.useFullTransitionAmount = iconState.noAnimations
                || (!ICON_ANMATIONS_WHILE_SCROLLING && fullTransitionAmount == 0.0f && scrolling);
            iconState.useLinearTransitionAmount = !ICON_ANMATIONS_WHILE_SCROLLING
                    && fullTransitionAmount == 0.0f && !mAmbientState.isExpansionChanging();
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
        if (mAmbientState.getDarkAmount() > 0 && !row.isInShelf()) {
            transitionAmount = mAmbientState.isFullyDark() ? 1 : 0;
        } else if (isLastChild || !USE_ANIMATIONS_WHEN_OPENING || iconState.useFullTransitionAmount
                || iconState.useLinearTransitionAmount) {
            transitionAmount = iconTransitionAmount;
        } else {
            // We take the clamped position instead
            transitionAmount = clampedAmount;
            iconState.needsCannedAnimation = iconState.clampedAppearAmount != clampedAmount
                    && !mNoAnimationsInThisFrame;
        }
        iconState.iconAppearAmount = !USE_ANIMATIONS_WHEN_OPENING
                    || iconState.useFullTransitionAmount
                ? fullTransitionAmount
                : transitionAmount;
        iconState.clampedAppearAmount = clampedAmount;
        float contentTransformationAmount = !mAmbientState.isAboveShelf(row)
                    && (isLastChild || iconState.translateContent)
                ? iconTransitionAmount
                : 0.0f;
        row.setContentTransformationAmount(contentTransformationAmount, isLastChild);
        setIconTransformationAmount(row, transitionAmount, iconTransformDistance,
                clampedAmount != transitionAmount, isLastChild);
    }

    private void setIconTransformationAmount(ExpandableNotificationRow row,
            float transitionAmount, float iconTransformDistance, boolean usingLinearInterpolation,
            boolean isLastChild) {
        StatusBarIconView icon = row.getEntry().expandedIcon;
        NotificationIconContainer.IconState iconState = getIconState(icon);

        View rowIcon = row.getNotificationIcon();
        float notificationIconPosition = row.getTranslationY() + row.getContentTranslation();
        boolean stayingInShelf = row.isInShelf() && !row.isTransformingIntoShelf();
        if (usingLinearInterpolation && !stayingInShelf) {
            // If we interpolate from the notification position, this might lead to a slightly
            // odd interpolation, since the notification position changes as well. Let's interpolate
            // from a fixed distance. We can only do this if we don't animate and the icon is
            // always in the interpolated positon.
            notificationIconPosition = getTranslationY() - iconTransformDistance;
        }
        float notificationIconSize = 0.0f;
        int iconTopPadding;
        if (rowIcon != null) {
            iconTopPadding = row.getRelativeTopPadding(rowIcon);
            notificationIconSize = rowIcon.getHeight();
        } else {
            iconTopPadding = mIconAppearTopPadding;
        }
        notificationIconPosition += iconTopPadding;
        float shelfIconPosition = getTranslationY() + icon.getTop();
        shelfIconPosition += (icon.getHeight() - icon.getIconScale() * mIconSize) / 2.0f;
        float iconYTranslation = NotificationUtils.interpolate(
                notificationIconPosition - shelfIconPosition,
                0,
                transitionAmount);
        float shelfIconSize = mIconSize * icon.getIconScale();
        float alpha = 1.0f;
        boolean noIcon = !row.isShowingIcon();
        if (noIcon) {
            // The view currently doesn't have an icon, lets transform it in!
            alpha = transitionAmount;
            notificationIconSize = shelfIconSize / 2.0f;
        }
        // The notification size is different from the size in the shelf / statusbar
        float newSize = NotificationUtils.interpolate(notificationIconSize, shelfIconSize,
                transitionAmount);
        if (iconState != null) {
            iconState.scaleX = newSize / shelfIconSize;
            iconState.scaleY = iconState.scaleX;
            iconState.hidden = transitionAmount == 0.0f && !iconState.isAnimating(icon);
            boolean isAppearing = row.isDrawingAppearAnimation() && !row.isInShelf();
            if (isAppearing) {
                iconState.hidden = true;
                iconState.iconAppearAmount = 0.0f;
            }
            iconState.alpha = alpha;
            iconState.yTranslation = iconYTranslation;
            if (stayingInShelf) {
                iconState.iconAppearAmount = 1.0f;
                iconState.alpha = 1.0f;
                iconState.scaleX = 1.0f;
                iconState.scaleY = 1.0f;
                iconState.hidden = false;
            }
            if (mAmbientState.isAboveShelf(row) || (!row.isInShelf() && (isLastChild && row.areGutsExposed()
                    || row.getTranslationZ() > mAmbientState.getBaseZHeight()))) {
                iconState.hidden = true;
            }
            int backgroundColor = getBackgroundColorWithoutTint();
            int shelfColor = icon.getContrastedStaticDrawableColor(backgroundColor);
            if (!noIcon && shelfColor != StatusBarIconView.NO_COLOR) {
                int iconColor = row.getVisibleNotificationHeader().getOriginalIconColor();
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

    public int getNotificationMergeSize() {
        return getIntrinsicHeight();
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

    public boolean hidesBackground() {
        return mHideBackground;
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

    private void setOpenedAmount(float openedAmount) {
        mNoAnimationsInThisFrame = openedAmount == 1.0f && mOpenedAmount == 0.0f;
        mOpenedAmount = openedAmount;
        if (!mAmbientState.isPanelFullWidth()) {
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
                openedAmount);
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

    public void setStatusBarState(int statusBarState) {
        if (mStatusBarState != statusBarState) {
            mStatusBarState = statusBarState;
            updateInteractiveness();
        }
    }

    private void updateInteractiveness() {
        mInteractive = mStatusBarState == StatusBarState.KEYGUARD && mHasItemsInStableShelf
                && !mDark;
        setClickable(mInteractive);
        setFocusable(mInteractive);
        setImportantForAccessibility(mInteractive ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    @Override
    protected boolean isInteractive() {
        return mInteractive;
    }

    public void setMaxShelfEnd(float maxShelfEnd) {
        mMaxShelfEnd = maxShelfEnd;
    }

    public void setAnimationsEnabled(boolean enabled) {
        mAnimationsEnabled = enabled;
        mCollapsedIcons.setAnimationsEnabled(enabled);
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

    private class ShelfState extends ExpandableViewState {
        private float openedAmount;
        private boolean hasItemsInStableShelf;
        private float maxShelfEnd;

        @Override
        public void applyToView(View view) {
            if (!mShowNotificationShelf) {
                return;
            }

            super.applyToView(view);
            setMaxShelfEnd(maxShelfEnd);
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
            setMaxShelfEnd(maxShelfEnd);
            setOpenedAmount(openedAmount);
            updateAppearance();
            setHasItemsInStableShelf(hasItemsInStableShelf);
            mShelfIcons.setAnimationsEnabled(mAnimationsEnabled);
        }
    }
}
