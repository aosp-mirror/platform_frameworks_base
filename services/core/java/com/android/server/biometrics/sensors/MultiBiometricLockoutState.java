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
import android.os.SystemClock;
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

    MultiBiometricLockoutState() {
        this(SystemClock.currentNetworkTimeClock());
    }

    MultiBiometricLockoutState(Clock clock) {
        mCanUserAuthenticate = new HashMap<>();
        mClock = clock;
    }

    private Map<Integer, AuthenticatorState> createUnlockedMap() {
        Map<Integer, AuthenticatorState> lockOutMap = new HashMap<>();
        lockOutMap.put(BIOMETRIC_STRONG,
                new AuthenticatorState(BIOMETRIC_STRONG, false, 0, mClock));
        lockOutMap.put(BIOMETRIC_WEAK, new AuthenticatorState(BIOMETRIC_WEAK, false, 0, mClock));
        lockOutMap.put(BIOMETRIC_CONVENIENCE,
                new AuthenticatorState(BIOMETRIC_CONVENIENCE, false, 0, mClock));
        return lockOutMap;
    }

    private Map<Integer, AuthenticatorState> getAuthMapForUser(int userId) {
        if (!mCanUserAuthenticate.containsKey(userId)) {
            mCanUserAuthenticate.put(userId, createUnlockedMap());
        }
        return mCanUserAuthenticate.get(userId);
    }

    void setAuthenticatorTo(int userId, @Authenticators.Types int strength, boolean canAuth) {
        final Map<Integer, AuthenticatorState> authMap = getAuthMapForUser(userId);
        switch (strength) {
            case Authenticators.BIOMETRIC_STRONG:
                authMap.get(BIOMETRIC_STRONG).mPermanentlyLockedOut = !canAuth;
                // fall through
            case Authenticators.BIOMETRIC_WEAK:
                authMap.get(BIOMETRIC_WEAK).mPermanentlyLockedOut = !canAuth;
                // fall through
            case Authenticators.BIOMETRIC_CONVENIENCE:
                authMap.get(BIOMETRIC_CONVENIENCE).mPermanentlyLockedOut = !canAuth;
        }
    }

    void increaseLockoutTime(int userId, @Authenticators.Types int strength, long duration) {
        final Map<Integer, AuthenticatorState> authMap = getAuthMapForUser(userId);
        switch (strength) {
            case Authenticators.BIOMETRIC_STRONG:
                authMap.get(BIOMETRIC_STRONG).increaseLockoutTo(duration);
                // fall through
            case Authenticators.BIOMETRIC_WEAK:
                authMap.get(BIOMETRIC_WEAK).increaseLockoutTo(duration);
                // fall through
            case Authenticators.BIOMETRIC_CONVENIENCE:
                authMap.get(BIOMETRIC_CONVENIENCE).increaseLockoutTo(duration);
        }
    }

    void clearLockoutTime(int userId, @Authenticators.Types int strength) {
        final Map<Integer, AuthenticatorState> authMap = getAuthMapForUser(userId);
        switch (strength) {
            case Authenticators.BIOMETRIC_STRONG:
                authMap.get(BIOMETRIC_STRONG).setTimedLockout(0);
                // fall through
            case Authenticators.BIOMETRIC_WEAK:
                authMap.get(BIOMETRIC_WEAK).setTimedLockout(0);
                // fall through
            case Authenticators.BIOMETRIC_CONVENIENCE:
                authMap.get(BIOMETRIC_CONVENIENCE).setTimedLockout(0);
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
        final boolean canAuthenticate = getAuthMapForUser(userId).get(strength).canAuthenticate();
        Slog.d(TAG, "canUserAuthenticate(userId=" + userId + ", strength=" + strength + ") ="
                + canAuthenticate);
        return canAuthenticate;
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
        private long mTimedLockout;
        private Clock mClock;

        AuthenticatorState(Integer authenticatorId, boolean permanentlyLockedOut,
                long timedLockout, Clock clock) {
            mAuthenticatorType = authenticatorId;
            mPermanentlyLockedOut = permanentlyLockedOut;
            mTimedLockout = timedLockout;
            mClock = clock;
        }

        boolean canAuthenticate() {
            return !mPermanentlyLockedOut && mClock.millis() - mTimedLockout >= 0;
        }

        /**
         * Either increases the lockout to duration, or leaves it as it, whichever is longer.
         */
        void increaseLockoutTo(long duration) {
            mTimedLockout = Math.max(mTimedLockout, duration);
        }

        void setTimedLockout(long duration) {
            mTimedLockout = duration;
        }

        String toString(long currentTime) {
            final String duration =
                    mTimedLockout - currentTime > 0 ? (mTimedLockout - currentTime) + "ms" : "none";
            final String permanentLockout = mPermanentlyLockedOut ? "true" : "false";
            return String.format("(%s, permanentLockout=%s, timedLockoutRemaining=%s)",
                    BiometricManager.authenticatorToStr(mAuthenticatorType), permanentLockout,
                    duration);
        }
    }
}
