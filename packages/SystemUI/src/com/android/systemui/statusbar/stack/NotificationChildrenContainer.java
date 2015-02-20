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
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.ExpandableView;

import java.util.ArrayList;
import java.util.List;

/**
 * A container containing child notifications
 */
public class NotificationChildrenContainer extends ViewGroup {

    private final int mChildPadding;
    private final int mDividerHeight;
    private final int mMaxNotificationHeight;
    private final List<View> mDividers = new ArrayList<>();
    private final List<ExpandableNotificationRow> mChildren = new ArrayList<>();
    private final View mCollapseButton;
    private final View mCollapseDivider;
    private final int mCollapseButtonHeight;
    private final int mNotificationAppearDistance;

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
        mChildPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_children_padding);
        mDividerHeight = getResources().getDimensionPixelSize(
                R.dimen.notification_children_divider_height);
        mMaxNotificationHeight = getResources().getDimensionPixelSize(
                R.dimen.notification_max_height);
        mNotificationAppearDistance = getResources().getDimensionPixelSize(
                R.dimen.notification_appear_distance);
        LayoutInflater inflater = mContext.getSystemService(LayoutInflater.class);
        mCollapseButton = inflater.inflate(R.layout.notification_collapse_button, this,
                false);
        mCollapseButtonHeight = getResources().getDimensionPixelSize(
                R.dimen.notification_bottom_decor_height);
        addView(mCollapseButton);
        mCollapseDivider = inflateDivider();
        addView(mCollapseDivider);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int childCount = mChildren.size();
        boolean firstChild = true;
        for (int i = 0; i < childCount; i++) {
            View child = mChildren.get(i);
            boolean viewGone = child.getVisibility() == View.GONE;
            if (i != 0) {
                View divider = mDividers.get(i - 1);
                int dividerVisibility = divider.getVisibility();
                int newVisibility = viewGone ? INVISIBLE : VISIBLE;
                if (dividerVisibility != newVisibility) {
                    divider.setVisibility(newVisibility);
                }
            }
            if (viewGone) {
                continue;
            }
            child.layout(0, 0, getWidth(), child.getMeasuredHeight());
            if (!firstChild) {
                mDividers.get(i - 1).layout(0, 0, getWidth(), mDividerHeight);
            } else {
                firstChild = false;
            }
        }
        mCollapseButton.layout(0, 0, getWidth(), mCollapseButtonHeight);
        mCollapseDivider.layout(0, mCollapseButtonHeight - mDividerHeight, getWidth(),
                mCollapseButtonHeight);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int ownMaxHeight = mMaxNotificationHeight;
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        if (hasFixedHeight || isHeightLimited) {
            int size = MeasureSpec.getSize(heightMeasureSpec);
            ownMaxHeight = Math.min(ownMaxHeight, size);
        }
        int newHeightSpec = MeasureSpec.makeMeasureSpec(ownMaxHeight, MeasureSpec.AT_MOST);
        int dividerHeightSpec = MeasureSpec.makeMeasureSpec(mDividerHeight, MeasureSpec.EXACTLY);
        int collapseButtonHeightSpec = MeasureSpec.makeMeasureSpec(mCollapseButtonHeight,
                MeasureSpec.EXACTLY);
        mCollapseButton.measure(widthMeasureSpec, collapseButtonHeightSpec);
        mCollapseDivider.measure(widthMeasureSpec, dividerHeightSpec);
        int height = mCollapseButtonHeight;
        int childCount = mChildren.size();
        boolean firstChild = true;
        for (int i = 0; i < childCount; i++) {
            View child = mChildren.get(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            child.measure(widthMeasureSpec, newHeightSpec);
            height += child.getMeasuredHeight();
            if (!firstChild) {
                // layout the divider
                View divider = mDividers.get(i - 1);
                divider.measure(widthMeasureSpec, dividerHeightSpec);
                height += mChildPadding;
            } else {
                firstChild = false;
            }
        }
        int width = MeasureSpec.getSize(widthMeasureSpec);
        height = hasFixedHeight ? ownMaxHeight
                : isHeightLimited ? Math.min(ownMaxHeight, height)
                : height;
        setMeasuredDimension(width, height);
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
        if (mChildren.size() != 1) {
            View divider = inflateDivider();
            addView(divider);
            mDividers.add(Math.max(newIndex - 1, 0), divider);
        }
        // TODO: adapt background corners
        // TODO: fix overdraw
    }

    public void removeNotification(ExpandableNotificationRow row) {
        int childIndex = mChildren.indexOf(row);
        mChildren.remove(row);
        removeView(row);
        if (!mDividers.isEmpty()) {
            View divider = mDividers.remove(Math.max(childIndex - 1, 0));
            removeView(divider);
        }
        row.setSystemChildExpanded(false);
        // TODO: adapt background corners
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

        // Let's make the first child expanded!
        boolean first = true;
        for (int i = 0; i < childOrder.size(); i++) {
            ExpandableNotificationRow child = childOrder.get(i);
            child.setSystemChildExpanded(first);
            first = false;
        }
        return result;
    }

    public int getIntrinsicHeight() {
        int childCount = mChildren.size();
        int intrinsicHeight = 0;
        int visibleChildren = 0;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            intrinsicHeight += child.getIntrinsicHeight();
            visibleChildren++;
        }
        if (visibleChildren > 0) {
            intrinsicHeight += (visibleChildren - 1) * mDividerHeight;
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
        int yPosition = mCollapseButtonHeight;
        boolean firstChild = true;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            if (!firstChild) {
                // There's a divider
                yPosition += mChildPadding;
            } else {
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
            childState.scale =  parentState.scale;
            childState.clipTopAmount = 0;
            childState.topOverLap = 0;
            childState.location = parentState.location;
            yPosition += intrinsicHeight;
        }
    }

    public void applyState(StackScrollState state) {
        int childCount = mChildren.size();
        boolean firstChild = true;
        ViewState dividerState = new ViewState();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            StackViewState viewState = state.getViewStateForView(child);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            if (!firstChild) {
                // layout the divider
                View divider = mDividers.get(i - 1);
                dividerState.initFrom(divider);
                dividerState.yTranslation = (int) (viewState.yTranslation
                        - (mChildPadding + mDividerHeight) / 2.0f);
                dividerState.alpha = 1;
                state.applyViewState(divider, dividerState);
            } else {
                firstChild = false;
            }
            state.applyState(child, viewState);
        }
    }

    public void setCollapseClickListener(OnClickListener collapseClickListener) {
        mCollapseButton.setOnClickListener(collapseClickListener);
    }

    /**
     * This is called when the children expansion has changed and positions the children properly
     * for an appear animation.
     *
     * @param state the new state we animate to
     */
    public void prepareExpansionChanged(StackScrollState state) {
        int childCount = mChildren.size();
        boolean firstChild = true;
        StackViewState sourceState = new StackViewState();
        ViewState dividerState = new ViewState();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            StackViewState viewState = state.getViewStateForView(child);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            if (!firstChild) {
                // layout the divider
                View divider = mDividers.get(i - 1);
                dividerState.initFrom(divider);
                dividerState.yTranslation = viewState.yTranslation
                        - (mChildPadding + mDividerHeight) / 2.0f + mNotificationAppearDistance;
                dividerState.alpha = 0;
                state.applyViewState(divider, dividerState);
            } else {
                firstChild = false;
            }
            sourceState.copyFrom(viewState);
            sourceState.alpha = 0;
            sourceState.yTranslation += mNotificationAppearDistance;
            state.applyState(child, sourceState);
        }
        mCollapseButton.setAlpha(0);
        mCollapseDivider.setAlpha(0);
        mCollapseDivider.setTranslationY(mNotificationAppearDistance / 4);
    }

    public void startAnimationToState(StackScrollState state, StackStateAnimator stateAnimator,
            boolean withDelays, long baseDelay, long duration) {
        int childCount = mChildren.size();
        boolean firstChild = true;
        ViewState dividerState = new ViewState();
        int notGoneIndex = 0;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            StackViewState viewState = state.getViewStateForView(child);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            int difference = Math.min(StackStateAnimator.DELAY_EFFECT_MAX_INDEX_DIFFERENCE_CHILDREN,
                    notGoneIndex + 1);
            long delay = withDelays
                    ? difference * StackStateAnimator.ANIMATION_DELAY_PER_ELEMENT_EXPAND_CHILDREN
                    : 0;
            delay += baseDelay;
            if (!firstChild) {
                // layout the divider
                View divider = mDividers.get(i - 1);
                dividerState.initFrom(divider);
                dividerState.yTranslation = viewState.yTranslation
                        - (mChildPadding + mDividerHeight) / 2.0f;
                dividerState.alpha = 1;
                stateAnimator.startViewAnimations(divider, dividerState, delay, duration);
            } else {
                firstChild = false;
            }
            stateAnimator.startStackAnimations(child, viewState, state, -1, delay);
            notGoneIndex++;
        }
        dividerState.initFrom(mCollapseButton);
        dividerState.alpha = 1.0f;
        stateAnimator.startViewAnimations(mCollapseButton, dividerState, baseDelay, duration);
        dividerState.initFrom(mCollapseDivider);
        dividerState.alpha = 1.0f;
        dividerState.yTranslation = 0.0f;
        stateAnimator.startViewAnimations(mCollapseDivider, dividerState, baseDelay, duration);
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

    public void setTintColor(int color) {
        ExpandableNotificationRow.applyTint(mCollapseDivider, color);
    }
}
