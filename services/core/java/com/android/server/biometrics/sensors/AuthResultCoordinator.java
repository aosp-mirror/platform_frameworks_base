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

import java.util.ArrayList;
import java.util.List;

/**
 * A class that takes in a series of authentication attempts (successes, failures, lockouts)
 * across different biometric strengths (convenience, weak, strong) and returns a single AuthResult.
 *
 * The AuthResult will be the strongest biometric operation that occurred amongst all reported
 * operations, and if multiple such operations exist, it will favor a successful authentication.
 */
class AuthResultCoordinator {

    private static final String TAG = "AuthResultCoordinator";
    private final List<AuthResult> mOperations;

    AuthResultCoordinator() {
        mOperations = new ArrayList<>();
    }

    /**
     * Adds auth success for a given strength to the current operation list.
     */
    void authenticatedFor(@Authenticators.Types int strength) {
        mOperations.add(new AuthResult(AuthResult.AUTHENTICATED, strength));
    }

    /**
     * Adds auth ended for a given strength to the current operation list.
     */
    void authEndedFor(@Authenticators.Types int strength) {
        mOperations.add(new AuthResult(AuthResult.FAILED, strength));
    }

    /**
     * Adds a lock out of a given strength to the current operation list.
     */
    void lockedOutFor(@Authenticators.Types int strength) {
        mOperations.add(new AuthResult(AuthResult.LOCKED_OUT, strength));
    }

    /**
     * Obtains an auth result & strength from a current set of biometric operations.
     */
    AuthResult getResult() {
        AuthResult result = new AuthResult(AuthResult.FAILED, Authenticators.BIOMETRIC_CONVENIENCE);
        return mOperations.stream().filter(
                (element) -> element.getStatus() != AuthResult.FAILED).reduce(result,
                ((curr, next) -> {
                    int strengthCompare = curr.getBiometricStrength() - next.getBiometricStrength();
                    if (strengthCompare < 0) {
                        return curr;
                    } else if (strengthCompare == 0) {
                        // Equal level of strength, favor authentication.
                        if (curr.getStatus() == AuthResult.AUTHENTICATED) {
                            return curr;
                        } else {
                            // Either next is Authenticated, or it is not, either way return this
                            // one.
                            return next;
                        }
                    } else {
                        // curr is a weaker biometric
                        return next;
                    }
                }));
    }

    void resetState() {
        mOperations.clear();
    }
}


