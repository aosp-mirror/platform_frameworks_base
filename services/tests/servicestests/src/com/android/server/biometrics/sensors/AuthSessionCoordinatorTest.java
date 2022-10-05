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

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

@Presubmit
@SmallTest
public class AuthSessionCoordinatorTest {
    private static final int PRIMARY_USER = 0;
    private static final int SECONDARY_USER = 10;

    private AuthSessionCoordinator mCoordinator;

    @Before
    public void setUp() throws Exception {
        mCoordinator = new AuthSessionCoordinator();
    }

    @Test
    public void testUserUnlocked() {
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isFalse();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_WEAK)).isFalse();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_STRONG)).isFalse();

        mCoordinator.authStartedFor(PRIMARY_USER, 1);
        mCoordinator.authenticatedFor(PRIMARY_USER, BIOMETRIC_WEAK, 1);

        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_STRONG)).isFalse();
    }

    @Test
    public void testUserCanAuthDuringLockoutOfSameSession() {
        mCoordinator.resetLockoutFor(PRIMARY_USER, BIOMETRIC_STRONG);

        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_STRONG)).isTrue();

        mCoordinator.authStartedFor(PRIMARY_USER, 1);
        mCoordinator.authStartedFor(PRIMARY_USER, 2);
        mCoordinator.lockedOutFor(PRIMARY_USER, BIOMETRIC_WEAK, 2);

        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();
    }

    @Test
    public void testMultiUserAuth() {
        mCoordinator.resetLockoutFor(PRIMARY_USER, BIOMETRIC_STRONG);

        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_STRONG)).isTrue();

        assertThat(mCoordinator.getCanAuthFor(SECONDARY_USER, BIOMETRIC_CONVENIENCE)).isFalse();
        assertThat(mCoordinator.getCanAuthFor(SECONDARY_USER, BIOMETRIC_WEAK)).isFalse();
        assertThat(mCoordinator.getCanAuthFor(SECONDARY_USER, BIOMETRIC_STRONG)).isFalse();

        mCoordinator.authStartedFor(PRIMARY_USER, 1);
        mCoordinator.authStartedFor(PRIMARY_USER, 2);
        mCoordinator.lockedOutFor(PRIMARY_USER, BIOMETRIC_WEAK, 2);

        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();
        assertThat(mCoordinator.getCanAuthFor(PRIMARY_USER, BIOMETRIC_WEAK)).isTrue();

        assertThat(mCoordinator.getCanAuthFor(SECONDARY_USER, BIOMETRIC_CONVENIENCE)).isFalse();
        assertThat(mCoordinator.getCanAuthFor(SECONDARY_USER, BIOMETRIC_WEAK)).isFalse();
        assertThat(mCoordinator.getCanAuthFor(SECONDARY_USER, BIOMETRIC_STRONG)).isFalse();
    }
}
