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

import static com.google.common.truth.Truth.assertThat;

import android.hardware.biometrics.BiometricManager;

import org.junit.Before;
import org.junit.Test;

public class AuthResultCoordinatorTest {
    private AuthResultCoordinator mAuthResultCoordinator;

    @Before
    public void setUp() throws Exception {
        mAuthResultCoordinator = new AuthResultCoordinator();
    }

    @Test
    public void testDefaultMessage() {
        checkResult(mAuthResultCoordinator.getResult(),
                AuthResult.FAILED,
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);
    }

    @Test
    public void testSingleMessageCoordinator() {
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);
        checkResult(mAuthResultCoordinator.getResult(),
                AuthResult.AUTHENTICATED,
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);
    }

    @Test
    public void testLockout() {
        mAuthResultCoordinator.lockedOutFor(
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);
        checkResult(mAuthResultCoordinator.getResult(),
                AuthResult.LOCKED_OUT,
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);
    }

    @Test
    public void testHigherStrengthPrecedence() {
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);
        checkResult(mAuthResultCoordinator.getResult(),
                AuthResult.AUTHENTICATED,
                BiometricManager.Authenticators.BIOMETRIC_WEAK);

        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_STRONG);
        checkResult(mAuthResultCoordinator.getResult(),
                AuthResult.AUTHENTICATED,
                BiometricManager.Authenticators.BIOMETRIC_STRONG);
    }

    @Test
    public void testAuthPrecedence() {
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);
        mAuthResultCoordinator.lockedOutFor(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);
        checkResult(mAuthResultCoordinator.getResult(),
                AuthResult.AUTHENTICATED,
                BiometricManager.Authenticators.BIOMETRIC_WEAK);

    }

    void checkResult(AuthResult res, int status,
            @BiometricManager.Authenticators.Types int strength) {
        assertThat(res.getStatus()).isEqualTo(status);
        assertThat(res.getBiometricStrength()).isEqualTo(strength);
    }

}
