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

import static com.android.server.biometrics.sensors.AuthResultCoordinator.AUTHENTICATOR_PERMANENT_LOCKED;
import static com.android.server.biometrics.sensors.AuthResultCoordinator.AUTHENTICATOR_TIMED_LOCKED;
import static com.android.server.biometrics.sensors.AuthResultCoordinator.AUTHENTICATOR_UNLOCKED;

import android.hardware.biometrics.BiometricManager.Authenticators;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates lockout counter enforcement for all types of biometric strengths across all users.
 *
 * This class is not thread-safe. In general, all calls to this class should be made on the same
 * handler to ensure no collisions.
 */
public class AuthSessionCoordinator implements AuthSessionListener {
    private static final String TAG = "AuthSessionCoordinator";

    private final Set<Integer> mAuthOperations;
    private final MultiBiometricLockoutState mMultiBiometricLockoutState;
    private final RingBuffer mRingBuffer;

    private int mUserId;
    private boolean mIsAuthenticating;
    private AuthResultCoordinator mAuthResultCoordinator;

    public AuthSessionCoordinator() {
        this(SystemClock.elapsedRealtimeClock());
    }

    @VisibleForTesting
    AuthSessionCoordinator(Clock clock) {
        mAuthOperations = new HashSet<>();
        mAuthResultCoordinator = new AuthResultCoordinator();
        mMultiBiometricLockoutState = new MultiBiometricLockoutState(clock);
        mRingBuffer = new RingBuffer(100);
    }

    /**
     * A Call indicating that an auth session has started
     */
    void onAuthSessionStarted(int userId) {
        mAuthOperations.clear();
        mUserId = userId;
        mIsAuthenticating = true;
        mAuthResultCoordinator = new AuthResultCoordinator();
        mRingBuffer.addApiCall("internal : onAuthSessionStarted(" + userId + ")");
    }

    /**
     * Ends the current auth session and updates the lockout state.
     *
     * This can happen two ways.
     * 1. Manually calling this API
     * 2. If authStartedFor() was called, and any authentication attempts finish.
     */
    void endAuthSession() {
        // User unlocks can also unlock timed lockout Authenticator.Types
        final Map<Integer, Integer> result = mAuthResultCoordinator.getResult();
        for (int authenticator : Arrays.asList(Authenticators.BIOMETRIC_CONVENIENCE,
                Authenticators.BIOMETRIC_WEAK, Authenticators.BIOMETRIC_STRONG)) {
            final Integer value = result.get(authenticator);
            if ((value & AUTHENTICATOR_UNLOCKED) == AUTHENTICATOR_UNLOCKED) {
                mMultiBiometricLockoutState.clearPermanentLockOut(mUserId, authenticator);
                mMultiBiometricLockoutState.clearTimedLockout(mUserId, authenticator);
            } else if ((value & AUTHENTICATOR_PERMANENT_LOCKED) == AUTHENTICATOR_PERMANENT_LOCKED) {
                mMultiBiometricLockoutState.setPermanentLockOut(mUserId, authenticator);
            } else if ((value & AUTHENTICATOR_TIMED_LOCKED) == AUTHENTICATOR_TIMED_LOCKED) {
                mMultiBiometricLockoutState.setTimedLockout(mUserId, authenticator);
            }
        }

        if (mAuthOperations.isEmpty()) {
            mRingBuffer.addApiCall("internal : onAuthSessionEnded(" + mUserId + ")");
            clearSession();
        }
    }

    private void clearSession() {
        mIsAuthenticating = false;
        mAuthOperations.clear();
    }

    /**
     * Returns the current lockout state for a given user/strength.
     */
    @LockoutTracker.LockoutMode
    public int getLockoutStateFor(int userId, @Authenticators.Types int strength) {
        return mMultiBiometricLockoutState.getLockoutState(userId, strength);
    }

    @Override
    public void authStartedFor(int userId, int sensorId, long requestId) {
        mRingBuffer.addApiCall(
                "authStartedFor(userId=" + userId + ", sensorId=" + sensorId + ", requestId="
                        + requestId + ")");
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
    public void lockedOutFor(int userId, @Authenticators.Types int biometricStrength, int sensorId,
            long requestId) {
        final String lockedOutStr =
                "lockOutFor(userId=" + userId + ", biometricStrength=" + biometricStrength
                        + ", sensorId=" + sensorId + ", requestId=" + requestId + ")";
        mRingBuffer.addApiCall(lockedOutStr);
        mAuthResultCoordinator.lockedOutFor(biometricStrength);
        attemptToFinish(userId, sensorId, lockedOutStr);
    }

    @Override
    public void lockOutTimed(int userId, @Authenticators.Types int biometricStrength, int sensorId,
            long time, long requestId) {
        final String lockedOutStr =
                "lockOutTimedFor(userId=" + userId + ", biometricStrength=" + biometricStrength
                        + ", sensorId=" + sensorId + "time=" + time + ", requestId=" + requestId
                        + ")";
        mRingBuffer.addApiCall(lockedOutStr);
        mAuthResultCoordinator.lockOutTimed(biometricStrength);
        attemptToFinish(userId, sensorId, lockedOutStr);
    }

    @Override
    public void authEndedFor(int userId, @Authenticators.Types int biometricStrength, int sensorId,
            long requestId, boolean wasSuccessful) {
        final String authEndedStr =
                "authEndedFor(userId=" + userId + " ,biometricStrength=" + biometricStrength
                        + ", sensorId=" + sensorId + ", requestId=" + requestId + ", wasSuccessful="
                        + wasSuccessful + ")";
        mRingBuffer.addApiCall(authEndedStr);
        if (wasSuccessful) {
            mAuthResultCoordinator.authenticatedFor(biometricStrength);
        }
        attemptToFinish(userId, sensorId, authEndedStr);
    }

    @Override
    public void resetLockoutFor(int userId, @Authenticators.Types int biometricStrength,
            long requestId) {
        final String resetLockStr =
                "resetLockoutFor(userId=" + userId + " ,biometricStrength=" + biometricStrength
                        + ", requestId=" + requestId + ")";
        mRingBuffer.addApiCall(resetLockStr);
        if (biometricStrength == Authenticators.BIOMETRIC_STRONG) {
            clearSession();
        } else {
            // Lockouts cannot be reset by non-strong biometrics
            return;
        }
        mMultiBiometricLockoutState.clearPermanentLockOut(userId, biometricStrength);
        mMultiBiometricLockoutState.clearTimedLockout(userId, biometricStrength);
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
        if (mIsAuthenticating) {
            endAuthSession();
        }
    }

    /**
     * Returns a string representation of the past N API calls as well as the
     * permanent and timed lockout states for each user's authenticators.
     */
    @Override
    public String toString() {
        return mRingBuffer + "\n" + mMultiBiometricLockoutState;
    }

    private static class RingBuffer {
        private final String[] mApiCalls;
        private final int mSize;
        private int mCurr;
        private int mApiCallNumber;

        RingBuffer(int size) {
            if (size <= 0) {
                Slog.wtf(TAG, "Cannot initialize ring buffer of size: " + size);
            }
            mApiCalls = new String[size];
            mCurr = 0;
            mSize = size;
            mApiCallNumber = 0;
        }

        synchronized void addApiCall(String str) {
            mApiCalls[mCurr] = str;
            mCurr++;
            mCurr %= mSize;
            mApiCallNumber++;
        }

        @Override
        public synchronized String toString() {
            String buffer = "";
            int apiCall = mApiCallNumber > mSize ? mApiCallNumber - mSize : 0;
            for (int i = 0; i < mSize; i++) {
                final int location = (mCurr + i) % mSize;
                if (mApiCalls[location] != null) {
                    buffer += String.format("#%-5d %s\n", apiCall++, mApiCalls[location]);
                }
            }
            return buffer;
        }
    }
}
