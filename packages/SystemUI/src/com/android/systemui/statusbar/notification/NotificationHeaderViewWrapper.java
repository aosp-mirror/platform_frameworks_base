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

package com.android.systemui.statusbar.notification;

import android.app.Notification;
import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.PorterDuffColorFilter;
import android.util.ArraySet;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.widget.NotificationExpandButton;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;
import com.android.systemui.statusbar.phone.NotificationPanelView;

import java.util.Stack;

import static com.android.systemui.statusbar.notification.TransformState.TRANSFORM_Y;

/**
 * Wraps a notification header view.
 */
public class NotificationHeaderViewWrapper extends NotificationViewWrapper {

    private static final Interpolator LOW_PRIORITY_HEADER_CLOSE
            = new PathInterpolator(0.4f, 0f, 0.7f, 1f);

    protected final ViewInvertHelper mInvertHelper;
    protected final ViewTransformationHelper mTransformationHelper;

    protected int mColor;
    private ImageView mIcon;

    private NotificationExpandButton mExpandButton;
    private NotificationHeaderView mNotificationHeader;
    private TextView mHeaderText;
    private ImageView mWorkProfileImage;
    private boolean mIsLowPriority;
    private boolean mTransformLowPriorityTitle;
    private boolean mShowExpandButtonAtEnd;

    protected NotificationHeaderViewWrapper(Context ctx, View view, ExpandableNotificationRow row) {
        super(ctx, view, row);
        mShowExpandButtonAtEnd = ctx.getResources().getBoolean(
                R.bool.config_showNotificationExpandButtonAtEnd);
        mInvertHelper = new ViewInvertHelper(ctx, NotificationPanelView.DOZE_ANIMATION_DURATION);
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
        updateInvertHelper();
    }

    @Override
    protected NotificationDozeHelper createDozer(Context ctx) {
        return new NotificationIconDozeHelper(ctx);
    }

    @Override
    protected NotificationIconDozeHelper getDozer() {
        return (NotificationIconDozeHelper) super.getDozer();
    }

    protected void resolveHeaderViews() {
        mIcon = mView.findViewById(com.android.internal.R.id.icon);
        mHeaderText = mView.findViewById(com.android.internal.R.id.header_text);
        mExpandButton = mView.findViewById(com.android.internal.R.id.expand_button);
        mWorkProfileImage = mView.findViewById(com.android.internal.R.id.profile_badge);
        mColor = resolveColor(mExpandButton);
        mNotificationHeader = mView.findViewById(com.android.internal.R.id.notification_header);
        mNotificationHeader.setShowExpandButtonAtEnd(mShowExpandButtonAtEnd);
        getDozer().setColor(mColor);
    }

    private int resolveColor(ImageView icon) {
        if (icon != null && icon.getDrawable() != null) {
            ColorFilter filter = icon.getDrawable().getColorFilter();
            if (filter instanceof PorterDuffColorFilter) {
                return ((PorterDuffColorFilter) filter).getColor();
            }
        }
        return 0;
    }

    @Override
    public void onContentUpdated(ExpandableNotificationRow row) {
        super.onContentUpdated(row);
        mIsLowPriority = row.isLowPriority();
        mTransformLowPriorityTitle = !row.isChildInGroup() && !row.isSummaryWithChildren();
        ArraySet<View> previousViews = mTransformationHelper.getAllTransformingViews();

        // Reinspect the notification.
        resolveHeaderViews();
        updateInvertHelper();
        updateTransformedTypes();
        addRemainingTransformTypes();
        updateCropToPaddingForImageViews();
        Notification notification = row.getStatusBarNotification().getNotification();
        mIcon.setTag(ImageTransformState.ICON_TAG, notification.getSmallIcon());
        // The work profile image is always the same lets just set the icon tag for it not to
        // animate
        mWorkProfileImage.setTag(ImageTransformState.ICON_TAG, notification.getSmallIcon());

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
            if (child instanceof ImageView) {
                ((ImageView) child).setCropToPadding(true);
            } else if (child instanceof ViewGroup){
                ViewGroup group = (ViewGroup) child;
                for (int i = 0; i < group.getChildCount(); i++) {
                    stack.push(group.getChildAt(i));
                }
            }
        }
    }

    protected void updateInvertHelper() {
        mInvertHelper.clearTargets();
        for (int i = 0; i < mNotificationHeader.getChildCount(); i++) {
            View child = mNotificationHeader.getChildAt(i);
            if (child != mIcon) {
                mInvertHelper.addTarget(child);
            }
        }
    }

    protected void updateTransformedTypes() {
        mTransformationHelper.reset();
        mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_ICON, mIcon);
        if (mIsLowPriority) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_TITLE,
                    mHeaderText);
        }
    }

    @Override
    public void setDark(boolean dark, boolean fade, long delay) {
        if (dark == mDark && mDarkInitialized) {
            return;
        }
        super.setDark(dark, fade, delay);
        if (fade) {
            mInvertHelper.fade(dark, delay);
        } else {
            mInvertHelper.update(dark);
        }
        if (mIcon != null && !mRow.isChildInGroup()) {
            // We don't update the color for children views / their icon is invisible anyway.
            // It also may lead to bugs where the icon isn't correctly greyed out.
            boolean hadColorFilter = mNotificationHeader.getOriginalIconColor()
                    != NotificationHeaderView.NO_COLOR;

            getDozer().setImageDark(mIcon, dark, fade, delay, !hadColorFilter);
        }
    }

    @Override
    public void updateExpandability(boolean expandable, View.OnClickListener onClickListener) {
        mExpandButton.setVisibility(expandable ? View.VISIBLE : View.GONE);
        mNotificationHeader.setOnClickListener(expandable ? onClickListener : null);
    }

    @Override
    public NotificationHeaderView getNotificationHeader() {
        return mNotificationHeader;
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
