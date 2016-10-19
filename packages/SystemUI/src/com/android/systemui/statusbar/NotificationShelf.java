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
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.stack.AmbientState;
import com.android.systemui.statusbar.stack.ExpandableViewState;
import com.android.systemui.statusbar.stack.StackScrollAlgorithm;
import com.android.systemui.statusbar.stack.StackScrollState;
import com.android.systemui.statusbar.stack.ViewState;

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
        mShelfState.iconStates = mNotificationIconContainer.getIconStates();
        initDimens();
    }

    private void initDimens() {
        mIconAppearTopPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_icon_appear_padding);
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
            mShelfState.clipTopAmount = 0;
            mShelfState.alpha = 1.0f;
            mShelfState.belowShelf = false;
            mShelfState.shadowAlpha = 1.0f;
            mShelfState.isBottomClipped = false;
            mShelfState.hideSensitive = false;

            mShelfState.resetIcons();
            float numIconsInShelf = 0.0f;
            float viewStart;
            float maxShelfStart = maxShelfEnd - mShelfState.height;
            //  find the first view that doesn't overlap with the shelf
            for (int i = shelfIndex; i >= 0; i--) {
                lastView = algorithmState.visibleChildren.get(i);
                lastViewState = resultState.getViewStateForView(lastView);
                ExpandableNotificationRow row = null;
                if (lastView instanceof ExpandableNotificationRow) {
                    row = (ExpandableNotificationRow) lastView;
                }
                viewStart = lastViewState.yTranslation;
                viewEnd = viewStart + lastView.getIntrinsicHeight();
                if (viewEnd > maxShelfStart) {
                    if (viewStart < maxShelfStart) {
                        float transitionAmount = 1.0f - ((maxShelfStart - viewStart) /
                                lastView.getIntrinsicHeight());
                        numIconsInShelf += transitionAmount;
                    } else {
                        numIconsInShelf += 1.0f;
                        lastViewState.hidden = true;
                    }
                }
                if (row != null){
                    // Not in the shelf yet, Icon needs to be placed on top of the notification icon
                    updateIconAppearance(row.getEntry(),
                            (ExpandableNotificationRow.NotificationViewState) lastViewState,
                            mShelfState);
                }
            }
            mShelfState.iconStates = mNotificationIconContainer.calculateIconStates(
                    numIconsInShelf);
            mShelfState.hidden = numIconsInShelf == 0.0f;
            mShelfState.hideBackground = numIconsInShelf < 1.0f;
        } else {
            mShelfState.hideBackground = true;
            mShelfState.hidden = true;
            mShelfState.location = ExpandableViewState.LOCATION_GONE;
        }
    }

    private void updateIconAppearance(NotificationData.Entry entry,
            ExpandableNotificationRow.NotificationViewState rowState,
            ShelfState shelfState) {
        StatusBarIconView icon = entry.expandedIcon;
        ViewState iconState = shelfState.iconStates.get(icon);
        View rowIcon = entry.row.getNotificationIcon();
        float notificationIconPosition = rowState.yTranslation;
        float notificationIconSize = 0.0f;
        int iconTopPadding;
        if (rowIcon != null) {
            iconTopPadding = getIconTopPadding(rowIcon);
            notificationIconSize = rowIcon.getHeight();
        } else {
            iconTopPadding = mIconAppearTopPadding;
        }
        notificationIconPosition += iconTopPadding;
        float shelfIconPosition = mShelfState.yTranslation + icon.getTop();
        shelfIconPosition += ((1.0f - icon.getIconScale()) * icon.getHeight()) / 2.0f;
        float transitionDistance = getIntrinsicHeight() * 1.5f;
        float transformationStartPosition = mShelfState.yTranslation - transitionDistance;
        float transitionAmount = 0.0f;
        if (rowState.yTranslation < transformationStartPosition) {
            // We simply place it on the icon of the notification
            iconState.yTranslation = notificationIconPosition - shelfIconPosition;
        } else {
            transitionAmount = (rowState.yTranslation - transformationStartPosition)
                    / transitionDistance;
            float startPosition = transformationStartPosition + iconTopPadding;
            iconState.yTranslation = NotificationUtils.interpolate(
                    startPosition - shelfIconPosition, 0, transitionAmount);
        }
        float shelfIconSize = icon.getHeight() * icon.getIconScale();
        Float newSize = NotificationUtils.interpolate(notificationIconSize, shelfIconSize,
                transitionAmount);
        iconState.scaleX = newSize / icon.getHeight();
        iconState.scaleY = iconState.scaleX;
        iconState.hidden = transitionAmount == 0.0f;
        rowState.iconTransformationAmount = transitionAmount;
        if (!entry.row.isShowingIcon()) {
            iconState.alpha = transitionAmount;
        }
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

    private class ShelfState extends ExpandableViewState {
        private WeakHashMap<View, ViewState> iconStates = new WeakHashMap<>();
        private boolean hideBackground;

        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            mNotificationIconContainer.applyIconStates(iconStates);
            setHideBackground(hideBackground);
        }

        public void resetIcons() {
            mNotificationIconContainer.resetViewStates(iconStates);
        }
    }
}
