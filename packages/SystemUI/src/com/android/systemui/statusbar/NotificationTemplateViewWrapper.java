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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.systemui.statusbar.notification.ImageTransformState;
import com.android.systemui.statusbar.notification.TransformState;

import java.util.ArrayList;

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
    }

    private void resolveTemplateViews(StatusBarNotification notification) {
        mPicture = (ImageView) mView.findViewById(com.android.internal.R.id.right_icon);
        if (notification != null) {
            mPicture.setTag(ImageTransformState.ICON_TAG,
                    notification.getNotification().getLargeIcon());
        }
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
        // Reinspect the notification.
        resolveTemplateViews(notification);
        super.notifyContentUpdated(notification);
        addRemainingTransformTypes();
    }

    /**
     * Adds the remaining TransformTypes to the TransformHelper. This is done to make sure that each
     * child is faded automatically and doesn't have to be manually added.
     * The keys used for the views are the ids.
     */
    private void addRemainingTransformTypes() {

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
            mTransformationHelper.addTransformedView(TRANSFORMING_VIEW_TITLE, mTitle);
        }
        if (mText != null) {
            mTransformationHelper.addTransformedView(TRANSFORMING_VIEW_TEXT, mText);
        }
        if (mPicture != null) {
            mTransformationHelper.addTransformedView(TRANSFORMING_VIEW_IMAGE, mPicture);
        }
        if (mProgressBar != null) {
            mTransformationHelper.addTransformedView(TRANSFORMING_VIEW_PROGRESS, mProgressBar);
        }
    }

    @Override
    public void setDark(boolean dark, boolean fade, long delay) {
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
