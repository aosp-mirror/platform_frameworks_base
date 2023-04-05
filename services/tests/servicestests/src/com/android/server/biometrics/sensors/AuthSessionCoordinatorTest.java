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

import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;

@Presubmit
@SmallTest
public class AuthSessionCoordinatorTest {
    private static final int PRIMARY_USER = 0;
    private static final int SECONDARY_USER = 10;

    private AuthSessionCoordinator mCoordinator;
    @Mock
    private Clock mClock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mClock.millis()).thenReturn(0L);
        mCoordinator = new AuthSessionCoordinator(mClock);
    }

    @Test
    public void testUserUnlockedWithWeak() {
        mCoordinator.authStartedFor(PRIMARY_USER, 1 /* sensorId */, 0 /* requestId */);
        mCoordinator.lockedOutFor(PRIMARY_USER, BIOMETRIC_STRONG, 1 /* sensorId */,
                0 /* requestId */);

        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);

        mCoordinator.authStartedFor(PRIMARY_USER, 1 /* sensorId */, 0 /* requestId */);
        mCoordinator.authEndedFor(PRIMARY_USER, BIOMETRIC_WEAK, 1 /* sensorId */,
                0 /* requestId */, true /* wasSuccessful */);

        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
    }

    @Test
    public void testUserLocked() {
        mCoordinator.authStartedFor(PRIMARY_USER, 1 /* sensorId */, 0 /* requestId */);
        mCoordinator.authStartedFor(PRIMARY_USER, 2 /* sensorId */, 0 /* requestId */);
        mCoordinator.lockedOutFor(PRIMARY_USER, BIOMETRIC_STRONG, 1 /* sensorId */,
                0 /* requestId */);
        mCoordinator.authEndedFor(PRIMARY_USER, BIOMETRIC_WEAK, 2 /* sensorId */,
                0 /* requestId */, false /* wasSuccessful */);

        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);

        mCoordinator.authStartedFor(PRIMARY_USER, 1 /* sensorId */, 0 /* requestId */);
        mCoordinator.authEndedFor(PRIMARY_USER, BIOMETRIC_WEAK, 1 /* sensorId */,
                0 /* requestId */, false /* wasSuccessful */);

        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
    }

    @Test
    public void testWeakAndConvenientCannotResetLockout() {
        mCoordinator.authStartedFor(PRIMARY_USER, 1 /* sensorId */, 0 /* requestId */);
        mCoordinator.lockedOutFor(PRIMARY_USER, BIOMETRIC_STRONG, 1 /* sensorId */,
                0 /* requestId */);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);

        mCoordinator.resetLockoutFor(PRIMARY_USER, BIOMETRIC_WEAK, 0 /* requestId */);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);

        mCoordinator.resetLockoutFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE, 0 /* requestId */);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
    }

    @Test
    public void testUserCanAuthDuringLockoutOfSameSession() {
        mCoordinator.resetLockoutFor(PRIMARY_USER, BIOMETRIC_STRONG, 0 /* requestId */);

        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);

        mCoordinator.authStartedFor(PRIMARY_USER, 1 /* sensorId */, 0 /* requestId */);
        mCoordinator.authStartedFor(PRIMARY_USER, 2 /* sensorId */, 0 /* requestId */);
        mCoordinator.lockedOutFor(PRIMARY_USER, BIOMETRIC_WEAK, 2 /* sensorId */,
                0 /* requestId */);

        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
    }

    @Test
    public void testMultiUserAuth() {
        mCoordinator.authStartedFor(PRIMARY_USER, 1 /* sensorId */, 0 /* requestId */);
        mCoordinator.lockedOutFor(PRIMARY_USER, BIOMETRIC_STRONG, 1 /* sensorId */,
                0 /* requestId */);

        mCoordinator.authStartedFor(SECONDARY_USER, 1 /* sensorId */, 0 /* requestId */);
        mCoordinator.lockedOutFor(SECONDARY_USER, BIOMETRIC_STRONG, 1 /* sensorId */,
                0 /* requestId */);

        mCoordinator.resetLockoutFor(PRIMARY_USER, BIOMETRIC_STRONG, 0 /* requestId */);

        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);

        assertThat(
                mCoordinator.getLockoutStateFor(SECONDARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(SECONDARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(SECONDARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);

        mCoordinator.authStartedFor(PRIMARY_USER, 1 /* sensorId */, 0 /* requestId */);
        mCoordinator.authStartedFor(PRIMARY_USER, 2 /* sensorId */, 0 /* requestId */);
        mCoordinator.lockedOutFor(PRIMARY_USER, BIOMETRIC_WEAK, 2 /* sensorId */,
                0 /* requestId */);

        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mCoordinator.getLockoutStateFor(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);

        assertThat(
                mCoordinator.getLockoutStateFor(SECONDARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(SECONDARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mCoordinator.getLockoutStateFor(SECONDARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
    }
}
