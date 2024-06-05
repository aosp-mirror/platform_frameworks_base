/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.biometrics;

import android.util.Slog;

/**
 * Utility class for on-device biometric authentication data, including total authentication,
 * rejections, and the number of sent enrollment notifications.
 */
public class AuthenticationStats {

    private static final String TAG = "AuthenticationStats";

    private static final float FRR_NOT_ENOUGH_ATTEMPTS = -1.0f;

    private final int mUserId;
    private int mTotalAttempts;
    private int mRejectedAttempts;
    private int mEnrollmentNotifications;
    private final int mModality;

    public AuthenticationStats(final int userId, int totalAttempts, int rejectedAttempts,
            int enrollmentNotifications, final int modality) {
        mUserId = userId;
        mTotalAttempts = totalAttempts;
        mRejectedAttempts = rejectedAttempts;
        mEnrollmentNotifications = enrollmentNotifications;
        mModality = modality;
    }

    public AuthenticationStats(final int userId, final int modality) {
        mUserId = userId;
        mTotalAttempts = 0;
        mRejectedAttempts = 0;
        mEnrollmentNotifications = 0;
        mModality = modality;
    }

    public int getUserId() {
        return mUserId;
    }

    public int getTotalAttempts() {
        return mTotalAttempts;
    }

    public int getRejectedAttempts() {
        return mRejectedAttempts;
    }

    public int getEnrollmentNotifications() {
        return mEnrollmentNotifications;
    }

    public int getModality() {
        return mModality;
    }

    /** Calculate FRR. */
    public float getFrr() {
        if (mTotalAttempts > 0) {
            return mRejectedAttempts / (float) mTotalAttempts;
        } else {
            return FRR_NOT_ENOUGH_ATTEMPTS;
        }
    }

    /** Update total authentication attempts and rejections. */
    public void authenticate(boolean authenticated) {
        if (!authenticated) {
            mRejectedAttempts++;
        }
        mTotalAttempts++;
    }

    /** Reset total authentication attempts and rejections. */
    public void resetData() {
        mTotalAttempts = 0;
        mRejectedAttempts = 0;
        Slog.d(TAG, "Reset Counters.");
    }

    /** Update enrollment notification counter after sending a notification. */
    public void updateNotificationCounter() {
        mEnrollmentNotifications++;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof AuthenticationStats)) {
            return false;
        }

        AuthenticationStats target = (AuthenticationStats) obj;
        return this.getUserId() == target.getUserId()
                && this.getTotalAttempts()
                == target.getTotalAttempts()
                && this.getRejectedAttempts()
                == target.getRejectedAttempts()
                && this.getEnrollmentNotifications()
                == target.getEnrollmentNotifications()
                && this.getModality() == target.getModality();
    }

    @Override
    public int hashCode() {
        return String.format("userId: %d, totalAttempts: %d, rejectedAttempts: %d, "
                + "enrollmentNotifications: %d, modality: %d", mUserId, mTotalAttempts,
                mRejectedAttempts, mEnrollmentNotifications, mModality).hashCode();
    }
}
