/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.stack;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.HybridNotificationView;
import com.android.systemui.statusbar.notification.HybridNotificationViewManager;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.phone.NotificationPanelView;

import java.util.ArrayList;
import java.util.List;

/**
 * A container containing child notifications
 */
public class NotificationChildrenContainer extends ViewGroup {

    private static final int NUMBER_OF_CHILDREN_WHEN_COLLAPSED = 2;
    private static final int NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED = 5;
    private static final int NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED = 8;

    private final List<View> mDividers = new ArrayList<>();
    private final List<ExpandableNotificationRow> mChildren = new ArrayList<>();
    private int mChildPadding;
    private int mDividerHeight;
    private int mMaxNotificationHeight;
    private int mNotificationHeaderHeight;
    private int mNotificatonTopPadding;
    private float mCollapsedBottompadding;
    private boolean mChildrenExpanded;
    private ExpandableNotificationRow mNotificationParent;
    private int mRealHeight;
    private int mLayoutDirection = LAYOUT_DIRECTION_UNDEFINED;
    private boolean mUserLocked;
    private int mActualHeight;

    public NotificationChildrenContainer(Context context) {
        this(context, null);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initDimens();
    }

    private void initDimens() {
        mChildPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_children_padding);
        mDividerHeight = Math.max(1, getResources().getDimensionPixelSize(
                R.dimen.notification_divider_height));
        mMaxNotificationHeight = getResources().getDimensionPixelSize(
                R.dimen.notification_max_height);
        mNotificationHeaderHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_top);
        mNotificatonTopPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_children_container_top_padding);
        mCollapsedBottompadding = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_bottom);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int childCount = Math.min(mChildren.size(), NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
        for (int i = 0; i < childCount; i++) {
            View child = mChildren.get(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            child.layout(0, 0, getWidth(), child.getMeasuredHeight());
            mDividers.get(i).layout(0, 0, getWidth(), mDividerHeight);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int ownMaxHeight = mMaxNotificationHeight;
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        int size = MeasureSpec.getSize(heightMeasureSpec);
        if (hasFixedHeight || isHeightLimited) {
            ownMaxHeight = Math.min(ownMaxHeight, size);
        }
        int newHeightSpec = MeasureSpec.makeMeasureSpec(ownMaxHeight, MeasureSpec.AT_MOST);
        int dividerHeightSpec = MeasureSpec.makeMeasureSpec(mDividerHeight, MeasureSpec.EXACTLY);
        int height = mNotificationHeaderHeight + mNotificatonTopPadding;
        int childCount = Math.min(mChildren.size(), NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
        for (int i = 0; i < childCount; i++) {
            View child = mChildren.get(i);
            child.measure(widthMeasureSpec, newHeightSpec);
            height += child.getMeasuredHeight();

            // layout the divider
            View divider = mDividers.get(i);
            divider.measure(widthMeasureSpec, dividerHeightSpec);
            height += mDividerHeight;
        }
        int width = MeasureSpec.getSize(widthMeasureSpec);
        mRealHeight = height;
        if (heightMode != MeasureSpec.UNSPECIFIED) {
            height = Math.min(height, size);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    public boolean pointInView(float localX, float localY, float slop) {
        return localX >= -slop && localY >= -slop && localX < ((mRight - mLeft) + slop) &&
                localY < (mRealHeight + slop);
    }

    /**
     * Add a child notification to this view.
     *
     * @param row the row to add
     * @param childIndex the index to add it at, if -1 it will be added at the end
     */
    public void addNotification(ExpandableNotificationRow row, int childIndex) {
        int newIndex = childIndex < 0 ? mChildren.size() : childIndex;
        mChildren.add(newIndex, row);
        addView(row);
        row.setUserLocked(mUserLocked);

        View divider = inflateDivider();
        addView(divider);
        mDividers.add(newIndex, divider);
    }

    public void removeNotification(ExpandableNotificationRow row) {
        int childIndex = mChildren.indexOf(row);
        mChildren.remove(row);
        removeView(row);

        final View divider = mDividers.remove(childIndex);
        removeView(divider);
        getOverlay().add(divider);
        CrossFadeHelper.fadeOut(divider, new Runnable() {
            @Override
            public void run() {
                getOverlay().remove(divider);
            }
        });

        row.setSystemChildExpanded(false);
        row.setUserLocked(false);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int layoutDirection = getLayoutDirection();
        if (layoutDirection != mLayoutDirection) {
            mLayoutDirection = layoutDirection;
        }
    }

    private View inflateDivider() {
        return LayoutInflater.from(mContext).inflate(
                R.layout.notification_children_divider, this, false);
    }

    public List<ExpandableNotificationRow> getNotificationChildren() {
        return mChildren;
    }

    /**
     * Apply the order given in the list to the children.
     *
     * @param childOrder the new list order
     * @return whether the list order has changed
     */
    public boolean applyChildOrder(List<ExpandableNotificationRow> childOrder) {
        if (childOrder == null) {
            return false;
        }
        boolean result = false;
        for (int i = 0; i < mChildren.size() && i < childOrder.size(); i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            ExpandableNotificationRow desiredChild = childOrder.get(i);
            if (child != desiredChild) {
                mChildren.remove(desiredChild);
                mChildren.add(i, desiredChild);
                result = true;
            }
        }
        updateExpansionStates();
        return result;
    }

    private void updateExpansionStates() {
        if (mChildrenExpanded || mUserLocked) {
            // we don't modify it the group is expanded or if we are expanding it
            return;
        }
        int size = mChildren.size();
        for (int i = 0; i < size; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            child.setSystemChildExpanded(i == 0 && size == 1);
        }
    }

    /**
     *
     * @return the intrinsic size of this children container, i.e the natural fully expanded state
     */
    public int getIntrinsicHeight() {
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren();
        return getIntrinsicHeight(maxAllowedVisibleChildren);
    }

    /**
     * @return the intrinsic height with a number of children given
     *         in @param maxAllowedVisibleChildren
     */
    private int getIntrinsicHeight(float maxAllowedVisibleChildren) {
        int intrinsicHeight = mNotificationHeaderHeight;
        int visibleChildren = 0;
        int childCount = mChildren.size();
        boolean firstChild = true;
        float expandFactor = 0;
        if (mUserLocked) {
            expandFactor = getChildExpandFraction();
        }
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            if (!firstChild) {
                if (mUserLocked) {
                    intrinsicHeight += NotificationUtils.interpolate(mChildPadding, mDividerHeight,
                            expandFactor);
                } else {
                    intrinsicHeight += mChildrenExpanded ? mDividerHeight : mChildPadding;
                }
            } else {
                if (mUserLocked) {
                    intrinsicHeight += NotificationUtils.interpolate(
                            0,
                            mNotificatonTopPadding + mDividerHeight,
                            expandFactor);
                } else {
                    intrinsicHeight += mChildrenExpanded
                            ? mNotificatonTopPadding + mDividerHeight
                            : 0;
                }
                firstChild = false;
            }
            ExpandableNotificationRow child = mChildren.get(i);
            intrinsicHeight += child.getIntrinsicHeight();
            visibleChildren++;
        }
        if (mUserLocked) {
            intrinsicHeight += NotificationUtils.interpolate(mCollapsedBottompadding, 0.0f,
                    expandFactor);
        } else if (!mChildrenExpanded) {
            intrinsicHeight += mCollapsedBottompadding;
        }
        return intrinsicHeight;
    }

    /**
     * Update the state of all its children based on a linear layout algorithm.
     *
     * @param resultState the state to update
     * @param parentState the state of the parent
     */
    public void getState(StackScrollState resultState, StackViewState parentState) {
        int childCount = mChildren.size();
        int yPosition = mNotificationHeaderHeight;
        boolean firstChild = true;
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren();
        int lastVisibleIndex = maxAllowedVisibleChildren - 1;
        float expandFactor = 0;
        if (mUserLocked) {
            expandFactor = getChildExpandFraction();
        }
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            if (!firstChild) {
                if (mUserLocked) {
                     yPosition += NotificationUtils.interpolate(mChildPadding, mDividerHeight,
                             expandFactor);
                } else {
                    yPosition += mChildrenExpanded ? mDividerHeight : mChildPadding;
                }
            } else {
                if (mUserLocked) {
                    yPosition += NotificationUtils.interpolate(
                            0,
                            mNotificatonTopPadding + mDividerHeight,
                            expandFactor);
                } else {
                    yPosition += mChildrenExpanded ? mNotificatonTopPadding + mDividerHeight : 0;
                }
                firstChild = false;
            }
            StackViewState childState = resultState.getViewStateForView(child);
            int intrinsicHeight = child.getIntrinsicHeight();
            childState.yTranslation = yPosition;
            childState.zTranslation = 0;
            childState.height = intrinsicHeight;
            childState.dimmed = parentState.dimmed;
            childState.dark = parentState.dark;
            childState.hideSensitive = parentState.hideSensitive;
            childState.belowSpeedBump = parentState.belowSpeedBump;
            childState.clipTopAmount = 0;
            childState.topOverLap = 0;
            boolean visible = i <= lastVisibleIndex;
            childState.alpha = visible ? 1 : 0;
            childState.location = parentState.location;
            yPosition += intrinsicHeight;
        }
    }

    private int getMaxAllowedVisibleChildren() {
        return getMaxAllowedVisibleChildren(false /* likeCollapsed */);
    }

    private int getMaxAllowedVisibleChildren(boolean likeCollapsed) {
        if (!likeCollapsed && (mChildrenExpanded || mNotificationParent.isUserLocked())) {
            return NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED;
        }
        if (mNotificationParent.isExpanded() || mNotificationParent.isHeadsUp()) {
            return NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED;
        }
        return NUMBER_OF_CHILDREN_WHEN_COLLAPSED;
    }

    public void applyState(StackScrollState state) {
        int childCount = mChildren.size();
        ViewState tmpState = new ViewState();
        float expandFraction = getChildExpandFraction();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            StackViewState viewState = state.getViewStateForView(child);
            state.applyState(child, viewState);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.yTranslation = viewState.yTranslation - mDividerHeight;
            float alpha = mChildrenExpanded && viewState.alpha != 0 ? 0.5f : 0;
            if (mUserLocked && viewState.alpha != 0) {
                alpha = NotificationUtils.interpolate(0, 0.5f, expandFraction);
            }
            tmpState.alpha = alpha;
            state.applyViewState(divider, tmpState);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
    }

    /**
     * This is called when the children expansion has changed and positions the children properly
     * for an appear animation.
     *
     * @param state the new state we animate to
     */
    public void prepareExpansionChanged(StackScrollState state) {
        // TODO: do something that makes sense, like placing the invisible views correctly
        return;
    }

    public void startAnimationToState(StackScrollState state, StackStateAnimator stateAnimator,
            long baseDelay, long duration) {
        int childCount = mChildren.size();
        ViewState tmpState = new ViewState();
        float expandFraction = getChildExpandFraction();
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableNotificationRow child = mChildren.get(i);
            StackViewState viewState = state.getViewStateForView(child);
            stateAnimator.startStackAnimations(child, viewState, state, -1, baseDelay);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.yTranslation = viewState.yTranslation - mDividerHeight;
            float alpha = mChildrenExpanded && viewState.alpha != 0 ? 0.5f : 0;
            if (mUserLocked && viewState.alpha != 0) {
                alpha = NotificationUtils.interpolate(0, 0.5f, expandFraction);
            }
            tmpState.alpha = alpha;
            stateAnimator.startViewAnimations(divider, tmpState, baseDelay, duration);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
    }

    public ExpandableNotificationRow getViewAtPosition(float y) {
        // find the view under the pointer, accounting for GONE views
        final int count = mChildren.size();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableNotificationRow slidingChild = mChildren.get(childIdx);
            float childTop = slidingChild.getTranslationY();
            float top = childTop + slidingChild.getClipTopAmount();
            float bottom = childTop + slidingChild.getActualHeight();
            if (y >= top && y <= bottom) {
                return slidingChild;
            }
        }
        return null;
    }

    public void setChildrenExpanded(boolean childrenExpanded) {
        mChildrenExpanded = childrenExpanded;
        updateExpansionStates();
    }

    public void setNotificationParent(ExpandableNotificationRow parent) {
        mNotificationParent = parent;
    }

    public int getMaxContentHeight() {
        int maxContentHeight = mNotificationHeaderHeight + mNotificatonTopPadding;
        int visibleChildren = 0;
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED) {
                break;
            }
            ExpandableNotificationRow child = mChildren.get(i);
            float childHeight = child.isExpanded(true /* allowOnKeyguard */)
                    ? child.getMaxExpandHeight()
                    : child.getShowingLayout().getMinHeight(true /* likeGroupExpanded */);
            maxContentHeight += childHeight;
            visibleChildren++;
        }
        if (visibleChildren > 0) {
            maxContentHeight += visibleChildren * mDividerHeight;
        }
        return maxContentHeight;
    }

    public void setActualHeight(int actualHeight) {
        if (!mUserLocked) {
            return;
        }
        mActualHeight = actualHeight;
        float fraction = getChildExpandFraction();
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            float childHeight = child.isExpanded(true /* allowOnKeyguard */)
                    ? child.getMaxExpandHeight()
                    : child.getShowingLayout().getMinHeight(true /* likeGroupExpanded */);
            float singleLineHeight = child.getShowingLayout().getMinHeight(
                    false /* likeGroupExpanded */);
            child.setActualHeight((int) NotificationUtils.interpolate(singleLineHeight, childHeight,
                    fraction), false);
        }
    }

    public float getChildExpandFraction() {
        int allChildrenVisibleHeight = getChildrenExpandStartHeight();
        int maxContentHeight = getMaxContentHeight();
        float factor = (mActualHeight - allChildrenVisibleHeight)
                / (float) (maxContentHeight - allChildrenVisibleHeight);
        return Math.max(0.0f, Math.min(1.0f, factor));
    }

    private int getChildrenExpandStartHeight() {
        int intrinsicHeight = mNotificationHeaderHeight;
        int visibleChildren = 0;
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED) {
                break;
            }
            ExpandableNotificationRow child = mChildren.get(i);
            intrinsicHeight += child.getMinHeight();
            visibleChildren++;
        }
        if (visibleChildren > 0) {
            intrinsicHeight += (visibleChildren - 1) * mChildPadding;
        }
        intrinsicHeight += mCollapsedBottompadding;
        return intrinsicHeight;
    }

    public int getMinHeight() {
        return getIntrinsicHeight(NUMBER_OF_CHILDREN_WHEN_COLLAPSED);
    }

    public int getMinExpandHeight(boolean onKeyguard) {
        int maxAllowedVisibleChildren = onKeyguard ? NUMBER_OF_CHILDREN_WHEN_COLLAPSED
                : getMaxAllowedVisibleChildren(true /* forceCollapsed */);
        int minExpandHeight = mNotificationHeaderHeight;
        int visibleChildren = 0;
        boolean firstChild = true;
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            if (!firstChild) {
                minExpandHeight += mChildPadding;
            } else {
                firstChild = false;
            }
            ExpandableNotificationRow child = mChildren.get(i);
            minExpandHeight += child.getMinHeight();
            visibleChildren++;
        }
        minExpandHeight += mCollapsedBottompadding;
        return minExpandHeight;
    }

    public void reInflateViews() {
        initDimens();
        for (int i = 0; i < mDividers.size(); i++) {
            View prevDivider = mDividers.get(i);
            int index = indexOfChild(prevDivider);
            removeView(prevDivider);
            View divider = inflateDivider();
            addView(divider, index);
            mDividers.set(i, divider);
        }
    }

    public void setUserLocked(boolean userLocked) {
        mUserLocked = userLocked;
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            child.setUserLocked(userLocked);
        }
    }
}
