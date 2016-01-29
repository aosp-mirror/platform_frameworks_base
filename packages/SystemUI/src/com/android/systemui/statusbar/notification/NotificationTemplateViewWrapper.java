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

import com.android.systemui.Interpolators;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;
import com.android.systemui.statusbar.stack.StackStateAnimator;

/**
 * Wraps a notification view inflated from a template.
 */
public class NotificationTemplateViewWrapper extends NotificationHeaderViewWrapper {

    private static final int mDarkProgressTint = 0xffffffff;

    protected ImageView mPicture;
    private ProgressBar mProgressBar;
    private TextView mTitle;
    private TextView mText;

    protected NotificationTemplateViewWrapper(Context ctx, View view) {
        super(ctx, view);
        mTransformationHelper.setCustomTransformation(
                new ViewTransformationHelper.CustomTransformation() {
                    @Override
                    public boolean transformTo(TransformState ownState,
                            TransformableView notification, final Runnable endRunnable) {
                        if (!(notification instanceof HybridNotificationView)) {
                            return false;
                        }
                        TransformState otherState = notification.getCurrentState(
                                TRANSFORMING_VIEW_TITLE);
                        CrossFadeHelper.fadeOut(mText, endRunnable);
                        if (otherState != null) {
                            int[] otherStablePosition = otherState.getLaidOutLocationOnScreen();
                            int[] ownPosition = ownState.getLaidOutLocationOnScreen();
                            mText.animate()
                                    .translationY((otherStablePosition[1]
                                            + otherState.getTransformedView().getHeight()
                                            - ownPosition[1]) * 0.33f)
                                    .setDuration(
                                            StackStateAnimator.ANIMATION_DURATION_STANDARD)
                                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                                    .withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (endRunnable != null) {
                                                endRunnable.run();
                                            }
                                            TransformState.setClippingDeactivated(mText,
                                                    false);
                                        }
                                    });
                            TransformState.setClippingDeactivated(mText, true);
                            otherState.recycle();
                        }
                        return true;
                    }

                    @Override
                    public boolean transformFrom(TransformState ownState,
                            TransformableView notification) {
                        if (!(notification instanceof HybridNotificationView)) {
                            return false;
                        }
                        TransformState otherState = notification.getCurrentState(
                                TRANSFORMING_VIEW_TITLE);
                        boolean isVisible = mText.getVisibility() == View.VISIBLE;
                        CrossFadeHelper.fadeIn(mText);
                        if (otherState != null) {
                            int[] otherStablePosition = otherState.getLaidOutLocationOnScreen();
                            int[] ownStablePosition = ownState.getLaidOutLocationOnScreen();
                            if (!isVisible) {
                                mText.setTranslationY((otherStablePosition[1]
                                        + otherState.getTransformedView().getHeight()
                                        - ownStablePosition[1]) * 0.33f);
                            }
                            mText.animate()
                                    .translationY(0)
                                    .setDuration(
                                            StackStateAnimator.ANIMATION_DURATION_STANDARD)
                                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                                    .withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            TransformState.setClippingDeactivated(mText,
                                                    false);
                                        }
                                    });
                            TransformState.setClippingDeactivated(mText, true);
                            otherState.recycle();
                        }
                        return true;
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
        if (dark == mDark) {
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
}
