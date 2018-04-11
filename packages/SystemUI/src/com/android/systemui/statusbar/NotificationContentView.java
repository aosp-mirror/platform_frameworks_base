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

package com.android.systemui.statusbar;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.HybridGroupManager;
import com.android.systemui.statusbar.notification.HybridNotificationView;
import com.android.systemui.statusbar.notification.NotificationCustomViewWrapper;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.NotificationViewWrapper;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.RemoteInputView;
import com.android.systemui.statusbar.policy.SmartReplyConstants;
import com.android.systemui.statusbar.policy.SmartReplyView;

/**
 * A frame layout containing the actual payload of the notification, including the contracted,
 * expanded and heads up layout. This class is responsible for clipping the content and and
 * switching between the expanded, contracted and the heads up view depending on its clipped size.
 */
public class NotificationContentView extends FrameLayout {

    private static final String TAG = "NotificationContentView";
    public static final int VISIBLE_TYPE_CONTRACTED = 0;
    public static final int VISIBLE_TYPE_EXPANDED = 1;
    public static final int VISIBLE_TYPE_HEADSUP = 2;
    private static final int VISIBLE_TYPE_SINGLELINE = 3;
    public static final int VISIBLE_TYPE_AMBIENT = 4;
    private static final int VISIBLE_TYPE_AMBIENT_SINGLELINE = 5;
    public static final int UNDEFINED = -1;

    private final Rect mClipBounds = new Rect();

    private int mMinContractedHeight;
    private int mNotificationContentMarginEnd;
    private View mContractedChild;
    private View mExpandedChild;
    private View mHeadsUpChild;
    private HybridNotificationView mSingleLineView;
    private View mAmbientChild;
    private HybridNotificationView mAmbientSingleLineChild;

    private RemoteInputView mExpandedRemoteInput;
    private RemoteInputView mHeadsUpRemoteInput;

    private SmartReplyConstants mSmartReplyConstants;
    private SmartReplyView mExpandedSmartReplyView;

    private NotificationViewWrapper mContractedWrapper;
    private NotificationViewWrapper mExpandedWrapper;
    private NotificationViewWrapper mHeadsUpWrapper;
    private NotificationViewWrapper mAmbientWrapper;
    private HybridGroupManager mHybridGroupManager;
    private int mClipTopAmount;
    private int mContentHeight;
    private int mVisibleType = VISIBLE_TYPE_CONTRACTED;
    private boolean mDark;
    private boolean mAnimate;
    private boolean mIsHeadsUp;
    private boolean mLegacy;
    private boolean mIsChildInGroup;
    private int mSmallHeight;
    private int mHeadsUpHeight;
    private int mNotificationMaxHeight;
    private int mNotificationAmbientHeight;
    private StatusBarNotification mStatusBarNotification;
    private NotificationGroupManager mGroupManager;
    private RemoteInputController mRemoteInputController;
    private Runnable mExpandedVisibleListener;

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
    private int mAnimationStartVisibleType = UNDEFINED;
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
    private boolean mIconsVisible;
    private int mClipBottomAmount;
    private boolean mIsLowPriority;
    private boolean mIsContentExpandable;
    private boolean mRemoteInputVisible;
    private int mUnrestrictedContentHeight;


    public NotificationContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHybridGroupManager = new HybridGroupManager(getContext(), this);
        mSmartReplyConstants = Dependency.get(SmartReplyConstants.class);
        initView();
    }

    public void initView() {
        mMinContractedHeight = getResources().getDimensionPixelSize(
                R.dimen.min_notification_layout_height);
        mNotificationContentMarginEnd = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_end);
    }

    public void setHeights(int smallHeight, int headsUpMaxHeight, int maxHeight,
            int ambientHeight) {
        mSmallHeight = smallHeight;
        mHeadsUpHeight = headsUpMaxHeight;
        mNotificationMaxHeight = maxHeight;
        mNotificationAmbientHeight = ambientHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        int maxSize = Integer.MAX_VALUE;
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (hasFixedHeight || isHeightLimited) {
            maxSize = MeasureSpec.getSize(heightMeasureSpec);
        }
        int maxChildHeight = 0;
        if (mExpandedChild != null) {
            int size = Math.min(maxSize, mNotificationMaxHeight);
            ViewGroup.LayoutParams layoutParams = mExpandedChild.getLayoutParams();
            boolean useExactly = false;
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(maxSize, layoutParams.height);
                useExactly = true;
            }
            int spec = size == Integer.MAX_VALUE
                    ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    : MeasureSpec.makeMeasureSpec(size, useExactly
                            ? MeasureSpec.EXACTLY
                            : MeasureSpec.AT_MOST);
            measureChildWithMargins(mExpandedChild, widthMeasureSpec, 0, spec, 0);
            maxChildHeight = Math.max(maxChildHeight, mExpandedChild.getMeasuredHeight());
        }
        if (mContractedChild != null) {
            int heightSpec;
            int size = Math.min(maxSize, mSmallHeight);
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
            maxHeight += mHeadsUpWrapper.getExtraMeasureHeight();
            int size = Math.min(maxSize, maxHeight);
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
                    MeasureSpec.makeMeasureSpec(maxSize, MeasureSpec.AT_MOST));
            maxChildHeight = Math.max(maxChildHeight, mSingleLineView.getMeasuredHeight());
        }
        if (mAmbientChild != null) {
            int size = Math.min(maxSize, mNotificationAmbientHeight);
            ViewGroup.LayoutParams layoutParams = mAmbientChild.getLayoutParams();
            boolean useExactly = false;
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(size, layoutParams.height);
                useExactly = true;
            }
            mAmbientChild.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(size, useExactly ? MeasureSpec.EXACTLY
                            : MeasureSpec.AT_MOST));
            maxChildHeight = Math.max(maxChildHeight, mAmbientChild.getMeasuredHeight());
        }
        if (mAmbientSingleLineChild != null) {
            int size = Math.min(maxSize, mNotificationAmbientHeight);
            ViewGroup.LayoutParams layoutParams = mAmbientSingleLineChild.getLayoutParams();
            boolean useExactly = false;
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(size, layoutParams.height);
                useExactly = true;
            }
            int ambientSingleLineWidthSpec = widthMeasureSpec;
            if (mSingleLineWidthIndention != 0
                    && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                ambientSingleLineWidthSpec = MeasureSpec.makeMeasureSpec(
                        width - mSingleLineWidthIndention + mAmbientSingleLineChild.getPaddingEnd(),
                        MeasureSpec.EXACTLY);
            }
            mAmbientSingleLineChild.measure(ambientSingleLineWidthSpec,
                    MeasureSpec.makeMeasureSpec(size, useExactly ? MeasureSpec.EXACTLY
                            : MeasureSpec.AT_MOST));
            maxChildHeight = Math.max(maxChildHeight, mAmbientSingleLineChild.getMeasuredHeight());
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
        if (remoteInput != null && remoteInput.getVisibility() == VISIBLE
                && remoteInput.isActive()) {
            return getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.notification_content_margin);
        }
        return 0;
    }

    private boolean updateContractedHeaderWidth() {
        // We need to update the expanded and the collapsed header to have exactly the same with to
        // have the expand buttons laid out at the same location.
        NotificationHeaderView contractedHeader = mContractedWrapper.getNotificationHeader();
        if (contractedHeader != null) {
            if (mExpandedChild != null
                    && mExpandedWrapper.getNotificationHeader() != null) {
                NotificationHeaderView expandedHeader = mExpandedWrapper.getNotificationHeader();
                int expandedSize = expandedHeader.getMeasuredWidth()
                        - expandedHeader.getPaddingEnd();
                int collapsedSize = contractedHeader.getMeasuredWidth()
                        - expandedHeader.getPaddingEnd();
                if (expandedSize != collapsedSize) {
                    int paddingEnd = contractedHeader.getMeasuredWidth() - expandedSize;
                    contractedHeader.setPadding(
                            contractedHeader.isLayoutRtl()
                                    ? paddingEnd
                                    : contractedHeader.getPaddingLeft(),
                            contractedHeader.getPaddingTop(),
                            contractedHeader.isLayoutRtl()
                                    ? contractedHeader.getPaddingLeft()
                                    : paddingEnd,
                            contractedHeader.getPaddingBottom());
                    contractedHeader.setShowWorkBadgeAtEnd(true);
                    return true;
                }
            } else {
                int paddingEnd = mNotificationContentMarginEnd;
                if (contractedHeader.getPaddingEnd() != paddingEnd) {
                    contractedHeader.setPadding(
                            contractedHeader.isLayoutRtl()
                                    ? paddingEnd
                                    : contractedHeader.getPaddingLeft(),
                            contractedHeader.getPaddingTop(),
                            contractedHeader.isLayoutRtl()
                                    ? contractedHeader.getPaddingLeft()
                                    : paddingEnd,
                            contractedHeader.getPaddingBottom());
                    contractedHeader.setShowWorkBadgeAtEnd(false);
                    return true;
                }
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

    public View getAmbientChild() {
        return mAmbientChild;
    }

    public HybridNotificationView getAmbientSingleLineChild() {
        return mAmbientSingleLineChild;
    }

    public void setContractedChild(View child) {
        if (mContractedChild != null) {
            mContractedChild.animate().cancel();
            removeView(mContractedChild);
        }
        addView(child);
        mContractedChild = child;
        mContractedWrapper = NotificationViewWrapper.wrap(getContext(), child,
                mContainingNotification);
        mContractedWrapper.setDark(mDark, false /* animate */, 0 /* delay */);
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
        if (child == mAmbientChild) {
            return mAmbientWrapper;
        }
        return null;
    }

    public void setExpandedChild(View child) {
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
            mExpandedChild.animate().cancel();
            removeView(mExpandedChild);
            mExpandedRemoteInput = null;
        }
        if (child == null) {
            mExpandedChild = null;
            mExpandedWrapper = null;
            if (mVisibleType == VISIBLE_TYPE_EXPANDED) {
                mVisibleType = VISIBLE_TYPE_CONTRACTED;
            }
            if (mTransformationStartVisibleType == VISIBLE_TYPE_EXPANDED) {
                mTransformationStartVisibleType = UNDEFINED;
            }
            return;
        }
        addView(child);
        mExpandedChild = child;
        mExpandedWrapper = NotificationViewWrapper.wrap(getContext(), child,
                mContainingNotification);
    }

    public void setHeadsUpChild(View child) {
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
            mHeadsUpChild.animate().cancel();
            removeView(mHeadsUpChild);
            mHeadsUpRemoteInput = null;
        }
        if (child == null) {
            mHeadsUpChild = null;
            mHeadsUpWrapper = null;
            if (mVisibleType == VISIBLE_TYPE_HEADSUP) {
                mVisibleType = VISIBLE_TYPE_CONTRACTED;
            }
            if (mTransformationStartVisibleType == VISIBLE_TYPE_HEADSUP) {
                mTransformationStartVisibleType = UNDEFINED;
            }
            return;
        }
        addView(child);
        mHeadsUpChild = child;
        mHeadsUpWrapper = NotificationViewWrapper.wrap(getContext(), child,
                mContainingNotification);
    }

    public void setAmbientChild(View child) {
        if (mAmbientChild != null) {
            mAmbientChild.animate().cancel();
            removeView(mAmbientChild);
        }
        if (child == null) {
            return;
        }
        addView(child);
        mAmbientChild = child;
        mAmbientWrapper = NotificationViewWrapper.wrap(getContext(), child,
                mContainingNotification);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateVisibility();
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
            NotificationHeaderView header = getVisibleNotificationHeader();
            if (header != null) {
                ImageView expandButton = header.getExpandButton();
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
                    && !mContainingNotification.isOnKeyguard();
            if (transitioningBetweenHunAndExpanded || pinned) {
                return Math.min(getViewHeight(VISIBLE_TYPE_HEADSUP),
                        getViewHeight(VISIBLE_TYPE_EXPANDED));
            }
        }

        // Size change of the expanded version
        if ((mVisibleType == VISIBLE_TYPE_EXPANDED) && mContentHeightAtAnimationStart >= 0
                && mExpandedChild != null) {
            return Math.min(mContentHeightAtAnimationStart, getViewHeight(VISIBLE_TYPE_EXPANDED));
        }

        int hint;
        if (mAmbientChild != null && isVisibleOrTransitioning(VISIBLE_TYPE_AMBIENT)) {
            hint = mAmbientChild.getHeight();
        } else if (mAmbientSingleLineChild != null && isVisibleOrTransitioning(
                VISIBLE_TYPE_AMBIENT_SINGLELINE)) {
            hint = mAmbientSingleLineChild.getHeight();
        } else if (mHeadsUpChild != null && isVisibleOrTransitioning(VISIBLE_TYPE_HEADSUP)) {
            hint = getViewHeight(VISIBLE_TYPE_HEADSUP);
        } else if (mExpandedChild != null) {
            hint = getViewHeight(VISIBLE_TYPE_EXPANDED);
        } else {
            hint = getViewHeight(VISIBLE_TYPE_CONTRACTED)
                    + mContext.getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.notification_action_list_height);
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
        if (mTransformationStartVisibleType != UNDEFINED
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
        if (mContainingNotification.isShowingAmbient()) {
            return getShowingAmbientView().getHeight();
        } else if (mExpandedChild != null) {
            return getViewHeight(VISIBLE_TYPE_EXPANDED)
                    + getExtraRemoteInputHeight(mExpandedRemoteInput);
        } else if (mIsHeadsUp && mHeadsUpChild != null && !mContainingNotification.isOnKeyguard()) {
            return getViewHeight(VISIBLE_TYPE_HEADSUP)
                    + getExtraRemoteInputHeight(mHeadsUpRemoteInput);
        }
        return getViewHeight(VISIBLE_TYPE_CONTRACTED);
    }

    private int getViewHeight(int visibleType) {
        View view = getViewForVisibleType(visibleType);
        int height = view.getHeight();
        NotificationViewWrapper viewWrapper = getWrapperForView(view);
        if (viewWrapper != null) {
            height += viewWrapper.getHeaderTranslation();
        }
        return height;
    }

    public int getMinHeight() {
        return getMinHeight(false /* likeGroupExpanded */);
    }

    public int getMinHeight(boolean likeGroupExpanded) {
        if (mContainingNotification.isShowingAmbient()) {
            return getShowingAmbientView().getHeight();
        } else if (likeGroupExpanded || !mIsChildInGroup || isGroupExpanded()) {
            return getViewHeight(VISIBLE_TYPE_CONTRACTED);
        } else {
            return mSingleLineView.getHeight();
        }
    }

    public View getShowingAmbientView() {
        View v = mIsChildInGroup ? mAmbientSingleLineChild : mAmbientChild;
        if (v != null) {
            return v;
        } else {
            return mContractedChild;
        }
    }

    private boolean isGroupExpanded() {
        return mGroupManager.isGroupExpanded(mStatusBarNotification);
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
        forceUpdateVisibility(VISIBLE_TYPE_AMBIENT, mAmbientChild, mAmbientWrapper);
        forceUpdateVisibility(VISIBLE_TYPE_AMBIENT_SINGLELINE, mAmbientSingleLineChild,
                mAmbientSingleLineChild);
        fireExpandedVisibleListenerIfVisible();
        // forceUpdateVisibilities cancels outstanding animations without updating the
        // mAnimationStartVisibleType. Do so here instead.
        mAnimationStartVisibleType = UNDEFINED;
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

    public int getVisibleType() {
        return mVisibleType;
    }

    public int getBackgroundColorForExpansionState() {
        // When expanding or user locked we want the new type, when collapsing we want
        // the original type
        final int visibleType = (mContainingNotification.isGroupExpanded()
                || mContainingNotification.isUserLocked())
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
        updateViewVisibility(visibleType, VISIBLE_TYPE_AMBIENT,
                mAmbientChild, mAmbientWrapper);
        updateViewVisibility(visibleType, VISIBLE_TYPE_AMBIENT_SINGLELINE,
                mAmbientSingleLineChild, mAmbientSingleLineChild);
        fireExpandedVisibleListenerIfVisible();
        // updateViewVisibilities cancels outstanding animations without updating the
        // mAnimationStartVisibleType. Do so here instead.
        mAnimationStartVisibleType = UNDEFINED;
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
                mAnimationStartVisibleType = UNDEFINED;
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
            case VISIBLE_TYPE_AMBIENT:
                return mAmbientWrapper;
            case VISIBLE_TYPE_AMBIENT_SINGLELINE:
                return mAmbientSingleLineChild;
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
            case VISIBLE_TYPE_AMBIENT:
                return mAmbientChild;
            case VISIBLE_TYPE_AMBIENT_SINGLELINE:
                return mAmbientSingleLineChild;
            default:
                return mContractedChild;
        }
    }

    public NotificationViewWrapper getVisibleWrapper(int visibleType) {
        switch (visibleType) {
            case VISIBLE_TYPE_EXPANDED:
                return mExpandedWrapper;
            case VISIBLE_TYPE_HEADSUP:
                return mHeadsUpWrapper;
            case VISIBLE_TYPE_CONTRACTED:
                return mContractedWrapper;
            case VISIBLE_TYPE_AMBIENT:
                return mAmbientWrapper;
            default:
                return null;
        }
    }

    /**
     * @return one of the static enum types in this view, calculated form the current state
     */
    public int calculateVisibleType() {
        if (mContainingNotification.isShowingAmbient()) {
            if (mIsChildInGroup && mAmbientSingleLineChild != null) {
                return VISIBLE_TYPE_AMBIENT_SINGLELINE;
            } else if (mAmbientChild != null) {
                return VISIBLE_TYPE_AMBIENT;
            } else {
                return VISIBLE_TYPE_CONTRACTED;
            }
        }
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
                && !mContainingNotification.isOnKeyguard()) {
            if (viewHeight <= getViewHeight(VISIBLE_TYPE_HEADSUP) || noExpandedChild) {
                return VISIBLE_TYPE_HEADSUP;
            } else {
                return VISIBLE_TYPE_EXPANDED;
            }
        } else {
            if (noExpandedChild || (viewHeight <= getViewHeight(VISIBLE_TYPE_CONTRACTED)
                    && (!mIsChildInGroup || isGroupExpanded()
                            || !mContainingNotification.isExpanded(true /* allowOnKeyguard */)))) {
                return VISIBLE_TYPE_CONTRACTED;
            } else {
                return VISIBLE_TYPE_EXPANDED;
            }
        }
    }

    public boolean isContentExpandable() {
        return mIsContentExpandable;
    }

    public void setDark(boolean dark, boolean fade, long delay) {
        if (mContractedChild == null) {
            return;
        }
        mDark = dark;
        if (mVisibleType == VISIBLE_TYPE_CONTRACTED || !dark) {
            mContractedWrapper.setDark(dark, fade, delay);
        }
        if (mVisibleType == VISIBLE_TYPE_EXPANDED || (mExpandedChild != null && !dark)) {
            mExpandedWrapper.setDark(dark, fade, delay);
        }
        if (mVisibleType == VISIBLE_TYPE_HEADSUP || (mHeadsUpChild != null && !dark)) {
            mHeadsUpWrapper.setDark(dark, fade, delay);
        }
        if (mSingleLineView != null && (mVisibleType == VISIBLE_TYPE_SINGLELINE || !dark)) {
            mSingleLineView.setDark(dark, fade, delay);
        }
        selectLayout(!dark && fade /* animate */, false /* force */);
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
        if (mAmbientChild != null) {
            mAmbientWrapper.setIsChildInGroup(mIsChildInGroup);
        }
        updateAllSingleLineViews();
    }

    public void onNotificationUpdated(NotificationData.Entry entry) {
        mStatusBarNotification = entry.notification;
        mBeforeN = entry.targetSdk < Build.VERSION_CODES.N;
        updateAllSingleLineViews();
        if (mContractedChild != null) {
            mContractedWrapper.onContentUpdated(entry.row);
        }
        if (mExpandedChild != null) {
            mExpandedWrapper.onContentUpdated(entry.row);
        }
        if (mHeadsUpChild != null) {
            mHeadsUpWrapper.onContentUpdated(entry.row);
        }
        if (mAmbientChild != null) {
            mAmbientWrapper.onContentUpdated(entry.row);
        }
        applyRemoteInputAndSmartReply(entry);
        updateLegacy();
        mForceSelectNextLayout = true;
        setDark(mDark, false /* animate */, 0 /* delay */);
        mPreviousExpandedRemoteInputIntent = null;
        mPreviousHeadsUpRemoteInputIntent = null;
    }

    private void updateAllSingleLineViews() {
        updateSingleLineView();
        updateAmbientSingleLineView();
    }
    private void updateSingleLineView() {
        if (mIsChildInGroup) {
            mSingleLineView = mHybridGroupManager.bindFromNotification(
                    mSingleLineView, mStatusBarNotification.getNotification());
        } else if (mSingleLineView != null) {
            removeView(mSingleLineView);
            mSingleLineView = null;
        }
    }

    private void updateAmbientSingleLineView() {
        if (mIsChildInGroup) {
            mAmbientSingleLineChild = mHybridGroupManager.bindAmbientFromNotification(
                    mAmbientSingleLineChild, mStatusBarNotification.getNotification());
        } else if (mAmbientSingleLineChild != null) {
            removeView(mAmbientSingleLineChild);
            mAmbientSingleLineChild = null;
        }
    }

    private void applyRemoteInputAndSmartReply(final NotificationData.Entry entry) {
        if (mRemoteInputController == null) {
            return;
        }

        boolean enableSmartReplies = (mSmartReplyConstants.isEnabled()
                && (!mSmartReplyConstants.requiresTargetingP()
                    || entry.targetSdk >= Build.VERSION_CODES.P));

        boolean hasRemoteInput = false;
        RemoteInput remoteInputWithChoices = null;
        PendingIntent pendingIntentWithChoices = null;

        Notification.Action[] actions = entry.notification.getNotification().actions;
        if (actions != null) {
            for (Notification.Action a : actions) {
                if (a.getRemoteInputs() != null) {
                    for (RemoteInput ri : a.getRemoteInputs()) {
                        boolean showRemoteInputView = ri.getAllowFreeFormInput();
                        boolean showSmartReplyView = enableSmartReplies && ri.getChoices() != null
                                && ri.getChoices().length > 0;
                        if (showRemoteInputView) {
                            hasRemoteInput = true;
                        }
                        if (showSmartReplyView) {
                            remoteInputWithChoices = ri;
                            pendingIntentWithChoices = a.actionIntent;
                        }
                        if (showRemoteInputView || showSmartReplyView) {
                            break;
                        }
                    }
                }
            }
        }

        applyRemoteInput(entry, hasRemoteInput);
        applySmartReplyView(remoteInputWithChoices, pendingIntentWithChoices);
    }

    private void applyRemoteInput(NotificationData.Entry entry, boolean hasRemoteInput) {
        View bigContentView = mExpandedChild;
        if (bigContentView != null) {
            mExpandedRemoteInput = applyRemoteInput(bigContentView, entry, hasRemoteInput,
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
            mHeadsUpRemoteInput = applyRemoteInput(headsUpContentView, entry, hasRemoteInput,
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

    private RemoteInputView applyRemoteInput(View view, NotificationData.Entry entry,
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
                int color = entry.notification.getNotification().color;
                if (color == Notification.COLOR_DEFAULT) {
                    color = mContext.getColor(R.color.default_remote_input_background);
                }
                existing.setBackgroundColor(NotificationColorUtil.ensureTextBackgroundColor(color,
                        mContext.getColor(R.color.remote_input_text_enabled),
                        mContext.getColor(R.color.remote_input_hint)));

                existing.setWrapper(wrapper);
                existing.setOnVisibilityChangedListener(this::setRemoteInputVisible);

                if (existingPendingIntent != null || existing.isActive()) {
                    // The current action could be gone, or the pending intent no longer valid.
                    // If we find a matching action in the new notification, focus, otherwise close.
                    Notification.Action[] actions = entry.notification.getNotification().actions;
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

    private void applySmartReplyView(RemoteInput remoteInput, PendingIntent pendingIntent) {
        mExpandedSmartReplyView = mExpandedChild == null ?
                null : applySmartReplyView(mExpandedChild, remoteInput, pendingIntent);
    }

    private SmartReplyView applySmartReplyView(
            View view, RemoteInput remoteInput, PendingIntent pendingIntent) {
        View smartReplyContainerCandidate = view.findViewById(
                com.android.internal.R.id.smart_reply_container);
        if (!(smartReplyContainerCandidate instanceof LinearLayout)) {
            return null;
        }
        LinearLayout smartReplyContainer = (LinearLayout) smartReplyContainerCandidate;
        if (remoteInput == null || pendingIntent == null) {
            smartReplyContainer.setVisibility(View.GONE);
            return null;
        }
        SmartReplyView smartReplyView = null;
        if (smartReplyContainer.getChildCount() == 0) {
            smartReplyView = SmartReplyView.inflate(mContext, smartReplyContainer);
            smartReplyContainer.addView(smartReplyView);
        } else if (smartReplyContainer.getChildCount() == 1) {
            View child = smartReplyContainer.getChildAt(0);
            if (child instanceof SmartReplyView) {
                smartReplyView = (SmartReplyView) child;
            }
        }
        if (smartReplyView != null) {
            smartReplyView.setRepliesFromRemoteInput(remoteInput, pendingIntent);
            smartReplyContainer.setVisibility(View.VISIBLE);
        }
        return smartReplyView;
    }

    public void closeRemoteInput() {
        if (mHeadsUpRemoteInput != null) {
            mHeadsUpRemoteInput.close();
        }
        if (mExpandedRemoteInput != null) {
            mExpandedRemoteInput.close();
        }
    }

    public void setGroupManager(NotificationGroupManager groupManager) {
        mGroupManager = groupManager;
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
                    || mHeadsUpChild == null || mContainingNotification.isOnKeyguard()) {
                if (mExpandedChild.getHeight() <= mContractedChild.getHeight()) {
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

    public NotificationHeaderView getNotificationHeader() {
        NotificationHeaderView header = null;
        if (mContractedChild != null) {
            header = mContractedWrapper.getNotificationHeader();
        }
        if (header == null && mExpandedChild != null) {
            header = mExpandedWrapper.getNotificationHeader();
        }
        if (header == null && mHeadsUpChild != null) {
            header = mHeadsUpWrapper.getNotificationHeader();
        }
        if (header == null && mAmbientChild != null) {
            header = mAmbientWrapper.getNotificationHeader();
        }
        return header;
    }

    public void showAppOpsIcons(ArraySet<Integer> activeOps) {
        if (mContractedChild != null && mContractedWrapper.getNotificationHeader() != null) {
            mContractedWrapper.getNotificationHeader().showAppOpsIcons(activeOps);
        }
        if (mExpandedChild != null && mExpandedWrapper.getNotificationHeader() != null) {
            mExpandedWrapper.getNotificationHeader().showAppOpsIcons(activeOps);
        }
        if (mHeadsUpChild != null && mHeadsUpWrapper.getNotificationHeader() != null) {
            mHeadsUpWrapper.getNotificationHeader().showAppOpsIcons(activeOps);
        }
    }

    public NotificationHeaderView getContractedNotificationHeader() {
        if (mContractedChild != null) {
            return mContractedWrapper.getNotificationHeader();
        }
        return null;
    }

    public NotificationHeaderView getVisibleNotificationHeader() {
        NotificationViewWrapper wrapper = getVisibleWrapper(mVisibleType);
        return wrapper == null ? null : wrapper.getNotificationHeader();
    }

    public void setContainingNotification(ExpandableNotificationRow containingNotification) {
        mContainingNotification = containingNotification;
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
            mTransformationStartVisibleType = UNDEFINED;
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
    }

    public void setContentHeightAnimating(boolean animating) {
        if (!animating) {
            mContentHeightAtAnimationStart = UNDEFINED;
        }
    }

    @VisibleForTesting
    boolean isAnimatingVisibleType() {
        return mAnimationStartVisibleType != UNDEFINED;
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        selectLayout(false /* animate */, true /* force */);
    }

    public void setFocusOnVisibilityChange() {
        mFocusOnVisibilityChange = true;
    }

    public void setIconsVisible(boolean iconsVisible) {
        mIconsVisible = iconsVisible;
        updateIconVisibilities();
    }

    private void updateIconVisibilities() {
        if (mContractedWrapper != null) {
            NotificationHeaderView header = mContractedWrapper.getNotificationHeader();
            if (header != null) {
                header.getIcon().setForceHidden(!mIconsVisible);
            }
        }
        if (mHeadsUpWrapper != null) {
            NotificationHeaderView header = mHeadsUpWrapper.getNotificationHeader();
            if (header != null) {
                header.getIcon().setForceHidden(!mIconsVisible);
            }
        }
        if (mExpandedWrapper != null) {
            NotificationHeaderView header = mExpandedWrapper.getNotificationHeader();
            if (header != null) {
                header.getIcon().setForceHidden(!mIconsVisible);
            }
        }
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

    public void setIsLowPriority(boolean isLowPriority) {
        mIsLowPriority = isLowPriority;
    }

    public boolean isDimmable() {
        if (!mContractedWrapper.isDimmable()) {
            return false;
        }
        return true;
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

    public int getExpandHeight() {
        int viewType = VISIBLE_TYPE_EXPANDED;
        if (mExpandedChild == null) {
            viewType = VISIBLE_TYPE_CONTRACTED;
        }
        return getViewHeight(viewType) + getExtraRemoteInputHeight(mExpandedRemoteInput);
    }

    public int getHeadsUpHeight() {
        int viewType = VISIBLE_TYPE_HEADSUP;
        if (mHeadsUpChild == null) {
            viewType = VISIBLE_TYPE_CONTRACTED;
        }
        return getViewHeight(viewType) + getExtraRemoteInputHeight(mHeadsUpRemoteInput);
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
}
