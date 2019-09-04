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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.android.systemui.R;
import com.android.systemui.biometrics.AuthDialog;

/**
 * Controls the back panel and its animations for the BiometricPrompt UI.
 */
public class AuthPanelController extends ViewOutlineProvider {

    private static final String TAG = "BiometricPrompt/AuthPanelController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final View mPanelView;
    private final float mCornerRadius;
    private final int mBiometricMargin;

    private int mContainerWidth;
    private int mContainerHeight;

    private int mContentWidth;
    private int mContentHeight;

    @Override
    public void getOutline(View view, Outline outline) {
        final int left = (mContainerWidth - mContentWidth) / 2;
        final int right = mContainerWidth - left;
        final int top = mContentHeight < mContainerHeight
                ? mContainerHeight - mContentHeight - mBiometricMargin
                : mBiometricMargin;
        final int bottom = mContainerHeight - mBiometricMargin;
        outline.setRoundRect(left, top, right, bottom, mCornerRadius);
    }

    public void setContainerDimensions(int containerWidth, int containerHeight) {
        if (DEBUG) {
            Log.v(TAG, "Container Width: " + containerWidth + " Height: " + containerHeight);
        }
        mContainerWidth = containerWidth;
        mContainerHeight = containerHeight;
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
            heightAnimator.setDuration(AuthDialog.ANIMATE_DURATION_MS);
            heightAnimator.addUpdateListener((animation) -> {
                mContentHeight = (int) animation.getAnimatedValue();
                mPanelView.invalidateOutline();
            });
            heightAnimator.start();
        } else {
            mContentWidth = contentWidth;
            mContentHeight = contentHeight;
            mPanelView.invalidateOutline();
        }
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
