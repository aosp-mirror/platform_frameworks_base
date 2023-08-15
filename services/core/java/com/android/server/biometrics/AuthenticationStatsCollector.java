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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;

/**
 * Calculate and collect on-device False Rejection Rates (FRR).
 * FRR = All [given biometric modality] unlock failures / all [given biometric modality] unlock
 * attempts.
 */
public class AuthenticationStatsCollector {

    private static final String TAG = "AuthenticationStatsCollector";

    // The minimum number of attempts that will calculate the FRR and trigger the notification.
    private static final int MINIMUM_ATTEMPTS = 500;
    // The maximum number of eligible biometric enrollment notification can be sent.
    private static final int MAXIMUM_ENROLLMENT_NOTIFICATIONS = 2;

    private final float mThreshold;
    private final int mModality;

    @NonNull private final Map<Integer, AuthenticationStats> mUserAuthenticationStatsMap;

    public AuthenticationStatsCollector(@NonNull Context context, int modality) {
        mThreshold = context.getResources()
                .getFraction(R.fraction.config_biometricNotificationFrrThreshold, 1, 1);
        mUserAuthenticationStatsMap = new HashMap<>();
        mModality = modality;
    }

    /** Update total authentication and rejected attempts. */
    public void authenticate(int userId, boolean authenticated) {
        // Check if this is a new user.
        if (!mUserAuthenticationStatsMap.containsKey(userId)) {
            mUserAuthenticationStatsMap.put(userId, new AuthenticationStats(userId, mModality));
        }

        AuthenticationStats authenticationStats = mUserAuthenticationStatsMap.get(userId);

        authenticationStats.authenticate(authenticated);

        persistDataIfNeeded(userId);
        sendNotificationIfNeeded(userId);
    }

    private void sendNotificationIfNeeded(int userId) {
        AuthenticationStats authenticationStats = mUserAuthenticationStatsMap.get(userId);
        if (authenticationStats.getTotalAttempts() >= MINIMUM_ATTEMPTS) {
            // Send notification if FRR exceeds the threshold
            if (authenticationStats.getEnrollmentNotifications() < MAXIMUM_ENROLLMENT_NOTIFICATIONS
                    && authenticationStats.getFrr() >= mThreshold) {
                // TODO(wenhuiy): Send notifications.
            }

            authenticationStats.resetData();
        }
    }

    private void persistDataIfNeeded(int userId) {
        AuthenticationStats authenticationStats = mUserAuthenticationStatsMap.get(userId);
        if (authenticationStats.getTotalAttempts() % 50 == 0) {
            // TODO(wenhuiy): Persist data.
        }
    }

    /**
     * Only being used in tests. Callers should not make any changes to the returned
     * authentication stats.
     *
     * @return AuthenticationStats of the user, or null if the stats doesn't exist.
     */
    @Nullable
    @VisibleForTesting
    AuthenticationStats getAuthenticationStatsForUser(int userId) {
        return mUserAuthenticationStatsMap.getOrDefault(userId, null);
    }

    @VisibleForTesting
    void setAuthenticationStatsForUser(int userId, AuthenticationStats authenticationStats) {
        mUserAuthenticationStatsMap.put(userId, authenticationStats);
    }
}
