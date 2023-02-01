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

package com.android.systemui.statusbar.notification.row.wrapper;

import static com.android.systemui.statusbar.notification.TransformState.TRANSFORM_Y;

import android.app.Notification;
import android.content.Context;
import android.util.ArraySet;
import android.view.NotificationHeaderView;
import android.view.NotificationTopLineView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.internal.widget.CachingIconView;
import com.android.internal.widget.NotificationExpandButton;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;
import com.android.systemui.statusbar.notification.CustomInterpolatorTransformation;
import com.android.systemui.statusbar.notification.FeedbackIcon;
import com.android.systemui.statusbar.notification.ImageTransformState;
import com.android.systemui.statusbar.notification.Roundable;
import com.android.systemui.statusbar.notification.RoundableState;
import com.android.systemui.statusbar.notification.TransformState;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import java.util.Stack;

/**
 * Wraps a notification view which may or may not include a header.
 */
public class NotificationHeaderViewWrapper extends NotificationViewWrapper implements Roundable {

    private final RoundableState mRoundableState;
    private static final Interpolator LOW_PRIORITY_HEADER_CLOSE
            = new PathInterpolator(0.4f, 0f, 0.7f, 1f);
    protected final ViewTransformationHelper mTransformationHelper;
    private CachingIconView mIcon;
    private NotificationExpandButton mExpandButton;
    private View mAltExpandTarget;
    private View mIconContainer;
    protected NotificationHeaderView mNotificationHeader;
    protected NotificationTopLineView mNotificationTopLine;
    private TextView mHeaderText;
    private TextView mAppNameText;
    private ImageView mWorkProfileImage;
    private View mAudiblyAlertedIcon;
    private View mFeedbackIcon;
    private boolean mIsLowPriority;
    private boolean mTransformLowPriorityTitle;
    private boolean mUseRoundnessSourceTypes;
    private RoundnessChangedListener mRoundnessChangedListener;

    protected NotificationHeaderViewWrapper(Context ctx, View view, ExpandableNotificationRow row) {
        super(ctx, view, row);
        mRoundableState = new RoundableState(
                mView,
                this,
                ctx.getResources().getDimension(R.dimen.notification_corner_radius)
        );
        mTransformationHelper = new ViewTransformationHelper();

        // we want to avoid that the header clashes with the other text when transforming
        // low-priority
        mTransformationHelper.setCustomTransformation(
                new CustomInterpolatorTransformation(TRANSFORMING_VIEW_TITLE) {

                    @Override
                    public Interpolator getCustomInterpolator(
                            int interpolationType,
                            boolean isFrom) {
                        boolean isLowPriority = mView instanceof NotificationHeaderView;
                        if (interpolationType == TRANSFORM_Y) {
                            if (isLowPriority && !isFrom
                                    || !isLowPriority && isFrom) {
                                return Interpolators.LINEAR_OUT_SLOW_IN;
                            } else {
                                return LOW_PRIORITY_HEADER_CLOSE;
                            }
                        }
                        return null;
                    }

                    @Override
                    protected boolean hasCustomTransformation() {
                        return mIsLowPriority && mTransformLowPriorityTitle;
                    }
                },
                TRANSFORMING_VIEW_TITLE);
        resolveHeaderViews();
        addFeedbackOnClickListener(row);
    }

    @Override
    public RoundableState getRoundableState() {
        return mRoundableState;
    }

    @Override
    public void applyRoundnessAndInvalidate() {
        if (mUseRoundnessSourceTypes && mRoundnessChangedListener != null) {
            // We cannot apply the rounded corner to this View, so our parents (in drawChild()) will
            // clip our canvas. So we should invalidate our parent.
            mRoundnessChangedListener.applyRoundnessAndInvalidate();
        }
        Roundable.super.applyRoundnessAndInvalidate();
    }

    public void setOnRoundnessChangedListener(RoundnessChangedListener listener) {
        mRoundnessChangedListener = listener;
    }

    protected void resolveHeaderViews() {
        mIcon = mView.findViewById(com.android.internal.R.id.icon);
        mHeaderText = mView.findViewById(com.android.internal.R.id.header_text);
        mAppNameText = mView.findViewById(com.android.internal.R.id.app_name_text);
        mExpandButton = mView.findViewById(com.android.internal.R.id.expand_button);
        mAltExpandTarget = mView.findViewById(com.android.internal.R.id.alternate_expand_target);
        mIconContainer = mView.findViewById(com.android.internal.R.id.conversation_icon_container);
        mWorkProfileImage = mView.findViewById(com.android.internal.R.id.profile_badge);
        mNotificationHeader = mView.findViewById(com.android.internal.R.id.notification_header);
        mNotificationTopLine = mView.findViewById(com.android.internal.R.id.notification_top_line);
        mAudiblyAlertedIcon = mView.findViewById(com.android.internal.R.id.alerted_icon);
        mFeedbackIcon = mView.findViewById(com.android.internal.R.id.feedback);
    }

    private void addFeedbackOnClickListener(ExpandableNotificationRow row) {
        View.OnClickListener listener = row.getFeedbackOnClickListener();
        if (mNotificationTopLine != null) {
            mNotificationTopLine.setFeedbackOnClickListener(listener);
        }
        if (mFeedbackIcon != null) {
            mFeedbackIcon.setOnClickListener(listener);
        }
    }

    /**
     * Shows the given feedback icon, or hides the icon if null.
     */
    @Override
    public void setFeedbackIcon(@Nullable FeedbackIcon icon) {
        if (mFeedbackIcon != null) {
            mFeedbackIcon.setVisibility(icon != null ? View.VISIBLE : View.GONE);
            if (icon != null) {
                if (mFeedbackIcon instanceof ImageButton) {
                    ((ImageButton) mFeedbackIcon).setImageResource(icon.getIconRes());
                }
                mFeedbackIcon.setContentDescription(
                        mView.getContext().getString(icon.getContentDescRes()));
            }
        }
    }

    @Override
    public void onContentUpdated(ExpandableNotificationRow row) {
        super.onContentUpdated(row);
        mIsLowPriority = row.getEntry().isAmbient();
        mTransformLowPriorityTitle = !row.isChildInGroup() && !row.isSummaryWithChildren();
        ArraySet<View> previousViews = mTransformationHelper.getAllTransformingViews();

        // Reinspect the notification.
        resolveHeaderViews();
        updateTransformedTypes();
        addRemainingTransformTypes();
        updateCropToPaddingForImageViews();
        Notification notification = row.getEntry().getSbn().getNotification();
        mIcon.setTag(ImageTransformState.ICON_TAG, notification.getSmallIcon());

        // We need to reset all views that are no longer transforming in case a view was previously
        // transformed, but now we decided to transform its container instead.
        ArraySet<View> currentViews = mTransformationHelper.getAllTransformingViews();
        for (int i = 0; i < previousViews.size(); i++) {
            View view = previousViews.valueAt(i);
            if (!currentViews.contains(view)) {
                mTransformationHelper.resetTransformedView(view);
            }
        }
    }

    /**
     * Adds the remaining TransformTypes to the TransformHelper. This is done to make sure that each
     * child is faded automatically and doesn't have to be manually added.
     * The keys used for the views are the ids.
     */
    private void addRemainingTransformTypes() {
        mTransformationHelper.addRemainingTransformTypes(mView);
    }

    /**
     * Since we are deactivating the clipping when transforming the ImageViews don't get clipped
     * anymore during these transitions. We can avoid that by using
     * {@link ImageView#setCropToPadding(boolean)} on all ImageViews.
     */
    private void updateCropToPaddingForImageViews() {
        Stack<View> stack = new Stack<>();
        stack.push(mView);
        while (!stack.isEmpty()) {
            View child = stack.pop();
            if (child instanceof ImageView
                    // Skip the importance ring for conversations, disabled cropping is needed for
                    // its animation
                    && child.getId() != com.android.internal.R.id.conversation_icon_badge_ring) {
                ((ImageView) child).setCropToPadding(true);
            } else if (child instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) child;
                for (int i = 0; i < group.getChildCount(); i++) {
                    stack.push(group.getChildAt(i));
                }
            }
        }
    }

    protected void updateTransformedTypes() {
        mTransformationHelper.reset();
        mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_ICON, mIcon);
        mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_EXPANDER,
                mExpandButton);
        if (mIsLowPriority && mHeaderText != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_TITLE,
                    mHeaderText);
        }
        addViewsTransformingToSimilar(mWorkProfileImage, mAudiblyAlertedIcon, mFeedbackIcon);
    }

    @Override
    public void updateExpandability(
            boolean expandable,
            View.OnClickListener onClickListener,
            boolean requestLayout) {
        mExpandButton.setVisibility(expandable ? View.VISIBLE : View.GONE);
        mExpandButton.setOnClickListener(expandable ? onClickListener : null);
        if (mAltExpandTarget != null) {
            mAltExpandTarget.setOnClickListener(expandable ? onClickListener : null);
        }
        if (mIconContainer != null) {
            mIconContainer.setOnClickListener(expandable ? onClickListener : null);
        }
        if (mNotificationHeader != null) {
            mNotificationHeader.setOnClickListener(expandable ? onClickListener : null);
        }
        // Unfortunately, the NotificationContentView has to layout its children in order to
        // determine their heights, and that affects the button visibility.  If that happens
        // (thankfully it is rare) then we need to request layout of the expand button's parent
        // in order to ensure it gets laid out correctly.
        if (requestLayout) {
            mExpandButton.getParent().requestLayout();
        }
    }

    @Override
    public void setExpanded(boolean expanded) {
        mExpandButton.setExpanded(expanded);
    }

    @Override
    public void setRecentlyAudiblyAlerted(boolean audiblyAlerted) {
        if (mAudiblyAlertedIcon != null) {
            mAudiblyAlertedIcon.setVisibility(audiblyAlerted ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public NotificationHeaderView getNotificationHeader() {
        return mNotificationHeader;
    }

    @Override
    public View getExpandButton() {
        return mExpandButton;
    }

    @Override
    public CachingIconView getIcon() {
        return mIcon;
    }

    @Override
    public int getOriginalIconColor() {
        return mIcon.getOriginalIconColor();
    }

    @Override
    public View getShelfTransformationTarget() {
        return mIcon;
    }

    @Override
    public TransformState getCurrentState(int fadingView) {
        return mTransformationHelper.getCurrentState(fadingView);
    }

    @Override
    public void transformTo(TransformableView notification, Runnable endRunnable) {
        mTransformationHelper.transformTo(notification, endRunnable);
    }

    @Override
    public void transformTo(TransformableView notification, float transformationAmount) {
        mTransformationHelper.transformTo(notification, transformationAmount);
    }

    @Override
    public void transformFrom(TransformableView notification) {
        mTransformationHelper.transformFrom(notification);
    }

    @Override
    public void transformFrom(TransformableView notification, float transformationAmount) {
        mTransformationHelper.transformFrom(notification, transformationAmount);
    }

    @Override
    public void setIsChildInGroup(boolean isChildInGroup) {
        super.setIsChildInGroup(isChildInGroup);
        mTransformLowPriorityTitle = !isChildInGroup;
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        mTransformationHelper.setVisible(visible);
    }

    protected void addTransformedViews(View... views) {
        for (View view : views) {
            if (view != null) {
                mTransformationHelper.addTransformedView(view);
            }
        }
    }

    protected void addViewsTransformingToSimilar(View... views) {
        for (View view : views) {
            if (view != null) {
                mTransformationHelper.addViewTransformingToSimilar(view);
            }
        }
    }

    /**
     * Enable the support for rounded corner based on the SourceType
     *
     * @param enabled true if is supported
     */
    public void useRoundnessSourceTypes(boolean enabled) {
        mUseRoundnessSourceTypes = enabled;
    }

    /**
     * Interface that handle the Roundness changes
     */
    public interface RoundnessChangedListener {
        /**
         * This method will be called when this class call applyRoundnessAndInvalidate()
         */
        void applyRoundnessAndInvalidate();
    }
}
