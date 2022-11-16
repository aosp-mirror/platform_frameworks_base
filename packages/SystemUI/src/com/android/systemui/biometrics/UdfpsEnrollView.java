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

package com.android.systemui.biometrics;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * View corresponding with udfps_enroll_view.xml
 */
public class UdfpsEnrollView extends UdfpsAnimationView {
    @NonNull private final UdfpsEnrollDrawable mFingerprintDrawable;
    @NonNull private final UdfpsEnrollProgressBarDrawable mFingerprintProgressDrawable;
    @NonNull private final Handler mHandler;

    @NonNull private ImageView mFingerprintView;
    @NonNull private ImageView mFingerprintProgressView;

    private LayoutParams mProgressParams;
    private float mProgressBarRadius;

    public UdfpsEnrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mFingerprintDrawable = new UdfpsEnrollDrawable(mContext, attrs);
        mFingerprintProgressDrawable = new UdfpsEnrollProgressBarDrawable(context, attrs);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void onFinishInflate() {
        mFingerprintView = findViewById(R.id.udfps_enroll_animation_fp_view);
        mFingerprintProgressView = findViewById(R.id.udfps_enroll_animation_fp_progress_view);
        mFingerprintView.setImageDrawable(mFingerprintDrawable);
        mFingerprintProgressView.setImageDrawable(mFingerprintProgressDrawable);
    }

    @Override
    void onSensorRectUpdated(RectF bounds) {
        if (mUseExpandedOverlay) {
            RectF converted = getBoundsRelativeToView(bounds);

            mProgressParams = new LayoutParams(
                    (int) (converted.width() + mProgressBarRadius * 2),
                    (int) (converted.height() + mProgressBarRadius * 2));
            mProgressParams.setMargins(
                    (int) (converted.left - mProgressBarRadius),
                    (int) (converted.top - mProgressBarRadius),
                    (int) (converted.right + mProgressBarRadius),
                    (int) (converted.bottom + mProgressBarRadius)
            );

            mFingerprintProgressView.setLayoutParams(mProgressParams);
            super.onSensorRectUpdated(converted);
        } else {
            super.onSensorRectUpdated(bounds);
        }
    }

    void setProgressBarRadius(float radius) {
        mProgressBarRadius = radius;
    }

    @Override
    public UdfpsDrawable getDrawable() {
        return mFingerprintDrawable;
    }

    void updateSensorLocation(@NonNull Rect sensorBounds) {
        View fingerprintAccessibilityView = findViewById(R.id.udfps_enroll_accessibility_view);
        ViewGroup.LayoutParams params = fingerprintAccessibilityView.getLayoutParams();
        params.width = sensorBounds.width();
        params.height = sensorBounds.height();
        fingerprintAccessibilityView.setLayoutParams(params);
        fingerprintAccessibilityView.requestLayout();
    }

    void setEnrollHelper(UdfpsEnrollHelper enrollHelper) {
        mFingerprintDrawable.setEnrollHelper(enrollHelper);
    }

    void onEnrollmentProgress(int remaining, int totalSteps) {
        mHandler.post(() -> {
            mFingerprintProgressDrawable.onEnrollmentProgress(remaining, totalSteps);
            mFingerprintDrawable.onEnrollmentProgress(remaining, totalSteps);
        });
    }

    void onEnrollmentHelp(int remaining, int totalSteps) {
        mHandler.post(() -> mFingerprintProgressDrawable.onEnrollmentHelp(remaining, totalSteps));
    }

    void onLastStepAcquired() {
        mHandler.post(mFingerprintProgressDrawable::onLastStepAcquired);
    }
}
