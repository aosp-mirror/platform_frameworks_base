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

import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationHeaderUtil;
import com.android.systemui.statusbar.notification.HybridGroupManager;
import com.android.systemui.statusbar.notification.HybridNotificationView;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.NotificationViewWrapper;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
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
    private final HybridGroupManager mHybridGroupManager;
    private int mChildPadding;
    private int mDividerHeight;
    private int mMaxNotificationHeight;
    private int mNotificationHeaderMargin;
    private int mNotificatonTopPadding;
    private float mCollapsedBottompadding;
    private ViewInvertHelper mOverflowInvertHelper;
    private boolean mChildrenExpanded;
    private ExpandableNotificationRow mNotificationParent;
    private TextView mOverflowNumber;
    private ViewState mGroupOverFlowState;
    private int mRealHeight;
    private boolean mUserLocked;
    private int mActualHeight;
    private boolean mNeverAppliedGroupState;
    private int mHeaderHeight;

    private NotificationHeaderView mNotificationHeader;
    private NotificationViewWrapper mNotificationHeaderWrapper;
    private NotificationHeaderUtil mHeaderUtil;
    private ViewState mHeaderViewState;

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
        mHybridGroupManager = new HybridGroupManager(getContext(), this);
    }

    private void initDimens() {
        mChildPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_children_padding);
        mDividerHeight = Math.max(1, getResources().getDimensionPixelSize(
                R.dimen.notification_divider_height));
        mHeaderHeight = getResources().getDimensionPixelSize(R.dimen.notification_header_height);
        mMaxNotificationHeight = getResources().getDimensionPixelSize(
                R.dimen.notification_max_height);
        mNotificationHeaderMargin = getResources().getDimensionPixelSize(
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
            // We need to layout all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
            mDividers.get(i).layout(0, 0, getWidth(), mDividerHeight);
        }
        if (mOverflowNumber != null) {
            boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int left = (isRtl ? 0 : getWidth() - mOverflowNumber.getMeasuredWidth());
            int right = left + mOverflowNumber.getMeasuredWidth();
            mOverflowNumber.layout(left, 0, right, mOverflowNumber.getMeasuredHeight());
        }
        if (mNotificationHeader != null) {
            mNotificationHeader.layout(0, 0, mNotificationHeader.getMeasuredWidth(),
                    mNotificationHeader.getMeasuredHeight());
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
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (mOverflowNumber != null) {
            mOverflowNumber.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    newHeightSpec);
        }
        int dividerHeightSpec = MeasureSpec.makeMeasureSpec(mDividerHeight, MeasureSpec.EXACTLY);
        int height = mNotificationHeaderMargin + mNotificatonTopPadding;
        int childCount = Math.min(mChildren.size(), NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
        int collapsedChildren = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        int overflowIndex = childCount > collapsedChildren ? collapsedChildren - 1 : -1;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            // We need to measure all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen.
            boolean isOverflow = i == overflowIndex;
            child.setSingleLineWidthIndention(isOverflow && mOverflowNumber != null
                    ? mOverflowNumber.getMeasuredWidth()
                    : 0);
            child.measure(widthMeasureSpec, newHeightSpec);
            // layout the divider
            View divider = mDividers.get(i);
            divider.measure(widthMeasureSpec, dividerHeightSpec);
            if (child.getVisibility() != GONE) {
                height += child.getMeasuredHeight() + mDividerHeight;
            }
        }
        mRealHeight = height;
        if (heightMode != MeasureSpec.UNSPECIFIED) {
            height = Math.min(height, size);
        }

        if (mNotificationHeader != null) {
            int headerHeightSpec = MeasureSpec.makeMeasureSpec(mHeaderHeight, MeasureSpec.EXACTLY);
            mNotificationHeader.measure(widthMeasureSpec, headerHeightSpec);
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

        updateGroupOverflow();
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
        updateGroupOverflow();
        if (!row.isRemoved()) {
            mHeaderUtil.restoreNotificationHeader(row);
        }
    }

    /**
     * @return The number of notification children in the container.
     */
    public int getNotificationChildCount() {
        return mChildren.size();
    }

    public void recreateNotificationHeader(OnClickListener listener, StatusBarNotification notification) {
        final Notification.Builder builder = Notification.Builder.recoverBuilder(getContext(),
                mNotificationParent.getStatusBarNotification().getNotification());
        final RemoteViews header = builder.makeNotificationHeader();
        if (mNotificationHeader == null) {
            mNotificationHeader = (NotificationHeaderView) header.apply(getContext(), this);
            final View expandButton = mNotificationHeader.findViewById(
                    com.android.internal.R.id.expand_button);
            expandButton.setVisibility(VISIBLE);
            mNotificationHeader.setOnClickListener(listener);
            mNotificationHeaderWrapper = NotificationViewWrapper.wrap(getContext(),
                    mNotificationHeader, mNotificationParent);
            addView(mNotificationHeader, 0);
            invalidate();
        } else {
            header.reapply(getContext(), mNotificationHeader);
            mNotificationHeaderWrapper.notifyContentUpdated(notification);
        }
        updateChildrenHeaderAppearance();
    }

    public void updateChildrenHeaderAppearance() {
        mHeaderUtil.updateChildrenHeaderAppearance();
    }

    public void updateGroupOverflow() {
        int childCount = mChildren.size();
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        if (childCount > maxAllowedVisibleChildren) {
            mOverflowNumber = mHybridGroupManager.bindOverflowNumber(
                    mOverflowNumber, childCount - maxAllowedVisibleChildren);
            if (mOverflowInvertHelper == null) {
                mOverflowInvertHelper = new ViewInvertHelper(mOverflowNumber,
                        NotificationPanelView.DOZE_ANIMATION_DURATION);
            }
            if (mGroupOverFlowState == null) {
                mGroupOverFlowState = new ViewState();
                mNeverAppliedGroupState = true;
            }
        } else if (mOverflowNumber != null) {
            removeView(mOverflowNumber);
            if (isShown()) {
                final View removedOverflowNumber = mOverflowNumber;
                addTransientView(removedOverflowNumber, getTransientViewCount());
                CrossFadeHelper.fadeOut(removedOverflowNumber, new Runnable() {
                    @Override
                    public void run() {
                        removeTransientView(removedOverflowNumber);
                    }
                });
            }
            mOverflowNumber = null;
            mOverflowInvertHelper = null;
            mGroupOverFlowState = null;
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateGroupOverflow();
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
     * @param visualStabilityManager
     * @param callback
     * @return whether the list order has changed
     */
    public boolean applyChildOrder(List<ExpandableNotificationRow> childOrder,
            VisualStabilityManager visualStabilityManager,
            VisualStabilityManager.Callback callback) {
        if (childOrder == null) {
            return false;
        }
        boolean result = false;
        for (int i = 0; i < mChildren.size() && i < childOrder.size(); i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            ExpandableNotificationRow desiredChild = childOrder.get(i);
            if (child != desiredChild) {
                if (visualStabilityManager.canReorderNotification(desiredChild)) {
                    mChildren.remove(desiredChild);
                    mChildren.add(i, desiredChild);
                    result = true;
                } else {
                    visualStabilityManager.addReorderingAllowedCallback(callback);
                }
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
        int intrinsicHeight = mNotificationHeaderMargin;
        int visibleChildren = 0;
        int childCount = mChildren.size();
        boolean firstChild = true;
        float expandFactor = 0;
        if (mUserLocked) {
            expandFactor = getGroupExpandFraction();
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
        int yPosition = mNotificationHeaderMargin;
        boolean firstChild = true;
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren();
        int lastVisibleIndex = maxAllowedVisibleChildren - 1;
        int firstOverflowIndex = lastVisibleIndex + 1;
        float expandFactor = 0;
        if (mUserLocked) {
            expandFactor = getGroupExpandFraction();
            firstOverflowIndex = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        }

        boolean childrenExpanded = !mNotificationParent.isGroupExpansionChanging()
                && mChildrenExpanded;
        int parentHeight = parentState.height;
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
            if (childrenExpanded) {
                // When a group is expanded and moving into bottom stack, the bottom visible child
                // adjusts its height to move into it. Children after it are hidden.
                if (updateChildStateForExpandedGroup(child, parentHeight, childState, yPosition)) {
                    // Clipping might be deactivated if the view is transforming, however, clipping
                    // the child into the bottom stack should take precedent over this.
                    childState.isBottomClipped = true;
                }
            } else {
                childState.hidden = false;
                childState.height = intrinsicHeight;
                childState.isBottomClipped = false;
            }
            childState.yTranslation = yPosition;
            // When the group is expanded, the children cast the shadows rather than the parent
            // so use the parent's elevation here.
            childState.zTranslation = childrenExpanded
                    ? mNotificationParent.getTranslationZ()
                    : 0;
            childState.dimmed = parentState.dimmed;
            childState.dark = parentState.dark;
            childState.hideSensitive = parentState.hideSensitive;
            childState.belowSpeedBump = parentState.belowSpeedBump;
            childState.clipTopAmount = 0;
            childState.alpha = 0;
            if (i < firstOverflowIndex) {
                childState.alpha = 1;
            } else if (expandFactor == 1.0f && i <= lastVisibleIndex) {
                childState.alpha = (mActualHeight - childState.yTranslation) / childState.height;
                childState.alpha = Math.max(0.0f, Math.min(1.0f, childState.alpha));
            }
            childState.location = parentState.location;
            yPosition += intrinsicHeight;

        }
        if (mOverflowNumber != null) {
            ExpandableNotificationRow overflowView = mChildren.get(Math.min(
                    getMaxAllowedVisibleChildren(true /* likeCollpased */), childCount) - 1);
            mGroupOverFlowState.copyFrom(resultState.getViewStateForView(overflowView));
            if (!mChildrenExpanded) {
                if (mUserLocked) {
                    HybridNotificationView singleLineView = overflowView.getSingleLineView();
                    View mirrorView = singleLineView.getTextView();
                    if (mirrorView.getVisibility() == GONE) {
                        mirrorView = singleLineView.getTitleView();
                    }
                    if (mirrorView.getVisibility() == GONE) {
                        mirrorView = singleLineView;
                    }
                    mGroupOverFlowState.yTranslation += NotificationUtils.getRelativeYOffset(
                            mirrorView, overflowView);
                    mGroupOverFlowState.alpha = mirrorView.getAlpha();
                }
            } else {
                mGroupOverFlowState.yTranslation += mNotificationHeaderMargin;
                mGroupOverFlowState.alpha = 0.0f;
            }
        }
        if (mNotificationHeader != null) {
            if (mHeaderViewState == null) {
                mHeaderViewState = new ViewState();
            }
            mHeaderViewState.initFrom(mNotificationHeader);
            mHeaderViewState.zTranslation = childrenExpanded
                    ? mNotificationParent.getTranslationZ()
                    : 0;
        }
    }

    /**
     * When moving into the bottom stack, the bottom visible child in an expanded group adjusts its
     * height, children in the group after this are gone.
     *
     * @param child the child who's height to adjust.
     * @param parentHeight the height of the parent.
     * @param childState the state to update.
     * @param yPosition the yPosition of the view.
     * @return true if children after this one should be hidden.
     */
    private boolean updateChildStateForExpandedGroup(ExpandableNotificationRow child,
            int parentHeight, StackViewState childState, int yPosition) {
        final int top = yPosition + child.getClipTopAmount();
        final int intrinsicHeight = child.getIntrinsicHeight();
        final int bottom = top + intrinsicHeight;
        int newHeight = intrinsicHeight;
        if (bottom >= parentHeight) {
            // Child is either clipped or gone
            newHeight = Math.max((parentHeight - top), 0);
        }
        childState.hidden = newHeight == 0;
        childState.height = newHeight;
        return childState.height != intrinsicHeight && !childState.hidden;
    }

    private int getMaxAllowedVisibleChildren() {
        return getMaxAllowedVisibleChildren(false /* likeCollapsed */);
    }

    private int getMaxAllowedVisibleChildren(boolean likeCollapsed) {
        if (!likeCollapsed && (mChildrenExpanded || mNotificationParent.isUserLocked())) {
            return NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED;
        }
        if (!mNotificationParent.isOnKeyguard()
                && (mNotificationParent.isExpanded() || mNotificationParent.isHeadsUp())) {
            return NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED;
        }
        return NUMBER_OF_CHILDREN_WHEN_COLLAPSED;
    }

    public void applyState(StackScrollState state) {
        int childCount = mChildren.size();
        ViewState tmpState = new ViewState();
        float expandFraction = 0.0f;
        if (mUserLocked) {
            expandFraction = getGroupExpandFraction();
        }
        final boolean dividersVisible = mUserLocked
                || mNotificationParent.isGroupExpansionChanging();
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
                alpha = NotificationUtils.interpolate(0, 0.5f,
                        Math.min(viewState.alpha, expandFraction));
            }
            tmpState.hidden = !dividersVisible;
            tmpState.alpha = alpha;
            state.applyViewState(divider, tmpState);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
        if (mOverflowNumber != null) {
            state.applyViewState(mOverflowNumber, mGroupOverFlowState);
            mNeverAppliedGroupState = false;
        }
        if (mNotificationHeader != null) {
            state.applyViewState(mNotificationHeader, mHeaderViewState);
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
        float expandFraction = getGroupExpandFraction();
        final boolean dividersVisible = mUserLocked
                || mNotificationParent.isGroupExpansionChanging();
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
                alpha = NotificationUtils.interpolate(0, 0.5f,
                        Math.min(viewState.alpha, expandFraction));
            }
            tmpState.hidden = !dividersVisible;
            tmpState.alpha = alpha;
            stateAnimator.startViewAnimations(divider, tmpState, baseDelay, duration);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
        if (mOverflowNumber != null) {
            if (mNeverAppliedGroupState) {
                float alpha = mGroupOverFlowState.alpha;
                mGroupOverFlowState.alpha = 0;
                state.applyViewState(mOverflowNumber, mGroupOverFlowState);
                mGroupOverFlowState.alpha = alpha;
                mNeverAppliedGroupState = false;
            }
            stateAnimator.startViewAnimations(mOverflowNumber, mGroupOverFlowState,
                    baseDelay, duration);
        }
        if (mNotificationHeader != null) {
            state.applyViewState(mNotificationHeader, mHeaderViewState);
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
        if (mNotificationHeader != null) {
            mNotificationHeader.setExpanded(childrenExpanded);
        }
        final int count = mChildren.size();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableNotificationRow child = mChildren.get(childIdx);
            child.setChildrenExpanded(childrenExpanded, false);
        }
    }

    public void setNotificationParent(ExpandableNotificationRow parent) {
        mNotificationParent = parent;
        mHeaderUtil = new NotificationHeaderUtil(mNotificationParent);
    }

    public ExpandableNotificationRow getNotificationParent() {
        return mNotificationParent;
    }

    public NotificationHeaderView getHeaderView() {
        return mNotificationHeader;
    }

    public void updateHeaderVisibility(int visiblity) {
        if (mNotificationHeader != null) {
            mNotificationHeader.setVisibility(visiblity);
        }
    }

    /**
     * Called when a groups expansion changes to adjust the background of the header view.
     *
     * @param expanded whether the group is expanded.
     */
    public void updateHeaderForExpansion(boolean expanded) {
        if (mNotificationHeader != null) {
            if (expanded) {
                ColorDrawable cd = new ColorDrawable();
                cd.setColor(mNotificationParent.calculateBgColor());
                mNotificationHeader.setHeaderBackgroundDrawable(cd);
            } else {
                mNotificationHeader.setHeaderBackgroundDrawable(null);
            }
        }
    }

    public int getMaxContentHeight() {
        int maxContentHeight = mNotificationHeaderMargin + mNotificatonTopPadding;
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
        float fraction = getGroupExpandFraction();
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* forceCollapsed */);
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            float childHeight = child.isExpanded(true /* allowOnKeyguard */)
                    ? child.getMaxExpandHeight()
                    : child.getShowingLayout().getMinHeight(true /* likeGroupExpanded */);
            if (i < maxAllowedVisibleChildren) {
                float singleLineHeight = child.getShowingLayout().getMinHeight(
                        false /* likeGroupExpanded */);
                child.setActualHeight((int) NotificationUtils.interpolate(singleLineHeight,
                        childHeight, fraction), false);
            } else {
                child.setActualHeight((int) childHeight, false);
            }
        }
    }

    public float getGroupExpandFraction() {
        int visibleChildrenExpandedHeight = getVisibleChildrenExpandHeight();
        int minExpandHeight = getCollapsedHeight();
        float factor = (mActualHeight - minExpandHeight)
                / (float) (visibleChildrenExpandedHeight - minExpandHeight);
        return Math.max(0.0f, Math.min(1.0f, factor));
    }

    private int getVisibleChildrenExpandHeight() {
        int intrinsicHeight = mNotificationHeaderMargin + mNotificatonTopPadding + mDividerHeight;
        int visibleChildren = 0;
        int childCount = mChildren.size();
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* forceCollapsed */);
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            ExpandableNotificationRow child = mChildren.get(i);
            float childHeight = child.isExpanded(true /* allowOnKeyguard */)
                    ? child.getMaxExpandHeight()
                    : child.getShowingLayout().getMinHeight(true /* likeGroupExpanded */);
            intrinsicHeight += childHeight;
            visibleChildren++;
        }
        return intrinsicHeight;
    }

    public int getMinHeight() {
        return getMinHeight(NUMBER_OF_CHILDREN_WHEN_COLLAPSED);
    }

    public int getCollapsedHeight() {
        return getMinHeight(getMaxAllowedVisibleChildren(true /* forceCollapsed */));
    }

    private int getMinHeight(int maxAllowedVisibleChildren) {
        int minExpandHeight = mNotificationHeaderMargin;
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
            minExpandHeight += child.getSingleLineView().getHeight();
            visibleChildren++;
        }
        minExpandHeight += mCollapsedBottompadding;
        return minExpandHeight;
    }

    public void setDark(boolean dark, boolean fade, long delay) {
        if (mOverflowNumber != null) {
            mOverflowInvertHelper.setInverted(dark, fade, delay);
        }
        mNotificationHeaderWrapper.setDark(dark, fade, delay);
    }

    public void reInflateViews(OnClickListener listener, StatusBarNotification notification) {
        removeView(mNotificationHeader);
        mNotificationHeader = null;
        recreateNotificationHeader(listener, notification);
        initDimens();
        for (int i = 0; i < mDividers.size(); i++) {
            View prevDivider = mDividers.get(i);
            int index = indexOfChild(prevDivider);
            removeView(prevDivider);
            View divider = inflateDivider();
            addView(divider, index);
            mDividers.set(i, divider);
        }
        removeView(mOverflowNumber);
        mOverflowNumber = null;
        mOverflowInvertHelper = null;
        mGroupOverFlowState = null;
        updateGroupOverflow();
    }

    public void setUserLocked(boolean userLocked) {
        mUserLocked = userLocked;
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            child.setUserLocked(userLocked);
        }
    }

    public void onNotificationUpdated() {
        mHybridGroupManager.setOverflowNumberColor(mOverflowNumber,
                mNotificationParent.getNotificationColor());
    }

    public int getPositionInLinearLayout(View childInGroup) {
        int position = mNotificationHeaderMargin + mNotificatonTopPadding;

        for (int i = 0; i < mChildren.size(); i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            boolean notGone = child.getVisibility() != View.GONE;
            if (notGone) {
                position += mDividerHeight;
            }
            if (child == childInGroup) {
                return position;
            }
            if (notGone) {
                position += child.getIntrinsicHeight();
            }
        }
        return 0;
    }
}
