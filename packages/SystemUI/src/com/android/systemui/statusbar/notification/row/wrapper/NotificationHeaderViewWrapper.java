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

import android.app.AppOpsManager;
import android.app.Notification;
import android.content.Context;
import android.util.ArraySet;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.widget.CachingIconView;
import com.android.internal.widget.NotificationExpandButton;
import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;
import com.android.systemui.statusbar.notification.CustomInterpolatorTransformation;
import com.android.systemui.statusbar.notification.ImageTransformState;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.TransformState;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import java.util.Stack;

/**
 * Wraps a notification header view.
 */
public class NotificationHeaderViewWrapper extends NotificationViewWrapper {

    private static final Interpolator LOW_PRIORITY_HEADER_CLOSE
            = new PathInterpolator(0.4f, 0f, 0.7f, 1f);

    protected final ViewTransformationHelper mTransformationHelper;

    protected int mColor;

    private CachingIconView mIcon;
    private NotificationExpandButton mExpandButton;
    protected NotificationHeaderView mNotificationHeader;
    private TextView mHeaderText;
    private TextView mAppNameText;
    private ImageView mWorkProfileImage;
    private View mCameraIcon;
    private View mMicIcon;
    private View mOverlayIcon;
    private View mAppOps;
    private View mAudiblyAlertedIcon;
    private FrameLayout mIconContainer;

    private boolean mIsLowPriority;
    private boolean mTransformLowPriorityTitle;
    private boolean mShowExpandButtonAtEnd;

    protected NotificationHeaderViewWrapper(Context ctx, View view, ExpandableNotificationRow row) {
        super(ctx, view, row);
        mShowExpandButtonAtEnd = ctx.getResources().getBoolean(
                R.bool.config_showNotificationExpandButtonAtEnd)
                || NotificationUtils.useNewInterruptionModel(ctx);
        mTransformationHelper = new ViewTransformationHelper();

        // we want to avoid that the header clashes with the other text when transforming
        // low-priority
        mTransformationHelper.setCustomTransformation(
                new CustomInterpolatorTransformation(TRANSFORMING_VIEW_TITLE) {

                    @Override
                    public Interpolator getCustomInterpolator(int interpolationType,
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
                }, TRANSFORMING_VIEW_TITLE);
        resolveHeaderViews();
        addAppOpsOnClickListener(row);
    }

    protected void resolveHeaderViews() {
        mIconContainer = mView.findViewById(com.android.internal.R.id.header_icon_container);
        mIcon = mView.findViewById(com.android.internal.R.id.icon);
        mHeaderText = mView.findViewById(com.android.internal.R.id.header_text);
        mAppNameText = mView.findViewById(com.android.internal.R.id.app_name_text);
        mExpandButton = mView.findViewById(com.android.internal.R.id.expand_button);
        mWorkProfileImage = mView.findViewById(com.android.internal.R.id.profile_badge);
        mNotificationHeader = mView.findViewById(com.android.internal.R.id.notification_header);
        mCameraIcon = mView.findViewById(com.android.internal.R.id.camera);
        mMicIcon = mView.findViewById(com.android.internal.R.id.mic);
        mOverlayIcon = mView.findViewById(com.android.internal.R.id.overlay);
        mAppOps = mView.findViewById(com.android.internal.R.id.app_ops);
        mAudiblyAlertedIcon = mView.findViewById(com.android.internal.R.id.alerted_icon);
        if (mNotificationHeader != null) {
            mNotificationHeader.setShowExpandButtonAtEnd(mShowExpandButtonAtEnd);
            mColor = mNotificationHeader.getOriginalIconColor();
        }
    }

    private void addAppOpsOnClickListener(ExpandableNotificationRow row) {
        View.OnClickListener listener = row.getAppOpsOnClickListener();
        if (mNotificationHeader != null) {
            mNotificationHeader.setAppOpsOnClickListener(listener);
        }
        if (mAppOps != null) {
            mAppOps.setOnClickListener(listener);
        }
    }

    /**
     * Shows or hides 'app op in use' icons based on app usage.
     */
    @Override
    public void showAppOpsIcons(ArraySet<Integer> appOps) {
        if (appOps == null) {
            return;
        }
        if (mOverlayIcon != null) {
            mOverlayIcon.setVisibility(appOps.contains(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)
                    ? View.VISIBLE : View.GONE);
        }
        if (mCameraIcon != null) {
            mCameraIcon.setVisibility(appOps.contains(AppOpsManager.OP_CAMERA)
                    ? View.VISIBLE : View.GONE);
        }
        if (mMicIcon != null) {
            mMicIcon.setVisibility(appOps.contains(AppOpsManager.OP_RECORD_AUDIO)
                    ? View.VISIBLE : View.GONE);
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

    public void applyConversationSkin() {
        if (mAppNameText != null) {
            mAppNameText.setTextAppearance(
                    com.android.internal.R.style
                            .TextAppearance_DeviceDefault_Notification_Conversation_AppName);
            ViewGroup.MarginLayoutParams layoutParams =
                    (ViewGroup.MarginLayoutParams) mAppNameText.getLayoutParams();
            layoutParams.setMarginStart(0);
        }
        if (mIconContainer != null) {
            ViewGroup.MarginLayoutParams layoutParams =
                    (ViewGroup.MarginLayoutParams) mIconContainer.getLayoutParams();
            layoutParams.width =
                    mIconContainer.getContext().getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.conversation_content_start);
            final int marginStart =
                    mIconContainer.getContext().getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.notification_content_margin_start);
            layoutParams.setMarginStart(marginStart * -1);
        }
        if (mIcon != null) {
            ViewGroup.MarginLayoutParams layoutParams =
                    (ViewGroup.MarginLayoutParams) mIcon.getLayoutParams();
            layoutParams.setMarginEnd(0);
        }
    }

    public void clearConversationSkin() {
        if (mAppNameText != null) {
            final int textAppearance = Utils.getThemeAttr(
                    mAppNameText.getContext(),
                    com.android.internal.R.attr.notificationHeaderTextAppearance,
                    com.android.internal.R.style.TextAppearance_DeviceDefault_Notification_Info);
            mAppNameText.setTextAppearance(textAppearance);
            ViewGroup.MarginLayoutParams layoutParams =
                    (ViewGroup.MarginLayoutParams) mAppNameText.getLayoutParams();
            final int marginStart = mAppNameText.getContext().getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.notification_header_app_name_margin_start);
            layoutParams.setMarginStart(marginStart);
        }
        if (mIconContainer != null) {
            ViewGroup.MarginLayoutParams layoutParams =
                    (ViewGroup.MarginLayoutParams) mIconContainer.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            layoutParams.setMarginStart(0);
        }
        if (mIcon != null) {
            ViewGroup.MarginLayoutParams layoutParams =
                    (ViewGroup.MarginLayoutParams) mIcon.getLayoutParams();
            final int marginEnd = mIcon.getContext().getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.notification_header_icon_margin_end);
            layoutParams.setMarginEnd(marginEnd);
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
            } else if (child instanceof ViewGroup){
                ViewGroup group = (ViewGroup) child;
                for (int i = 0; i < group.getChildCount(); i++) {
                    stack.push(group.getChildAt(i));
                }
            }
        }
    }

    protected void updateTransformedTypes() {
        mTransformationHelper.reset();
        mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_ICON,
                mIcon);
        mTransformationHelper.addViewTransformingToSimilar(mWorkProfileImage);
        if (mIsLowPriority && mHeaderText != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_TITLE,
                    mHeaderText);
        }
        if (mCameraIcon != null) {
            mTransformationHelper.addViewTransformingToSimilar(mCameraIcon);
        }
        if (mMicIcon != null) {
            mTransformationHelper.addViewTransformingToSimilar(mMicIcon);
        }
        if (mOverlayIcon != null) {
            mTransformationHelper.addViewTransformingToSimilar(mOverlayIcon);
        }
        if (mAudiblyAlertedIcon != null) {
            mTransformationHelper.addViewTransformingToSimilar(mAudiblyAlertedIcon);
        }
    }

    @Override
    public void updateExpandability(boolean expandable, View.OnClickListener onClickListener) {
        mExpandButton.setVisibility(expandable ? View.VISIBLE : View.GONE);
        if (mNotificationHeader != null) {
            mNotificationHeader.setOnClickListener(expandable ? onClickListener : null);
        }
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
    public int getOriginalIconColor() {
        return mIcon.getOriginalIconColor();
    }

    @Override
    public View getShelfTransformationTarget() {
        return mIcon;
    }

    @Override
    public void setShelfIconVisible(boolean visible) {
        super.setShelfIconVisible(visible);
        mIcon.setForceHidden(visible);
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
}
