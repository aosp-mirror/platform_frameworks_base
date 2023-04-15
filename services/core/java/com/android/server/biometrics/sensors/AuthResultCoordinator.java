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
import android.util.ArrayMap;

import java.util.Collections;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * A class that takes in a series of authentication attempts (successes, failures, lockouts)
 * across different biometric strengths (convenience, weak, strong) and returns a single AuthResult.
 *
 * The AuthResult will be the strongest biometric operation that occurred amongst all reported
 * operations, and if multiple such operations exist, it will favor a successful authentication.
 */
class AuthResultCoordinator {

    /**
     * Indicates no change has occurred with this authenticator.
     */
    static final int AUTHENTICATOR_DEFAULT = 0;
    /**
     * Indicated this authenticator has received a permanent lockout.
     */
    static final int AUTHENTICATOR_PERMANENT_LOCKED = 1 << 0;
    /**
     * Indicates this authenticator has received a timed unlock.
     */
    static final int AUTHENTICATOR_TIMED_LOCKED = 1 << 1;
    /**
     * Indicates this authenticator has received a successful unlock.
     */
    static final int AUTHENTICATOR_UNLOCKED = 1 << 2;
    private static final String TAG = "AuthResultCoordinator";
    private final Map<Integer, Integer> mAuthenticatorState;

    AuthResultCoordinator() {
        mAuthenticatorState = new ArrayMap<>();
        mAuthenticatorState.put(Authenticators.BIOMETRIC_STRONG, AUTHENTICATOR_DEFAULT);
        mAuthenticatorState.put(Authenticators.BIOMETRIC_WEAK, AUTHENTICATOR_DEFAULT);
        mAuthenticatorState.put(Authenticators.BIOMETRIC_CONVENIENCE, AUTHENTICATOR_DEFAULT);
    }

    private void updateState(@Authenticators.Types int strength, IntFunction<Integer> mapper) {
        switch (strength) {
            case Authenticators.BIOMETRIC_STRONG:
                mAuthenticatorState.put(Authenticators.BIOMETRIC_STRONG,
                        mapper.apply(mAuthenticatorState.get(Authenticators.BIOMETRIC_STRONG)));
                // fall through
            case Authenticators.BIOMETRIC_WEAK:
                mAuthenticatorState.put(Authenticators.BIOMETRIC_WEAK,
                        mapper.apply(mAuthenticatorState.get(Authenticators.BIOMETRIC_WEAK)));
                // fall through
            case Authenticators.BIOMETRIC_CONVENIENCE:
                mAuthenticatorState.put(Authenticators.BIOMETRIC_CONVENIENCE,
                        mapper.apply(
                                mAuthenticatorState.get(Authenticators.BIOMETRIC_CONVENIENCE)));
        }
    }

    /**
     * Adds auth success for a given strength to the current operation list.
     */
    void authenticatedFor(@Authenticators.Types int strength) {
        // Only strong unlocks matter.
        if (strength == Authenticators.BIOMETRIC_STRONG) {
            updateState(strength, (old) -> AUTHENTICATOR_UNLOCKED | old);
        }
    }

    /**
     * Adds a lock out of a given strength to the current operation list.
     */
    void lockedOutFor(@Authenticators.Types int strength) {
        updateState(strength, (old) -> AUTHENTICATOR_PERMANENT_LOCKED | old);
    }

    /**
     * Adds a timed lock out of a given strength to the current operation list.
     */
    void lockOutTimed(@Authenticators.Types int strength) {
        updateState(strength, (old) -> AUTHENTICATOR_TIMED_LOCKED | old);
    }

    /**
     * Returns the current authenticator state. Each authenticator will have
     * the associated operations that were performed on them(DEFAULT, LOCKED, UNLOCKED).
     */
    final Map<Integer, Integer> getResult() {
        return Collections.unmodifiableMap(mAuthenticatorState);
    }
}
