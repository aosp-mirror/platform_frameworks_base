/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import static android.hardware.biometrics.BiometricManager.Authenticators;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK;

import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is used as a system to store the state of each
 * {@link Authenticators.Types} status for every user.
 */
class MultiBiometricLockoutState {

    private static final String TAG = "MultiBiometricLockoutState";
    private static final Map<Integer, List<Integer>> PRECEDENCE;

    static {
        Map<Integer, List<Integer>> precedence = new ArrayMap<>();
        precedence.put(Authenticators.BIOMETRIC_STRONG,
                Arrays.asList(BIOMETRIC_STRONG, BIOMETRIC_WEAK, BIOMETRIC_CONVENIENCE));
        precedence.put(BIOMETRIC_WEAK, Arrays.asList(BIOMETRIC_WEAK, BIOMETRIC_CONVENIENCE));
        precedence.put(BIOMETRIC_CONVENIENCE, Arrays.asList(BIOMETRIC_CONVENIENCE));
        PRECEDENCE = Collections.unmodifiableMap(precedence);
    }

    private final Map<Integer, Map<Integer, Boolean>> mCanUserAuthenticate;

    @VisibleForTesting
    MultiBiometricLockoutState() {
        mCanUserAuthenticate = new HashMap<>();
    }

    private static Map<Integer, Boolean> createLockedOutMap() {
        Map<Integer, Boolean> lockOutMap = new HashMap<>();
        lockOutMap.put(BIOMETRIC_STRONG, false);
        lockOutMap.put(BIOMETRIC_WEAK, false);
        lockOutMap.put(BIOMETRIC_CONVENIENCE, false);
        return lockOutMap;
    }

    private Map<Integer, Boolean> getAuthMapForUser(int userId) {
        if (!mCanUserAuthenticate.containsKey(userId)) {
            mCanUserAuthenticate.put(userId, createLockedOutMap());
        }
        return mCanUserAuthenticate.get(userId);
    }

    /**
     * Indicates a {@link Authenticators} has been locked for userId.
     *
     * @param userId   The user.
     * @param strength The strength of biometric that is requested to be locked.
     */
    void onUserLocked(int userId, @Authenticators.Types int strength) {
        Slog.d(TAG, "onUserLocked(userId=" + userId + ", strength=" + strength + ")");
        Map<Integer, Boolean> canUserAuthState = getAuthMapForUser(userId);
        for (int strengthToLockout : PRECEDENCE.get(strength)) {
            canUserAuthState.put(strengthToLockout, false);
        }
    }

    /**
     * Indicates that a user has unlocked a {@link Authenticators}
     *
     * @param userId   The user.
     * @param strength The strength of biometric that is unlocked.
     */
    void onUserUnlocked(int userId, @Authenticators.Types int strength) {
        Slog.d(TAG, "onUserUnlocked(userId=" + userId + ", strength=" + strength + ")");
        Map<Integer, Boolean> canUserAuthState = getAuthMapForUser(userId);
        for (int strengthToLockout : PRECEDENCE.get(strength)) {
            canUserAuthState.put(strengthToLockout, true);
        }
    }

    /**
     * Indicates if a user can perform an authentication operation with a given
     * {@link Authenticators.Types}
     *
     * @param userId   The user.
     * @param strength The strength of biometric that is requested to authenticate.
     * @return If a user can authenticate with a given biometric of this strength.
     */
    boolean canUserAuthenticate(int userId, @Authenticators.Types int strength) {
        final boolean canAuthenticate = getAuthMapForUser(userId).get(strength);
        Slog.d(TAG, "canUserAuthenticate(userId=" + userId + ", strength=" + strength + ") ="
                + canAuthenticate);
        return canAuthenticate;
    }
}
