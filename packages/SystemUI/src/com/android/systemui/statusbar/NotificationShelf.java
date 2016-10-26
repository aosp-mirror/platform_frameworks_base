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

import static com.android.systemui.statusbar.phone.NotificationIconContainer.IconState;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
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
import com.android.systemui.statusbar.stack.StackScrollAlgorithm;
import com.android.systemui.statusbar.stack.StackScrollState;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * A notification shelf view that is placed inside the notification scroller. It manages the
 * overflow icons that don't fit into the regular list anymore.
 */
public class NotificationShelf extends ActivatableNotificationView {

    private ViewInvertHelper mViewInvertHelper;
    private boolean mDark;
    private NotificationIconContainer mNotificationIconContainer;
    private ArrayList<StatusBarIconView> mIcons = new ArrayList<>();
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

    public NotificationShelf(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mNotificationIconContainer = (NotificationIconContainer) findViewById(R.id.content);
        mNotificationIconContainer.setClipChildren(false);
        mNotificationIconContainer.setClipToPadding(false);
        setClipToActualHeight(false);
        setClipChildren(false);
        setClipToPadding(false);
        mNotificationIconContainer.setShowAllIcons(false);
        mViewInvertHelper = new ViewInvertHelper(mNotificationIconContainer,
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
        return mNotificationIconContainer;
    }

    public NotificationIconContainer getNotificationIconContainer() {
        return mNotificationIconContainer;
    }

    @Override
    public ExpandableViewState createNewViewState(StackScrollState stackScrollState) {
        return mShelfState;
    }

    public void updateState(StackScrollState resultState,
            StackScrollAlgorithm.StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        int shelfIndex = ambientState.getShelfIndex() - 1;
        if (shelfIndex != -1) {
            float maxShelfEnd = ambientState.getInnerHeight() + ambientState.getTopPadding()
                    + ambientState.getStackTranslation();
            ExpandableView lastView = algorithmState.visibleChildren.get(shelfIndex);
            ExpandableViewState lastViewState = resultState.getViewStateForView(lastView);
            float viewEnd = lastViewState.yTranslation + lastViewState.height;
            mShelfState.copyFrom(lastViewState);
            mShelfState.height = getIntrinsicHeight();
            mShelfState.yTranslation = Math.min(viewEnd, maxShelfEnd) - mShelfState.height;
            mShelfState.zTranslation = Math.max(mShelfState.zTranslation,
                    ambientState.getBaseZHeight());
            float openedAmount = (mShelfState.yTranslation - getFullyClosedTranslation())
                    / (getIntrinsicHeight() * 2);
            openedAmount = Math.min(1.0f, openedAmount);
            mShelfState.iconContainerTranslation = (1.0f - openedAmount)
                    * (mStatusBarPaddingStart - mNotificationIconContainer.getLeft());
            mShelfState.clipTopAmount = 0;
            mShelfState.alpha = 1.0f;
            mShelfState.belowShelf = false;
            mShelfState.shadowAlpha = 1.0f;
            mShelfState.isBottomClipped = false;
            mShelfState.hideSensitive = false;
        } else {
            mShelfState.hidden = true;
            mShelfState.location = ExpandableViewState.LOCATION_GONE;
        }
    }

    /**
     * Update the shelf appearance based on the other notifications around it. This transforms
     * the icons from the notification area into the shelf.
     */
    public void updateAppearance() {
        WeakHashMap<View, IconState> iconStates =
                mNotificationIconContainer.resetViewStates();
        float numIconsInShelf = 0.0f;
        int shelfIndex = mAmbientState.getShelfIndex();
        //  find the first view that doesn't overlap with the shelf
        for (int i = shelfIndex - 1; i >= 0; i--) {
            ExpandableView child = (ExpandableView) mHostLayout.getChildAt(i);
            if (!(child instanceof ExpandableNotificationRow)
                    || child.getVisibility() == GONE) {
                continue;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            StatusBarIconView icon = row.getEntry().expandedIcon;
            IconState iconState = iconStates.get(icon);
            float notificationClipEnd;
            float shelfStart;
            if (i == shelfIndex - 1) {
                shelfStart = getTranslationY();
                notificationClipEnd = shelfStart + getIntrinsicHeight();
            } else {
                shelfStart = getTranslationY();
                notificationClipEnd = shelfStart - mPaddingBetweenElements;
                float height = notificationClipEnd - row.getTranslationY();
                if (height <= getNotificationMergeSize()) {
                    // We want the gap to close when we reached the minimum size and only shrink
                    // before
                    notificationClipEnd = Math.min(shelfStart,
                            row.getTranslationY() + getNotificationMergeSize());
                }
            }
            updateNotificationClipHeight(row, notificationClipEnd);
            updateIconAppearance(shelfStart, row, iconState, icon);
            numIconsInShelf += iconState.iconAppearAmount;
        }
        for (int i = mHostLayout.getChildCount() - 1; i >= shelfIndex; i--) {
            // We need to reset the clipping in case a notification switches from high to low
            // priority.
            ExpandableView child = (ExpandableView) mHostLayout.getChildAt(i);
            if (child.getClipBottomAmount() != 0) {
                child.setClipBottomAmount(0);
            }
        }
        mNotificationIconContainer.calculateIconTranslations();
        mNotificationIconContainer.applyIconStates();
        setVisibility(numIconsInShelf == 0.0f ? INVISIBLE : VISIBLE);
        setHideBackground(numIconsInShelf < 1.0f);
    }

    private void updateNotificationClipHeight(ExpandableNotificationRow row,
            float notificationClipEnd) {
        float viewEnd = row.getTranslationY() + row.getActualHeight();
        if (viewEnd > notificationClipEnd) {
            row.setClipBottomAmount((int) (viewEnd - notificationClipEnd));
        } else {
            row.setClipBottomAmount(0);
        }
    }

    private void updateIconAppearance(float shelfTransformationStart, ExpandableNotificationRow row,
            IconState iconState, StatusBarIconView icon) {
        // Let calculate how much the view is in the shelf
        float viewStart = row.getTranslationY();
        int transformHeight = row.getActualHeight() + mPaddingBetweenElements;
        float viewEnd = viewStart + transformHeight;
        if (viewEnd >= shelfTransformationStart) {
            if (viewStart < shelfTransformationStart) {
                float linearAmount = (shelfTransformationStart - viewStart) / transformHeight;
                float interpolatedAmount =  Interpolators.ACCELERATE_DECELERATE.getInterpolation(
                        linearAmount);
                float interpolationStart = mMaxLayoutHeight - getIntrinsicHeight() * 2;
                float expandAmount = 0.0f;
                if (getTranslationY() >= interpolationStart) {
                    expandAmount = (getTranslationY() - interpolationStart) / getIntrinsicHeight();
                    expandAmount = Math.min(1.0f, expandAmount);
                }
                interpolatedAmount = NotificationUtils.interpolate(
                        interpolatedAmount, linearAmount, expandAmount);
                iconState.iconAppearAmount = 1.0f - interpolatedAmount;
            } else {
                iconState.iconAppearAmount = 1.0f;
            }
        } else {
            iconState.iconAppearAmount = 0.0f;
        }

        // Lets now calculate how much of the transformation has already happened. This is different
        // from the above, since we only start transforming when the view is already quite a bit
        // pushed in.
        View rowIcon = row.getNotificationIcon();
        float notificationIconPosition = viewStart;
        float notificationIconSize = 0.0f;
        int iconTopPadding;
        if (rowIcon != null) {
            iconTopPadding = getIconTopPadding(rowIcon);
            notificationIconSize = rowIcon.getHeight();
        } else {
            iconTopPadding = mIconAppearTopPadding;
        }
        notificationIconPosition += iconTopPadding;
        float shelfIconPosition = getTranslationY() + icon.getTop();
        shelfIconPosition += ((1.0f - icon.getIconScale()) * icon.getHeight()) / 2.0f;
        float transitionDistance = getIntrinsicHeight() * 1.5f;
        float transformationStartPosition = getTranslationY() - transitionDistance;
        float transitionAmount = 0.0f;
        if (viewStart < transformationStartPosition) {
            // We simply place it on the icon of the notification
            iconState.yTranslation = notificationIconPosition - shelfIconPosition;
        } else {
            transitionAmount = (viewStart - transformationStartPosition)
                    / transitionDistance;
            float startPosition = transformationStartPosition + iconTopPadding;
            iconState.yTranslation = NotificationUtils.interpolate(
                    startPosition - shelfIconPosition, 0, transitionAmount);
        }
        float shelfIconSize = icon.getHeight() * icon.getIconScale();
        if (!row.isShowingIcon()) {
            // The view currently doesn't have an icon, lets transform it in!
            iconState.alpha = transitionAmount;
            notificationIconSize = shelfIconSize / 2.0f;
        }
        // The notification size is different from the size in the shelf / statusbar
        float newSize = NotificationUtils.interpolate(notificationIconSize, shelfIconSize,
                transitionAmount);
        iconState.scaleX = newSize / icon.getHeight();
        iconState.scaleY = iconState.scaleX;
        iconState.hidden = transitionAmount == 0.0f;
        row.setIconTransformationAmount(transitionAmount);
        icon.setVisibility(transitionAmount == 0.0f ? INVISIBLE : VISIBLE);
    }

    private float getFullyClosedTranslation() {
        return - (getIntrinsicHeight() - mStatusBarHeight) / 2;
    }

    private int getIconTopPadding(View icon) {
        View view = icon;
        int topPadding = 0;
        while (view.getParent() instanceof ViewGroup) {
            topPadding += view.getTop();
            view = (View) view.getParent();
            if (view instanceof ExpandableNotificationRow) {
                return topPadding;
            }
        }
        return topPadding;
    }

    public int getNotificationMergeSize() {
        return getIntrinsicHeight();
    }

    @Override
    public boolean hasNoContentHeight() {
        return true;
    }

    private void setHideBackground(boolean hideBackground) {
        mHideBackground = hideBackground;
        updateBackground();
        updateOutline();
    }

    @Override
    protected boolean needsOutline() {
        return !mHideBackground && super.needsOutline();
    }

    @Override
    protected boolean shouldHideBackground() {
        return super.shouldHideBackground() || mHideBackground;
    }

    private void setIconContainerTranslation(float iconContainerTranslation) {
        mNotificationIconContainer.setTranslationX(iconContainerTranslation);
    }

    public void setMaxLayoutHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
    }

    private class ShelfState extends ExpandableViewState {
        private float iconContainerTranslation;

        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            updateAppearance();
            setIconContainerTranslation(iconContainerTranslation);
        }

        @Override
        public void animateTo(View child, AnimationProperties properties) {
            super.animateTo(child, properties);
            updateAppearance();
            setIconContainerTranslation(iconContainerTranslation);
        }
    }
}
