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

package com.android.systemui.statusbar.notification;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;

/**
 * Wraps a notification view inflated from a template.
 */
public class NotificationTemplateViewWrapper extends NotificationHeaderViewWrapper {

    private static final int mDarkProgressTint = 0xffffffff;

    protected ImageView mPicture;
    private ProgressBar mProgressBar;
    private TextView mTitle;
    private TextView mText;
    private View mActionsContainer;

    private int mContentHeight;
    private int mMinHeightHint;

    protected NotificationTemplateViewWrapper(Context ctx, View view, ExpandableNotificationRow row) {
        super(ctx, view, row);
        mTransformationHelper.setCustomTransformation(
                new ViewTransformationHelper.CustomTransformation() {
                    @Override
                    public boolean transformTo(TransformState ownState,
                            TransformableView notification, final float transformationAmount) {
                        if (!(notification instanceof HybridNotificationView)) {
                            return false;
                        }
                        TransformState otherState = notification.getCurrentState(
                                TRANSFORMING_VIEW_TITLE);
                        final View text = ownState.getTransformedView();
                        CrossFadeHelper.fadeOut(text, transformationAmount);
                        if (otherState != null) {
                            ownState.transformViewVerticalTo(otherState, this,
                                    transformationAmount);
                            otherState.recycle();
                        }
                        return true;
                    }

                    @Override
                    public boolean customTransformTarget(TransformState ownState,
                            TransformState otherState) {
                        float endY = getTransformationY(ownState, otherState);
                        ownState.setTransformationEndY(endY);
                        return true;
                    }

                    @Override
                    public boolean transformFrom(TransformState ownState,
                            TransformableView notification, float transformationAmount) {
                        if (!(notification instanceof HybridNotificationView)) {
                            return false;
                        }
                        TransformState otherState = notification.getCurrentState(
                                TRANSFORMING_VIEW_TITLE);
                        final View text = ownState.getTransformedView();
                        CrossFadeHelper.fadeIn(text, transformationAmount);
                        if (otherState != null) {
                            ownState.transformViewVerticalFrom(otherState, this,
                                    transformationAmount);
                            otherState.recycle();
                        }
                        return true;
                    }

                    @Override
                    public boolean initTransformation(TransformState ownState,
                            TransformState otherState) {
                        float startY = getTransformationY(ownState, otherState);
                        ownState.setTransformationStartY(startY);
                        return true;
                    }

                    private float getTransformationY(TransformState ownState,
                            TransformState otherState) {
                        int[] otherStablePosition = otherState.getLaidOutLocationOnScreen();
                        int[] ownStablePosition = ownState.getLaidOutLocationOnScreen();
                        return (otherStablePosition[1]
                                + otherState.getTransformedView().getHeight()
                                - ownStablePosition[1]) * 0.33f;
                    }

                }, TRANSFORMING_VIEW_TEXT);
    }

    private void resolveTemplateViews(StatusBarNotification notification) {
        mPicture = (ImageView) mView.findViewById(com.android.internal.R.id.right_icon);
        mPicture.setTag(ImageTransformState.ICON_TAG,
                notification.getNotification().getLargeIcon());
        mTitle = (TextView) mView.findViewById(com.android.internal.R.id.title);
        mText = (TextView) mView.findViewById(com.android.internal.R.id.text);
        final View progress = mView.findViewById(com.android.internal.R.id.progress);
        if (progress instanceof ProgressBar) {
            mProgressBar = (ProgressBar) progress;
        } else {
            // It's still a viewstub
            mProgressBar = null;
        }
        mActionsContainer = mView.findViewById(com.android.internal.R.id.actions_container);
    }

    @Override
    public void notifyContentUpdated(StatusBarNotification notification) {
        // Reinspect the notification. Before the super call, because the super call also updates
        // the transformation types and we need to have our values set by then.
        resolveTemplateViews(notification);
        super.notifyContentUpdated(notification);
    }

    @Override
    protected void updateInvertHelper() {
        super.updateInvertHelper();
        View mainColumn = mView.findViewById(com.android.internal.R.id.notification_main_column);
        if (mainColumn != null) {
            mInvertHelper.addTarget(mainColumn);
        }
    }

    @Override
    protected void updateTransformedTypes() {
        // This also clears the existing types
        super.updateTransformedTypes();
        if (mTitle != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_TITLE, mTitle);
        }
        if (mText != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_TEXT, mText);
        }
        if (mPicture != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_IMAGE, mPicture);
        }
        if (mProgressBar != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_PROGRESS, mProgressBar);
        }
    }

    @Override
    public void setDark(boolean dark, boolean fade, long delay) {
        if (dark == mDark && mDarkInitialized) {
            return;
        }
        super.setDark(dark, fade, delay);
        setPictureGrayscale(dark, fade, delay);
        setProgressBarDark(dark, fade, delay);
    }

    private void setProgressBarDark(boolean dark, boolean fade, long delay) {
        if (mProgressBar != null) {
            if (fade) {
                fadeProgressDark(mProgressBar, dark, delay);
            } else {
                updateProgressDark(mProgressBar, dark);
            }
        }
    }

    private void fadeProgressDark(final ProgressBar target, final boolean dark, long delay) {
        startIntensityAnimation(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                updateProgressDark(target, t);
            }
        }, dark, delay, null /* listener */);
    }

    private void updateProgressDark(ProgressBar target, float intensity) {
        int color = interpolateColor(mColor, mDarkProgressTint, intensity);
        target.getIndeterminateDrawable().mutate().setTint(color);
        target.getProgressDrawable().mutate().setTint(color);
    }

    private void updateProgressDark(ProgressBar target, boolean dark) {
        updateProgressDark(target, dark ? 1f : 0f);
    }

    protected void setPictureGrayscale(boolean grayscale, boolean fade, long delay) {
        if (mPicture != null) {
            if (fade) {
                fadeGrayscale(mPicture, grayscale, delay);
            } else {
                updateGrayscale(mPicture, grayscale);
            }
        }
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
    public void setContentHeight(int contentHeight, int minHeightHint) {
        super.setContentHeight(contentHeight, minHeightHint);

        mContentHeight = contentHeight;
        mMinHeightHint = minHeightHint;
        updateActionOffset();
    }

    private void updateActionOffset() {
        if (mActionsContainer != null) {
            // We should never push the actions higher than they are in the headsup view.
            int constrainedContentHeight = Math.max(mContentHeight, mMinHeightHint);
            mActionsContainer.setTranslationY(constrainedContentHeight - mView.getHeight());
        }
    }
}
