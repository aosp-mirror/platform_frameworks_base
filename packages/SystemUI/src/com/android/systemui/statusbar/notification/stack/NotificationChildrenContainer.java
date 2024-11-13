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

package com.android.systemui.statusbar.notification.stack;

import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.drawable.ColorDrawable;
import android.os.Trace;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.NotificationExpandButton;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.NotificationGroupingUtil;
import com.android.systemui.statusbar.notification.FeedbackIcon;
import com.android.systemui.statusbar.notification.NotificationFadeAware;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.Roundable;
import com.android.systemui.statusbar.notification.RoundableState;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.HybridGroupManager;
import com.android.systemui.statusbar.notification.row.HybridNotificationView;
import com.android.systemui.statusbar.notification.row.shared.AsyncGroupHeaderViewInflation;
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * A container containing child notifications
 */
public class NotificationChildrenContainer extends ViewGroup
        implements NotificationFadeAware, Roundable {

    private static final String TAG = "NotificationChildrenContainer";

    @VisibleForTesting
    static final int NUMBER_OF_CHILDREN_WHEN_COLLAPSED = 2;
    @VisibleForTesting
    static final int NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED = 5;
    public static final int NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED = 8;
    private static final AnimationProperties ALPHA_FADE_IN = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);
    private static final SourceType FROM_PARENT = SourceType.from("FromParent(NCC)");

    private final List<View> mDividers = new ArrayList<>();
    private final List<ExpandableNotificationRow> mAttachedChildren = new ArrayList<>();
    private final HybridGroupManager mHybridGroupManager;
    private int mChildPadding;
    private int mDividerHeight;
    private float mDividerAlpha;
    private int mNotificationHeaderMargin;

    private int mNotificationTopPadding;
    private float mCollapsedBottomPadding;
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

    private NotificationHeaderView mGroupHeader;
    private NotificationHeaderViewWrapper mGroupHeaderWrapper;
    private NotificationHeaderView mMinimizedGroupHeader;
    private NotificationHeaderViewWrapper mMinimizedGroupHeaderWrapper;
    private NotificationGroupingUtil mGroupingUtil;
    private ViewState mHeaderViewState;
    private int mClipBottomAmount;
    private boolean mIsMinimized;
    private OnClickListener mHeaderClickListener;
    private ViewGroup mCurrentHeader;
    private boolean mIsConversation;
    private Path mChildClipPath = null;
    private final Path mHeaderPath = new Path();
    private boolean mShowGroupCountInExpander;
    private boolean mShowDividersWhenExpanded;
    private boolean mHideDividersDuringExpand;
    private int mTranslationForHeader;
    private int mCurrentHeaderTranslation = 0;
    private float mHeaderVisibleAmount = 1.0f;
    private int mUntruncatedChildCount;
    private boolean mContainingNotificationIsFaded = false;
    private RoundableState mRoundableState;
    private int mMinSingleLineHeight;

    private NotificationChildrenContainerLogger mLogger;

    public NotificationChildrenContainer(Context context) {
        this(context, null);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationChildrenContainer(
            Context context,
            AttributeSet attrs,
            int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mHybridGroupManager = new HybridGroupManager(getContext());
        mRoundableState = new RoundableState(this, this, 0f);
        initDimens();
        setClipChildren(false);
    }

    private void initDimens() {
        Resources res = getResources();
        mChildPadding = res.getDimensionPixelOffset(R.dimen.notification_children_padding);
        mDividerHeight = res.getDimensionPixelOffset(
                R.dimen.notification_children_container_divider_height);
        mDividerAlpha = res.getFloat(R.dimen.notification_divider_alpha);
        mNotificationHeaderMargin = res.getDimensionPixelOffset(
                R.dimen.notification_children_container_margin_top);
        mNotificationTopPadding = res.getDimensionPixelOffset(
                R.dimen.notification_children_container_top_padding);
        mHeaderHeight = mNotificationHeaderMargin + mNotificationTopPadding;
        mCollapsedBottomPadding = res.getDimensionPixelOffset(
                R.dimen.notification_children_collapsed_bottom_padding);
        mEnableShadowOnChildNotifications =
                res.getBoolean(R.bool.config_enableShadowOnChildNotifications);
        mShowGroupCountInExpander =
                res.getBoolean(R.bool.config_showNotificationGroupCountInExpander);
        mShowDividersWhenExpanded =
                res.getBoolean(R.bool.config_showDividersWhenGroupNotificationExpanded);
        mHideDividersDuringExpand =
                res.getBoolean(R.bool.config_hideDividersDuringExpand);
        mTranslationForHeader = res.getDimensionPixelOffset(
                com.android.internal.R.dimen.notification_content_margin)
                - mNotificationHeaderMargin;
        mHybridGroupManager.initDimens();
        mMinSingleLineHeight = getResources().getDimensionPixelSize(
                R.dimen.conversation_single_line_face_pile_size);
    }

    @NonNull
    @Override
    public RoundableState getRoundableState() {
        return mRoundableState;
    }

    @Override
    public int getClipHeight() {
        return Math.max(mActualHeight - mClipBottomAmount, 0);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int childCount =
                Math.min(mAttachedChildren.size(), NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
        for (int i = 0; i < childCount; i++) {
            View child = mAttachedChildren.get(i);
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
        if (mGroupHeader != null) {
            mGroupHeader.layout(0, 0, mGroupHeader.getMeasuredWidth(),
                    mGroupHeader.getMeasuredHeight());
        }
        if (mMinimizedGroupHeader != null) {
            mMinimizedGroupHeader.layout(0, 0,
                    mMinimizedGroupHeader.getMeasuredWidth(),
                    mMinimizedGroupHeader.getMeasuredHeight());
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Trace.beginSection("NotificationChildrenContainer#onMeasure");
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
        int height = mNotificationHeaderMargin + mNotificationTopPadding;
        int childCount =
                Math.min(mAttachedChildren.size(), NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
        int collapsedChildren = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        int overflowIndex = childCount > collapsedChildren ? collapsedChildren - 1 : -1;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            // We need to measure all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen.
            boolean isOverflow = i == overflowIndex;
            child.setSingleLineWidthIndention(isOverflow && mOverflowNumber != null
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
        if (mGroupHeader != null) {
            mGroupHeader.measure(widthMeasureSpec, headerHeightSpec);
        }
        if (mMinimizedGroupHeader != null) {
            mMinimizedGroupHeader.measure(widthMeasureSpec, headerHeightSpec);
        }

        setMeasuredDimension(width, height);
        Trace.endSection();
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
     * Set the untruncated number of children in the group so that the view can update the UI
     * appropriately. Note that this may differ from the number of views attached as truncated
     * children will not have views.
     */
    public void setUntruncatedChildCount(int childCount) {
        mUntruncatedChildCount = childCount;
        updateGroupOverflow();
    }

    /**
     * Set the notification time in the group so that the view can show the latest event in the UI
     * appropriately.
     */
    public void setNotificationGroupWhen(long whenMillis) {
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setNotificationWhen(whenMillis);
        }
        if (mMinimizedGroupHeaderWrapper != null) {
            mMinimizedGroupHeaderWrapper.setNotificationWhen(whenMillis);
        }
    }

    /**
     * Add a child notification to this view.
     *
     * @param row        the row to add
     * @param childIndex the index to add it at, if -1 it will be added at the end
     */
    public void addNotification(ExpandableNotificationRow row, int childIndex) {
        ensureRemovedFromTransientContainer(row);
        int newIndex = childIndex < 0 ? mAttachedChildren.size() : childIndex;
        mAttachedChildren.add(newIndex, row);
        addView(row);
        row.setUserLocked(mUserLocked);

        View divider = inflateDivider();
        addView(divider);
        mDividers.add(newIndex, divider);

        row.setContentTransformationAmount(0, false /* isLastChild */);
        row.setNotificationFaded(mContainingNotificationIsFaded);

        // It doesn't make sense to keep old animations around, lets cancel them!
        ExpandableViewState viewState = row.getViewState();
        if (viewState != null) {
            viewState.cancelAnimations(row);
            row.cancelAppearDrawing();
        }

        applyRoundnessAndInvalidate();
    }

    private void ensureRemovedFromTransientContainer(View v) {
        if (v.getParent() != null && v instanceof ExpandableView) {
            // If the child is animating away, it will still have a parent, so detach it first
            // TODO: We should really cancel the active animations here. This will
            //  happen automatically when the view's intro animation starts, but
            //  it's a fragile link.
            ((ExpandableView) v).removeFromTransientContainerForAdditionTo(this);
        }
    }

    public void removeNotification(ExpandableNotificationRow row) {
        int childIndex = mAttachedChildren.indexOf(row);
        mAttachedChildren.remove(row);
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
        row.setNotificationFaded(false);
        row.setUserLocked(false);
        if (!row.isRemoved()) {
            mGroupingUtil.restoreChildNotification(row);
        }

        row.requestRoundnessReset(FROM_PARENT, /* animate = */ false);
        applyRoundnessAndInvalidate();
    }

    /**
     * @return The number of notification children in the container.
     */
    public int getNotificationChildCount() {
        return mAttachedChildren.size();
    }

    /**
     * Re-create the Notification header view
     * @param listener OnClickListener of the header view
     * @param isConversation if the notification group is a conversation group
     */
    public void recreateNotificationHeader(
            OnClickListener listener,
            boolean isConversation
    ) {
        // We don't want to inflate headers from the main thread when async inflation enabled
        AsyncGroupHeaderViewInflation.assertInLegacyMode();
        // TODO(b/217799515): remove traces from this function in a follow-up change
        Trace.beginSection("NotifChildCont#recreateHeader");
        mHeaderClickListener = listener;
        mIsConversation = isConversation;
        StatusBarNotification notification = mContainingNotification.getEntry().getSbn();
        final Notification.Builder builder = Notification.Builder.recoverBuilder(getContext(),
                notification.getNotification());
        Trace.beginSection("recreateHeader#makeNotificationGroupHeader");
        RemoteViews header = builder.makeNotificationGroupHeader();
        Trace.endSection();
        if (mGroupHeader == null) {
            Trace.beginSection("recreateHeader#apply");
            mGroupHeader = (NotificationHeaderView) header.apply(getContext(), this);
            Trace.endSection();
            mGroupHeader.findViewById(com.android.internal.R.id.expand_button)
                    .setVisibility(VISIBLE);
            mGroupHeader.setOnClickListener(mHeaderClickListener);
            mGroupHeaderWrapper =
                    (NotificationHeaderViewWrapper) NotificationViewWrapper.wrap(
                            getContext(),
                            mGroupHeader,
                            mContainingNotification);
            mGroupHeaderWrapper.setOnRoundnessChangedListener(this::invalidate);
            addView(mGroupHeader, 0);
            invalidate();
        } else {
            Trace.beginSection("recreateHeader#reapply");
            header.reapply(getContext(), mGroupHeader);
            Trace.endSection();
        }
        mGroupHeaderWrapper.setExpanded(mChildrenExpanded);
        mGroupHeaderWrapper.onContentUpdated(mContainingNotification);
        recreateLowPriorityHeader(builder, isConversation);
        updateHeaderVisibility(false /* animate */);
        updateChildrenAppearance();
        Trace.endSection();
    }

    /**
     * Update the expand state of the group header.
     */
    public void updateGroupHeaderExpandState() {
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setExpanded(mChildrenExpanded);
        }
    }

    private void removeGroupHeader() {
        if (mGroupHeader == null) {
            return;
        }
        removeView(mGroupHeader);
        mGroupHeader = null;
        mGroupHeaderWrapper = null;
    }

    private void removeLowPriorityGroupHeader() {
        if (mMinimizedGroupHeader == null) {
            return;
        }
        removeView(mMinimizedGroupHeader);
        mMinimizedGroupHeader = null;
        mMinimizedGroupHeaderWrapper = null;
    }

    /**
     * Set the group header view
     * @param headerView view to set
     * @param onClickListener OnClickListener of the header view
     */
    public void setGroupHeader(
            NotificationHeaderView headerView,
            OnClickListener onClickListener
    ) {
        if (AsyncGroupHeaderViewInflation.isUnexpectedlyInLegacyMode()) return;
        mHeaderClickListener = onClickListener;

        removeGroupHeader();

        if (headerView == null) {
            return;
        }

        mGroupHeader = headerView;
        mGroupHeader.findViewById(com.android.internal.R.id.expand_button)
                .setVisibility(VISIBLE);
        mGroupHeader.setOnClickListener(mHeaderClickListener);
        mGroupHeaderWrapper =
                (NotificationHeaderViewWrapper) NotificationViewWrapper.wrap(
                        getContext(),
                        mGroupHeader,
                        mContainingNotification);
        mGroupHeaderWrapper.setOnRoundnessChangedListener(this::invalidate);
        addView(mGroupHeader, 0);
        invalidate();

        mGroupHeaderWrapper.setExpanded(mChildrenExpanded);
        mGroupHeaderWrapper.onContentUpdated(mContainingNotification);

        updateHeaderVisibility(false /* animate */);
        updateChildrenAppearance();

        Trace.endSection();
    }

    /**
     * Set the low-priority group header view
     * @param headerViewLowPriority header view to set
     * @param onClickListener OnClickListener of the header view
     */
    public void setLowPriorityGroupHeader(
            NotificationHeaderView headerViewLowPriority,
            OnClickListener onClickListener
    ) {
        if (AsyncGroupHeaderViewInflation.isUnexpectedlyInLegacyMode()) return;
        removeLowPriorityGroupHeader();
        if (headerViewLowPriority == null) {
            return;
        }

        mMinimizedGroupHeader = headerViewLowPriority;
        mMinimizedGroupHeader.findViewById(com.android.internal.R.id.expand_button)
                .setVisibility(VISIBLE);
        mMinimizedGroupHeader.setOnClickListener(onClickListener);
        mMinimizedGroupHeaderWrapper =
                (NotificationHeaderViewWrapper) NotificationViewWrapper.wrap(
                        getContext(),
                        mMinimizedGroupHeader,
                        mContainingNotification);
        mMinimizedGroupHeaderWrapper.setOnRoundnessChangedListener(this::invalidate);
        addView(mMinimizedGroupHeader, 0);
        invalidate();

        mMinimizedGroupHeaderWrapper.onContentUpdated(mContainingNotification);
        updateHeaderVisibility(false /* animate */);
        updateChildrenAppearance();
    }

    /**
     * Recreate the low-priority header.
     *
     * @param builder a builder to reuse. Otherwise the builder will be recovered.
     */
    @VisibleForTesting
    void recreateLowPriorityHeader(Notification.Builder builder, boolean isConversation) {
        AsyncGroupHeaderViewInflation.assertInLegacyMode();
        RemoteViews header;
        StatusBarNotification notification = mContainingNotification.getEntry().getSbn();
        if (mIsMinimized) {
            if (builder == null) {
                builder = Notification.Builder.recoverBuilder(getContext(),
                        notification.getNotification());
            }
            header = builder.makeLowPriorityContentView(true /* useRegularSubtext */);
            if (mMinimizedGroupHeader == null) {
                mMinimizedGroupHeader = (NotificationHeaderView) header.apply(getContext(),
                        this);
                mMinimizedGroupHeader.findViewById(com.android.internal.R.id.expand_button)
                        .setVisibility(VISIBLE);
                mMinimizedGroupHeader.setOnClickListener(mHeaderClickListener);
                mMinimizedGroupHeaderWrapper =
                        (NotificationHeaderViewWrapper) NotificationViewWrapper.wrap(
                                getContext(),
                                mMinimizedGroupHeader,
                                mContainingNotification);
                mGroupHeaderWrapper.setOnRoundnessChangedListener(this::invalidate);
                addView(mMinimizedGroupHeader, 0);
                invalidate();
            } else {
                header.reapply(getContext(), mMinimizedGroupHeader);
            }
            mMinimizedGroupHeaderWrapper.onContentUpdated(mContainingNotification);
            resetHeaderVisibilityIfNeeded(mMinimizedGroupHeader, calculateDesiredHeader());
        } else {
            removeView(mMinimizedGroupHeader);
            mMinimizedGroupHeader = null;
            mMinimizedGroupHeaderWrapper = null;
        }
    }

    /**
     * Update the appearance of the children to reduce redundancies.
     */
    public void updateChildrenAppearance() {
        mGroupingUtil.updateChildrenAppearance();
    }

    private void setExpandButtonNumber(NotificationViewWrapper wrapper) {
        View expandButton = wrapper == null
                ? null : wrapper.getExpandButton();
        if (expandButton instanceof NotificationExpandButton) {
            ((NotificationExpandButton) expandButton).setNumber(mUntruncatedChildCount);
        }
    }

    public void updateGroupOverflow() {
        if (mShowGroupCountInExpander) {
            setExpandButtonNumber(mGroupHeaderWrapper);
            setExpandButtonNumber(mMinimizedGroupHeaderWrapper);
            return;
        }
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        if (mUntruncatedChildCount > maxAllowedVisibleChildren) {
            int number = mUntruncatedChildCount - maxAllowedVisibleChildren;
            mOverflowNumber = mHybridGroupManager.bindOverflowNumber(mOverflowNumber, number, this);
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
        View divider = LayoutInflater.from(mContext).inflate(
                R.layout.notification_children_divider, this, false);
        divider.setAlpha(0f);
        return divider;
    }

    /**
     * Get notification children that are attached currently.
     */
    public List<ExpandableNotificationRow> getAttachedChildren() {
        return mAttachedChildren;
    }

    /**
     * Sets the alpha on the content, while leaving the background of the container itself as is.
     *
     * @param alpha alpha value to apply to the content
     */
    public void setContentAlpha(float alpha) {
        if (mGroupHeader != null) {
            for (int i = 0; i < mGroupHeader.getChildCount(); i++) {
                mGroupHeader.getChildAt(i).setAlpha(alpha);
            }
        }
        for (ExpandableNotificationRow child : getAttachedChildren()) {
            child.setContentAlpha(alpha);
        }
    }

    /**
     * To be called any time the rows have been updated
     */
    public void updateExpansionStates() {
        if (mChildrenExpanded || mUserLocked) {
            // we don't modify it the group is expanded or if we are expanding it
            return;
        }
        int size = mAttachedChildren.size();
        for (int i = 0; i < size; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            child.setSystemChildExpanded(i == 0 && size == 1);
        }
    }

    /**
     * @return the intrinsic size of this children container, i.e the natural fully expanded state
     */
    public int getIntrinsicHeight() {
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren();
        return getIntrinsicHeight(maxAllowedVisibleChildren);
    }

    /**
     * @return the intrinsic height with a number of children given
     * in @param maxAllowedVisibleChildren
     */
    private int getIntrinsicHeight(float maxAllowedVisibleChildren) {
        if (showingAsLowPriority()) {
            if (AsyncGroupHeaderViewInflation.isEnabled()) {
                return mHeaderHeight;
            } else {
                return mMinimizedGroupHeader.getHeight();
            }
        }
        int intrinsicHeight = mNotificationHeaderMargin + mCurrentHeaderTranslation;
        int visibleChildren = 0;
        int childCount = mAttachedChildren.size();
        boolean firstChild = true;
        float expandFactor = 0;
        if (mUserLocked) {
            expandFactor = getGroupExpandFraction();
        }
        boolean childrenExpanded = mChildrenExpanded;
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
                            mNotificationTopPadding + mDividerHeight,
                            expandFactor);
                } else {
                    intrinsicHeight += childrenExpanded
                            ? mNotificationTopPadding + mDividerHeight
                            : 0;
                }
                firstChild = false;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            intrinsicHeight += child.getIntrinsicHeight();
            visibleChildren++;
        }
        if (mUserLocked) {
            intrinsicHeight += NotificationUtils.interpolate(mCollapsedBottomPadding, 0.0f,
                    expandFactor);
        } else if (!childrenExpanded) {
            intrinsicHeight += mCollapsedBottomPadding;
        }
        return intrinsicHeight;
    }

    /**
     * Update the state of all its children based on a linear layout algorithm.
     *
     * @param parentState  the state of the parent
     */
    public void updateState(ExpandableViewState parentState) {
        int childCount = mAttachedChildren.size();
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
            ExpandableNotificationRow child = mAttachedChildren.get(i);
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
                            mNotificationTopPadding + mDividerHeight,
                            expandFactor);
                } else {
                    yPosition += mChildrenExpanded ? mNotificationTopPadding + mDividerHeight : 0;
                }
                firstChild = false;
            }

            ExpandableViewState childState = child.getViewState();
            int intrinsicHeight = child.getIntrinsicHeight();
            childState.height = intrinsicHeight;
            childState.setYTranslation(yPosition + launchTransitionCompensation);
            childState.hidden = false;
            if (child.isExpandAnimationRunning() || mContainingNotification.hasExpandingChild()) {
                // Not modifying translationZ during launch animation. The translationZ of the
                // expanding child is handled inside ExpandableNotificationRow and the translationZ
                // of the other children inside the group should remain unchanged. In particular,
                // they should not take over the translationZ of the parent, since the parent has
                // a positive translationZ set only for the expanding child to be drawn above other
                // notifications.
                childState.setZTranslation(child.getTranslationZ());
            } else if (childrenExpandedAndNotAnimating && mEnableShadowOnChildNotifications) {
                // When the group is expanded, the children cast the shadows rather than the parent
                // so use the parent's elevation here.
                childState.setZTranslation(parentState.getZTranslation());
            } else {
                childState.setZTranslation(0);
            }
            childState.hideSensitive = parentState.hideSensitive;
            childState.belowSpeedBump = parentState.belowSpeedBump;
            childState.clipTopAmount = 0;
            childState.setAlpha(0);
            if (i < firstOverflowIndex) {
                childState.setAlpha(showingAsLowPriority() ? expandFactor : 1.0f);
            } else if (expandFactor == 1.0f && i <= lastVisibleIndex) {
                childState.setAlpha(
                        (mActualHeight - childState.getYTranslation()) / childState.height);
                childState.setAlpha(Math.max(0.0f, Math.min(1.0f, childState.getAlpha())));
            }
            childState.location = parentState.location;
            childState.inShelf = parentState.inShelf;
            yPosition += intrinsicHeight;
        }
        if (mOverflowNumber != null) {
            ExpandableNotificationRow overflowView = mAttachedChildren.get(Math.min(
                    getMaxAllowedVisibleChildren(true /* likeCollapsed */), childCount) - 1);
            mGroupOverFlowState.copyFrom(overflowView.getViewState());

            if (!mChildrenExpanded) {
                HybridNotificationView alignView = overflowView.getSingleLineView();
                if (alignView != null) {
                    View mirrorView = alignView.getTextView();
                    if (mirrorView.getVisibility() == GONE) {
                        mirrorView = alignView.getTitleView();
                    }
                    if (mirrorView.getVisibility() == GONE) {
                        mirrorView = alignView;
                    }
                    mGroupOverFlowState.setAlpha(mirrorView.getAlpha());
                    float yTranslation = mGroupOverFlowState.getYTranslation()
                            + NotificationUtils.getRelativeYOffset(
                            mirrorView, overflowView);
                    mGroupOverFlowState.setYTranslation(yTranslation);
                }
            } else {
                mGroupOverFlowState.setYTranslation(
                        mGroupOverFlowState.getYTranslation() + mNotificationHeaderMargin);
                mGroupOverFlowState.setAlpha(0.0f);
            }
        }
        if (mGroupHeader != null) {
            if (mHeaderViewState == null) {
                mHeaderViewState = new ViewState();
            }
            mHeaderViewState.initFrom(mGroupHeader);

            if (mContainingNotification.hasExpandingChild()) {
                // Not modifying translationZ during expand animation.
                mHeaderViewState.setZTranslation(mGroupHeader.getTranslationZ());
            } else if (childrenExpandedAndNotAnimating) {
                mHeaderViewState.setZTranslation(parentState.getZTranslation());
            } else {
                mHeaderViewState.setZTranslation(0);
            }
            mHeaderViewState.setYTranslation(mCurrentHeaderTranslation);
            mHeaderViewState.setAlpha(mHeaderVisibleAmount);
            // The hiding is done automatically by the alpha, otherwise we'll pick it up again
            // in the next frame with the initFrom call above and have an invisible header
            mHeaderViewState.hidden = false;
        }
    }

    /**
     * When moving into the bottom stack, the bottom visible child in an expanded group adjusts its
     * height, children in the group after this are gone.
     *
     * @param child        the child who's height to adjust.
     * @param parentHeight the height of the parent.
     * @param childState   the state to update.
     * @param yPosition    the yPosition of the view.
     * @return true if children after this one should be hidden.
     */
    private boolean updateChildStateForExpandedGroup(
            ExpandableNotificationRow child,
            int parentHeight,
            ExpandableViewState childState,
            int yPosition) {
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

    @VisibleForTesting
    int getMaxAllowedVisibleChildren() {
        return getMaxAllowedVisibleChildren(false /* likeCollapsed */);
    }

    @VisibleForTesting
    int getMaxAllowedVisibleChildren(boolean likeCollapsed) {
        if (!likeCollapsed && (mChildrenExpanded || mContainingNotification.isUserLocked())
                && !showingAsLowPriority()) {
            return NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED;
        }
        if (mIsMinimized
                || (!mContainingNotification.isOnKeyguard() && mContainingNotification.isExpanded())
                || (mContainingNotification.isHeadsUpState()
                && mContainingNotification.canShowHeadsUp())) {
            return NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED;
        }
        return NUMBER_OF_CHILDREN_WHEN_COLLAPSED;
    }

    /**
     * Applies state to children.
     */
    public void applyState() {
        int childCount = mAttachedChildren.size();
        ViewState tmpState = new ViewState();
        float expandFraction = 0.0f;
        if (mUserLocked) {
            expandFraction = getGroupExpandFraction();
        }
        final boolean isExpanding = !showingAsLowPriority()
                && (mUserLocked || mContainingNotification.isGroupExpansionChanging());
        final boolean dividersVisible = (mChildrenExpanded && mShowDividersWhenExpanded)
                || (isExpanding && !mHideDividersDuringExpand);
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            ExpandableViewState viewState = child.getViewState();
            viewState.applyToView(child);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.setYTranslation(viewState.getYTranslation() - mDividerHeight);
            float alpha = mChildrenExpanded && viewState.getAlpha() != 0 ? mDividerAlpha : 0;
            if (mUserLocked && !showingAsLowPriority() && viewState.getAlpha() != 0) {
                alpha = NotificationUtils.interpolate(0, mDividerAlpha,
                        Math.min(viewState.getAlpha(), expandFraction));
            }
            tmpState.hidden = !dividersVisible;
            tmpState.setAlpha(alpha);
            tmpState.applyToView(divider);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
        if (mGroupOverFlowState != null) {
            mGroupOverFlowState.applyToView(mOverflowNumber);
            mNeverAppliedGroupState = false;
        }
        if (mHeaderViewState != null) {
            mHeaderViewState.applyToView(mGroupHeader);
        }
        updateChildrenClipping();
    }

    private void updateChildrenClipping() {
        if (mContainingNotification.hasExpandingChild()) {
            return;
        }
        int childCount = mAttachedChildren.size();
        int layoutEnd = mContainingNotification.getActualHeight() - mClipBottomAmount;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
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

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        boolean isCanvasChanged = false;

        Path clipPath = mChildClipPath;
        if (clipPath != null) {
            final float translation;
            if (child instanceof ExpandableNotificationRow notificationRow) {
                translation = notificationRow.getTranslation();
            } else {
                translation = child.getTranslationX();
            }

            isCanvasChanged = true;
            canvas.save();
            if (translation != 0f) {
                clipPath.offset(translation, 0f);
                canvas.clipPath(clipPath);
                clipPath.offset(-translation, 0f);
            } else {
                canvas.clipPath(clipPath);
            }
        }

        if (child instanceof NotificationHeaderView
                && mGroupHeaderWrapper.hasRoundedCorner()) {
            float[] radii = mGroupHeaderWrapper.getUpdatedRadii();
            mHeaderPath.reset();
            mHeaderPath.addRoundRect(
                    child.getLeft(),
                    child.getTop(),
                    child.getRight(),
                    child.getBottom(),
                    radii,
                    Direction.CW
            );
            if (!isCanvasChanged) {
                isCanvasChanged = true;
                canvas.save();
            }
            canvas.clipPath(mHeaderPath);
        }

        if (isCanvasChanged) {
            boolean result = super.drawChild(canvas, child, drawingTime);
            canvas.restore();
            return result;
        } else {
            // If there have been no changes to the canvas we can proceed as usual
            return super.drawChild(canvas, child, drawingTime);
        }
    }


    /**
     * This is called when the children expansion has changed and positions the children properly
     * for an appear animation.
     */
    public void prepareExpansionChanged() {
        // TODO: do something that makes sense, like placing the invisible views correctly
        return;
    }

    /**
     * Animate to a given state.
     */
    public void startAnimationToState(AnimationProperties properties) {
        int childCount = mAttachedChildren.size();
        ViewState tmpState = new ViewState();
        float expandFraction = getGroupExpandFraction();
        final boolean isExpanding = !showingAsLowPriority()
                && (mUserLocked || mContainingNotification.isGroupExpansionChanging());
        final boolean dividersVisible = (mChildrenExpanded && mShowDividersWhenExpanded)
                || (isExpanding && !mHideDividersDuringExpand);
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            ExpandableViewState viewState = child.getViewState();
            viewState.animateTo(child, properties);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.setYTranslation(viewState.getYTranslation() - mDividerHeight);
            float alpha = mChildrenExpanded && viewState.getAlpha() != 0 ? mDividerAlpha : 0;
            if (mUserLocked && !showingAsLowPriority() && viewState.getAlpha() != 0) {
                alpha = NotificationUtils.interpolate(0, mDividerAlpha,
                        Math.min(viewState.getAlpha(), expandFraction));
            }
            tmpState.hidden = !dividersVisible;
            tmpState.setAlpha(alpha);
            tmpState.animateTo(divider, properties);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
        if (mOverflowNumber != null) {
            if (mNeverAppliedGroupState) {
                float alpha = mGroupOverFlowState.getAlpha();
                mGroupOverFlowState.setAlpha(0);
                mGroupOverFlowState.applyToView(mOverflowNumber);
                mGroupOverFlowState.setAlpha(alpha);
                mNeverAppliedGroupState = false;
            }
            mGroupOverFlowState.animateTo(mOverflowNumber, properties);
        }
        if (mGroupHeader != null) {
            mHeaderViewState.applyToView(mGroupHeader);
        }
        updateChildrenClipping();
    }

    public ExpandableNotificationRow getViewAtPosition(float y) {
        // find the view under the pointer, accounting for GONE views
        final int count = mAttachedChildren.size();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableNotificationRow slidingChild = mAttachedChildren.get(childIdx);
            float childTop = slidingChild.getTranslationY();
            float top = childTop + Math.max(0, slidingChild.getClipTopAmount());
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
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setExpanded(childrenExpanded);
        }
        final int count = mAttachedChildren.size();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableNotificationRow child = mAttachedChildren.get(childIdx);
            child.setChildrenExpanded(childrenExpanded, false);
        }
        updateHeaderTouchability();
    }

    public void setContainingNotification(ExpandableNotificationRow parent) {
        mContainingNotification = parent;
        mGroupingUtil = new NotificationGroupingUtil(mContainingNotification);
    }

    public ExpandableNotificationRow getContainingNotification() {
        return mContainingNotification;
    }

    public NotificationViewWrapper getNotificationViewWrapper() {
        return mGroupHeaderWrapper;
    }

    public NotificationViewWrapper getMinimizedGroupHeaderWrapper() {
        return mMinimizedGroupHeaderWrapper;
    }

    @VisibleForTesting
    public ViewGroup getCurrentHeaderView() {
        return mCurrentHeader;
    }

    public NotificationHeaderView getGroupHeader() {
        return mGroupHeader;
    }

    public NotificationHeaderView getMinimizedNotificationHeader() {
        return mMinimizedGroupHeader;
    }

    private void updateHeaderVisibility(boolean animate) {
        ViewGroup desiredHeader;
        ViewGroup currentHeader = mCurrentHeader;
        desiredHeader = calculateDesiredHeader();

        if (currentHeader == desiredHeader) {
            return;
        }

        if (AsyncGroupHeaderViewInflation.isEnabled() && desiredHeader == null) {
            return;
        }

        if (animate) {
            if (desiredHeader != null && currentHeader != null) {
                currentHeader.setVisibility(VISIBLE);
                desiredHeader.setVisibility(VISIBLE);
                NotificationViewWrapper visibleWrapper = getWrapperForView(desiredHeader);
                NotificationViewWrapper hiddenWrapper = getWrapperForView(currentHeader);
                visibleWrapper.transformFrom(hiddenWrapper);
                hiddenWrapper.transformTo(visibleWrapper, () -> updateHeaderVisibility(false));
                startChildAlphaAnimations(desiredHeader == mGroupHeader);
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

        resetHeaderVisibilityIfNeeded(mGroupHeader, desiredHeader);
        resetHeaderVisibilityIfNeeded(mMinimizedGroupHeader, desiredHeader);

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
        if (showingAsLowPriority()) {
            desiredHeader = mMinimizedGroupHeader;
        } else {
            desiredHeader = mGroupHeader;
        }
        return desiredHeader;
    }

    private void startChildAlphaAnimations(boolean toVisible) {
        float target = toVisible ? 1.0f : 0.0f;
        float start = 1.0f - target;
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (i >= NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED) {
                break;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            child.setAlpha(start);
            ViewState viewState = new ViewState();
            viewState.initFrom(child);
            viewState.setAlpha(target);
            ALPHA_FADE_IN.setDelay(i * 50);
            viewState.animateTo(child, ALPHA_FADE_IN);
        }
    }


    private void updateHeaderTransformation() {
        if (mUserLocked && showingAsLowPriority()) {
            float fraction = getGroupExpandFraction();
            mGroupHeaderWrapper.transformFrom(mMinimizedGroupHeaderWrapper,
                    fraction);
            mGroupHeader.setVisibility(VISIBLE);
            mMinimizedGroupHeaderWrapper.transformTo(mGroupHeaderWrapper,
                    fraction);
        }

    }

    private NotificationViewWrapper getWrapperForView(View visibleHeader) {
        if (visibleHeader == mGroupHeader) {
            return mGroupHeaderWrapper;
        }
        return mMinimizedGroupHeaderWrapper;
    }

    /**
     * Called when a groups expansion changes to adjust the background of the header view.
     *
     * @param expanded whether the group is expanded.
     */
    public void updateHeaderForExpansion(boolean expanded) {
        if (mGroupHeader != null) {
            if (expanded) {
                ColorDrawable cd = new ColorDrawable();
                cd.setColor(mContainingNotification.calculateBgColor());
                mGroupHeader.setHeaderBackgroundDrawable(cd);
            } else {
                mGroupHeader.setHeaderBackgroundDrawable(null);
            }
        }
    }

    public int getMaxContentHeight() {
        if (showingAsLowPriority()) {
            return getMinHeight(NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED, true
                    /* likeHighPriority */);
        }
        int maxContentHeight = mNotificationHeaderMargin + mCurrentHeaderTranslation
                + mNotificationTopPadding;
        int visibleChildren = 0;
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED) {
                break;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);
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
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
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
                + mNotificationTopPadding + mDividerHeight;
        int visibleChildren = 0;
        int childCount = mAttachedChildren.size();
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* forceCollapsed */);
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            float childHeight = child.isExpanded(true /* allowOnKeyguard */)
                    ? child.getMaxExpandHeight()
                    : child.getShowingLayout().getMinHeight(true /* likeGroupExpanded */);
            intrinsicHeight += childHeight;
            visibleChildren++;
        }
        return intrinsicHeight;
    }

    public int getMinHeight() {
        return getMinHeight(NUMBER_OF_CHILDREN_WHEN_COLLAPSED, false /* likeHighPriority */);
    }

    public int getCollapsedHeight() {
        return getMinHeight(getMaxAllowedVisibleChildren(true /* forceCollapsed */),
                false /* likeHighPriority */);
    }

    public int getCollapsedHeightWithoutHeader() {
        return getMinHeight(getMaxAllowedVisibleChildren(true /* forceCollapsed */),
                false /* likeHighPriority */, 0);
    }

    /**
     * Get the minimum Height for this group.
     *
     * @param maxAllowedVisibleChildren the number of children that should be visible
     * @param likeHighPriority          if the height should be calculated as if it were not low
     *                                  priority
     */
    private int getMinHeight(int maxAllowedVisibleChildren, boolean likeHighPriority) {
        return getMinHeight(maxAllowedVisibleChildren, likeHighPriority, mCurrentHeaderTranslation);
    }

    /**
     * Get the minimum Height for this group.
     *
     * @param maxAllowedVisibleChildren the number of children that should be visible
     * @param likeHighPriority          if the height should be calculated as if it were not low
     *                                  priority
     * @param headerTranslation         the translation amount of the header
     */
    private int getMinHeight(
            int maxAllowedVisibleChildren,
            boolean likeHighPriority,
            int headerTranslation) {
        if (!likeHighPriority && showingAsLowPriority()) {
            if (AsyncGroupHeaderViewInflation.isEnabled()) {
                return mHeaderHeight;
            }
            if (mMinimizedGroupHeader == null) {
                Log.e(TAG, "getMinHeight: low priority header is null", new Exception());
                return 0;
            }
            return mMinimizedGroupHeader.getHeight();
        }
        int minExpandHeight = mNotificationHeaderMargin + headerTranslation;
        int visibleChildren = 0;
        boolean firstChild = true;
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            if (!firstChild) {
                minExpandHeight += mChildPadding;
            } else {
                firstChild = false;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            View singleLineView = child.getSingleLineView();
            if (singleLineView != null) {
                minExpandHeight += singleLineView.getHeight();
            } else {
                if (AsyncHybridViewInflation.isEnabled()) {
                    minExpandHeight += mMinSingleLineHeight;
                } else {
                    Log.e(TAG, "getMinHeight: child " + child.getEntry().getKey()
                            + " single line view is null", new Exception());
                }
            }
            visibleChildren++;
        }
        minExpandHeight += mCollapsedBottomPadding;
        return minExpandHeight;
    }

    public boolean showingAsLowPriority() {
        return mIsMinimized && !mContainingNotification.isExpanded();
    }

    public void reInflateViews(OnClickListener listener, StatusBarNotification notification) {
        if (!AsyncGroupHeaderViewInflation.isEnabled()) {
            // When Async header inflation is enabled, we do not reinflate headers because they are
            // inflated from the background thread
            if (mGroupHeader != null) {
                removeView(mGroupHeader);
                mGroupHeader = null;
            }
            if (mMinimizedGroupHeader != null) {
                removeView(mMinimizedGroupHeader);
                mMinimizedGroupHeader = null;
            }
            recreateNotificationHeader(listener, mIsConversation);
        }
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
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            child.setUserLocked(userLocked && !showingAsLowPriority());
        }
        updateHeaderTouchability();
    }

    private void updateHeaderTouchability() {
        if (mGroupHeader != null) {
            mGroupHeader.setAcceptAllTouches(mChildrenExpanded || mUserLocked);
        }
    }

    public void onNotificationUpdated() {
        if (mShowGroupCountInExpander) {
            // The overflow number is not used, so its color is irrelevant; skip this
            return;
        }
        int color = mContainingNotification.getNotificationColor();
        Resources.Theme theme = new ContextThemeWrapper(mContext,
                com.android.internal.R.style.Theme_DeviceDefault_DayNight).getTheme();
        try (TypedArray ta = theme.obtainStyledAttributes(
                new int[]{com.android.internal.R.attr.materialColorPrimary})) {
            color = ta.getColor(0, color);
        }
        mHybridGroupManager.setOverflowNumberColor(mOverflowNumber, color);
    }

    public int getPositionInLinearLayout(View childInGroup) {
        int position = mNotificationHeaderMargin + mCurrentHeaderTranslation
                + mNotificationTopPadding;

        for (int i = 0; i < mAttachedChildren.size(); i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
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

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        updateChildrenClipping();
    }

    /**
     * Set whether the children container is minimized.
     */
    public void setIsMinimized(boolean isMinimized) {
        mIsMinimized = isMinimized;
        if (mContainingNotification != null) { /* we're not yet set up yet otherwise */
            if (!AsyncGroupHeaderViewInflation.isEnabled()) {
                recreateLowPriorityHeader(null /* existingBuilder */, mIsConversation);
            }
            updateHeaderVisibility(false /* animate */);
        }
        if (mUserLocked) {
            setUserLocked(mUserLocked);
        }
    }

    /**
     * @return the view wrapper for the currently showing priority.
     */
    public NotificationViewWrapper getVisibleWrapper() {
        if (showingAsLowPriority()) {
            return mMinimizedGroupHeaderWrapper;
        }
        return mGroupHeaderWrapper;
    }

    public void onExpansionChanged() {
        if (mIsMinimized) {
            if (mUserLocked) {
                setUserLocked(mUserLocked);
            }
            updateHeaderVisibility(true /* animate */);
        }
    }

    @VisibleForTesting
    public boolean isUserLocked() {
        return mUserLocked;
    }

    @Override
    public void applyRoundnessAndInvalidate() {
        boolean last = true;
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.requestTopRoundness(
                    /* value = */ getTopRoundness(),
                    /* sourceType = */ FROM_PARENT,
                    /* animate = */ false
            );
        }
        if (mMinimizedGroupHeaderWrapper != null) {
            mMinimizedGroupHeaderWrapper.requestTopRoundness(
                    /* value = */ getTopRoundness(),
                    /* sourceType = */ FROM_PARENT,
                    /* animate = */ false
            );
        }
        for (int i = mAttachedChildren.size() - 1; i >= 0; i--) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            child.requestRoundness(
                    /* top = */ 0f,
                    /* bottom = */ last ? getBottomRoundness() : 0f,
                    /* sourceType = */ FROM_PARENT,
                    /* animate = */ false);
            last = false;
        }
        Roundable.super.applyRoundnessAndInvalidate();
    }

    public void setHeaderVisibleAmount(float headerVisibleAmount) {
        mHeaderVisibleAmount = headerVisibleAmount;
        mCurrentHeaderTranslation = (int) ((1.0f - headerVisibleAmount) * mTranslationForHeader);
    }

    /**
     * Shows the given feedback icon, or hides the icon if null.
     */
    public void setFeedbackIcon(@Nullable FeedbackIcon icon) {
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setFeedbackIcon(icon);
        }
        if (mMinimizedGroupHeaderWrapper != null) {
            mMinimizedGroupHeaderWrapper.setFeedbackIcon(icon);
        }
    }

    public void setRecentlyAudiblyAlerted(boolean audiblyAlertedRecently) {
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setRecentlyAudiblyAlerted(audiblyAlertedRecently);
        }
        if (mMinimizedGroupHeaderWrapper != null) {
            mMinimizedGroupHeaderWrapper.setRecentlyAudiblyAlerted(audiblyAlertedRecently);
        }
    }

    @Override
    public void setNotificationFaded(boolean faded) {
        mContainingNotificationIsFaded = faded;
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setNotificationFaded(faded);
        }
        if (mMinimizedGroupHeaderWrapper != null) {
            mMinimizedGroupHeaderWrapper.setNotificationFaded(faded);
        }
        for (ExpandableNotificationRow child : mAttachedChildren) {
            child.setNotificationFaded(faded);
        }
    }

    /**
     * Allow to define a path the clip the children in #drawChild()
     *
     * @param childClipPath path used to clip the children
     */
    public void setChildClipPath(@Nullable Path childClipPath) {
        mChildClipPath = childClipPath;
        invalidate();
    }

    public NotificationHeaderViewWrapper getNotificationHeaderWrapper() {
        return mGroupHeaderWrapper;
    }

    public void setLogger(NotificationChildrenContainerLogger logger) {
        mLogger = logger;
    }

    @Override
    public void addTransientView(View view, int index) {
        if (mLogger != null && view instanceof ExpandableNotificationRow) {
            mLogger.addTransientRow(
                    ((ExpandableNotificationRow) view).getEntry(),
                    getContainingNotification().getEntry(),
                    index
            );
        }
        super.addTransientView(view, index);
    }

    @Override
    public void removeTransientView(View view) {
        if (mLogger != null && view instanceof ExpandableNotificationRow) {
            mLogger.removeTransientRow(
                    ((ExpandableNotificationRow) view).getEntry(),
                    getContainingNotification().getEntry()
            );
        }
        super.removeTransientView(view);
    }

    public String debugString() {
        return TAG + " { "
                + "visibility: " + getVisibility()
                + ", alpha: " + getAlpha()
                + ", translationY: " + getTranslationY()
                + ", roundableState: " + getRoundableState().debugString() + "}";
    }
}
