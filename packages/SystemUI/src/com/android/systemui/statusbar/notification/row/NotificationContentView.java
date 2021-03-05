/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationCustomViewWrapper;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;
import com.android.systemui.statusbar.policy.InflatedSmartReplyState;
import com.android.systemui.statusbar.policy.InflatedSmartReplyViewHolder;
import com.android.systemui.statusbar.policy.RemoteInputView;
import com.android.systemui.statusbar.policy.SmartReplyConstants;
import com.android.systemui.statusbar.policy.SmartReplyStateInflaterKt;
import com.android.systemui.statusbar.policy.SmartReplyView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A frame layout containing the actual payload of the notification, including the contracted,
 * expanded and heads up layout. This class is responsible for clipping the content and and
 * switching between the expanded, contracted and the heads up view depending on its clipped size.
 */
public class NotificationContentView extends FrameLayout {

    private static final String TAG = "NotificationContentView";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    public static final int VISIBLE_TYPE_CONTRACTED = 0;
    public static final int VISIBLE_TYPE_EXPANDED = 1;
    public static final int VISIBLE_TYPE_HEADSUP = 2;
    private static final int VISIBLE_TYPE_SINGLELINE = 3;
    /**
     * Used when there is no content on the view such as when we're a public layout but don't
     * need to show.
     */
    private static final int VISIBLE_TYPE_NONE = -1;

    private static final int UNDEFINED = -1;

    private final Rect mClipBounds = new Rect();

    private int mMinContractedHeight;
    private int mNotificationContentMarginEnd;
    private View mContractedChild;
    private View mExpandedChild;
    private View mHeadsUpChild;
    private HybridNotificationView mSingleLineView;

    private RemoteInputView mExpandedRemoteInput;
    private RemoteInputView mHeadsUpRemoteInput;

    private SmartReplyConstants mSmartReplyConstants;
    private SmartReplyView mExpandedSmartReplyView;
    private SmartReplyView mHeadsUpSmartReplyView;
    private SmartReplyController mSmartReplyController;
    private InflatedSmartReplyViewHolder mExpandedInflatedSmartReplies;
    private InflatedSmartReplyViewHolder mHeadsUpInflatedSmartReplies;
    private InflatedSmartReplyState mCurrentSmartReplyState;

    private NotificationViewWrapper mContractedWrapper;
    private NotificationViewWrapper mExpandedWrapper;
    private NotificationViewWrapper mHeadsUpWrapper;
    private HybridGroupManager mHybridGroupManager;
    private int mClipTopAmount;
    private int mContentHeight;
    private int mVisibleType = VISIBLE_TYPE_NONE;
    private boolean mAnimate;
    private boolean mIsHeadsUp;
    private boolean mLegacy;
    private boolean mIsChildInGroup;
    private int mSmallHeight;
    private int mHeadsUpHeight;
    private int mNotificationMaxHeight;
    private NotificationEntry mNotificationEntry;
    private GroupMembershipManager mGroupMembershipManager;
    private RemoteInputController mRemoteInputController;
    private Runnable mExpandedVisibleListener;
    private PeopleNotificationIdentifier mPeopleIdentifier;
    /**
     * List of listeners for when content views become inactive (i.e. not the showing view).
     */
    private final ArrayMap<View, Runnable> mOnContentViewInactiveListeners = new ArrayMap<>();

    private final ViewTreeObserver.OnPreDrawListener mEnableAnimationPredrawListener
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            // We need to post since we don't want the notification to animate on the very first
            // frame
            post(new Runnable() {
                @Override
                public void run() {
                    mAnimate = true;
                }
            });
            getViewTreeObserver().removeOnPreDrawListener(this);
            return true;
        }
    };

    private OnClickListener mExpandClickListener;
    private boolean mBeforeN;
    private boolean mExpandable;
    private boolean mClipToActualHeight = true;
    private ExpandableNotificationRow mContainingNotification;
    /** The visible type at the start of a touch driven transformation */
    private int mTransformationStartVisibleType;
    /** The visible type at the start of an animation driven transformation */
    private int mAnimationStartVisibleType = VISIBLE_TYPE_NONE;
    private boolean mUserExpanding;
    private int mSingleLineWidthIndention;
    private boolean mForceSelectNextLayout = true;
    private PendingIntent mPreviousExpandedRemoteInputIntent;
    private PendingIntent mPreviousHeadsUpRemoteInputIntent;
    private RemoteInputView mCachedExpandedRemoteInput;
    private RemoteInputView mCachedHeadsUpRemoteInput;

    private int mContentHeightAtAnimationStart = UNDEFINED;
    private boolean mFocusOnVisibilityChange;
    private boolean mHeadsUpAnimatingAway;
    private int mClipBottomAmount;
    private boolean mIsLowPriority;
    private boolean mIsContentExpandable;
    private boolean mRemoteInputVisible;
    private int mUnrestrictedContentHeight;

    public NotificationContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHybridGroupManager = new HybridGroupManager(getContext());
        mSmartReplyConstants = Dependency.get(SmartReplyConstants.class);
        mSmartReplyController = Dependency.get(SmartReplyController.class);
        initView();
    }

    public void initView() {
        mMinContractedHeight = getResources().getDimensionPixelSize(
                R.dimen.min_notification_layout_height);
        mNotificationContentMarginEnd = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_end);
    }

    public void setHeights(int smallHeight, int headsUpMaxHeight, int maxHeight) {
        mSmallHeight = smallHeight;
        mHeadsUpHeight = headsUpMaxHeight;
        mNotificationMaxHeight = maxHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        int maxSize = Integer.MAX_VALUE / 2;
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (hasFixedHeight || isHeightLimited) {
            maxSize = MeasureSpec.getSize(heightMeasureSpec);
        }
        int maxChildHeight = 0;
        if (mExpandedChild != null) {
            int notificationMaxHeight = mNotificationMaxHeight;
            if (mExpandedSmartReplyView != null) {
                notificationMaxHeight += mExpandedSmartReplyView.getHeightUpperLimit();
            }
            notificationMaxHeight += mExpandedWrapper.getExtraMeasureHeight();
            int size = notificationMaxHeight;
            ViewGroup.LayoutParams layoutParams = mExpandedChild.getLayoutParams();
            boolean useExactly = false;
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(size, layoutParams.height);
                useExactly = true;
            }
            int spec = MeasureSpec.makeMeasureSpec(size, useExactly
                            ? MeasureSpec.EXACTLY
                            : MeasureSpec.AT_MOST);
            measureChildWithMargins(mExpandedChild, widthMeasureSpec, 0, spec, 0);
            maxChildHeight = Math.max(maxChildHeight, mExpandedChild.getMeasuredHeight());
        }
        if (mContractedChild != null) {
            int heightSpec;
            int size = mSmallHeight;
            ViewGroup.LayoutParams layoutParams = mContractedChild.getLayoutParams();
            boolean useExactly = false;
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(size, layoutParams.height);
                useExactly = true;
            }
            if (shouldContractedBeFixedSize() || useExactly) {
                heightSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
            } else {
                heightSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST);
            }
            measureChildWithMargins(mContractedChild, widthMeasureSpec, 0, heightSpec, 0);
            int measuredHeight = mContractedChild.getMeasuredHeight();
            if (measuredHeight < mMinContractedHeight) {
                heightSpec = MeasureSpec.makeMeasureSpec(mMinContractedHeight, MeasureSpec.EXACTLY);
                measureChildWithMargins(mContractedChild, widthMeasureSpec, 0, heightSpec, 0);
            }
            maxChildHeight = Math.max(maxChildHeight, measuredHeight);
            if (updateContractedHeaderWidth()) {
                measureChildWithMargins(mContractedChild, widthMeasureSpec, 0, heightSpec, 0);
            }
            if (mExpandedChild != null
                    && mContractedChild.getMeasuredHeight() > mExpandedChild.getMeasuredHeight()) {
                // the Expanded child is smaller then the collapsed. Let's remeasure it.
                heightSpec = MeasureSpec.makeMeasureSpec(mContractedChild.getMeasuredHeight(),
                        MeasureSpec.EXACTLY);
                measureChildWithMargins(mExpandedChild, widthMeasureSpec, 0, heightSpec, 0);
            }
        }
        if (mHeadsUpChild != null) {
            int maxHeight = mHeadsUpHeight;
            if (mHeadsUpSmartReplyView != null) {
                maxHeight += mHeadsUpSmartReplyView.getHeightUpperLimit();
            }
            maxHeight += mHeadsUpWrapper.getExtraMeasureHeight();
            int size = maxHeight;
            ViewGroup.LayoutParams layoutParams = mHeadsUpChild.getLayoutParams();
            boolean useExactly = false;
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(size, layoutParams.height);
                useExactly = true;
            }
            measureChildWithMargins(mHeadsUpChild, widthMeasureSpec, 0,
                    MeasureSpec.makeMeasureSpec(size, useExactly ? MeasureSpec.EXACTLY
                            : MeasureSpec.AT_MOST), 0);
            maxChildHeight = Math.max(maxChildHeight, mHeadsUpChild.getMeasuredHeight());
        }
        if (mSingleLineView != null) {
            int singleLineWidthSpec = widthMeasureSpec;
            if (mSingleLineWidthIndention != 0
                    && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                singleLineWidthSpec = MeasureSpec.makeMeasureSpec(
                        width - mSingleLineWidthIndention + mSingleLineView.getPaddingEnd(),
                        MeasureSpec.EXACTLY);
            }
            mSingleLineView.measure(singleLineWidthSpec,
                    MeasureSpec.makeMeasureSpec(mNotificationMaxHeight, MeasureSpec.AT_MOST));
            maxChildHeight = Math.max(maxChildHeight, mSingleLineView.getMeasuredHeight());
        }
        int ownHeight = Math.min(maxChildHeight, maxSize);
        setMeasuredDimension(width, ownHeight);
    }

    /**
     * Get the extra height that needs to be added to the notification height for a given
     * {@link RemoteInputView}.
     * This is needed when the user is inline replying in order to ensure that the reply bar has
     * enough padding.
     *
     * @param remoteInput The remote input to check.
     * @return The extra height needed.
     */
    private int getExtraRemoteInputHeight(RemoteInputView remoteInput) {
        if (remoteInput != null && (remoteInput.isActive() || remoteInput.isSending())) {
            return getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.notification_content_margin);
        }
        return 0;
    }

    private boolean updateContractedHeaderWidth() {
        // We need to update the expanded and the collapsed header to have exactly the same with to
        // have the expand buttons laid out at the same location.
        NotificationHeaderView contractedHeader = mContractedWrapper.getNotificationHeader();
        if (contractedHeader != null && mExpandedWrapper != null
                && mExpandedWrapper.getNotificationHeader() != null) {
            NotificationHeaderView expandedHeader = mExpandedWrapper.getNotificationHeader();
            int headerTextMargin = expandedHeader.getTopLineExtraMarginEnd();
            if (headerTextMargin != contractedHeader.getTopLineExtraMarginEnd()) {
                contractedHeader.setTopLineExtraMarginEnd(headerTextMargin);
                return true;
            }
        }
        return false;
    }

    private boolean shouldContractedBeFixedSize() {
        return mBeforeN && mContractedWrapper instanceof NotificationCustomViewWrapper;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int previousHeight = 0;
        if (mExpandedChild != null) {
            previousHeight = mExpandedChild.getHeight();
        }
        super.onLayout(changed, left, top, right, bottom);
        if (previousHeight != 0 && mExpandedChild.getHeight() != previousHeight) {
            mContentHeightAtAnimationStart = previousHeight;
        }
        updateClipping();
        invalidateOutline();
        selectLayout(false /* animate */, mForceSelectNextLayout /* force */);
        mForceSelectNextLayout = false;
        updateExpandButtons(mExpandable);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateVisibility();
    }

    public View getContractedChild() {
        return mContractedChild;
    }

    public View getExpandedChild() {
        return mExpandedChild;
    }

    public View getHeadsUpChild() {
        return mHeadsUpChild;
    }

    /**
     * Sets the contracted view. Child may be null to remove the content view.
     *
     * @param child contracted content view to set
     */
    public void setContractedChild(@Nullable View child) {
        if (mContractedChild != null) {
            mOnContentViewInactiveListeners.remove(mContractedChild);
            mContractedChild.animate().cancel();
            removeView(mContractedChild);
        }
        if (child == null) {
            mContractedChild = null;
            mContractedWrapper = null;
            if (mTransformationStartVisibleType == VISIBLE_TYPE_CONTRACTED) {
                mTransformationStartVisibleType = VISIBLE_TYPE_NONE;
            }
            return;
        }
        addView(child);
        mContractedChild = child;
        mContractedWrapper = NotificationViewWrapper.wrap(getContext(), child,
                mContainingNotification);
    }

    private NotificationViewWrapper getWrapperForView(View child) {
        if (child == mContractedChild) {
            return mContractedWrapper;
        }
        if (child == mExpandedChild) {
            return mExpandedWrapper;
        }
        if (child == mHeadsUpChild) {
            return mHeadsUpWrapper;
        }
        return null;
    }

    /**
     * Sets the expanded view. Child may be null to remove the content view.
     *
     * @param child expanded content view to set
     */
    public void setExpandedChild(@Nullable View child) {
        if (mExpandedChild != null) {
            mPreviousExpandedRemoteInputIntent = null;
            if (mExpandedRemoteInput != null) {
                mExpandedRemoteInput.onNotificationUpdateOrReset();
                if (mExpandedRemoteInput.isActive()) {
                    mPreviousExpandedRemoteInputIntent = mExpandedRemoteInput.getPendingIntent();
                    mCachedExpandedRemoteInput = mExpandedRemoteInput;
                    mExpandedRemoteInput.dispatchStartTemporaryDetach();
                    ((ViewGroup)mExpandedRemoteInput.getParent()).removeView(mExpandedRemoteInput);
                }
            }
            mOnContentViewInactiveListeners.remove(mExpandedChild);
            mExpandedChild.animate().cancel();
            removeView(mExpandedChild);
            mExpandedRemoteInput = null;
        }
        if (child == null) {
            mExpandedChild = null;
            mExpandedWrapper = null;
            if (mTransformationStartVisibleType == VISIBLE_TYPE_EXPANDED) {
                mTransformationStartVisibleType = VISIBLE_TYPE_NONE;
            }
            if (mVisibleType == VISIBLE_TYPE_EXPANDED) {
                selectLayout(false /* animate */, true /* force */);
            }
            return;
        }
        addView(child);
        mExpandedChild = child;
        mExpandedWrapper = NotificationViewWrapper.wrap(getContext(), child,
                mContainingNotification);
        if (mContainingNotification != null) {
            applySystemActions(mExpandedChild, mContainingNotification.getEntry());
        }
    }

    /**
     * Sets the heads up view. Child may be null to remove the content view.
     *
     * @param child heads up content view to set
     */
    public void setHeadsUpChild(@Nullable View child) {
        if (mHeadsUpChild != null) {
            mPreviousHeadsUpRemoteInputIntent = null;
            if (mHeadsUpRemoteInput != null) {
                mHeadsUpRemoteInput.onNotificationUpdateOrReset();
                if (mHeadsUpRemoteInput.isActive()) {
                    mPreviousHeadsUpRemoteInputIntent = mHeadsUpRemoteInput.getPendingIntent();
                    mCachedHeadsUpRemoteInput = mHeadsUpRemoteInput;
                    mHeadsUpRemoteInput.dispatchStartTemporaryDetach();
                    ((ViewGroup)mHeadsUpRemoteInput.getParent()).removeView(mHeadsUpRemoteInput);
                }
            }
            mOnContentViewInactiveListeners.remove(mHeadsUpChild);
            mHeadsUpChild.animate().cancel();
            removeView(mHeadsUpChild);
            mHeadsUpRemoteInput = null;
        }
        if (child == null) {
            mHeadsUpChild = null;
            mHeadsUpWrapper = null;
            if (mTransformationStartVisibleType == VISIBLE_TYPE_HEADSUP) {
                mTransformationStartVisibleType = VISIBLE_TYPE_NONE;
            }
            if (mVisibleType == VISIBLE_TYPE_HEADSUP) {
                selectLayout(false /* animate */, true /* force */);
            }
            return;
        }
        addView(child);
        mHeadsUpChild = child;
        mHeadsUpWrapper = NotificationViewWrapper.wrap(getContext(), child,
                mContainingNotification);
        if (mContainingNotification != null) {
            applySystemActions(mHeadsUpChild, mContainingNotification.getEntry());
        }
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        child.setTag(R.id.row_tag_for_content_view, mContainingNotification);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateVisibility();
        if (visibility != VISIBLE && !mOnContentViewInactiveListeners.isEmpty()) {
            // View is no longer visible so all content views are inactive.
            // Clone list as runnables may modify the list of listeners
            ArrayList<Runnable> listeners = new ArrayList<>(
                    mOnContentViewInactiveListeners.values());
            for (Runnable r : listeners) {
                r.run();
            }
            mOnContentViewInactiveListeners.clear();
        }
    }

    private void updateVisibility() {
        setVisible(isShown());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(mEnableAnimationPredrawListener);
    }

    private void setVisible(final boolean isVisible) {
        if (isVisible) {
            // This call can happen multiple times, but removing only removes a single one.
            // We therefore need to remove the old one.
            getViewTreeObserver().removeOnPreDrawListener(mEnableAnimationPredrawListener);
            // We only animate if we are drawn at least once, otherwise the view might animate when
            // it's shown the first time
            getViewTreeObserver().addOnPreDrawListener(mEnableAnimationPredrawListener);
        } else {
            getViewTreeObserver().removeOnPreDrawListener(mEnableAnimationPredrawListener);
            mAnimate = false;
        }
    }

    private void focusExpandButtonIfNecessary() {
        if (mFocusOnVisibilityChange) {
            NotificationViewWrapper wrapper = getVisibleWrapper(mVisibleType);
            if (wrapper != null) {
                View expandButton = wrapper.getExpandButton();
                if (expandButton != null) {
                    expandButton.requestAccessibilityFocus();
                }
            }
            mFocusOnVisibilityChange = false;
        }
    }

    public void setContentHeight(int contentHeight) {
        mUnrestrictedContentHeight = Math.max(contentHeight, getMinHeight());
        int maxContentHeight = mContainingNotification.getIntrinsicHeight()
                - getExtraRemoteInputHeight(mExpandedRemoteInput)
                - getExtraRemoteInputHeight(mHeadsUpRemoteInput);
        mContentHeight = Math.min(mUnrestrictedContentHeight, maxContentHeight);
        selectLayout(mAnimate /* animate */, false /* force */);

        if (mContractedChild == null) {
            // Contracted child may be null if this is the public content view and we don't need to
            // show it.
            return;
        }

        int minHeightHint = getMinContentHeightHint();

        NotificationViewWrapper wrapper = getVisibleWrapper(mVisibleType);
        if (wrapper != null) {
            wrapper.setContentHeight(mUnrestrictedContentHeight, minHeightHint);
        }

        wrapper = getVisibleWrapper(mTransformationStartVisibleType);
        if (wrapper != null) {
            wrapper.setContentHeight(mUnrestrictedContentHeight, minHeightHint);
        }

        updateClipping();
        invalidateOutline();
    }

    /**
     * @return the minimum apparent height that the wrapper should allow for the purpose
     *         of aligning elements at the bottom edge. If this is larger than the content
     *         height, the notification is clipped instead of being further shrunk.
     */
    private int getMinContentHeightHint() {
        if (mIsChildInGroup && isVisibleOrTransitioning(VISIBLE_TYPE_SINGLELINE)) {
            return mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.notification_action_list_height);
        }

        // Transition between heads-up & expanded, or pinned.
        if (mHeadsUpChild != null && mExpandedChild != null) {
            boolean transitioningBetweenHunAndExpanded =
                    isTransitioningFromTo(VISIBLE_TYPE_HEADSUP, VISIBLE_TYPE_EXPANDED) ||
                    isTransitioningFromTo(VISIBLE_TYPE_EXPANDED, VISIBLE_TYPE_HEADSUP);
            boolean pinned = !isVisibleOrTransitioning(VISIBLE_TYPE_CONTRACTED)
                    && (mIsHeadsUp || mHeadsUpAnimatingAway)
                    && mContainingNotification.canShowHeadsUp();
            if (transitioningBetweenHunAndExpanded || pinned) {
                return Math.min(getViewHeight(VISIBLE_TYPE_HEADSUP),
                        getViewHeight(VISIBLE_TYPE_EXPANDED));
            }
        }

        // Size change of the expanded version
        if ((mVisibleType == VISIBLE_TYPE_EXPANDED) && mContentHeightAtAnimationStart != UNDEFINED
                && mExpandedChild != null) {
            return Math.min(mContentHeightAtAnimationStart, getViewHeight(VISIBLE_TYPE_EXPANDED));
        }

        int hint;
        if (mHeadsUpChild != null && isVisibleOrTransitioning(VISIBLE_TYPE_HEADSUP)) {
            hint = getViewHeight(VISIBLE_TYPE_HEADSUP);
        } else if (mExpandedChild != null) {
            hint = getViewHeight(VISIBLE_TYPE_EXPANDED);
        } else if (mContractedChild != null) {
            hint = getViewHeight(VISIBLE_TYPE_CONTRACTED)
                    + mContext.getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.notification_action_list_height);
        } else {
            hint = getMinHeight();
        }

        if (mExpandedChild != null && isVisibleOrTransitioning(VISIBLE_TYPE_EXPANDED)) {
            hint = Math.min(hint, getViewHeight(VISIBLE_TYPE_EXPANDED));
        }
        return hint;
    }

    private boolean isTransitioningFromTo(int from, int to) {
        return (mTransformationStartVisibleType == from || mAnimationStartVisibleType == from)
                && mVisibleType == to;
    }

    private boolean isVisibleOrTransitioning(int type) {
        return mVisibleType == type || mTransformationStartVisibleType == type
                || mAnimationStartVisibleType == type;
    }

    private void updateContentTransformation() {
        int visibleType = calculateVisibleType();
        if (getTransformableViewForVisibleType(mVisibleType) == null) {
            // Case where visible view was removed in middle of transformation. In this case, we
            // just update immediately to the appropriate view.
            mVisibleType = visibleType;
            updateViewVisibilities(visibleType);
            updateBackgroundColor(false);
            return;
        }
        if (visibleType != mVisibleType) {
            // A new transformation starts
            mTransformationStartVisibleType = mVisibleType;
            final TransformableView shownView = getTransformableViewForVisibleType(visibleType);
            final TransformableView hiddenView = getTransformableViewForVisibleType(
                    mTransformationStartVisibleType);
            shownView.transformFrom(hiddenView, 0.0f);
            getViewForVisibleType(visibleType).setVisibility(View.VISIBLE);
            hiddenView.transformTo(shownView, 0.0f);
            mVisibleType = visibleType;
            updateBackgroundColor(true /* animate */);
        }
        if (mForceSelectNextLayout) {
            forceUpdateVisibilities();
        }
        if (mTransformationStartVisibleType != VISIBLE_TYPE_NONE
                && mVisibleType != mTransformationStartVisibleType
                && getViewForVisibleType(mTransformationStartVisibleType) != null) {
            final TransformableView shownView = getTransformableViewForVisibleType(mVisibleType);
            final TransformableView hiddenView = getTransformableViewForVisibleType(
                    mTransformationStartVisibleType);
            float transformationAmount = calculateTransformationAmount();
            shownView.transformFrom(hiddenView, transformationAmount);
            hiddenView.transformTo(shownView, transformationAmount);
            updateBackgroundTransformation(transformationAmount);
        } else {
            updateViewVisibilities(visibleType);
            updateBackgroundColor(false);
        }
    }

    private void updateBackgroundTransformation(float transformationAmount) {
        int endColor = getBackgroundColor(mVisibleType);
        int startColor = getBackgroundColor(mTransformationStartVisibleType);
        if (endColor != startColor) {
            if (startColor == 0) {
                startColor = mContainingNotification.getBackgroundColorWithoutTint();
            }
            if (endColor == 0) {
                endColor = mContainingNotification.getBackgroundColorWithoutTint();
            }
            endColor = NotificationUtils.interpolateColors(startColor, endColor,
                    transformationAmount);
        }
        mContainingNotification.updateBackgroundAlpha(transformationAmount);
        mContainingNotification.setContentBackground(endColor, false, this);
    }

    private float calculateTransformationAmount() {
        int startHeight = getViewHeight(mTransformationStartVisibleType);
        int endHeight = getViewHeight(mVisibleType);
        int progress = Math.abs(mContentHeight - startHeight);
        int totalDistance = Math.abs(endHeight - startHeight);
        if (totalDistance == 0) {
            Log.wtf(TAG, "the total transformation distance is 0"
                    + "\n StartType: " + mTransformationStartVisibleType + " height: " + startHeight
                    + "\n VisibleType: " + mVisibleType + " height: " + endHeight
                    + "\n mContentHeight: " + mContentHeight);
            return 1.0f;
        }
        float amount = (float) progress / (float) totalDistance;
        return Math.min(1.0f, amount);
    }

    public int getContentHeight() {
        return mContentHeight;
    }

    public int getMaxHeight() {
        if (mExpandedChild != null) {
            return getViewHeight(VISIBLE_TYPE_EXPANDED)
                    + getExtraRemoteInputHeight(mExpandedRemoteInput);
        } else if (mIsHeadsUp && mHeadsUpChild != null && mContainingNotification.canShowHeadsUp()) {
            return getViewHeight(VISIBLE_TYPE_HEADSUP)
                    + getExtraRemoteInputHeight(mHeadsUpRemoteInput);
        } else if (mContractedChild != null) {
            return getViewHeight(VISIBLE_TYPE_CONTRACTED);
        }
        return mNotificationMaxHeight;
    }

    private int getViewHeight(int visibleType) {
        return getViewHeight(visibleType, false /* forceNoHeader */);
    }

    private int getViewHeight(int visibleType, boolean forceNoHeader) {
        View view = getViewForVisibleType(visibleType);
        int height = view.getHeight();
        NotificationViewWrapper viewWrapper = getWrapperForView(view);
        if (viewWrapper != null) {
            height += viewWrapper.getHeaderTranslation(forceNoHeader);
        }
        return height;
    }

    public int getMinHeight() {
        return getMinHeight(false /* likeGroupExpanded */);
    }

    public int getMinHeight(boolean likeGroupExpanded) {
        if (likeGroupExpanded || !mIsChildInGroup || isGroupExpanded()) {
            return mContractedChild != null
                    ? getViewHeight(VISIBLE_TYPE_CONTRACTED) : mMinContractedHeight;
        } else {
            return mSingleLineView.getHeight();
        }
    }

    private boolean isGroupExpanded() {
        return mContainingNotification.isGroupExpanded();
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        updateClipping();
    }


    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        updateClipping();
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        updateClipping();
    }

    private void updateClipping() {
        if (mClipToActualHeight) {
            int top = (int) (mClipTopAmount - getTranslationY());
            int bottom = (int) (mUnrestrictedContentHeight - mClipBottomAmount - getTranslationY());
            bottom = Math.max(top, bottom);
            mClipBounds.set(0, top, getWidth(), bottom);
            setClipBounds(mClipBounds);
        } else {
            setClipBounds(null);
        }
    }

    public void setClipToActualHeight(boolean clipToActualHeight) {
        mClipToActualHeight = clipToActualHeight;
        updateClipping();
    }

    private void selectLayout(boolean animate, boolean force) {
        if (mContractedChild == null) {
            return;
        }
        if (mUserExpanding) {
            updateContentTransformation();
        } else {
            int visibleType = calculateVisibleType();
            boolean changedType = visibleType != mVisibleType;
            if (changedType || force) {
                View visibleView = getViewForVisibleType(visibleType);
                if (visibleView != null) {
                    visibleView.setVisibility(VISIBLE);
                    transferRemoteInputFocus(visibleType);
                }

                if (animate && ((visibleType == VISIBLE_TYPE_EXPANDED && mExpandedChild != null)
                        || (visibleType == VISIBLE_TYPE_HEADSUP && mHeadsUpChild != null)
                        || (visibleType == VISIBLE_TYPE_SINGLELINE && mSingleLineView != null)
                        || visibleType == VISIBLE_TYPE_CONTRACTED)) {
                    animateToVisibleType(visibleType);
                } else {
                    updateViewVisibilities(visibleType);
                }
                mVisibleType = visibleType;
                if (changedType) {
                    focusExpandButtonIfNecessary();
                }
                NotificationViewWrapper visibleWrapper = getVisibleWrapper(visibleType);
                if (visibleWrapper != null) {
                    visibleWrapper.setContentHeight(mUnrestrictedContentHeight,
                            getMinContentHeightHint());
                }
                updateBackgroundColor(animate);
            }
        }
    }

    private void forceUpdateVisibilities() {
        forceUpdateVisibility(VISIBLE_TYPE_CONTRACTED, mContractedChild, mContractedWrapper);
        forceUpdateVisibility(VISIBLE_TYPE_EXPANDED, mExpandedChild, mExpandedWrapper);
        forceUpdateVisibility(VISIBLE_TYPE_HEADSUP, mHeadsUpChild, mHeadsUpWrapper);
        forceUpdateVisibility(VISIBLE_TYPE_SINGLELINE, mSingleLineView, mSingleLineView);
        fireExpandedVisibleListenerIfVisible();
        // forceUpdateVisibilities cancels outstanding animations without updating the
        // mAnimationStartVisibleType. Do so here instead.
        mAnimationStartVisibleType = VISIBLE_TYPE_NONE;
    }

    private void fireExpandedVisibleListenerIfVisible() {
        if (mExpandedVisibleListener != null && mExpandedChild != null && isShown()
                && mExpandedChild.getVisibility() == VISIBLE) {
            Runnable listener = mExpandedVisibleListener;
            mExpandedVisibleListener = null;
            listener.run();
        }
    }

    private void forceUpdateVisibility(int type, View view, TransformableView wrapper) {
        if (view == null) {
            return;
        }
        boolean visible = mVisibleType == type
                || mTransformationStartVisibleType == type;
        if (!visible) {
            view.setVisibility(INVISIBLE);
        } else {
            wrapper.setVisible(true);
        }
    }

    public void updateBackgroundColor(boolean animate) {
        int customBackgroundColor = getBackgroundColor(mVisibleType);
        mContainingNotification.resetBackgroundAlpha();
        mContainingNotification.setContentBackground(customBackgroundColor, animate, this);
    }

    public void setBackgroundTintColor(int color) {
        if (mExpandedSmartReplyView != null) {
            mExpandedSmartReplyView.setBackgroundTintColor(color);
        }
        if (mHeadsUpSmartReplyView != null) {
            mHeadsUpSmartReplyView.setBackgroundTintColor(color);
        }
    }

    public int getVisibleType() {
        return mVisibleType;
    }

    public int getBackgroundColorForExpansionState() {
        // When expanding or user locked we want the new type, when collapsing we want
        // the original type
        final int visibleType = (
                isGroupExpanded() || mContainingNotification.isUserLocked())
                    ? calculateVisibleType()
                    : getVisibleType();
        return getBackgroundColor(visibleType);
    }

    public int getBackgroundColor(int visibleType) {
        NotificationViewWrapper currentVisibleWrapper = getVisibleWrapper(visibleType);
        int customBackgroundColor = 0;
        if (currentVisibleWrapper != null) {
            customBackgroundColor = currentVisibleWrapper.getCustomBackgroundColor();
        }
        return customBackgroundColor;
    }

    private void updateViewVisibilities(int visibleType) {
        updateViewVisibility(visibleType, VISIBLE_TYPE_CONTRACTED,
                mContractedChild, mContractedWrapper);
        updateViewVisibility(visibleType, VISIBLE_TYPE_EXPANDED,
                mExpandedChild, mExpandedWrapper);
        updateViewVisibility(visibleType, VISIBLE_TYPE_HEADSUP,
                mHeadsUpChild, mHeadsUpWrapper);
        updateViewVisibility(visibleType, VISIBLE_TYPE_SINGLELINE,
                mSingleLineView, mSingleLineView);
        fireExpandedVisibleListenerIfVisible();
        // updateViewVisibilities cancels outstanding animations without updating the
        // mAnimationStartVisibleType. Do so here instead.
        mAnimationStartVisibleType = VISIBLE_TYPE_NONE;
    }

    private void updateViewVisibility(int visibleType, int type, View view,
            TransformableView wrapper) {
        if (view != null) {
            wrapper.setVisible(visibleType == type);
        }
    }

    private void animateToVisibleType(int visibleType) {
        final TransformableView shownView = getTransformableViewForVisibleType(visibleType);
        final TransformableView hiddenView = getTransformableViewForVisibleType(mVisibleType);
        if (shownView == hiddenView || hiddenView == null) {
            shownView.setVisible(true);
            return;
        }
        mAnimationStartVisibleType = mVisibleType;
        shownView.transformFrom(hiddenView);
        getViewForVisibleType(visibleType).setVisibility(View.VISIBLE);
        hiddenView.transformTo(shownView, new Runnable() {
            @Override
            public void run() {
                if (hiddenView != getTransformableViewForVisibleType(mVisibleType)) {
                    hiddenView.setVisible(false);
                }
                mAnimationStartVisibleType = VISIBLE_TYPE_NONE;
            }
        });
        fireExpandedVisibleListenerIfVisible();
    }

    private void transferRemoteInputFocus(int visibleType) {
        if (visibleType == VISIBLE_TYPE_HEADSUP
                && mHeadsUpRemoteInput != null
                && (mExpandedRemoteInput != null && mExpandedRemoteInput.isActive())) {
            mHeadsUpRemoteInput.stealFocusFrom(mExpandedRemoteInput);
        }
        if (visibleType == VISIBLE_TYPE_EXPANDED
                && mExpandedRemoteInput != null
                && (mHeadsUpRemoteInput != null && mHeadsUpRemoteInput.isActive())) {
            mExpandedRemoteInput.stealFocusFrom(mHeadsUpRemoteInput);
        }
    }

    /**
     * @param visibleType one of the static enum types in this view
     * @return the corresponding transformable view according to the given visible type
     */
    private TransformableView getTransformableViewForVisibleType(int visibleType) {
        switch (visibleType) {
            case VISIBLE_TYPE_EXPANDED:
                return mExpandedWrapper;
            case VISIBLE_TYPE_HEADSUP:
                return mHeadsUpWrapper;
            case VISIBLE_TYPE_SINGLELINE:
                return mSingleLineView;
            default:
                return mContractedWrapper;
        }
    }

    /**
     * @param visibleType one of the static enum types in this view
     * @return the corresponding view according to the given visible type
     */
    private View getViewForVisibleType(int visibleType) {
        switch (visibleType) {
            case VISIBLE_TYPE_EXPANDED:
                return mExpandedChild;
            case VISIBLE_TYPE_HEADSUP:
                return mHeadsUpChild;
            case VISIBLE_TYPE_SINGLELINE:
                return mSingleLineView;
            default:
                return mContractedChild;
        }
    }

    public @NonNull View[] getAllViews() {
        return new View[] {
                mContractedChild,
                mHeadsUpChild,
                mExpandedChild,
                mSingleLineView };
    }

    public NotificationViewWrapper getVisibleWrapper() {
        return getVisibleWrapper(mVisibleType);
    }

    public NotificationViewWrapper getVisibleWrapper(int visibleType) {
        switch (visibleType) {
            case VISIBLE_TYPE_EXPANDED:
                return mExpandedWrapper;
            case VISIBLE_TYPE_HEADSUP:
                return mHeadsUpWrapper;
            case VISIBLE_TYPE_CONTRACTED:
                return mContractedWrapper;
            default:
                return null;
        }
    }

    /**
     * @return one of the static enum types in this view, calculated form the current state
     */
    public int calculateVisibleType() {
        if (mUserExpanding) {
            int height = !mIsChildInGroup || isGroupExpanded()
                    || mContainingNotification.isExpanded(true /* allowOnKeyguard */)
                    ? mContainingNotification.getMaxContentHeight()
                    : mContainingNotification.getShowingLayout().getMinHeight();
            if (height == 0) {
                height = mContentHeight;
            }
            int expandedVisualType = getVisualTypeForHeight(height);
            int collapsedVisualType = mIsChildInGroup && !isGroupExpanded()
                    ? VISIBLE_TYPE_SINGLELINE
                    : getVisualTypeForHeight(mContainingNotification.getCollapsedHeight());
            return mTransformationStartVisibleType == collapsedVisualType
                    ? expandedVisualType
                    : collapsedVisualType;
        }
        int intrinsicHeight = mContainingNotification.getIntrinsicHeight();
        int viewHeight = mContentHeight;
        if (intrinsicHeight != 0) {
            // the intrinsicHeight might be 0 because it was just reset.
            viewHeight = Math.min(mContentHeight, intrinsicHeight);
        }
        return getVisualTypeForHeight(viewHeight);
    }

    private int getVisualTypeForHeight(float viewHeight) {
        boolean noExpandedChild = mExpandedChild == null;
        if (!noExpandedChild && viewHeight == getViewHeight(VISIBLE_TYPE_EXPANDED)) {
            return VISIBLE_TYPE_EXPANDED;
        }
        if (!mUserExpanding && mIsChildInGroup && !isGroupExpanded()) {
            return VISIBLE_TYPE_SINGLELINE;
        }

        if ((mIsHeadsUp || mHeadsUpAnimatingAway) && mHeadsUpChild != null
                && mContainingNotification.canShowHeadsUp()) {
            if (viewHeight <= getViewHeight(VISIBLE_TYPE_HEADSUP) || noExpandedChild) {
                return VISIBLE_TYPE_HEADSUP;
            } else {
                return VISIBLE_TYPE_EXPANDED;
            }
        } else {
            if (noExpandedChild || (mContractedChild != null
                    && viewHeight <= getViewHeight(VISIBLE_TYPE_CONTRACTED)
                    && (!mIsChildInGroup || isGroupExpanded()
                            || !mContainingNotification.isExpanded(true /* allowOnKeyguard */)))) {
                return VISIBLE_TYPE_CONTRACTED;
            } else if (!noExpandedChild) {
                return VISIBLE_TYPE_EXPANDED;
            } else {
                return VISIBLE_TYPE_NONE;
            }
        }
    }

    public boolean isContentExpandable() {
        return mIsContentExpandable;
    }

    public void setHeadsUp(boolean headsUp) {
        mIsHeadsUp = headsUp;
        selectLayout(false /* animate */, true /* force */);
        updateExpandButtons(mExpandable);
    }

    @Override
    public boolean hasOverlappingRendering() {

        // This is not really true, but good enough when fading from the contracted to the expanded
        // layout, and saves us some layers.
        return false;
    }

    public void setLegacy(boolean legacy) {
        mLegacy = legacy;
        updateLegacy();
    }

    private void updateLegacy() {
        if (mContractedChild != null) {
            mContractedWrapper.setLegacy(mLegacy);
        }
        if (mExpandedChild != null) {
            mExpandedWrapper.setLegacy(mLegacy);
        }
        if (mHeadsUpChild != null) {
            mHeadsUpWrapper.setLegacy(mLegacy);
        }
    }

    public void setIsChildInGroup(boolean isChildInGroup) {
        mIsChildInGroup = isChildInGroup;
        if (mContractedChild != null) {
            mContractedWrapper.setIsChildInGroup(mIsChildInGroup);
        }
        if (mExpandedChild != null) {
            mExpandedWrapper.setIsChildInGroup(mIsChildInGroup);
        }
        if (mHeadsUpChild != null) {
            mHeadsUpWrapper.setIsChildInGroup(mIsChildInGroup);
        }
        updateAllSingleLineViews();
    }

    public void onNotificationUpdated(NotificationEntry entry) {
        mNotificationEntry = entry;
        mBeforeN = entry.targetSdk < Build.VERSION_CODES.N;
        updateAllSingleLineViews();
        ExpandableNotificationRow row = entry.getRow();
        if (mContractedChild != null) {
            mContractedWrapper.onContentUpdated(row);
        }
        if (mExpandedChild != null) {
            mExpandedWrapper.onContentUpdated(row);
        }
        if (mHeadsUpChild != null) {
            mHeadsUpWrapper.onContentUpdated(row);
        }
        applyRemoteInputAndSmartReply(entry);
        updateLegacy();
        mForceSelectNextLayout = true;
        mPreviousExpandedRemoteInputIntent = null;
        mPreviousHeadsUpRemoteInputIntent = null;
        applySystemActions(mExpandedChild, entry);
        applySystemActions(mHeadsUpChild, entry);
    }

    private void updateAllSingleLineViews() {
        updateSingleLineView();
    }

    private void updateSingleLineView() {
        if (mIsChildInGroup) {
            boolean isNewView = mSingleLineView == null;
            mSingleLineView = mHybridGroupManager.bindFromNotification(
                    mSingleLineView, mContractedChild, mNotificationEntry.getSbn(), this);
            if (isNewView) {
                updateViewVisibility(mVisibleType, VISIBLE_TYPE_SINGLELINE,
                        mSingleLineView, mSingleLineView);
            }
        } else if (mSingleLineView != null) {
            removeView(mSingleLineView);
            mSingleLineView = null;
        }
    }

    /**
     * Returns whether the {@link Notification} represented by entry has a free-form remote input.
     * Such an input can be used e.g. to implement smart reply buttons - by passing the replies
     * through the remote input.
     */
    public static boolean hasFreeformRemoteInput(NotificationEntry entry) {
        Notification notification = entry.getSbn().getNotification();
        return null != notification.findRemoteInputActionPair(true /* freeform */);
    }

    private void applyRemoteInputAndSmartReply(final NotificationEntry entry) {
        if (mRemoteInputController == null) {
            return;
        }

        applyRemoteInput(entry, hasFreeformRemoteInput(entry));

        if (mCurrentSmartReplyState == null) {
            if (DEBUG) {
                Log.d(TAG, "InflatedSmartReplies are null, don't add smart replies.");
            }
            return;
        }
        if (DEBUG) {
            Log.d(TAG, String.format("Adding suggestions for %s, %d actions, and %d replies.",
                    entry.getSbn().getKey(),
                    mCurrentSmartReplyState.getSmartActionsList().size(),
                    mCurrentSmartReplyState.getSmartRepliesList().size()));
        }
        applySmartReplyView(mCurrentSmartReplyState, entry);
    }

    private void applyRemoteInput(NotificationEntry entry, boolean hasFreeformRemoteInput) {
        View bigContentView = mExpandedChild;
        if (bigContentView != null) {
            mExpandedRemoteInput = applyRemoteInput(bigContentView, entry, hasFreeformRemoteInput,
                    mPreviousExpandedRemoteInputIntent, mCachedExpandedRemoteInput,
                    mExpandedWrapper);
        } else {
            mExpandedRemoteInput = null;
        }
        if (mCachedExpandedRemoteInput != null
                && mCachedExpandedRemoteInput != mExpandedRemoteInput) {
            // We had a cached remote input but didn't reuse it. Clean up required.
            mCachedExpandedRemoteInput.dispatchFinishTemporaryDetach();
        }
        mCachedExpandedRemoteInput = null;

        View headsUpContentView = mHeadsUpChild;
        if (headsUpContentView != null) {
            mHeadsUpRemoteInput = applyRemoteInput(
                    headsUpContentView, entry, hasFreeformRemoteInput,
                    mPreviousHeadsUpRemoteInputIntent, mCachedHeadsUpRemoteInput, mHeadsUpWrapper);
        } else {
            mHeadsUpRemoteInput = null;
        }
        if (mCachedHeadsUpRemoteInput != null
                && mCachedHeadsUpRemoteInput != mHeadsUpRemoteInput) {
            // We had a cached remote input but didn't reuse it. Clean up required.
            mCachedHeadsUpRemoteInput.dispatchFinishTemporaryDetach();
        }
        mCachedHeadsUpRemoteInput = null;
    }

    private RemoteInputView applyRemoteInput(View view, NotificationEntry entry,
            boolean hasRemoteInput, PendingIntent existingPendingIntent,
            RemoteInputView cachedView, NotificationViewWrapper wrapper) {
        View actionContainerCandidate = view.findViewById(
                com.android.internal.R.id.actions_container);
        if (actionContainerCandidate instanceof FrameLayout) {
            RemoteInputView existing = (RemoteInputView)
                    view.findViewWithTag(RemoteInputView.VIEW_TAG);

            if (existing != null) {
                existing.onNotificationUpdateOrReset();
            }

            if (existing == null && hasRemoteInput) {
                ViewGroup actionContainer = (FrameLayout) actionContainerCandidate;
                if (cachedView == null) {
                    RemoteInputView riv = RemoteInputView.inflate(
                            mContext, actionContainer, entry, mRemoteInputController);

                    riv.setVisibility(View.INVISIBLE);
                    actionContainer.addView(riv, new LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT)
                    );
                    existing = riv;
                } else {
                    actionContainer.addView(cachedView);
                    cachedView.dispatchFinishTemporaryDetach();
                    cachedView.requestFocus();
                    existing = cachedView;
                }
            }
            if (hasRemoteInput) {
                int color = entry.getSbn().getNotification().color;
                if (color == Notification.COLOR_DEFAULT) {
                    color = mContext.getColor(R.color.default_remote_input_background);
                }
                existing.setBackgroundColor(ContrastColorUtil.ensureTextBackgroundColor(color,
                        mContext.getColor(R.color.remote_input_text_enabled),
                        mContext.getColor(R.color.remote_input_hint)));

                existing.setWrapper(wrapper);
                existing.setOnVisibilityChangedListener(this::setRemoteInputVisible);

                if (existingPendingIntent != null || existing.isActive()) {
                    // The current action could be gone, or the pending intent no longer valid.
                    // If we find a matching action in the new notification, focus, otherwise close.
                    Notification.Action[] actions = entry.getSbn().getNotification().actions;
                    if (existingPendingIntent != null) {
                        existing.setPendingIntent(existingPendingIntent);
                    }
                    if (existing.updatePendingIntentFromActions(actions)) {
                        if (!existing.isActive()) {
                            existing.focus();
                        }
                    } else {
                        if (existing.isActive()) {
                            existing.close();
                        }
                    }
                }
            }
            return existing;
        }
        return null;
    }

    /**
     * Call to update state of the bubble button (i.e. does it show bubble or unbubble or no
     * icon at all).
     *
     * @param entry the new entry to use.
     */
    public void updateBubbleButton(NotificationEntry entry) {
        applyBubbleAction(mExpandedChild, entry);
    }

    private boolean isBubblesEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.NOTIFICATION_BUBBLES, 0) == 1;
    }

    /**
     * Setup icon buttons provided by System UI.
     */
    private void applySystemActions(View layout, NotificationEntry entry) {
        applySnoozeAction(layout);
        applyBubbleAction(layout, entry);
    }

    private void applyBubbleAction(View layout, NotificationEntry entry) {
        if (layout == null || mContainingNotification == null || mPeopleIdentifier == null) {
            return;
        }
        ImageView bubbleButton = layout.findViewById(com.android.internal.R.id.bubble_button);
        View actionContainer = layout.findViewById(com.android.internal.R.id.actions_container);
        LinearLayout actionContainerLayout =
                layout.findViewById(com.android.internal.R.id.actions_container_layout);
        if (bubbleButton == null || actionContainer == null || actionContainerLayout == null) {
            return;
        }
        boolean isPersonWithShortcut =
                mPeopleIdentifier.getPeopleNotificationType(entry)
                        >= PeopleNotificationIdentifier.TYPE_FULL_PERSON;
        boolean showButton = isBubblesEnabled()
                && isPersonWithShortcut
                && entry.getBubbleMetadata() != null;
        if (showButton) {
            Drawable d = mContext.getResources().getDrawable(entry.isBubble()
                    ? R.drawable.bubble_ic_stop_bubble
                    : R.drawable.bubble_ic_create_bubble);
            mContainingNotification.updateNotificationColor();
            final int tint = mContainingNotification.getNotificationColor();
            d.setTint(tint);

            String contentDescription = mContext.getResources().getString(entry.isBubble()
                    ? R.string.notification_conversation_unbubble
                    : R.string.notification_conversation_bubble);

            bubbleButton.setContentDescription(contentDescription);
            bubbleButton.setImageDrawable(d);
            bubbleButton.setOnClickListener(mContainingNotification.getBubbleClickListener());
            bubbleButton.setVisibility(VISIBLE);
            actionContainer.setVisibility(VISIBLE);
        } else  {
            bubbleButton.setVisibility(GONE);
        }
    }

    private void applySnoozeAction(View layout) {
        if (layout == null || mContainingNotification == null) {
            return;
        }
        ImageView snoozeButton = layout.findViewById(com.android.internal.R.id.snooze_button);
        View actionContainer = layout.findViewById(com.android.internal.R.id.actions_container);
        if (snoozeButton == null || actionContainer == null) {
            return;
        }
        final boolean showSnooze = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.SHOW_NOTIFICATION_SNOOZE, 0) == 1;
        // Notification.Builder can 'disable' the snooze button to prevent it from being shown here
        boolean snoozeDisabled = !snoozeButton.isEnabled();
        if (!showSnooze || snoozeDisabled) {
            snoozeButton.setVisibility(GONE);
            return;
        }

        Drawable snoozeDrawable = mContext.getDrawable(R.drawable.ic_snooze);
        mContainingNotification.updateNotificationColor();
        snoozeDrawable.setTint(mContainingNotification.getNotificationColor());
        snoozeButton.setImageDrawable(snoozeDrawable);

        final NotificationSnooze snoozeGuts = (NotificationSnooze) LayoutInflater.from(mContext)
                .inflate(R.layout.notification_snooze, null, false);
        final String snoozeDescription = mContext.getString(
                R.string.notification_menu_snooze_description);
        final NotificationMenuRowPlugin.MenuItem snoozeMenuItem =
                new NotificationMenuRow.NotificationMenuItem(
                        mContext, snoozeDescription, snoozeGuts, R.drawable.ic_snooze);
        snoozeButton.setContentDescription(
                mContext.getResources().getString(R.string.notification_menu_snooze_description));
        snoozeButton.setOnClickListener(
                mContainingNotification.getSnoozeClickListener(snoozeMenuItem));
        snoozeButton.setVisibility(VISIBLE);
        actionContainer.setVisibility(VISIBLE);
    }

    private void applySmartReplyView(
            InflatedSmartReplyState state,
            NotificationEntry entry) {
        if (mContractedChild != null) {
            applyExternalSmartReplyState(mContractedChild, state);
        }
        if (mExpandedChild != null) {
            applyExternalSmartReplyState(mExpandedChild, state);
            mExpandedSmartReplyView = applySmartReplyView(mExpandedChild, state,
                    entry, mExpandedInflatedSmartReplies);
            if (mExpandedSmartReplyView != null) {
                SmartReplyView.SmartReplies smartReplies = state.getSmartReplies();
                SmartReplyView.SmartActions smartActions = state.getSmartActions();
                if (smartReplies != null || smartActions != null) {
                    int numSmartReplies = smartReplies == null ? 0 : smartReplies.choices.size();
                    int numSmartActions = smartActions == null ? 0 : smartActions.actions.size();
                    boolean fromAssistant = smartReplies == null
                            ? smartActions.fromAssistant
                            : smartReplies.fromAssistant;
                    boolean editBeforeSending = smartReplies != null
                            && mSmartReplyConstants.getEffectiveEditChoicesBeforeSending(
                                    smartReplies.remoteInput.getEditChoicesBeforeSending());

                    mSmartReplyController.smartSuggestionsAdded(entry, numSmartReplies,
                            numSmartActions, fromAssistant, editBeforeSending);
                }
            }
        }
        if (mHeadsUpChild != null) {
            applyExternalSmartReplyState(mHeadsUpChild, state);
            if (mSmartReplyConstants.getShowInHeadsUp()) {
                mHeadsUpSmartReplyView = applySmartReplyView(mHeadsUpChild, state,
                        entry, mHeadsUpInflatedSmartReplies);
            }
        }
    }

    private void applyExternalSmartReplyState(View view, InflatedSmartReplyState state) {
        boolean hasPhishingAlert = state != null && state.getHasPhishingAction();
        View phishingAlertIcon = view.findViewById(com.android.internal.R.id.phishing_alert);
        if (phishingAlertIcon != null) {
            if (DEBUG) {
                Log.d(TAG, "Setting 'phishing_alert' view visible=" + hasPhishingAlert + ".");
            }
            phishingAlertIcon.setVisibility(hasPhishingAlert ? View.VISIBLE : View.GONE);
        }
        List<Integer> suppressedActionIndices = state != null
                ? state.getSuppressedActionIndices()
                : Collections.emptyList();
        ViewGroup actionsList = view.findViewById(com.android.internal.R.id.actions);
        if (actionsList != null) {
            if (DEBUG && !suppressedActionIndices.isEmpty()) {
                Log.d(TAG, "Suppressing actions with indices: " + suppressedActionIndices);
            }
            for (int i = 0; i < actionsList.getChildCount(); i++) {
                View actionBtn = actionsList.getChildAt(i);
                Object actionIndex =
                        actionBtn.getTag(com.android.internal.R.id.notification_action_index_tag);
                boolean suppressAction = actionIndex instanceof Integer
                        && suppressedActionIndices.contains(actionIndex);
                actionBtn.setVisibility(suppressAction ? View.GONE : View.VISIBLE);
            }
        }
    }

    @Nullable
    private SmartReplyView applySmartReplyView(View view,
            InflatedSmartReplyState smartReplyState,
            NotificationEntry entry, InflatedSmartReplyViewHolder inflatedSmartReplyViewHolder) {
        View smartReplyContainerCandidate = view.findViewById(
                com.android.internal.R.id.smart_reply_container);
        if (!(smartReplyContainerCandidate instanceof LinearLayout)) {
            return null;
        }

        LinearLayout smartReplyContainer = (LinearLayout) smartReplyContainerCandidate;
        if (!SmartReplyStateInflaterKt.shouldShowSmartReplyView(entry, smartReplyState)) {
            smartReplyContainer.setVisibility(View.GONE);
            return null;
        }

        SmartReplyView smartReplyView = null;
        if (smartReplyContainer.getChildCount() == 1
                && smartReplyContainer.getChildAt(0) instanceof SmartReplyView) {
            // If we already have a SmartReplyView - replace it with the newly inflated one. The
            // newly inflated one is connected to the new inflated smart reply/action buttons.
            smartReplyContainer.removeAllViews();
        }
        if (smartReplyContainer.getChildCount() == 0
                && inflatedSmartReplyViewHolder != null
                && inflatedSmartReplyViewHolder.getSmartReplyView() != null) {
            smartReplyView = inflatedSmartReplyViewHolder.getSmartReplyView();
            smartReplyContainer.addView(smartReplyView);
        }
        if (smartReplyView != null) {
            smartReplyView.resetSmartSuggestions(smartReplyContainer);
            smartReplyView.addPreInflatedButtons(
                    inflatedSmartReplyViewHolder.getSmartSuggestionButtons());
            // Ensure the colors of the smart suggestion buttons are up-to-date.
            smartReplyView.setBackgroundTintColor(entry.getRow().getCurrentBackgroundTint());
            smartReplyContainer.setVisibility(View.VISIBLE);
        }
        return smartReplyView;
    }

    /**
     * Set pre-inflated views necessary to display smart replies and actions in the expanded
     * notification state.
     *
     * @param inflatedSmartReplies the pre-inflated state to add to this view. If null the existing
     * {@link SmartReplyView} related to the expanded notification state is cleared.
     */
    public void setExpandedInflatedSmartReplies(
            @Nullable InflatedSmartReplyViewHolder inflatedSmartReplies) {
        mExpandedInflatedSmartReplies = inflatedSmartReplies;
        if (inflatedSmartReplies == null) {
            mExpandedSmartReplyView = null;
        }
    }

    /**
     * Set pre-inflated views necessary to display smart replies and actions in the heads-up
     * notification state.
     *
     * @param inflatedSmartReplies the pre-inflated state to add to this view. If null the existing
     * {@link SmartReplyView} related to the heads-up notification state is cleared.
     */
    public void setHeadsUpInflatedSmartReplies(
            @Nullable InflatedSmartReplyViewHolder inflatedSmartReplies) {
        mHeadsUpInflatedSmartReplies = inflatedSmartReplies;
        if (inflatedSmartReplies == null) {
            mHeadsUpSmartReplyView = null;
        }
    }

    /**
     * Set pre-inflated replies and actions for the notification.
     * This can be relevant to any state of the notification, even contracted, because smart actions
     * may cause a phishing alert to be made visible.
     * @param smartReplyState the pre-inflated list of replies and actions
     */
    public void setInflatedSmartReplyState(
            @NonNull InflatedSmartReplyState smartReplyState) {
        mCurrentSmartReplyState = smartReplyState;
    }

    /**
     * Returns the smart replies and actions currently shown in the notification.
     */
    @Nullable public InflatedSmartReplyState getCurrentSmartReplyState() {
        return mCurrentSmartReplyState;
    }

    public void closeRemoteInput() {
        if (mHeadsUpRemoteInput != null) {
            mHeadsUpRemoteInput.close();
        }
        if (mExpandedRemoteInput != null) {
            mExpandedRemoteInput.close();
        }
    }

    public void setGroupMembershipManager(GroupMembershipManager groupMembershipManager) {
        mGroupMembershipManager = groupMembershipManager;
    }

    public void setRemoteInputController(RemoteInputController r) {
        mRemoteInputController = r;
    }

    public void setExpandClickListener(OnClickListener expandClickListener) {
        mExpandClickListener = expandClickListener;
    }

    public void updateExpandButtons(boolean expandable) {
        mExpandable = expandable;
        // if the expanded child has the same height as the collapsed one we hide it.
        if (mExpandedChild != null && mExpandedChild.getHeight() != 0) {
            if ((!mIsHeadsUp && !mHeadsUpAnimatingAway)
                    || mHeadsUpChild == null || !mContainingNotification.canShowHeadsUp()) {
                if (mContractedChild == null
                        || mExpandedChild.getHeight() <= mContractedChild.getHeight()) {
                    expandable = false;
                }
            } else if (mExpandedChild.getHeight() <= mHeadsUpChild.getHeight()) {
                expandable = false;
            }
        }
        if (mExpandedChild != null) {
            mExpandedWrapper.updateExpandability(expandable, mExpandClickListener);
        }
        if (mContractedChild != null) {
            mContractedWrapper.updateExpandability(expandable, mExpandClickListener);
        }
        if (mHeadsUpChild != null) {
            mHeadsUpWrapper.updateExpandability(expandable,  mExpandClickListener);
        }
        mIsContentExpandable = expandable;
    }

    /**
     * @return a view wrapper for one of the inflated states of the notification.
     */
    public NotificationViewWrapper getNotificationViewWrapper() {
        if (mContractedChild != null && mContractedWrapper != null) {
            return mContractedWrapper;
        }
        if (mExpandedChild != null && mExpandedWrapper != null) {
            return mExpandedWrapper;
        }
        if (mHeadsUpChild != null && mHeadsUpWrapper != null) {
            return mHeadsUpWrapper;
        }
        return null;
    }

    public void showFeedbackIcon(boolean show, Pair<Integer, Integer> resIds) {
        if (mContractedChild != null) {
            mContractedWrapper.showFeedbackIcon(show, resIds);
        }
        if (mExpandedChild != null) {
            mExpandedWrapper.showFeedbackIcon(show, resIds);
        }
        if (mHeadsUpChild != null) {
            mHeadsUpWrapper.showFeedbackIcon(show, resIds);
        }
    }

    /** Sets whether the notification being displayed audibly alerted the user. */
    public void setRecentlyAudiblyAlerted(boolean audiblyAlerted) {
        if (mContractedChild != null) {
            mContractedWrapper.setRecentlyAudiblyAlerted(audiblyAlerted);
        }
        if (mExpandedChild != null) {
            mExpandedWrapper.setRecentlyAudiblyAlerted(audiblyAlerted);
        }
        if (mHeadsUpChild != null) {
            mHeadsUpWrapper.setRecentlyAudiblyAlerted(audiblyAlerted);
        }
    }

    public void setContainingNotification(ExpandableNotificationRow containingNotification) {
        mContainingNotification = containingNotification;
    }

    public void setPeopleNotificationIdentifier(PeopleNotificationIdentifier peopleIdentifier) {
        mPeopleIdentifier = peopleIdentifier;
    }

    public void requestSelectLayout(boolean needsAnimation) {
        selectLayout(needsAnimation, false);
    }

    public void reInflateViews() {
        if (mIsChildInGroup && mSingleLineView != null) {
            removeView(mSingleLineView);
            mSingleLineView = null;
            updateAllSingleLineViews();
        }
    }

    public void setUserExpanding(boolean userExpanding) {
        mUserExpanding = userExpanding;
        if (userExpanding) {
            mTransformationStartVisibleType = mVisibleType;
        } else {
            mTransformationStartVisibleType = VISIBLE_TYPE_NONE;
            mVisibleType = calculateVisibleType();
            updateViewVisibilities(mVisibleType);
            updateBackgroundColor(false);
        }
    }

    /**
     * Set by how much the single line view should be indented. Used when a overflow indicator is
     * present and only during measuring
     */
    public void setSingleLineWidthIndention(int singleLineWidthIndention) {
        if (singleLineWidthIndention != mSingleLineWidthIndention) {
            mSingleLineWidthIndention = singleLineWidthIndention;
            mContainingNotification.forceLayout();
            forceLayout();
        }
    }

    public HybridNotificationView getSingleLineView() {
        return mSingleLineView;
    }

    public void setRemoved() {
        if (mExpandedRemoteInput != null) {
            mExpandedRemoteInput.setRemoved();
        }
        if (mHeadsUpRemoteInput != null) {
            mHeadsUpRemoteInput.setRemoved();
        }
        if (mExpandedWrapper != null) {
            mExpandedWrapper.setRemoved();
        }
        if (mContractedWrapper != null) {
            mContractedWrapper.setRemoved();
        }
        if (mHeadsUpWrapper != null) {
            mHeadsUpWrapper.setRemoved();
        }
    }

    public void setContentHeightAnimating(boolean animating) {
        //TODO: It's odd that this does nothing when animating is true
        if (!animating) {
            mContentHeightAtAnimationStart = UNDEFINED;
        }
    }

    @VisibleForTesting
    boolean isAnimatingVisibleType() {
        return mAnimationStartVisibleType != VISIBLE_TYPE_NONE;
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        selectLayout(false /* animate */, true /* force */);
    }

    public void setFocusOnVisibilityChange() {
        mFocusOnVisibilityChange = true;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) {
            fireExpandedVisibleListenerIfVisible();
        }
    }

    /**
     * Sets a one-shot listener for when the expanded view becomes visible.
     *
     * This will fire the listener immediately if the expanded view is already visible.
     */
    public void setOnExpandedVisibleListener(Runnable r) {
        mExpandedVisibleListener = r;
        fireExpandedVisibleListenerIfVisible();
    }

    /**
     * Set a one-shot listener to run when a given content view becomes inactive.
     *
     * @param visibleType visible type corresponding to the content view to listen
     * @param listener runnable to run once when the content view becomes inactive
     */
    void performWhenContentInactive(int visibleType, Runnable listener) {
        View view = getViewForVisibleType(visibleType);
        // View is already inactive
        if (view == null || isContentViewInactive(visibleType)) {
            listener.run();
            return;
        }
        mOnContentViewInactiveListeners.put(view, listener);
    }

    /**
     * Remove content inactive listeners for a given content view . See
     * {@link #performWhenContentInactive}.
     *
     * @param visibleType visible type corresponding to the content type
     */
    void removeContentInactiveRunnable(int visibleType) {
        View view = getViewForVisibleType(visibleType);
        // View is already inactive
        if (view == null) {
            return;
        }

        mOnContentViewInactiveListeners.remove(view);
    }

    /**
     * Whether or not the content view is inactive.  This means it should not be visible
     * or the showing content as removing it would cause visual jank.
     *
     * @param visibleType visible type corresponding to the content view to be removed
     * @return true if the content view is inactive, false otherwise
     */
    public boolean isContentViewInactive(int visibleType) {
        View view = getViewForVisibleType(visibleType);
        return isContentViewInactive(view);
    }

    /**
     * Whether or not the content view is inactive.
     *
     * @param view view to see if its inactive
     * @return true if the view is inactive, false o/w
     */
    private boolean isContentViewInactive(View view) {
        if (view == null) {
            return true;
        }
        return !isShown()
                || (view.getVisibility() != VISIBLE && getViewForVisibleType(mVisibleType) != view);
    }

    @Override
    protected void onChildVisibilityChanged(View child, int oldVisibility, int newVisibility) {
        super.onChildVisibilityChanged(child, oldVisibility, newVisibility);
        if (isContentViewInactive(child)) {
            Runnable listener = mOnContentViewInactiveListeners.remove(child);
            if (listener != null) {
                listener.run();
            }
        }
    }

    public void setIsLowPriority(boolean isLowPriority) {
        mIsLowPriority = isLowPriority;
    }

    public boolean isDimmable() {
        return mContractedWrapper != null && mContractedWrapper.isDimmable();
    }

    /**
     * Should a single click be disallowed on this view when on the keyguard?
     */
    public boolean disallowSingleClick(float x, float y) {
        NotificationViewWrapper visibleWrapper = getVisibleWrapper(getVisibleType());
        if (visibleWrapper != null) {
            return visibleWrapper.disallowSingleClick(x, y);
        }
        return false;
    }

    public boolean shouldClipToRounding(boolean topRounded, boolean bottomRounded) {
        boolean needsPaddings = shouldClipToRounding(getVisibleType(), topRounded, bottomRounded);
        if (mUserExpanding) {
             needsPaddings |= shouldClipToRounding(mTransformationStartVisibleType, topRounded,
                     bottomRounded);
        }
        return needsPaddings;
    }

    private boolean shouldClipToRounding(int visibleType, boolean topRounded,
            boolean bottomRounded) {
        NotificationViewWrapper visibleWrapper = getVisibleWrapper(visibleType);
        if (visibleWrapper == null) {
            return false;
        }
        return visibleWrapper.shouldClipToRounding(topRounded, bottomRounded);
    }

    public CharSequence getActiveRemoteInputText() {
        if (mExpandedRemoteInput != null && mExpandedRemoteInput.isActive()) {
            return mExpandedRemoteInput.getText();
        }
        if (mHeadsUpRemoteInput != null && mHeadsUpRemoteInput.isActive()) {
            return mHeadsUpRemoteInput.getText();
        }
        return null;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        float y = ev.getY();
        // We still want to distribute touch events to the remote input even if it's outside the
        // view boundary. We're therefore manually dispatching these events to the remote view
        RemoteInputView riv = getRemoteInputForView(getViewForVisibleType(mVisibleType));
        if (riv != null && riv.getVisibility() == VISIBLE) {
            int inputStart = mUnrestrictedContentHeight - riv.getHeight();
            if (y <= mUnrestrictedContentHeight && y >= inputStart) {
                ev.offsetLocation(0, -inputStart);
                return riv.dispatchTouchEvent(ev);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * Overridden to make sure touches to the reply action bar actually go through to this view
     */
    @Override
    public boolean pointInView(float localX, float localY, float slop) {
        float top = mClipTopAmount;
        float bottom = mUnrestrictedContentHeight;
        return localX >= -slop && localY >= top - slop && localX < ((mRight - mLeft) + slop) &&
                localY < (bottom + slop);
    }

    private RemoteInputView getRemoteInputForView(View child) {
        if (child == mExpandedChild) {
            return mExpandedRemoteInput;
        } else if (child == mHeadsUpChild) {
            return mHeadsUpRemoteInput;
        }
        return null;
    }

    public int getExpandHeight() {
        int viewType;
        if (mExpandedChild != null) {
            viewType = VISIBLE_TYPE_EXPANDED;
        } else if (mContractedChild != null) {
            viewType = VISIBLE_TYPE_CONTRACTED;
        } else {
            return getMinHeight();
        }
        return getViewHeight(viewType) + getExtraRemoteInputHeight(mExpandedRemoteInput);
    }

    public int getHeadsUpHeight(boolean forceNoHeader) {
        int viewType;
        if (mHeadsUpChild != null) {
            viewType = VISIBLE_TYPE_HEADSUP;
        } else if (mContractedChild != null) {
            viewType = VISIBLE_TYPE_CONTRACTED;
        } else {
            return getMinHeight();
        }
        // The headsUp remote input quickly switches to the expanded one, so lets also include that
        // one
        return getViewHeight(viewType, forceNoHeader)
                + getExtraRemoteInputHeight(mHeadsUpRemoteInput)
                + getExtraRemoteInputHeight(mExpandedRemoteInput);
    }

    public void setRemoteInputVisible(boolean remoteInputVisible) {
        mRemoteInputVisible = remoteInputVisible;
        setClipChildren(!remoteInputVisible);
    }

    @Override
    public void setClipChildren(boolean clipChildren) {
        clipChildren = clipChildren && !mRemoteInputVisible;
        super.setClipChildren(clipChildren);
    }

    public void setHeaderVisibleAmount(float headerVisibleAmount) {
        if (mContractedWrapper != null) {
            mContractedWrapper.setHeaderVisibleAmount(headerVisibleAmount);
        }
        if (mHeadsUpWrapper != null) {
            mHeadsUpWrapper.setHeaderVisibleAmount(headerVisibleAmount);
        }
        if (mExpandedWrapper != null) {
            mExpandedWrapper.setHeaderVisibleAmount(headerVisibleAmount);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("    ");
        pw.print("contentView visibility: " + getVisibility());
        pw.print(", alpha: " + getAlpha());
        pw.print(", clipBounds: " + getClipBounds());
        pw.print(", contentHeight: " + mContentHeight);
        pw.print(", visibleType: " + mVisibleType);
        View view = getViewForVisibleType(mVisibleType);
        pw.print(", visibleView ");
        if (view != null) {
            pw.print(" visibility: " + view.getVisibility());
            pw.print(", alpha: " + view.getAlpha());
            pw.print(", clipBounds: " + view.getClipBounds());
        } else {
            pw.print("null");
        }
        pw.println();
    }

    public RemoteInputView getExpandedRemoteInput() {
        return mExpandedRemoteInput;
    }

    /**
     * @return get the transformation target of the shelf, which usually is the icon
     */
    public View getShelfTransformationTarget() {
        NotificationViewWrapper visibleWrapper = getVisibleWrapper(mVisibleType);
        if (visibleWrapper != null) {
            return visibleWrapper.getShelfTransformationTarget();
        }
        return null;
    }

    public int getOriginalIconColor() {
        NotificationViewWrapper visibleWrapper = getVisibleWrapper(mVisibleType);
        if (visibleWrapper != null) {
            return visibleWrapper.getOriginalIconColor();
        }
        return Notification.COLOR_INVALID;
    }
}
