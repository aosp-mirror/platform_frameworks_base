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
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;

@SmallTest
@Presubmit
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MultiBiometricLockoutStateTest {
    private static final int PRIMARY_USER = 0;
    private MultiBiometricLockoutState mLockoutState;
    @Mock
    private Clock mClock;

    private static void unlockAllBiometrics(MultiBiometricLockoutState lockoutState, int userId) {
        lockoutState.clearPermanentLockOut(userId, BIOMETRIC_STRONG);
        assertThat(lockoutState.getLockoutState(userId, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(lockoutState.getLockoutState(userId, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(lockoutState.getLockoutState(userId, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
    }

    private static void lockoutAllBiometrics(MultiBiometricLockoutState lockoutState, int userId) {
        lockoutState.setPermanentLockOut(userId, BIOMETRIC_STRONG);
        assertThat(lockoutState.getLockoutState(userId, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(lockoutState.getLockoutState(userId, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(lockoutState.getLockoutState(userId, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
    }

    private void unlockAllBiometrics() {
        unlockAllBiometrics(mLockoutState, PRIMARY_USER);
    }

    private void lockoutAllBiometrics() {
        lockoutAllBiometrics(mLockoutState, PRIMARY_USER);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mClock.millis()).thenReturn(0L);
        mLockoutState = new MultiBiometricLockoutState(mClock);
    }

    @Test
    public void testInitialStateLockedOut() {
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
    }

    @Test
    public void testConvenienceLockout() {
        unlockAllBiometrics();
        mLockoutState.setPermanentLockOut(PRIMARY_USER, BIOMETRIC_CONVENIENCE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(
                mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
    }

    @Test
    public void testWeakLockout() {
        unlockAllBiometrics();
        mLockoutState.setPermanentLockOut(PRIMARY_USER, BIOMETRIC_WEAK);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(
                mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
    }

    @Test
    public void testStrongLockout() {
        lockoutAllBiometrics();
        mLockoutState.setPermanentLockOut(PRIMARY_USER, BIOMETRIC_STRONG);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(
                mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
    }

    @Test
    public void testConvenienceUnlock() {
        lockoutAllBiometrics();
        mLockoutState.clearPermanentLockOut(PRIMARY_USER, BIOMETRIC_CONVENIENCE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
    }

    @Test
    public void testWeakUnlock() {
        lockoutAllBiometrics();
        mLockoutState.clearPermanentLockOut(PRIMARY_USER, BIOMETRIC_WEAK);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
    }

    @Test
    public void testStrongUnlock() {
        lockoutAllBiometrics();
        mLockoutState.clearPermanentLockOut(PRIMARY_USER, BIOMETRIC_STRONG);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
    }

    @Test
    public void multiUser_userOneDoesNotAffectUserTwo() {
        final int userOne = 1;
        final int userTwo = 2;
        MultiBiometricLockoutState lockoutState = new MultiBiometricLockoutState(mClock);
        lockoutAllBiometrics(lockoutState, userOne);
        lockoutAllBiometrics(lockoutState, userTwo);

        lockoutState.clearPermanentLockOut(userOne, BIOMETRIC_WEAK);
        assertThat(lockoutState.getLockoutState(userOne, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(lockoutState.getLockoutState(userOne, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(lockoutState.getLockoutState(userOne, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);

        assertThat(lockoutState.getLockoutState(userTwo, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(lockoutState.getLockoutState(userTwo, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
        assertThat(lockoutState.getLockoutState(userTwo, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_PERMANENT);
    }

    @Test
    public void testTimedLockout() {
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);

        mLockoutState.setTimedLockout(PRIMARY_USER, BIOMETRIC_STRONG);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_TIMED);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_TIMED);
        assertThat(
                mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_TIMED);
    }

    @Test
    public void testTimedLockoutAfterDuration() {
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);

        mLockoutState.setTimedLockout(PRIMARY_USER, BIOMETRIC_STRONG);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_TIMED);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_TIMED);
        assertThat(
                mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_TIMED);

        mLockoutState.clearTimedLockout(PRIMARY_USER, BIOMETRIC_STRONG);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_STRONG)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_WEAK)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
        assertThat(mLockoutState.getLockoutState(PRIMARY_USER, BIOMETRIC_CONVENIENCE)).isEqualTo(
                LockoutTracker.LOCKOUT_NONE);
    }
}
