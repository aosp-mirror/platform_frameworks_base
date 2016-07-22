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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;
import com.android.systemui.statusbar.phone.NotificationPanelView;

import java.util.Stack;

/**
 * Wraps a notification header view.
 */
public class NotificationHeaderViewWrapper extends NotificationViewWrapper {

    private final PorterDuffColorFilter mIconColorFilter = new PorterDuffColorFilter(
            0, PorterDuff.Mode.SRC_ATOP);
    private final int mIconDarkAlpha;
    private final int mIconDarkColor = 0xffffffff;
    protected final ViewInvertHelper mInvertHelper;

    protected final ViewTransformationHelper mTransformationHelper;

    protected int mColor;
    private ImageView mIcon;

    private ImageView mExpandButton;
    private NotificationHeaderView mNotificationHeader;

    protected NotificationHeaderViewWrapper(Context ctx, View view, ExpandableNotificationRow row) {
        super(view, row);
        mIconDarkAlpha = ctx.getResources().getInteger(R.integer.doze_small_icon_alpha);
        mInvertHelper = new ViewInvertHelper(ctx, NotificationPanelView.DOZE_ANIMATION_DURATION);
        mTransformationHelper = new ViewTransformationHelper();
        resolveHeaderViews();
        updateInvertHelper();
    }

    protected void resolveHeaderViews() {
        mIcon = (ImageView) mView.findViewById(com.android.internal.R.id.icon);
        mExpandButton = (ImageView) mView.findViewById(com.android.internal.R.id.expand_button);
        mColor = resolveColor(mExpandButton);
        mNotificationHeader = (NotificationHeaderView) mView.findViewById(
                com.android.internal.R.id.notification_header);
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
    public void notifyContentUpdated(StatusBarNotification notification) {
        super.notifyContentUpdated(notification);

        ArraySet<View> previousViews = mTransformationHelper.getAllTransformingViews();

        // Reinspect the notification.
        resolveHeaderViews();
        updateInvertHelper();
        updateTransformedTypes();
        addRemainingTransformTypes();
        updateCropToPaddingForImageViews();

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
        mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_HEADER,
                mNotificationHeader);
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
            if (fade) {
                if (hadColorFilter) {
                    fadeIconColorFilter(mIcon, dark, delay);
                    fadeIconAlpha(mIcon, dark, delay);
                } else {
                    fadeGrayscale(mIcon, dark, delay);
                }
            } else {
                if (hadColorFilter) {
                    updateIconColorFilter(mIcon, dark);
                    updateIconAlpha(mIcon, dark);
                } else {
                    updateGrayscale(mIcon, dark);
                }
            }
        }
    }

    private void fadeIconColorFilter(final ImageView target, boolean dark, long delay) {
        startIntensityAnimation(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                updateIconColorFilter(target, (Float) animation.getAnimatedValue());
            }
        }, dark, delay, null /* listener */);
    }

    private void fadeIconAlpha(final ImageView target, boolean dark, long delay) {
        startIntensityAnimation(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                target.setImageAlpha((int) (255 * (1f - t) + mIconDarkAlpha * t));
            }
        }, dark, delay, null /* listener */);
    }

    protected void fadeGrayscale(final ImageView target, final boolean dark, long delay) {
        startIntensityAnimation(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                updateGrayscaleMatrix((float) animation.getAnimatedValue());
                target.setColorFilter(new ColorMatrixColorFilter(mGrayscaleColorMatrix));
            }
        }, dark, delay, new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!dark) {
                    target.setColorFilter(null);
                }
            }
        });
    }

    private void updateIconColorFilter(ImageView target, boolean dark) {
        updateIconColorFilter(target, dark ? 1f : 0f);
    }

    private void updateIconColorFilter(ImageView target, float intensity) {
        int color = interpolateColor(mColor, mIconDarkColor, intensity);
        mIconColorFilter.setColor(color);
        Drawable iconDrawable = target.getDrawable();

        // Also, the notification might have been modified during the animation, so background
        // might be null here.
        if (iconDrawable != null) {
            Drawable d = iconDrawable.mutate();
            // DrawableContainer ignores the color filter if it's already set, so clear it first to
            // get it set and invalidated properly.
            d.setColorFilter(null);
            d.setColorFilter(mIconColorFilter);
        }
    }

    private void updateIconAlpha(ImageView target, boolean dark) {
        target.setImageAlpha(dark ? mIconDarkAlpha : 255);
    }

    protected void updateGrayscale(ImageView target, boolean dark) {
        if (dark) {
            updateGrayscaleMatrix(1f);
            target.setColorFilter(new ColorMatrixColorFilter(mGrayscaleColorMatrix));
        } else {
            target.setColorFilter(null);
        }
    }

    @Override
    public void updateExpandability(boolean expandable, View.OnClickListener onClickListener) {
        mExpandButton.setVisibility(expandable ? View.VISIBLE : View.GONE);
        mNotificationHeader.setOnClickListener(expandable ? onClickListener : null);
    }

    private static int interpolateColor(int source, int target, float t) {
        int aSource = Color.alpha(source);
        int rSource = Color.red(source);
        int gSource = Color.green(source);
        int bSource = Color.blue(source);
        int aTarget = Color.alpha(target);
        int rTarget = Color.red(target);
        int gTarget = Color.green(target);
        int bTarget = Color.blue(target);
        return Color.argb(
                (int) (aSource * (1f - t) + aTarget * t),
                (int) (rSource * (1f - t) + rTarget * t),
                (int) (gSource * (1f - t) + gTarget * t),
                (int) (bSource * (1f - t) + bTarget * t));
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
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        mTransformationHelper.setVisible(visible);
    }
}
