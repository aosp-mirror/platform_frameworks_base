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

import static com.android.server.biometrics.sensors.AuthResultCoordinator.AUTHENTICATOR_DEFAULT;
import static com.android.server.biometrics.sensors.AuthResultCoordinator.AUTHENTICATOR_PERMANENT_LOCKED;
import static com.android.server.biometrics.sensors.AuthResultCoordinator.AUTHENTICATOR_TIMED_LOCKED;
import static com.android.server.biometrics.sensors.AuthResultCoordinator.AUTHENTICATOR_UNLOCKED;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.biometrics.BiometricManager;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;

public class AuthResultCoordinatorTest {
    private AuthResultCoordinator mAuthResultCoordinator;

    @Before
    public void setUp() throws Exception {
        mAuthResultCoordinator = new AuthResultCoordinator();
    }

    @Test
    public void testDefaultMessage() {
        final Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
    }

    @Test
    public void testSingleMessageCoordinator() {
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);

        final Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
    }

    @Test
    public void testConvenientLockout() {
        mAuthResultCoordinator.lockedOutFor(
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);

        final Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_PERMANENT_LOCKED);
    }

    @Test
    public void testConvenientLockoutTimed() {
        mAuthResultCoordinator.lockOutTimed(
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);

        final Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_TIMED_LOCKED);
    }

    @Test
    public void testConvenientUnlock() {
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE);

        Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
    }

    @Test
    public void testWeakLockout() {
        mAuthResultCoordinator.lockedOutFor(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);

        Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_PERMANENT_LOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_PERMANENT_LOCKED);
    }

    @Test
    public void testWeakLockoutTimed() {
        mAuthResultCoordinator.lockOutTimed(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);

        Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_TIMED_LOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_TIMED_LOCKED);
    }

    @Test
    public void testWeakUnlock() {
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);

        Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
    }

    @Test
    public void testStrongLockout() {
        mAuthResultCoordinator.lockedOutFor(
                BiometricManager.Authenticators.BIOMETRIC_STRONG);

        Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_PERMANENT_LOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_PERMANENT_LOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_PERMANENT_LOCKED);
    }

    @Test
    public void testStrongLockoutTimed() {
        mAuthResultCoordinator.lockOutTimed(
                BiometricManager.Authenticators.BIOMETRIC_STRONG);

        Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_TIMED_LOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_TIMED_LOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_TIMED_LOCKED);
    }

    @Test
    public void testStrongUnlock() {
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_STRONG);

        final Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_UNLOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_UNLOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_UNLOCKED);
    }

    @Test
    public void testAuthAndLockout() {
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);
        mAuthResultCoordinator.lockedOutFor(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);

        final Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)).isEqualTo(
                AUTHENTICATOR_DEFAULT);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)).isEqualTo(
                AUTHENTICATOR_PERMANENT_LOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)).isEqualTo(
                AUTHENTICATOR_PERMANENT_LOCKED);

    }

    @Test
    public void testLockoutAndAuth() {
        mAuthResultCoordinator.lockedOutFor(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);
        mAuthResultCoordinator.authenticatedFor(
                BiometricManager.Authenticators.BIOMETRIC_STRONG);

        final Map<Integer, Integer> authMap = mAuthResultCoordinator.getResult();

        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                & AUTHENTICATOR_UNLOCKED).isEqualTo(
                AUTHENTICATOR_UNLOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                & AUTHENTICATOR_UNLOCKED).isEqualTo(
                AUTHENTICATOR_UNLOCKED);
        assertThat(authMap.get(BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE)
                & AUTHENTICATOR_UNLOCKED).isEqualTo(
                AUTHENTICATOR_UNLOCKED);
    }

}
