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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.systemui.R;

/**
 * Class that coordinates non-HBM animations during enrollment.
 */
public class UdfpsAnimationViewEnroll extends UdfpsAnimationView
        implements UdfpsEnrollHelper.Listener {

    private static final String TAG = "UdfpsAnimationViewEnroll";

    @NonNull private UdfpsAnimationEnroll mUdfpsAnimation;
    @NonNull private UdfpsProgressBar mProgressBar;
    @Nullable private UdfpsEnrollHelper mEnrollHelper;

    @NonNull
    @Override
    protected UdfpsAnimation getUdfpsAnimation() {
        return mUdfpsAnimation;
    }

    public UdfpsAnimationViewEnroll(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mUdfpsAnimation = new UdfpsAnimationEnroll(context);
    }

    public void setEnrollHelper(@NonNull UdfpsEnrollHelper helper) {
        mEnrollHelper = helper;
        mUdfpsAnimation.setEnrollHelper(helper);
    }

    @Override
    protected void onFinishInflate() {
        mProgressBar = findViewById(R.id.progress_bar);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mEnrollHelper == null) {
            Log.e(TAG, "Enroll helper is null");
            return;
        }

        if (mEnrollHelper.shouldShowProgressBar()) {
            mProgressBar.setVisibility(View.VISIBLE);

            // Only need enrollment updates if the progress bar is showing :)
            mEnrollHelper.setListener(this);
        }
    }

    @Override
    public void onEnrollmentProgress(int remaining, int totalSteps) {
        final int interpolatedProgress = mProgressBar.getMax()
                * Math.max(0, totalSteps + 1 - remaining) / (totalSteps + 1);

        mProgressBar.setProgress(interpolatedProgress, true);
    }

    @NonNull
    @Override
    PointF getTouchTranslation() {
        if (!mEnrollHelper.isCenterEnrollmentComplete()) {
            return new PointF(0, 0);
        } else {
            return mEnrollHelper.getNextGuidedEnrollmentPoint();
        }
    }
}
