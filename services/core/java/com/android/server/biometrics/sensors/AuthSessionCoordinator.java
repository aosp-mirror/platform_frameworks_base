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

import android.hardware.biometrics.BiometricManager.Authenticators;
import android.util.Slog;

import java.util.HashSet;
import java.util.Set;

/**
 * Coordinates lockout counter enforcement for all types of biometric strengths across all users.
 *
 * This class is not thread-safe. In general, all calls to this class should be made on the same
 * handler to ensure no collisions.
 */
class AuthSessionCoordinator implements AuthSessionListener {
    private static final String TAG = "AuthSessionCoordinator";

    private final Set<Integer> mAuthOperations;

    private int mUserId;
    private boolean mIsAuthenticating;
    private AuthResultCoordinator mAuthResultCoordinator;
    private MultiBiometricLockoutState mMultiBiometricLockoutState;

    AuthSessionCoordinator() {
        mAuthOperations = new HashSet<>();
        mAuthResultCoordinator = new AuthResultCoordinator();
        mMultiBiometricLockoutState = new MultiBiometricLockoutState();
    }

    /**
     * A Call indicating that an auth session has started
     */
    void onAuthSessionStarted(int userId) {
        mAuthOperations.clear();
        mUserId = userId;
        mIsAuthenticating = true;
        mAuthResultCoordinator.resetState();
    }

    /**
     * Ends the current auth session and updates the lockout state.
     *
     * This can happen two ways.
     * 1. Manually calling this API
     * 2. If authStartedFor() was called, and all authentication attempts finish.
     */
    void endAuthSession() {
        if (mIsAuthenticating) {
            mAuthOperations.clear();
            AuthResult res =
                    mAuthResultCoordinator.getResult();
            if (res.getStatus() == AuthResult.AUTHENTICATED) {
                mMultiBiometricLockoutState.onUserUnlocked(mUserId, res.getBiometricStrength());
            } else if (res.getStatus() == AuthResult.LOCKED_OUT) {
                mMultiBiometricLockoutState.onUserLocked(mUserId, res.getBiometricStrength());
            }
            mAuthResultCoordinator.resetState();
            mIsAuthenticating = false;
        }
    }

    /**
     * @return true if a user can authenticate with a given strength.
     */
    boolean getCanAuthFor(int userId, @Authenticators.Types int strength) {
        return mMultiBiometricLockoutState.canUserAuthenticate(userId, strength);
    }

    @Override
    public void authStartedFor(int userId, int sensorId) {
        if (!mIsAuthenticating) {
            onAuthSessionStarted(userId);
        }

        if (mAuthOperations.contains(sensorId)) {
            Slog.e(TAG, "Error, authStartedFor(" + sensorId + ") without being finished");
            return;
        }

        if (mUserId != userId) {
            Slog.e(TAG, "Error authStartedFor(" + userId + ") Incorrect userId, expected" + mUserId
                    + ", ignoring...");
            return;
        }

        mAuthOperations.add(sensorId);
    }

    @Override
    public void authenticatedFor(int userId, @Authenticators.Types int biometricStrength,
            int sensorId) {
        mAuthResultCoordinator.authenticatedFor(biometricStrength);
        attemptToFinish(userId, sensorId,
                "authenticatedFor(userId=" + userId + ", biometricStrength=" + biometricStrength
                        + ", sensorId=" + sensorId + "");
    }

    @Override
    public void lockedOutFor(int userId, @Authenticators.Types int biometricStrength,
            int sensorId) {
        mAuthResultCoordinator.lockedOutFor(biometricStrength);
        attemptToFinish(userId, sensorId,
                "lockOutFor(userId=" + userId + ", biometricStrength=" + biometricStrength
                        + ", sensorId=" + sensorId + "");
    }

    @Override
    public void authEndedFor(int userId, @Authenticators.Types int biometricStrength,
            int sensorId) {
        mAuthResultCoordinator.authEndedFor(biometricStrength);
        attemptToFinish(userId, sensorId,
                "authEndedFor(userId=" + userId + " ,biometricStrength=" + biometricStrength
                        + ", sensorId=" + sensorId);
    }

    @Override
    public void resetLockoutFor(int userId, @Authenticators.Types int biometricStrength) {
        mMultiBiometricLockoutState.onUserUnlocked(userId, biometricStrength);
    }

    private void attemptToFinish(int userId, int sensorId, String description) {
        boolean didFail = false;
        if (!mAuthOperations.contains(sensorId)) {
            Slog.e(TAG, "Error unable to find auth operation : " + description);
            didFail = true;
        }
        if (userId != mUserId) {
            Slog.e(TAG, "Error mismatched userId, expected=" + mUserId + " for " + description);
            didFail = true;
        }
        if (didFail) {
            return;
        }
        mAuthOperations.remove(sensorId);
        if (mIsAuthenticating && mAuthOperations.isEmpty()) {
            endAuthSession();
        }
    }

}
