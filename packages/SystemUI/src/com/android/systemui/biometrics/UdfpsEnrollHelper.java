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

import android.hardware.fingerprint.IUdfpsOverlayController;

import androidx.annotation.NonNull;

/**
 * Helps keep track of enrollment state and animates the progress bar accordingly.
 */
public class UdfpsEnrollHelper {
    private static final String TAG = "UdfpsEnrollHelper";

    // IUdfpsOverlayController reason
    private final int mEnrollReason;

    private int mTotalSteps = -1;
    private int mCurrentProgress = 0;

    public UdfpsEnrollHelper(int reason) {
        mEnrollReason = reason;
    }

    boolean shouldShowProgressBar() {
        return mEnrollReason == IUdfpsOverlayController.REASON_ENROLL_ENROLLING;
    }

    void onEnrollmentProgress(int remaining, @NonNull UdfpsProgressBar progressBar) {
        if (mTotalSteps == -1) {
            mTotalSteps = remaining;
        }

        mCurrentProgress = progressBar.getMax() * Math.max(0, mTotalSteps + 1 - remaining)
                / (mTotalSteps + 1);
        progressBar.setProgress(mCurrentProgress, true /* animate */);
    }

    void updateProgress(@NonNull UdfpsProgressBar progressBar) {
        progressBar.setProgress(mCurrentProgress);
    }

    void onEnrollmentHelp() {

    }
}
