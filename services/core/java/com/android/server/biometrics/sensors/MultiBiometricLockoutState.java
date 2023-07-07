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

import android.hardware.biometrics.BiometricManager;
import android.util.Slog;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class is used as a system to store the state of each
 * {@link Authenticators.Types} status for every user.
 *
 * Note that initially all biomertics are unlocked, meaning users can authenticate
 * with each strength.
 */
class MultiBiometricLockoutState {

    private static final String TAG = "MultiBiometricLockoutState";
    private final Map<Integer, Map<Integer, AuthenticatorState>> mCanUserAuthenticate;
    private final Clock mClock;

    MultiBiometricLockoutState(Clock clock) {
        mCanUserAuthenticate = new HashMap<>();
        mClock = clock;
    }

    private Map<Integer, AuthenticatorState> createUnlockedMap() {
        Map<Integer, AuthenticatorState> lockOutMap = new HashMap<>();
        lockOutMap.put(BIOMETRIC_STRONG,
                new AuthenticatorState(BIOMETRIC_STRONG, false, false));
        lockOutMap.put(BIOMETRIC_WEAK,
                new AuthenticatorState(BIOMETRIC_WEAK, false, false));
        lockOutMap.put(BIOMETRIC_CONVENIENCE,
                new AuthenticatorState(BIOMETRIC_CONVENIENCE, false, false));
        return lockOutMap;
    }

    private Map<Integer, AuthenticatorState> getAuthMapForUser(int userId) {
        if (!mCanUserAuthenticate.containsKey(userId)) {
            mCanUserAuthenticate.put(userId, createUnlockedMap());
        }
        return mCanUserAuthenticate.get(userId);
    }

    void setPermanentLockOut(int userId, @Authenticators.Types int strength) {
        final Map<Integer, AuthenticatorState> authMap = getAuthMapForUser(userId);
        switch (strength) {
            case Authenticators.BIOMETRIC_STRONG:
                authMap.get(BIOMETRIC_STRONG).mPermanentlyLockedOut = true;
                // fall through
            case Authenticators.BIOMETRIC_WEAK:
                authMap.get(BIOMETRIC_WEAK).mPermanentlyLockedOut = true;
                // fall through
            case Authenticators.BIOMETRIC_CONVENIENCE:
                authMap.get(BIOMETRIC_CONVENIENCE).mPermanentlyLockedOut = true;
                return;
            default:
                Slog.e(TAG, "increaseLockoutTime called for invalid strength : "  + strength);
        }
    }

    void clearPermanentLockOut(int userId, @Authenticators.Types int strength) {
        final Map<Integer, AuthenticatorState> authMap = getAuthMapForUser(userId);
        switch (strength) {
            case Authenticators.BIOMETRIC_STRONG:
                authMap.get(BIOMETRIC_STRONG).mPermanentlyLockedOut = false;
                // fall through
            case Authenticators.BIOMETRIC_WEAK:
                authMap.get(BIOMETRIC_WEAK).mPermanentlyLockedOut = false;
                // fall through
            case Authenticators.BIOMETRIC_CONVENIENCE:
                authMap.get(BIOMETRIC_CONVENIENCE).mPermanentlyLockedOut = false;
                return;
            default:
                Slog.e(TAG, "increaseLockoutTime called for invalid strength : "  + strength);
        }
    }

    void setTimedLockout(int userId, @Authenticators.Types int strength) {
        final Map<Integer, AuthenticatorState> authMap = getAuthMapForUser(userId);
        switch (strength) {
            case Authenticators.BIOMETRIC_STRONG:
                authMap.get(BIOMETRIC_STRONG).mTimedLockout = true;
                // fall through
            case Authenticators.BIOMETRIC_WEAK:
                authMap.get(BIOMETRIC_WEAK).mTimedLockout = true;
                // fall through
            case Authenticators.BIOMETRIC_CONVENIENCE:
                authMap.get(BIOMETRIC_CONVENIENCE).mTimedLockout = true;
                return;
            default:
                Slog.e(TAG, "increaseLockoutTime called for invalid strength : "  + strength);
        }
    }

    void clearTimedLockout(int userId, @Authenticators.Types int strength) {
        final Map<Integer, AuthenticatorState> authMap = getAuthMapForUser(userId);
        switch (strength) {
            case Authenticators.BIOMETRIC_STRONG:
                authMap.get(BIOMETRIC_STRONG).mTimedLockout = false;
                // fall through
            case Authenticators.BIOMETRIC_WEAK:
                authMap.get(BIOMETRIC_WEAK).mTimedLockout = false;
                // fall through
            case Authenticators.BIOMETRIC_CONVENIENCE:
                authMap.get(BIOMETRIC_CONVENIENCE).mTimedLockout = false;
                return;
            default:
                Slog.e(TAG, "increaseLockoutTime called for invalid strength : "  + strength);
        }
    }

    /**
     * Retrieves the lockout state for a user of a specified strength.
     *
     * @param userId   The user.
     * @param strength The strength of biometric that is requested to authenticate.
     */
    @LockoutTracker.LockoutMode
    int getLockoutState(int userId, @Authenticators.Types int strength) {
        final Map<Integer, AuthenticatorState> authMap = getAuthMapForUser(userId);
        if (!authMap.containsKey(strength)) {
            Slog.e(TAG, "Error, getLockoutState for unknown strength: " + strength
                    + " returning LOCKOUT_NONE");
            return LockoutTracker.LOCKOUT_NONE;
        }
        final AuthenticatorState state = authMap.get(strength);
        if (state.mPermanentlyLockedOut) {
            return LockoutTracker.LOCKOUT_PERMANENT;
        } else if (state.mTimedLockout) {
            return LockoutTracker.LOCKOUT_TIMED;
        } else {
            return LockoutTracker.LOCKOUT_NONE;
        }
    }

    @Override
    public String toString() {
        String dumpState = "Permanent Lockouts\n";
        final long time = mClock.millis();
        for (Map.Entry<Integer, Map<Integer, AuthenticatorState>> userState :
                mCanUserAuthenticate.entrySet()) {
            final int userId = userState.getKey();
            final Map<Integer, AuthenticatorState> map = userState.getValue();
            String prettyStr = map.entrySet().stream().map(
                    (Map.Entry<Integer, AuthenticatorState> entry) -> entry.getValue().toString(
                            time)).collect(Collectors.joining(", "));
            dumpState += "UserId=" + userId + ", {" + prettyStr + "}\n";
        }
        return dumpState;
    }

    private static class AuthenticatorState {
        private Integer mAuthenticatorType;
        private boolean mPermanentlyLockedOut;
        private boolean mTimedLockout;

        AuthenticatorState(Integer authenticatorId, boolean permanentlyLockedOut,
                boolean timedLockout) {
            mAuthenticatorType = authenticatorId;
            mPermanentlyLockedOut = permanentlyLockedOut;
            mTimedLockout = timedLockout;
        }

        String toString(long currentTime) {
            final String timedLockout = mTimedLockout ? "true" : "false";
            final String permanentLockout = mPermanentlyLockedOut ? "true" : "false";
            return String.format("(%s, permanentLockout=%s, timedLockout=%s)",
                    BiometricManager.authenticatorToStr(mAuthenticatorType), permanentLockout,
                    timedLockout);
        }
    }
}
