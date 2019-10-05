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
import android.graphics.Color;
import android.graphics.Outline;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;

import com.android.systemui.R;

/**
 * Controls the back panel and its animations for the BiometricPrompt UI.
 */
public class AuthPanelController extends ViewOutlineProvider {

    private static final String TAG = "BiometricPrompt/AuthPanelController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final View mPanelView;
    private final boolean mIsManagedProfile;

    private boolean mUseFullScreen;

    private int mContainerWidth;
    private int mContainerHeight;

    private int mContentWidth;
    private int mContentHeight;

    private float mCornerRadius;
    private int mMargin;

    @Override
    public void getOutline(View view, Outline outline) {
        final int left = (mContainerWidth - mContentWidth) / 2;
        final int right = mContainerWidth - left;

        // If the content fits within the container, shrink the height to wrap the content.
        // Otherwise, set the outline to be the display size minus the margin - the content within
        // is scrollable.
        final int top = mContentHeight < mContainerHeight
                ? mContainerHeight - mContentHeight - mMargin
                : mMargin;

        // TODO(b/139954942) Likely don't need to "+1" after we resolve the navbar styling.
        final int bottom = mContainerHeight - mMargin + 1;
        outline.setRoundRect(left, top, right, bottom, mCornerRadius);
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

    public ValueAnimator getTranslationAnimator(float relativeTranslationY) {
        final ValueAnimator animator = ValueAnimator.ofFloat(
                mPanelView.getY(), mPanelView.getY() - relativeTranslationY);
        animator.addUpdateListener(animation -> {
            final float translation = (float) animation.getAnimatedValue();
            mPanelView.setTranslationY(translation);
        });
        return animator;
    }

    public ValueAnimator getAlphaAnimator(float alpha) {
        final ValueAnimator animator = ValueAnimator.ofFloat(mPanelView.getAlpha(), alpha);
        animator.addUpdateListener(animation -> {
            mPanelView.setAlpha((float) animation.getAnimatedValue());
        });
        return animator;
    }

    public void updateForContentDimensions(int contentWidth, int contentHeight,
            int animateDurationMs) {
        if (DEBUG) {
            Log.v(TAG, "Content Width: " + contentWidth
                    + " Height: " + contentHeight
                    + " Animate: " + animateDurationMs);
        }

        if (mContainerWidth == 0 || mContainerHeight == 0) {
            Log.w(TAG, "Not done measuring yet");
            return;
        }

        final int margin = mUseFullScreen ? 0 : (int) mContext.getResources()
                .getDimension(R.dimen.biometric_dialog_border_padding);
        final float cornerRadius = mUseFullScreen ? 0 : mContext.getResources()
                .getDimension(R.dimen.biometric_dialog_corner_size);

        // When going to full-screen for managed profiles, fade away so the managed profile
        // background behind this view becomes visible.
        final boolean shouldFadeAway = mUseFullScreen && mIsManagedProfile;
        final int alpha = shouldFadeAway ? 0 : 255;
        final float elevation = shouldFadeAway ? 0 :
                mContext.getResources().getDimension(R.dimen.biometric_dialog_elevation);

        if (animateDurationMs > 0) {
            // Animate margin
            ValueAnimator marginAnimator = ValueAnimator.ofInt(mMargin, margin);
            marginAnimator.addUpdateListener((animation) -> {
                mMargin = (int) animation.getAnimatedValue();
            });

            // Animate corners
            ValueAnimator cornerAnimator = ValueAnimator.ofFloat(mCornerRadius, cornerRadius);
            cornerAnimator.addUpdateListener((animation) -> {
                mCornerRadius = (float) animation.getAnimatedValue();
            });

            // Animate height
            ValueAnimator heightAnimator = ValueAnimator.ofInt(mContentHeight, contentHeight);
            heightAnimator.addUpdateListener((animation) -> {
                mContentHeight = (int) animation.getAnimatedValue();
                mPanelView.invalidateOutline();
            });
            heightAnimator.start();

            // Animate width
            ValueAnimator widthAnimator = ValueAnimator.ofInt(mContentWidth, contentWidth);
            widthAnimator.addUpdateListener((animation) -> {
                mContentWidth = (int) animation.getAnimatedValue();
            });

            // Animate background
            ValueAnimator alphaAnimator = ValueAnimator.ofInt(
                    mPanelView.getBackground().getAlpha(), alpha);
            alphaAnimator.addUpdateListener((animation) -> {
                if (shouldFadeAway) {
                    mPanelView.getBackground().setAlpha((int) animation.getAnimatedValue());
                }
            });

            // Play together
            AnimatorSet as = new AnimatorSet();
            as.setDuration(animateDurationMs);
            as.setInterpolator(new AccelerateDecelerateInterpolator());
            as.playTogether(cornerAnimator, widthAnimator, marginAnimator, alphaAnimator);
            as.start();

        } else {
            mMargin = margin;
            mCornerRadius = cornerRadius;
            mContentWidth = contentWidth;
            mContentHeight = contentHeight;
            mPanelView.getBackground().setAlpha(alpha);
            mPanelView.invalidateOutline();
        }
    }

    int getContainerWidth() {
        return mContainerWidth;
    }

    int getContainerHeight() {
        return mContainerHeight;
    }

    AuthPanelController(Context context, View panelView, boolean isManagedProfile) {
        mContext = context;
        mPanelView = panelView;
        mIsManagedProfile = isManagedProfile;
        mCornerRadius = context.getResources()
                .getDimension(R.dimen.biometric_dialog_corner_size);
        mMargin = (int) context.getResources()
                .getDimension(R.dimen.biometric_dialog_border_padding);
        mPanelView.setOutlineProvider(this);
        mPanelView.setClipToOutline(true);
    }

}
