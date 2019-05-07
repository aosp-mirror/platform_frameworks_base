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
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationHeaderUtil;
import com.android.systemui.statusbar.notification.HybridGroupManager;
import com.android.systemui.statusbar.notification.HybridNotificationView;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.NotificationViewWrapper;
import com.android.systemui.statusbar.notification.VisualStabilityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * A container containing child notifications
 */
public class NotificationChildrenContainer extends ViewGroup {

    private static final int NUMBER_OF_CHILDREN_WHEN_COLLAPSED = 2;
    private static final int NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED = 5;
    private static final int NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED = 8;
    private static final int NUMBER_OF_CHILDREN_WHEN_AMBIENT = 1;
    private static final AnimationProperties ALPHA_FADE_IN = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);

    private final List<View> mDividers = new ArrayList<>();
    private final List<ExpandableNotificationRow> mChildren = new ArrayList<>();
    private final HybridGroupManager mHybridGroupManager;
    private int mChildPadding;
    private int mDividerHeight;
    private float mDividerAlpha;
    private int mNotificationHeaderMargin;

    private int mNotificatonTopPadding;
    private float mCollapsedBottompadding;
    private boolean mChildrenExpanded;
    private ExpandableNotificationRow mContainingNotification;
    private TextView mOverflowNumber;
    private ViewState mGroupOverFlowState;
    private int mRealHeight;
    private boolean mUserLocked;
    private int mActualHeight;
    private boolean mNeverAppliedGroupState;
    private int mHeaderHeight;

    /**
     * Whether or not individual notifications that are part of this container will have shadows.
     */
    private boolean mEnableShadowOnChildNotifications;

    private NotificationHeaderView mNotificationHeader;
    private NotificationViewWrapper mNotificationHeaderWrapper;
    private NotificationHeaderView mNotificationHeaderLowPriority;
    private NotificationViewWrapper mNotificationHeaderWrapperLowPriority;
    private ViewGroup mNotificationHeaderAmbient;
    private NotificationViewWrapper mNotificationHeaderWrapperAmbient;
    private NotificationHeaderUtil mHeaderUtil;
    private ViewState mHeaderViewState;
    private int mClipBottomAmount;
    private boolean mIsLowPriority;
    private OnClickListener mHeaderClickListener;
    private ViewGroup mCurrentHeader;

    private boolean mShowDividersWhenExpanded;
    private boolean mHideDividersDuringExpand;
    private int mTranslationForHeader;
    private int mCurrentHeaderTranslation = 0;
    private float mHeaderVisibleAmount = 1.0f;

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
        mHybridGroupManager = new HybridGroupManager(getContext(), this);
        initDimens();
        setClipChildren(false);
    }

    private void initDimens() {
        Resources res = getResources();
        mChildPadding = res.getDimensionPixelSize(R.dimen.notification_children_padding);
        mDividerHeight = res.getDimensionPixelSize(
                R.dimen.notification_children_container_divider_height);
        mDividerAlpha = res.getFloat(R.dimen.notification_divider_alpha);
        mNotificationHeaderMargin = res.getDimensionPixelSize(
                R.dimen.notification_children_container_margin_top);
        mNotificatonTopPadding = res.getDimensionPixelSize(
                R.dimen.notification_children_container_top_padding);
        mHeaderHeight = mNotificationHeaderMargin + mNotificatonTopPadding;
        mCollapsedBottompadding = res.getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin);
        mEnableShadowOnChildNotifications =
                res.getBoolean(R.bool.config_enableShadowOnChildNotifications);
        mShowDividersWhenExpanded =
                res.getBoolean(R.bool.config_showDividersWhenGroupNotificationExpanded);
        mHideDividersDuringExpand =
                res.getBoolean(R.bool.config_hideDividersDuringExpand);
        mTranslationForHeader = res.getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin)
                - mNotificationHeaderMargin;
        mHybridGroupManager.initDimens();
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
        if (mNotificationHeaderLowPriority != null) {
            mNotificationHeaderLowPriority.layout(0, 0,
                    mNotificationHeaderLowPriority.getMeasuredWidth(),
                    mNotificationHeaderLowPriority.getMeasuredHeight());
        }
        if (mNotificationHeaderAmbient != null) {
            mNotificationHeaderAmbient.layout(0, 0,
                    mNotificationHeaderAmbient.getMeasuredWidth(),
                    mNotificationHeaderAmbient.getMeasuredHeight());
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int newHeightSpec = heightMeasureSpec;
        if (hasFixedHeight || isHeightLimited) {
            newHeightSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST);
        }
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
            child.setSingleLineWidthIndention(isOverflow && mOverflowNumber != null &&
                    !mContainingNotification.isShowingAmbient()
                    ? mOverflowNumber.getMeasuredWidth() : 0);
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

        int headerHeightSpec = MeasureSpec.makeMeasureSpec(mHeaderHeight, MeasureSpec.EXACTLY);
        if (mNotificationHeader != null) {
            mNotificationHeader.measure(widthMeasureSpec, headerHeightSpec);
        }
        if (mNotificationHeaderLowPriority != null) {
            headerHeightSpec = MeasureSpec.makeMeasureSpec(mHeaderHeight, MeasureSpec.EXACTLY);
            mNotificationHeaderLowPriority.measure(widthMeasureSpec, headerHeightSpec);
        }
        if (mNotificationHeaderAmbient != null) {
            headerHeightSpec = MeasureSpec.makeMeasureSpec(mHeaderHeight, MeasureSpec.EXACTLY);
            mNotificationHeaderAmbient.measure(widthMeasureSpec, headerHeightSpec);
        }

        setMeasuredDimension(width, height);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
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
        row.setContentTransformationAmount(0, false /* isLastChild */);
        // It doesn't make sense to keep old animations around, lets cancel them!
        ExpandableNotificationRow.NotificationViewState viewState = row.getViewState();
        if (viewState != null) {
            viewState.cancelAnimations(row);
            row.cancelAppearDrawing();
        }
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

    public void recreateNotificationHeader(OnClickListener listener) {
        mHeaderClickListener = listener;
        StatusBarNotification notification = mContainingNotification.getStatusBarNotification();
        final Notification.Builder builder = Notification.Builder.recoverBuilder(getContext(),
                notification.getNotification());
        RemoteViews header = builder.makeNotificationHeader(false /* ambient */);
        if (mNotificationHeader == null) {
            mNotificationHeader = (NotificationHeaderView) header.apply(getContext(), this);
            final View expandButton = mNotificationHeader.findViewById(
                    com.android.internal.R.id.expand_button);
            expandButton.setVisibility(VISIBLE);
            mNotificationHeader.setOnClickListener(mHeaderClickListener);
            mNotificationHeaderWrapper = NotificationViewWrapper.wrap(getContext(),
                    mNotificationHeader, mContainingNotification);
            addView(mNotificationHeader, 0);
            invalidate();
        } else {
            header.reapply(getContext(), mNotificationHeader);
        }
        mNotificationHeaderWrapper.onContentUpdated(mContainingNotification);
        recreateLowPriorityHeader(builder);
        recreateAmbientHeader(builder);
        updateHeaderVisibility(false /* animate */);
        updateChildrenHeaderAppearance();
    }

    private void recreateAmbientHeader(Notification.Builder builder) {
        RemoteViews header;
        StatusBarNotification notification = mContainingNotification.getStatusBarNotification();
        if (builder == null) {
            builder = Notification.Builder.recoverBuilder(getContext(),
                    notification.getNotification());
        }
        header = builder.makeNotificationHeader(true /* ambient */);
        if (mNotificationHeaderAmbient == null) {
            mNotificationHeaderAmbient = (ViewGroup) header.apply(getContext(), this);
            mNotificationHeaderWrapperAmbient = NotificationViewWrapper.wrap(getContext(),
                    mNotificationHeaderAmbient, mContainingNotification);
            mNotificationHeaderWrapperAmbient.onContentUpdated(mContainingNotification);
            addView(mNotificationHeaderAmbient, 0);
            invalidate();
        } else {
            header.reapply(getContext(), mNotificationHeaderAmbient);
        }
        resetHeaderVisibilityIfNeeded(mNotificationHeaderAmbient, calculateDesiredHeader());
        mNotificationHeaderWrapperAmbient.onContentUpdated(mContainingNotification);
    }

    /**
     * Recreate the low-priority header.
     *
     * @param builder a builder to reuse. Otherwise the builder will be recovered.
     */
    private void recreateLowPriorityHeader(Notification.Builder builder) {
        RemoteViews header;
        StatusBarNotification notification = mContainingNotification.getStatusBarNotification();
        if (mIsLowPriority) {
            if (builder == null) {
                builder = Notification.Builder.recoverBuilder(getContext(),
                        notification.getNotification());
            }
            header = builder.makeLowPriorityContentView(true /* useRegularSubtext */);
            if (mNotificationHeaderLowPriority == null) {
                mNotificationHeaderLowPriority = (NotificationHeaderView) header.apply(getContext(),
                        this);
                final View expandButton = mNotificationHeaderLowPriority.findViewById(
                        com.android.internal.R.id.expand_button);
                expandButton.setVisibility(VISIBLE);
                mNotificationHeaderLowPriority.setOnClickListener(mHeaderClickListener);
                mNotificationHeaderWrapperLowPriority = NotificationViewWrapper.wrap(getContext(),
                        mNotificationHeaderLowPriority, mContainingNotification);
                addView(mNotificationHeaderLowPriority, 0);
                invalidate();
            } else {
                header.reapply(getContext(), mNotificationHeaderLowPriority);
            }
            mNotificationHeaderWrapperLowPriority.onContentUpdated(mContainingNotification);
            resetHeaderVisibilityIfNeeded(mNotificationHeaderLowPriority, calculateDesiredHeader());
        } else {
            removeView(mNotificationHeaderLowPriority);
            mNotificationHeaderLowPriority = null;
            mNotificationHeaderWrapperLowPriority = null;
        }
    }

    public void updateChildrenHeaderAppearance() {
        mHeaderUtil.updateChildrenHeaderAppearance();
    }

    public void updateGroupOverflow() {
        int childCount = mChildren.size();
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        if (childCount > maxAllowedVisibleChildren) {
            int number = childCount - maxAllowedVisibleChildren;
            mOverflowNumber = mHybridGroupManager.bindOverflowNumber(mOverflowNumber, number);
            if (mContainingNotification.isShowingAmbient()) {
                ExpandableNotificationRow overflowView = mChildren.get(0);
                HybridNotificationView ambientSingleLineView = overflowView == null ? null
                        : overflowView.getAmbientSingleLineView();
                if (ambientSingleLineView != null) {
                    mHybridGroupManager.bindOverflowNumberAmbient(
                            ambientSingleLineView.getTitleView(),
                            mContainingNotification.getStatusBarNotification().getNotification(),
                            number);
                }
            }
            if (mGroupOverFlowState == null) {
                mGroupOverFlowState = new ViewState();
                mNeverAppliedGroupState = true;
            }
        } else if (mOverflowNumber != null) {
            removeView(mOverflowNumber);
            if (isShown() && isAttachedToWindow()) {
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
        if (showingAsLowPriority()) {
            return mNotificationHeaderLowPriority.getHeight();
        }
        int intrinsicHeight = mNotificationHeaderMargin + mCurrentHeaderTranslation;
        int visibleChildren = 0;
        int childCount = mChildren.size();
        boolean firstChild = true;
        float expandFactor = 0;
        if (mUserLocked) {
            expandFactor = getGroupExpandFraction();
        }
        boolean childrenExpanded = mChildrenExpanded || mContainingNotification.isShowingAmbient();
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            if (!firstChild) {
                if (mUserLocked) {
                    intrinsicHeight += NotificationUtils.interpolate(mChildPadding, mDividerHeight,
                            expandFactor);
                } else {
                    intrinsicHeight += childrenExpanded ? mDividerHeight : mChildPadding;
                }
            } else {
                if (mUserLocked) {
                    intrinsicHeight += NotificationUtils.interpolate(
                            0,
                            mNotificatonTopPadding + mDividerHeight,
                            expandFactor);
                } else {
                    intrinsicHeight += childrenExpanded
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
        } else if (!childrenExpanded) {
            intrinsicHeight += mCollapsedBottompadding;
        }
        return intrinsicHeight;
    }

    /**
     * Update the state of all its children based on a linear layout algorithm.
     *  @param resultState the state to update
     * @param parentState the state of the parent
     * @param ambientState
     */
    public void getState(StackScrollState resultState, ExpandableViewState parentState,
            AmbientState ambientState) {
        int childCount = mChildren.size();
        int yPosition = mNotificationHeaderMargin + mCurrentHeaderTranslation;
        boolean firstChild = true;
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren();
        int lastVisibleIndex = maxAllowedVisibleChildren - 1;
        int firstOverflowIndex = lastVisibleIndex + 1;
        float expandFactor = 0;
        boolean expandingToExpandedGroup = mUserLocked && !showingAsLowPriority();
        if (mUserLocked) {
            expandFactor = getGroupExpandFraction();
            firstOverflowIndex = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        }

        boolean childrenExpandedAndNotAnimating = mChildrenExpanded
                && !mContainingNotification.isGroupExpansionChanging();
        int launchTransitionCompensation = 0;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            if (!firstChild) {
                if (expandingToExpandedGroup) {
                    yPosition += NotificationUtils.interpolate(mChildPadding, mDividerHeight,
                            expandFactor);
                } else {
                    yPosition += mChildrenExpanded ? mDividerHeight : mChildPadding;
                }
            } else {
                if (expandingToExpandedGroup) {
                    yPosition += NotificationUtils.interpolate(
                            0,
                            mNotificatonTopPadding + mDividerHeight,
                            expandFactor);
                } else {
                    yPosition += mChildrenExpanded ? mNotificatonTopPadding + mDividerHeight : 0;
                }
                firstChild = false;
            }

            ExpandableViewState childState = resultState.getViewStateForView(child);
            int intrinsicHeight = child.getIntrinsicHeight();
            childState.height = intrinsicHeight;
            childState.yTranslation = yPosition + launchTransitionCompensation;
            childState.hidden = false;
            // When the group is expanded, the children cast the shadows rather than the parent
            // so use the parent's elevation here.
            childState.zTranslation =
                    (childrenExpandedAndNotAnimating && mEnableShadowOnChildNotifications)
                    ? parentState.zTranslation
                    : 0;
            childState.dimmed = parentState.dimmed;
            childState.dark = parentState.dark;
            childState.hideSensitive = parentState.hideSensitive;
            childState.belowSpeedBump = parentState.belowSpeedBump;
            childState.clipTopAmount = 0;
            childState.alpha = 0;
            if (i < firstOverflowIndex) {
                childState.alpha = showingAsLowPriority() ? expandFactor : 1.0f;
            } else if (expandFactor == 1.0f && i <= lastVisibleIndex) {
                childState.alpha = (mActualHeight - childState.yTranslation) / childState.height;
                childState.alpha = Math.max(0.0f, Math.min(1.0f, childState.alpha));
            }
            childState.location = parentState.location;
            childState.inShelf = parentState.inShelf;
            yPosition += intrinsicHeight;
            if (child.isExpandAnimationRunning()) {
                launchTransitionCompensation = -ambientState.getExpandAnimationTopChange();
            }

        }
        if (mOverflowNumber != null) {
            ExpandableNotificationRow overflowView = mChildren.get(Math.min(
                    getMaxAllowedVisibleChildren(true /* likeCollapsed */), childCount) - 1);
            mGroupOverFlowState.copyFrom(resultState.getViewStateForView(overflowView));

            if (mContainingNotification.isShowingAmbient()) {
                mGroupOverFlowState.alpha = 0.0f;
            } else if (!mChildrenExpanded) {
                HybridNotificationView alignView = overflowView.getSingleLineView();
                if (alignView != null) {
                    View mirrorView = alignView.getTextView();
                    if (mirrorView.getVisibility() == GONE) {
                        mirrorView = alignView.getTitleView();
                    }
                    if (mirrorView.getVisibility() == GONE) {
                        mirrorView = alignView;
                    }
                    mGroupOverFlowState.alpha = mirrorView.getAlpha();
                    mGroupOverFlowState.yTranslation += NotificationUtils.getRelativeYOffset(
                            mirrorView, overflowView);
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
            mHeaderViewState.zTranslation = childrenExpandedAndNotAnimating
                    ? parentState.zTranslation
                    : 0;
            mHeaderViewState.yTranslation = mCurrentHeaderTranslation;
            mHeaderViewState.alpha = mHeaderVisibleAmount;
            // The hiding is done automatically by the alpha, otherwise we'll pick it up again
            // in the next frame with the initFrom call above and have an invisible header
            mHeaderViewState.hidden = false;
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
            int parentHeight, ExpandableViewState childState, int yPosition) {
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
        if (mContainingNotification.isShowingAmbient()) {
            return NUMBER_OF_CHILDREN_WHEN_AMBIENT;
        }
        if (!likeCollapsed && (mChildrenExpanded || mContainingNotification.isUserLocked())) {
            return NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED;
        }
        if (mIsLowPriority || !mContainingNotification.isOnKeyguard()
                && (mContainingNotification.isExpanded() || mContainingNotification.isHeadsUp())) {
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
        final boolean dividersVisible = mUserLocked && !showingAsLowPriority()
                || (mChildrenExpanded && mShowDividersWhenExpanded)
                || (mContainingNotification.isGroupExpansionChanging()
                && !mHideDividersDuringExpand);
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            ExpandableViewState viewState = state.getViewStateForView(child);
            viewState.applyToView(child);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.yTranslation = viewState.yTranslation - mDividerHeight;
            float alpha = mChildrenExpanded && viewState.alpha != 0 ? mDividerAlpha : 0;
            if (mUserLocked && !showingAsLowPriority() && viewState.alpha != 0) {
                alpha = NotificationUtils.interpolate(0, 0.5f,
                        Math.min(viewState.alpha, expandFraction));
            }
            tmpState.hidden = !dividersVisible;
            tmpState.alpha = alpha;
            tmpState.applyToView(divider);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
        if (mGroupOverFlowState != null) {
            mGroupOverFlowState.applyToView(mOverflowNumber);
            mNeverAppliedGroupState = false;
        }
        if (mHeaderViewState != null) {
            mHeaderViewState.applyToView(mNotificationHeader);
        }
        updateChildrenClipping();
    }

    private void updateChildrenClipping() {
        if (mContainingNotification.hasExpandingChild()) {
            return;
        }
        int childCount = mChildren.size();
        int layoutEnd = mContainingNotification.getActualHeight() - mClipBottomAmount;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            float childTop = child.getTranslationY();
            float childBottom = childTop + child.getActualHeight();
            boolean visible = true;
            int clipBottomAmount = 0;
            if (childTop > layoutEnd) {
                visible = false;
            } else if (childBottom > layoutEnd) {
                clipBottomAmount = (int) (childBottom - layoutEnd);
            }

            boolean isVisible = child.getVisibility() == VISIBLE;
            if (visible != isVisible) {
                child.setVisibility(visible ? VISIBLE : INVISIBLE);
            }

            child.setClipBottomAmount(clipBottomAmount);
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

    public void startAnimationToState(StackScrollState state, AnimationProperties properties) {
        int childCount = mChildren.size();
        ViewState tmpState = new ViewState();
        float expandFraction = getGroupExpandFraction();
        final boolean dividersVisible = mUserLocked && !showingAsLowPriority()
                || (mChildrenExpanded && mShowDividersWhenExpanded)
                || (mContainingNotification.isGroupExpansionChanging()
                && !mHideDividersDuringExpand);
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableNotificationRow child = mChildren.get(i);
            ExpandableViewState viewState = state.getViewStateForView(child);
            viewState.animateTo(child, properties);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.yTranslation = viewState.yTranslation - mDividerHeight;
            float alpha = mChildrenExpanded && viewState.alpha != 0 ? 0.5f : 0;
            if (mUserLocked && !showingAsLowPriority() && viewState.alpha != 0) {
                alpha = NotificationUtils.interpolate(0, 0.5f,
                        Math.min(viewState.alpha, expandFraction));
            }
            tmpState.hidden = !dividersVisible;
            tmpState.alpha = alpha;
            tmpState.animateTo(divider, properties);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
        if (mOverflowNumber != null) {
            if (mNeverAppliedGroupState) {
                float alpha = mGroupOverFlowState.alpha;
                mGroupOverFlowState.alpha = 0;
                mGroupOverFlowState.applyToView(mOverflowNumber);
                mGroupOverFlowState.alpha = alpha;
                mNeverAppliedGroupState = false;
            }
            mGroupOverFlowState.animateTo(mOverflowNumber, properties);
        }
        if (mNotificationHeader != null) {
            mHeaderViewState.applyToView(mNotificationHeader);
        }
        updateChildrenClipping();
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
        updateHeaderTouchability();
    }

    public void setContainingNotification(ExpandableNotificationRow parent) {
        mContainingNotification = parent;
        mHeaderUtil = new NotificationHeaderUtil(mContainingNotification);
    }

    public ExpandableNotificationRow getContainingNotification() {
        return mContainingNotification;
    }

    public NotificationHeaderView getHeaderView() {
        return mNotificationHeader;
    }

    public NotificationHeaderView getLowPriorityHeaderView() {
        return mNotificationHeaderLowPriority;
    }

    @VisibleForTesting
    public ViewGroup getCurrentHeaderView() {
        return mCurrentHeader;
    }

    public void notifyShowAmbientChanged() {
        updateHeaderVisibility(false);
        updateGroupOverflow();
    }

    private void updateHeaderVisibility(boolean animate) {
        ViewGroup desiredHeader;
        ViewGroup currentHeader = mCurrentHeader;
        desiredHeader = calculateDesiredHeader();

        if (currentHeader == desiredHeader) {
            return;
        }
        if (desiredHeader == mNotificationHeaderAmbient
                || currentHeader == mNotificationHeaderAmbient) {
            animate = false;
        }

        if (animate) {
            if (desiredHeader != null && currentHeader != null) {
                currentHeader.setVisibility(VISIBLE);
                desiredHeader.setVisibility(VISIBLE);
                NotificationViewWrapper visibleWrapper = getWrapperForView(desiredHeader);
                NotificationViewWrapper hiddenWrapper = getWrapperForView(currentHeader);
                visibleWrapper.transformFrom(hiddenWrapper);
                hiddenWrapper.transformTo(visibleWrapper, () -> updateHeaderVisibility(false));
                startChildAlphaAnimations(desiredHeader == mNotificationHeader);
            } else {
                animate = false;
            }
        }
        if (!animate) {
            if (desiredHeader != null) {
                getWrapperForView(desiredHeader).setVisible(true);
                desiredHeader.setVisibility(VISIBLE);
            }
            if (currentHeader != null) {
                // Wrapper can be null if we were a low priority notification
                // and just destroyed it by calling setIsLowPriority(false)
                NotificationViewWrapper wrapper = getWrapperForView(currentHeader);
                if (wrapper != null) {
                    wrapper.setVisible(false);
                }
                currentHeader.setVisibility(INVISIBLE);
            }
        }

        resetHeaderVisibilityIfNeeded(mNotificationHeader, desiredHeader);
        resetHeaderVisibilityIfNeeded(mNotificationHeaderAmbient, desiredHeader);
        resetHeaderVisibilityIfNeeded(mNotificationHeaderLowPriority, desiredHeader);

        mCurrentHeader = desiredHeader;
    }

    private void resetHeaderVisibilityIfNeeded(View header, View desiredHeader) {
        if (header == null) {
            return;
        }
        if (header != mCurrentHeader && header != desiredHeader) {
            getWrapperForView(header).setVisible(false);
            header.setVisibility(INVISIBLE);
        }
        if (header == desiredHeader && header.getVisibility() != VISIBLE) {
            getWrapperForView(header).setVisible(true);
            header.setVisibility(VISIBLE);
        }
    }

    private ViewGroup calculateDesiredHeader() {
        ViewGroup desiredHeader;
        if (mContainingNotification.isShowingAmbient()) {
            desiredHeader = mNotificationHeaderAmbient;
        } else if (showingAsLowPriority()) {
            desiredHeader = mNotificationHeaderLowPriority;
        } else {
            desiredHeader = mNotificationHeader;
        }
        return desiredHeader;
    }

    private void startChildAlphaAnimations(boolean toVisible) {
        float target = toVisible ? 1.0f : 0.0f;
        float start = 1.0f - target;
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (i >= NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED) {
                break;
            }
            ExpandableNotificationRow child = mChildren.get(i);
            child.setAlpha(start);
            ViewState viewState = new ViewState();
            viewState.initFrom(child);
            viewState.alpha = target;
            ALPHA_FADE_IN.setDelay(i * 50);
            viewState.animateTo(child, ALPHA_FADE_IN);
        }
    }


    private void updateHeaderTransformation() {
        if (mUserLocked && showingAsLowPriority()) {
            float fraction = getGroupExpandFraction();
            mNotificationHeaderWrapper.transformFrom(mNotificationHeaderWrapperLowPriority,
                    fraction);
            mNotificationHeader.setVisibility(VISIBLE);
            mNotificationHeaderWrapperLowPriority.transformTo(mNotificationHeaderWrapper,
                    fraction);
        }

    }

    private NotificationViewWrapper getWrapperForView(View visibleHeader) {
        if (visibleHeader == mNotificationHeader) {
            return mNotificationHeaderWrapper;
        }
        if (visibleHeader == mNotificationHeaderAmbient) {
            return mNotificationHeaderWrapperAmbient;
        }
        return mNotificationHeaderWrapperLowPriority;
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
                cd.setColor(mContainingNotification.calculateBgColor());
                mNotificationHeader.setHeaderBackgroundDrawable(cd);
            } else {
                mNotificationHeader.setHeaderBackgroundDrawable(null);
            }
        }
    }

    public int getMaxContentHeight() {
        if (showingAsLowPriority()) {
            return getMinHeight(NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED, true
                    /* likeHighPriority */);
        }
        int maxContentHeight = mNotificationHeaderMargin + mCurrentHeaderTranslation
                + mNotificatonTopPadding;
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
        boolean showingLowPriority = showingAsLowPriority();
        updateHeaderTransformation();
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* forceCollapsed */);
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            float childHeight;
            if (showingLowPriority) {
                childHeight = child.getShowingLayout().getMinHeight(false /* likeGroupExpanded */);
            } else if (child.isExpanded(true /* allowOnKeyguard */)) {
                childHeight = child.getMaxExpandHeight();
            } else {
                childHeight = child.getShowingLayout().getMinHeight(
                        true /* likeGroupExpanded */);
            }
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
        int visibleChildrenExpandedHeight = showingAsLowPriority() ? getMaxContentHeight()
                : getVisibleChildrenExpandHeight();
        int minExpandHeight = getCollapsedHeight();
        float factor = (mActualHeight - minExpandHeight)
                / (float) (visibleChildrenExpandedHeight - minExpandHeight);
        return Math.max(0.0f, Math.min(1.0f, factor));
    }

    private int getVisibleChildrenExpandHeight() {
        int intrinsicHeight = mNotificationHeaderMargin + mCurrentHeaderTranslation
                + mNotificatonTopPadding + mDividerHeight;
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
        return getMinHeight(mContainingNotification.isShowingAmbient()
                ? NUMBER_OF_CHILDREN_WHEN_AMBIENT
                : NUMBER_OF_CHILDREN_WHEN_COLLAPSED, false /* likeHighPriority */);
    }

    public int getCollapsedHeight() {
        return getMinHeight(getMaxAllowedVisibleChildren(true /* forceCollapsed */),
                false /* likeHighPriority */);
    }

    /**
     * Get the minimum Height for this group.
     *
     * @param maxAllowedVisibleChildren the number of children that should be visible
     * @param likeHighPriority if the height should be calculated as if it were not low priority
     */
    private int getMinHeight(int maxAllowedVisibleChildren, boolean likeHighPriority) {
        if (!likeHighPriority && showingAsLowPriority()) {
            return mNotificationHeaderLowPriority.getHeight();
        }
        int minExpandHeight = mNotificationHeaderMargin + mCurrentHeaderTranslation;
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

    public boolean showingAsLowPriority() {
        return mIsLowPriority && !mContainingNotification.isExpanded();
    }

    public void setDark(boolean dark, boolean fade, long delay) {
        if (mOverflowNumber != null) {
            mHybridGroupManager.setOverflowNumberDark(mOverflowNumber, dark, fade, delay);
        }
    }

    public void reInflateViews(OnClickListener listener, StatusBarNotification notification) {
        if (mNotificationHeader != null) {
            removeView(mNotificationHeader);
            mNotificationHeader = null;
        }
        if (mNotificationHeaderLowPriority != null) {
            removeView(mNotificationHeaderLowPriority);
            mNotificationHeaderLowPriority = null;
        }
        if (mNotificationHeaderAmbient != null) {
            removeView(mNotificationHeaderAmbient);
            mNotificationHeaderAmbient = null;
        }
        recreateNotificationHeader(listener);
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
        mGroupOverFlowState = null;
        updateGroupOverflow();
    }

    public void setUserLocked(boolean userLocked) {
        mUserLocked = userLocked;
        if (!mUserLocked) {
            updateHeaderVisibility(false /* animate */);
        }
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            child.setUserLocked(userLocked && !showingAsLowPriority());
        }
        updateHeaderTouchability();
    }

    private void updateHeaderTouchability() {
        if (mNotificationHeader != null) {
            mNotificationHeader.setAcceptAllTouches(mChildrenExpanded || mUserLocked);
        }
    }

    public void onNotificationUpdated() {
        mHybridGroupManager.setOverflowNumberColor(mOverflowNumber,
                mContainingNotification.getNotificationColor(),
                mContainingNotification.getNotificationColorAmbient());
    }

    public int getPositionInLinearLayout(View childInGroup) {
        int position = mNotificationHeaderMargin + mCurrentHeaderTranslation
                + mNotificatonTopPadding;

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

    public void setIconsVisible(boolean iconsVisible) {
        if (mNotificationHeaderWrapper != null) {
            NotificationHeaderView header = mNotificationHeaderWrapper.getNotificationHeader();
            if (header != null) {
                header.getIcon().setForceHidden(!iconsVisible);
            }
        }
        if (mNotificationHeaderWrapperLowPriority != null) {
            NotificationHeaderView header
                    = mNotificationHeaderWrapperLowPriority.getNotificationHeader();
            if (header != null) {
                header.getIcon().setForceHidden(!iconsVisible);
            }
        }
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        updateChildrenClipping();
    }

    public void setIsLowPriority(boolean isLowPriority) {
        mIsLowPriority = isLowPriority;
        if (mContainingNotification != null) { /* we're not yet set up yet otherwise */
            recreateLowPriorityHeader(null /* existingBuilder */);
            updateHeaderVisibility(false /* animate */);
        }
        if (mUserLocked) {
            setUserLocked(mUserLocked);
        }
    }

    public NotificationHeaderView getVisibleHeader() {
        NotificationHeaderView header = mNotificationHeader;
        if (showingAsLowPriority()) {
            header = mNotificationHeaderLowPriority;
        }
        return header;
    }

    public void onExpansionChanged() {
        if (mIsLowPriority) {
            if (mUserLocked) {
                setUserLocked(mUserLocked);
            }
            updateHeaderVisibility(true /* animate */);
        }
    }

    public float getIncreasedPaddingAmount() {
        if (showingAsLowPriority()) {
            return 0.0f;
        }
        return getGroupExpandFraction();
    }

    @VisibleForTesting
    public boolean isUserLocked() {
        return mUserLocked;
    }

    public void setCurrentBottomRoundness(float currentBottomRoundness) {
        boolean last = true;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            ExpandableNotificationRow child = mChildren.get(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            float bottomRoundness = last ? currentBottomRoundness : 0.0f;
            child.setBottomRoundness(bottomRoundness, isShown() /* animate */);
            last = false;
        }
    }

    public void setHeaderVisibleAmount(float headerVisibleAmount) {
        mHeaderVisibleAmount = headerVisibleAmount;
        mCurrentHeaderTranslation = (int) ((1.0f - headerVisibleAmount) * mTranslationForHeader);
    }
}
