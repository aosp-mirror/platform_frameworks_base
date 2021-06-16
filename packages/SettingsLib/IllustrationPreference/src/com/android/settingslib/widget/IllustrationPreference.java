/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.airbnb.lottie.LottieAnimationView;

/**
 * IllustrationPreference is a preference that can play lottie format animation
 */
public class IllustrationPreference extends Preference {

    static final String TAG = "IllustrationPreference";

    private int mAnimationId;
    private boolean mIsAutoScale;
    private LottieAnimationView mIllustrationView;
    private View mMiddleGroundView;
    private FrameLayout mMiddleGroundLayout;

    public IllustrationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public IllustrationPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    public IllustrationPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (mAnimationId == 0) {
            Log.w(TAG, "Invalid illustration resource id.");
            return;
        }
        mMiddleGroundLayout = (FrameLayout) holder.findViewById(R.id.middleground_layout);
        mIllustrationView = (LottieAnimationView) holder.findViewById(R.id.lottie_view);
        mIllustrationView.setAnimation(mAnimationId);
        mIllustrationView.loop(true);
        ColorUtils.applyDynamicColors(getContext(), mIllustrationView);
        mIllustrationView.playAnimation();
        if (mIsAutoScale) {
            enableAnimationAutoScale(mIsAutoScale);
        }
        if (mMiddleGroundView != null) {
            enableMiddleGroundView();
        }
    }

    @VisibleForTesting
    boolean isAnimating() {
        return mIllustrationView.isAnimating();
    }

    /**
     * Set the middle ground view to preference. The user
     * can overlay a view on top of the animation.
     */
    public void setMiddleGroundView(View view) {
        mMiddleGroundView = view;
        if (mMiddleGroundLayout == null) {
            return;
        }
        enableMiddleGroundView();
    }

    /**
     * Remove the middle ground view of preference.
     */
    public void removeMiddleGroundView() {
        if (mMiddleGroundLayout == null) {
            return;
        }
        mMiddleGroundLayout.removeAllViews();
        mMiddleGroundLayout.setVisibility(View.GONE);
    }

    /**
     * Enables the auto scale feature of animation view.
     */
    public void enableAnimationAutoScale(boolean enable) {
        mIsAutoScale = enable;
        if (mIllustrationView == null) {
            return;
        }
        mIllustrationView.setScaleType(
                mIsAutoScale ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.CENTER_INSIDE);
    }

    private void enableMiddleGroundView() {
        mMiddleGroundLayout.removeAllViews();
        mMiddleGroundLayout.addView(mMiddleGroundView);
        mMiddleGroundLayout.setVisibility(View.VISIBLE);
    }

    private void init(Context context, AttributeSet attrs) {
        setLayoutResource(R.layout.illustration_preference);

        mIsAutoScale = false;
        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(attrs,
                    R.styleable.LottieAnimationView, 0 /*defStyleAttr*/, 0 /*defStyleRes*/);
            mAnimationId = a.getResourceId(R.styleable.LottieAnimationView_lottie_rawRes, 0);
            a.recycle();
        }
    }
}
