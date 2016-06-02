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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.support.v4.graphics.ColorUtils;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.NotificationPanelView;

/**
 * Wraps a notification containing a custom view.
 */
public class NotificationCustomViewWrapper extends NotificationViewWrapper {

    private final ViewInvertHelper mInvertHelper;
    private final Paint mGreyPaint = new Paint();
    private int mBackgroundColor = 0;
    private static final int CUSTOM_BACKGROUND_TAG = R.id.custom_background_color;
    private boolean mShouldInvertDark;
    private boolean mShowingLegacyBackground;

    protected NotificationCustomViewWrapper(View view, ExpandableNotificationRow row) {
        super(view, row);
        mInvertHelper = new ViewInvertHelper(view, NotificationPanelView.DOZE_ANIMATION_DURATION);
    }

    @Override
    public void setDark(boolean dark, boolean fade, long delay) {
        if (dark == mDark && mDarkInitialized) {
            return;
        }
        super.setDark(dark, fade, delay);
        if (!mShowingLegacyBackground && mShouldInvertDark) {
            if (fade) {
                mInvertHelper.fade(dark, delay);
            } else {
                mInvertHelper.update(dark);
            }
        } else {
            mView.setLayerType(dark ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
            if (fade) {
                fadeGrayscale(dark, delay);
            } else {
                updateGrayscale(dark);
            }
        }
    }

    protected void fadeGrayscale(final boolean dark, long delay) {
        startIntensityAnimation(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                updateGrayscaleMatrix((float) animation.getAnimatedValue());
                mGreyPaint.setColorFilter(new ColorMatrixColorFilter(mGrayscaleColorMatrix));
                mView.setLayerPaint(mGreyPaint);
            }
        }, dark, delay, new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!dark) {
                    mView.setLayerType(View.LAYER_TYPE_NONE, null);
                }
            }
        });
    }

    protected void updateGrayscale(boolean dark) {
        if (dark) {
            updateGrayscaleMatrix(1f);
            mGreyPaint.setColorFilter(
                    new ColorMatrixColorFilter(mGrayscaleColorMatrix));
            mView.setLayerPaint(mGreyPaint);
        }
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        mView.setAlpha(visible ? 1.0f : 0.0f);
    }

    @Override
    public void notifyContentUpdated(StatusBarNotification notification) {
        super.notifyContentUpdated(notification);
        Drawable background = mView.getBackground();
        mBackgroundColor = 0;
        if (background instanceof ColorDrawable) {
            mBackgroundColor = ((ColorDrawable) background).getColor();
            mView.setBackground(null);
            mView.setTag(CUSTOM_BACKGROUND_TAG, mBackgroundColor);
        } else if (mView.getTag(CUSTOM_BACKGROUND_TAG) != null) {
            mBackgroundColor = (int) mView.getTag(CUSTOM_BACKGROUND_TAG);
        }
        mShouldInvertDark = mBackgroundColor == 0 || isColorLight(mBackgroundColor);
    }

    private boolean isColorLight(int backgroundColor) {
        return Color.alpha(backgroundColor) == 0
                || ColorUtils.calculateLuminance(backgroundColor) > 0.5;
    }

    @Override
    public int getCustomBackgroundColor() {
        // Parent notifications should always use the normal background color
        return mRow.isSummaryWithChildren() ? 0 : mBackgroundColor;
    }

    @Override
    public void setShowingLegacyBackground(boolean showing) {
        super.setShowingLegacyBackground(showing);
        mShowingLegacyBackground = showing;
    }
}
