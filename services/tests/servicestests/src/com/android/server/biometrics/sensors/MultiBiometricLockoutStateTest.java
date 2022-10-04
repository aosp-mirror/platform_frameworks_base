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

import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_CONVENIENCE;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MultiBiometricLockoutStateTest {
    private static final int PRIMARY_USER = 0;
    private MultiBiometricLockoutState mCoordinator;

    private void unlockAllBiometrics() {
        unlockAllBiometrics(mCoordinator, PRIMARY_USER);
    }

    private void lockoutAllBiometrics() {
        lockoutAllBiometrics(mCoordinator, PRIMARY_USER);
    }

    private static void unlockAllBiometrics(MultiBiometricLockoutState coordinator, int userId) {
        coordinator.onUserUnlocked(userId, BIOMETRIC_STRONG);
        assertThat(coordinator.canUserAuthenticate(userId, BIOMETRIC_STRONG)).isTrue();
        assertThat(coordinator.canUserAuthenticate(userId, BIOMETRIC_WEAK)).isTrue();
        assertThat(coordinator.canUserAuthenticate(userId, BIOMETRIC_CONVENIENCE)).isTrue();
    }

    private static void lockoutAllBiometrics(MultiBiometricLockoutState coordinator, int userId) {
        coordinator.onUserLocked(userId, BIOMETRIC_STRONG);
        assertThat(coordinator.canUserAuthenticate(userId, BIOMETRIC_STRONG)).isFalse();
        assertThat(coordinator.canUserAuthenticate(userId, BIOMETRIC_WEAK)).isFalse();
        assertThat(coordinator.canUserAuthenticate(userId, BIOMETRIC_CONVENIENCE)).isFalse();
    }

    @Before
    public void setUp() throws Exception {
        mCoordinator = new MultiBiometricLockoutState();
    }

    @Test
    public void testInitialStateLockedOut() {
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_STRONG)).isFalse();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_WEAK)).isFalse();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isFalse();
    }

    @Test
    public void testConvenienceLockout() {
        unlockAllBiometrics();
        mCoordinator.onUserLocked(PRIMARY_USER, BIOMETRIC_CONVENIENCE);
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_STRONG)).isTrue();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isFalse();
    }

    @Test
    public void testWeakLockout() {
        unlockAllBiometrics();
        mCoordinator.onUserLocked(PRIMARY_USER, BIOMETRIC_WEAK);
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_STRONG)).isTrue();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_WEAK)).isFalse();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isFalse();
    }

    @Test
    public void testStrongLockout() {
        unlockAllBiometrics();
        mCoordinator.onUserLocked(PRIMARY_USER, BIOMETRIC_STRONG);
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_STRONG)).isFalse();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_WEAK)).isFalse();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isFalse();
    }

    @Test
    public void testConvenienceUnlock() {
        lockoutAllBiometrics();
        mCoordinator.onUserUnlocked(PRIMARY_USER, BIOMETRIC_CONVENIENCE);
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_STRONG)).isFalse();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_WEAK)).isFalse();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isTrue();
    }

    @Test
    public void testWeakUnlock() {
        lockoutAllBiometrics();
        mCoordinator.onUserUnlocked(PRIMARY_USER, BIOMETRIC_WEAK);
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_STRONG)).isFalse();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isTrue();
    }

    @Test
    public void testStrongUnlock() {
        lockoutAllBiometrics();
        mCoordinator.onUserUnlocked(PRIMARY_USER, BIOMETRIC_STRONG);
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_STRONG)).isTrue();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();
        assertThat(mCoordinator.canUserAuthenticate(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isTrue();
    }

    @Test
    public void multiUser_userOneDoesNotAffectUserTwo() {
        final int userOne = 1;
        final int userTwo = 2;
        MultiBiometricLockoutState coordinator = new MultiBiometricLockoutState();
        lockoutAllBiometrics(coordinator, userOne);
        lockoutAllBiometrics(coordinator, userTwo);

        coordinator.onUserUnlocked(userOne, BIOMETRIC_WEAK);
        assertThat(coordinator.canUserAuthenticate(userOne, BIOMETRIC_STRONG)).isFalse();
        assertThat(coordinator.canUserAuthenticate(userOne, BIOMETRIC_WEAK)).isTrue();
        assertThat(coordinator.canUserAuthenticate(userOne, BIOMETRIC_CONVENIENCE)).isTrue();

        assertThat(coordinator.canUserAuthenticate(userTwo, BIOMETRIC_STRONG)).isFalse();
        assertThat(coordinator.canUserAuthenticate(userTwo, BIOMETRIC_WEAK)).isFalse();
        assertThat(coordinator.canUserAuthenticate(userTwo, BIOMETRIC_CONVENIENCE)).isFalse();
    }
}
