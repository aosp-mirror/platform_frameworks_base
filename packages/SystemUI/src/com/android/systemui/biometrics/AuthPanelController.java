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
import android.annotation.IntDef;
import android.content.Context;
import android.graphics.Outline;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;

import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Controls the back panel and its animations for the BiometricPrompt UI.
 */
public class AuthPanelController extends ViewOutlineProvider {
    public static final int POSITION_BOTTOM = 1;
    public static final int POSITION_LEFT = 2;
    public static final int POSITION_RIGHT = 3;

    @IntDef({POSITION_BOTTOM, POSITION_LEFT, POSITION_RIGHT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Position {}

    private static final String TAG = "BiometricPrompt/AuthPanelController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final View mPanelView;

    @Position private int mPosition = POSITION_BOTTOM;
    private boolean mUseFullScreen;

    private int mContainerWidth;
    private int mContainerHeight;

    private int mContentWidth;
    private int mContentHeight;

    private float mCornerRadius;
    private int mMargin;

    @Override
    public void getOutline(View view, Outline outline) {
        final int left = getLeftBound(mPosition);
        final int right = left + mContentWidth;

        // If the content fits in the container, shrink the height to wrap it. Otherwise, expand to
        // fill the display (minus the margin), since the content is scrollable.
        final int top = getTopBound(mPosition);
        final int bottom = Math.min(top + mContentHeight, mContainerHeight - mMargin);

        outline.setRoundRect(left, top, right, bottom, mCornerRadius);
    }

    private int getLeftBound(@Position int position) {
        switch (position) {
            case POSITION_BOTTOM:
                return (mContainerWidth - mContentWidth) / 2;
            case POSITION_LEFT:
                return mMargin;
            case POSITION_RIGHT:
                return mContainerWidth - mContentWidth - mMargin;
            default:
                Log.e(TAG, "Unrecognized position: " + position);
                return getLeftBound(POSITION_BOTTOM);
        }
    }

    private int getTopBound(@Position int position) {
        switch (position) {
            case POSITION_BOTTOM:
                return Math.max(mContainerHeight - mContentHeight - mMargin, mMargin);
            case POSITION_LEFT:
            case POSITION_RIGHT:
                return Math.max((mContainerHeight - mContentHeight) / 2, mMargin);
            default:
                Log.e(TAG, "Unrecognized position: " + position);
                return getTopBound(POSITION_BOTTOM);
        }
    }

    public void setContainerDimensions(int containerWidth, int containerHeight) {
        if (DEBUG) {
            Log.v(TAG, "Container Width: " + containerWidth + " Height: " + containerHeight);
        }
        mContainerWidth = containerWidth;
        mContainerHeight = containerHeight;
    }

    public void setPosition(@Position int position) {
        mPosition = position;
    }

    public void setUseFullScreen(boolean fullScreen) {
        mUseFullScreen = fullScreen;
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

            // Animate width
            ValueAnimator widthAnimator = ValueAnimator.ofInt(mContentWidth, contentWidth);
            widthAnimator.addUpdateListener((animation) -> {
                mContentWidth = (int) animation.getAnimatedValue();
            });

            // Play together
            AnimatorSet as = new AnimatorSet();
            as.setDuration(animateDurationMs);
            as.setInterpolator(new AccelerateDecelerateInterpolator());
            as.playTogether(cornerAnimator, heightAnimator, widthAnimator, marginAnimator);
            as.start();

        } else {
            mMargin = margin;
            mCornerRadius = cornerRadius;
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
        mMargin = (int) context.getResources()
                .getDimension(R.dimen.biometric_dialog_border_padding);
        mPanelView.setOutlineProvider(this);
        mPanelView.setClipToOutline(true);
    }

}
