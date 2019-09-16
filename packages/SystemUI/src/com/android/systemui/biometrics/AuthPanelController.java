/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.systemui.biometrics;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.android.systemui.R;

/**
 * Controls the back panel and its animations for the BiometricPrompt UI.
 */
public class AuthPanelController extends ViewOutlineProvider {

    private static final String TAG = "BiometricPrompt/AuthPanelController";
    private static final boolean DEBUG = true;

    private final Context mContext;
    private final View mPanelView;
    private final float mCornerRadius;
    private final int mBiometricMargin;

    private boolean mUseFullScreen;

    private int mContainerWidth;
    private int mContainerHeight;

    private int mContentWidth;
    private int mContentHeight;

    @Override
    public void getOutline(View view, Outline outline) {
        final int left = (mContainerWidth - mContentWidth) / 2;
        final int right = mContainerWidth - left;

        final int margin = mUseFullScreen ? 0 : mBiometricMargin;
        final float cornerRadius = mUseFullScreen ? 0 : mCornerRadius;

        final int top = mContentHeight < mContainerHeight
                ? mContainerHeight - mContentHeight - margin
                : margin;
        final int bottom = mContainerHeight - margin;
        outline.setRoundRect(left, top, right, bottom, cornerRadius);
    }

    public void setContainerDimensions(int containerWidth, int containerHeight) {
        if (DEBUG) {
            Log.v(TAG, "Container Width: " + containerWidth + " Height: " + containerHeight);
        }
        mContainerWidth = containerWidth;
        mContainerHeight = containerHeight;
    }

    public void setUseFullScreen(boolean fullScreen) {
        mUseFullScreen = fullScreen;
    }

    public void updateForContentDimensions(int contentWidth, int contentHeight, boolean animate) {
        if (DEBUG) {
            Log.v(TAG, "Content Width: " + contentWidth
                    + " Height: " + contentHeight
                    + " Animate: " + animate);
        }

        if (mContainerWidth == 0 || mContainerHeight == 0) {
            Log.w(TAG, "Not done measuring yet");
            return;
        }

        if (animate) {
            ValueAnimator heightAnimator = ValueAnimator.ofInt(mContentHeight, contentHeight);
            heightAnimator.addUpdateListener((animation) -> {
                mContentHeight = (int) animation.getAnimatedValue();
                mPanelView.invalidateOutline();
            });
            heightAnimator.start();

            ValueAnimator widthAnimator = ValueAnimator.ofInt(mContentWidth, contentWidth);
            widthAnimator.addUpdateListener((animation) -> {
                mContentWidth = (int) animation.getAnimatedValue();
            });

            AnimatorSet as = new AnimatorSet();
            as.setDuration(AuthDialog.ANIMATE_DURATION_MS);
            as.play(heightAnimator).with(widthAnimator);
            as.start();
        } else {
            mContentWidth = contentWidth;
            mContentHeight = contentHeight;
            mPanelView.invalidateOutline();
        }
    }

    int getContainerWidth() {
        return mContainerWidth;
    }

    int getContainerHeight() {
        return mContainerHeight;
    }

    AuthPanelController(Context context, View panelView) {
        mContext = context;
        mPanelView = panelView;
        mCornerRadius = context.getResources()
                .getDimension(R.dimen.biometric_dialog_corner_size);
        mBiometricMargin = (int) context.getResources()
                .getDimension(R.dimen.biometric_dialog_border_padding);
        mPanelView.setOutlineProvider(this);
        mPanelView.setClipToOutline(true);
    }

}
