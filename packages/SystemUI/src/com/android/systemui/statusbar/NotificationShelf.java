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

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Property;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.stack.AmbientState;
import com.android.systemui.statusbar.stack.AnimationProperties;
import com.android.systemui.statusbar.stack.ExpandableViewState;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.stack.StackScrollState;

/**
 * A notification shelf view that is placed inside the notification scroller. It manages the
 * overflow icons that don't fit into the regular list anymore.
 */
public class NotificationShelf extends ActivatableNotificationView {

    private static final boolean USE_ANIMATIONS_WHEN_OPENING =
            SystemProperties.getBoolean("debug.icon_opening_animations", true);
    private ViewInvertHelper mViewInvertHelper;
    private boolean mDark;
    private NotificationIconContainer mShelfIcons;
    private ShelfState mShelfState;
    private int[] mTmp = new int[2];
    private boolean mHideBackground;
    private int mIconAppearTopPadding;
    private int mStatusBarHeight;
    private int mStatusBarPaddingStart;
    private AmbientState mAmbientState;
    private NotificationStackScrollLayout mHostLayout;
    private int mMaxLayoutHeight;
    private int mPaddingBetweenElements;
    private int mNotGoneIndex;
    private boolean mHasItemsInStableShelf;
    private NotificationIconContainer mCollapsedIcons;

    public NotificationShelf(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mShelfIcons = (NotificationIconContainer) findViewById(R.id.content);
        mShelfIcons.setClipChildren(false);
        mShelfIcons.setClipToPadding(false);

        setClipToActualHeight(false);
        setClipChildren(false);
        setClipToPadding(false);
        mShelfIcons.setShowAllIcons(false);
        mViewInvertHelper = new ViewInvertHelper(mShelfIcons,
                NotificationPanelView.DOZE_ANIMATION_DURATION);
        mShelfState = new ShelfState();
        initDimens();
    }

    public void bind(AmbientState ambientState, NotificationStackScrollLayout hostLayout) {
        mAmbientState = ambientState;
        mHostLayout = hostLayout;
    }

    private void initDimens() {
        mIconAppearTopPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_icon_appear_padding);
        mStatusBarHeight = getResources().getDimensionPixelOffset(R.dimen.status_bar_height);
        mStatusBarPaddingStart = getResources().getDimensionPixelOffset(
                R.dimen.status_bar_padding_start);
        mPaddingBetweenElements = getResources().getDimensionPixelSize(
                R.dimen.notification_divider_height);
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.height = getResources().getDimensionPixelOffset(
                R.dimen.notification_shelf_height);
        setLayoutParams(layoutParams);
        int padding = getResources().getDimensionPixelOffset(R.dimen.shelf_icon_container_padding);
        mShelfIcons.setPadding(padding, 0, padding, 0);
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
        if (fade) {
            mViewInvertHelper.fade(dark, delay);
        } else {
            mViewInvertHelper.update(dark);
        }
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
        if (lastView != null) {
            float maxShelfEnd = ambientState.getInnerHeight() + ambientState.getTopPadding()
                    + ambientState.getStackTranslation();
            ExpandableViewState lastViewState = resultState.getViewStateForView(lastView);
            float viewEnd = lastViewState.yTranslation + lastViewState.height;
            mShelfState.copyFrom(lastViewState);
            mShelfState.height = getIntrinsicHeight();
            mShelfState.yTranslation = Math.max(Math.min(viewEnd, maxShelfEnd) - mShelfState.height,
                    getFullyClosedTranslation());
            mShelfState.zTranslation = ambientState.getBaseZHeight();
            float openedAmount = (mShelfState.yTranslation - getFullyClosedTranslation())
                    / (getIntrinsicHeight() * 2);
            openedAmount = Math.min(1.0f, openedAmount);
            mShelfState.openedAmount = openedAmount;
            mShelfState.clipTopAmount = 0;
            mShelfState.alpha = 1.0f;
            mShelfState.belowSpeedBump = mAmbientState.getSpeedBumpIndex() == 0;
            mShelfState.shadowAlpha = 1.0f;
            mShelfState.hideSensitive = false;
            mShelfState.xTranslation = getTranslationX();
            if (mNotGoneIndex != -1) {
                mShelfState.notGoneIndex = Math.min(mShelfState.notGoneIndex, mNotGoneIndex);
            }
            mShelfState.hasItemsInStableShelf = lastViewState.inShelf;
            mShelfState.hidden = !mAmbientState.isShadeExpanded();
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
        int notificationIndex = 0;
        int notGoneIndex = 0;
        int colorOfViewBeforeLast = 0;
        boolean backgroundForceHidden = false;
        if (mHideBackground && !mShelfState.hasItemsInStableShelf) {
            backgroundForceHidden = true;
        }
        int colorTwoBefore = NO_COLOR;
        int previousColor = NO_COLOR;
        float transitionAmount = 0.0f;
        int baseZHeight = mAmbientState.getBaseZHeight();
        while (notificationIndex < mHostLayout.getChildCount()) {
            ExpandableView child = (ExpandableView) mHostLayout.getChildAt(notificationIndex);
            notificationIndex++;
            if (!(child instanceof ExpandableNotificationRow)
                    || child.getVisibility() == GONE) {
                continue;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            float notificationClipEnd;
            boolean aboveShelf = row.getTranslationZ() > baseZHeight;
            boolean isLastChild = child == lastChild;
            float rowTranslationY = row.getTranslationY();
            if (isLastChild || aboveShelf || backgroundForceHidden) {
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
            float inShelfAmount = updateIconAppearance(row, expandAmount, isLastChild);
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
            if (isLastChild && colorOfViewBeforeLast != NO_COLOR) {
                row.setOverrideTintColor(colorOfViewBeforeLast, inShelfAmount);
            } else {
                colorOfViewBeforeLast = ownColorUntinted;
                row.setOverrideTintColor(NO_COLOR, 0 /* overrideAmount */);
            }
            if (notGoneIndex != 0 || !aboveShelf) {
                row.setAboveShelf(false);
            }
            notGoneIndex++;
            previousColor = ownColorUntinted;
        }
        mShelfIcons.calculateIconTranslations();
        mShelfIcons.applyIconStates();
        boolean hideBackground = numViewsInShelf < 1.0f;
        setHideBackground(hideBackground || backgroundForceHidden);
        if (mNotGoneIndex == -1) {
            mNotGoneIndex = notGoneIndex;
        }
    }

    private void updateNotificationClipHeight(ExpandableNotificationRow row,
            float notificationClipEnd) {
        float viewEnd = row.getTranslationY() + row.getActualHeight();
        if (viewEnd > notificationClipEnd
                && (mAmbientState.isShadeExpanded()
                        || (!row.isPinned() && !row.isHeadsUpAnimatingAway()))) {
            row.setClipBottomAmount((int) (viewEnd - notificationClipEnd));
        } else {
            row.setClipBottomAmount(0);
        }
    }

    /**
     * @return the icon amount how much this notification is in the shelf;
     */
    private float updateIconAppearance(ExpandableNotificationRow row, float expandAmount,
            boolean isLastChild) {
        // Let calculate how much the view is in the shelf
        float viewStart = row.getTranslationY();
        int fullHeight = row.getActualHeight() + mPaddingBetweenElements;
        float iconTransformDistance = getIntrinsicHeight() * 1.5f;
        if (isLastChild) {
            fullHeight = Math.min(fullHeight, row.getMinHeight() - getIntrinsicHeight());
            iconTransformDistance = Math.min(iconTransformDistance, row.getMinHeight()
                    - getIntrinsicHeight());
        }
        float viewEnd = viewStart + fullHeight;
        float fullTransitionAmount;
        float iconTransitonAmount;
        if (viewEnd >= getTranslationY() && (mAmbientState.isShadeExpanded()
                || (!row.isPinned() && !row.isHeadsUpAnimatingAway()))) {
            if (viewStart < getTranslationY()) {

                float fullAmount = (getTranslationY() - viewStart) / fullHeight;
                float interpolatedAmount =  Interpolators.ACCELERATE_DECELERATE.getInterpolation(
                        fullAmount);
                interpolatedAmount = NotificationUtils.interpolate(
                        interpolatedAmount, fullAmount, expandAmount);
                fullTransitionAmount = 1.0f - interpolatedAmount;

                iconTransitonAmount = (getTranslationY() - viewStart) / iconTransformDistance;
                iconTransitonAmount = Math.min(1.0f, iconTransitonAmount);
                iconTransitonAmount = 1.0f - iconTransitonAmount;

            } else {
                fullTransitionAmount = 1.0f;
                iconTransitonAmount = 1.0f;
            }
        } else {
            fullTransitionAmount = 0.0f;
            iconTransitonAmount = 0.0f;
        }
        row.setContentTransformationAmount(iconTransitonAmount, isLastChild);
        updateIconPositioning(row, iconTransitonAmount, fullTransitionAmount);
        return fullTransitionAmount;
    }

    private void updateIconPositioning(ExpandableNotificationRow row, float iconTransitionAmount,
            float fullTransitionAmount) {
        StatusBarIconView icon = row.getEntry().expandedIcon;
        NotificationIconContainer.IconState iconState = getIconState(icon);
        if (iconState == null) {
            return;
        }
        float clampedAmount = iconTransitionAmount > 0.5f ? 1.0f : 0.0f;
        boolean isLastChild = isLastChild(row);
        if (clampedAmount == iconTransitionAmount) {
            iconState.keepClampedPosition = false;
        }
        if (clampedAmount == fullTransitionAmount) {
            iconState.useFullTransitionAmount = fullTransitionAmount == 0.0f;
        }
        float transitionAmount;
        boolean needCannedAnimation = iconState.clampedAppearAmount == 1.0f
                && clampedAmount == 0.0f;
        if (isLastChild || !USE_ANIMATIONS_WHEN_OPENING || iconState.useFullTransitionAmount) {
            transitionAmount = iconTransitionAmount;
        } else if (iconState.keepClampedPosition
                && iconState.clampedAppearAmount != clampedAmount) {
            // We animated to the clamped amount but then decided to go the other way. Let's
            // animate it to the new position
            transitionAmount = iconTransitionAmount;
            iconState.needsCannedAnimation = true;
            iconState.keepClampedPosition = false;
        } else if (needCannedAnimation || iconState.keepClampedPosition
                || iconState.iconAppearAmount == 1.0f) {
            // We need to perform a canned animation since we crossed the treshhold
            transitionAmount = clampedAmount;
            iconState.keepClampedPosition = iconState.keepClampedPosition || needCannedAnimation;
            iconState.needsCannedAnimation = needCannedAnimation;
        } else {
            transitionAmount = iconTransitionAmount;
        }
        iconState.iconAppearAmount = !USE_ANIMATIONS_WHEN_OPENING
                    || iconState.useFullTransitionAmount
                ? fullTransitionAmount
                : transitionAmount;
        iconState.clampedAppearAmount = clampedAmount;
        setIconTransformationAmount(row, transitionAmount);
    }

    private boolean isLastChild(ExpandableNotificationRow row) {
        return row == mAmbientState.getLastVisibleBackgroundChild();
    }

    private void setIconTransformationAmount(ExpandableNotificationRow row,
            float transitionAmount) {
        StatusBarIconView icon = row.getEntry().expandedIcon;
        NotificationIconContainer.IconState iconState = getIconState(icon);

        View rowIcon = row.getNotificationIcon();
        float notificationIconPosition = row.getTranslationY();
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
        shelfIconPosition += ((1.0f - icon.getIconScale()) * icon.getHeight()) / 2.0f;
        float transitionDistance = getIntrinsicHeight() * 1.5f;
        if (row == mAmbientState.getLastVisibleBackgroundChild()) {
            transitionDistance = Math.min(transitionDistance, row.getMinHeight()
                    - getIntrinsicHeight());
        }
        float transformationStartPosition = getTranslationY() - transitionDistance;
        float iconYTranslation = NotificationUtils.interpolate(
                Math.min(notificationIconPosition, transformationStartPosition + iconTopPadding)
                        - shelfIconPosition,
                0,
                transitionAmount);
        float shelfIconSize = icon.getHeight() * icon.getIconScale();
        float alpha = 1.0f;
        if (!row.isShowingIcon()) {
            // The view currently doesn't have an icon, lets transform it in!
            alpha = transitionAmount;
            notificationIconSize = shelfIconSize / 2.0f;
        }
        // The notification size is different from the size in the shelf / statusbar
        float newSize = NotificationUtils.interpolate(notificationIconSize, shelfIconSize,
                transitionAmount);
        if (iconState != null) {
            iconState.scaleX = newSize / icon.getHeight() / icon.getIconScale();
            iconState.scaleY = iconState.scaleX;
            iconState.hidden = transitionAmount == 0.0f;
            iconState.alpha = alpha;
            iconState.yTranslation = iconYTranslation;
            if (row.isInShelf() && !row.isTransformingIntoShelf()) {
                iconState.iconAppearAmount = 1.0f;
                iconState.alpha = 1.0f;
                iconState.scaleX = 1.0f;
                iconState.scaleY = 1.0f;
                iconState.hidden = false;
            }
            if (row.isAboveShelf()) {
                iconState.hidden = true;
            }
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

    private void setOpenedAmount(float openedAmount) {
        mCollapsedIcons.getLocationOnScreen(mTmp);
        int start = mTmp[0];
        if (isLayoutRtl()) {
            start = getWidth() - start - mCollapsedIcons.getWidth();
        }
        int width = (int) NotificationUtils.interpolate(start + mCollapsedIcons.getWidth(),
                mShelfIcons.getWidth(),
                openedAmount);
        mShelfIcons.setActualLayoutWidth(width);
        float padding = NotificationUtils.interpolate(mCollapsedIcons.getPaddingEnd(),
                mShelfIcons.getPaddingEnd(),
                openedAmount);
        mShelfIcons.setActualPaddingEnd(padding);
        float paddingStart = NotificationUtils.interpolate(start,
                mShelfIcons.getPaddingStart(), openedAmount);
        mShelfIcons.setActualPaddingStart(paddingStart);
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
        mHasItemsInStableShelf = hasItemsInStableShelf;
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
    }

    private class ShelfState extends ExpandableViewState {
        private float openedAmount;
        private boolean hasItemsInStableShelf;

        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            updateAppearance();
            setOpenedAmount(openedAmount);
            setHasItemsInStableShelf(hasItemsInStableShelf);
        }

        @Override
        public void animateTo(View child, AnimationProperties properties) {
            super.animateTo(child, properties);
            setOpenedAmount(openedAmount);
            updateAppearance();
            setHasItemsInStableShelf(hasItemsInStableShelf);
        }
    }
}
