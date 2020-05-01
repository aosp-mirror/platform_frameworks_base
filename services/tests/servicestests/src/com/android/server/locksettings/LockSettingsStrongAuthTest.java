/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.locksettings;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT;
import static com.android.server.locksettings.LockSettingsStrongAuth.DEFAULT_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_MS;
import static com.android.server.locksettings.LockSettingsStrongAuth.DEFAULT_NON_STRONG_BIOMETRIC_TIMEOUT_MS;
import static com.android.server.locksettings.LockSettingsStrongAuth.NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_ALARM_TAG;
import static com.android.server.locksettings.LockSettingsStrongAuth.NON_STRONG_BIOMETRIC_TIMEOUT_ALARM_TAG;
import static com.android.server.locksettings.LockSettingsStrongAuth.STRONG_AUTH_TIMEOUT_ALARM_TAG;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.locksettings.LockSettingsStrongAuth.NonStrongBiometricIdleTimeoutAlarmListener;
import com.android.server.locksettings.LockSettingsStrongAuth.NonStrongBiometricTimeoutAlarmListener;
import com.android.server.locksettings.LockSettingsStrongAuth.StrongAuthTimeoutAlarmListener;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class LockSettingsStrongAuthTest {

    private static final String TAG = LockSettingsStrongAuthTest.class.getSimpleName();

    private static final int PRIMARY_USER_ID = 0;

    private LockSettingsStrongAuth mStrongAuth;
    private final int mDefaultStrongAuthFlags = STRONG_AUTH_NOT_REQUIRED;
    private final boolean mDefaultIsNonStrongBiometricAllowed = true;

    @Mock
    private Context mContext;
    @Mock
    private LockSettingsStrongAuth.Injector mInjector;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private DevicePolicyManager mDPM;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mInjector.getAlarmManager(mContext)).thenReturn(mAlarmManager);
        when(mInjector.getDefaultStrongAuthFlags(mContext)).thenReturn(mDefaultStrongAuthFlags);
        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(mDPM);

        mStrongAuth = new LockSettingsStrongAuth(mContext, mInjector);
    }

    @Test
    public void testScheduleNonStrongBiometricIdleTimeout() {
        final long nextAlarmTime = 1000;
        when(mInjector.getNextAlarmTimeMs(DEFAULT_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_MS))
                .thenReturn(nextAlarmTime);
        mStrongAuth.scheduleNonStrongBiometricIdleTimeout(PRIMARY_USER_ID);

        waitForIdle();
        NonStrongBiometricIdleTimeoutAlarmListener alarm = mStrongAuth
                .mNonStrongBiometricIdleTimeoutAlarmListener.get(PRIMARY_USER_ID);
        // verify that a new alarm for idle timeout is added for the user
        assertNotNull(alarm);
        // verify that the alarm is scheduled
        verifyAlarm(nextAlarmTime, NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_ALARM_TAG, alarm);
    }

    @Test
    public void testSetIsNonStrongBiometricAllowed_disallowed() {
        mStrongAuth.setIsNonStrongBiometricAllowed(false /* allowed */, PRIMARY_USER_ID);

        waitForIdle();
        // verify that unlocking with non-strong biometrics is not allowed
        assertFalse(mStrongAuth.mIsNonStrongBiometricAllowedForUser
                .get(PRIMARY_USER_ID, mDefaultIsNonStrongBiometricAllowed));
    }

    @Test
    public void testReportSuccessfulBiometricUnlock_nonStrongBiometric_fallbackTimeout() {
        final long nextAlarmTime = 1000;
        when(mInjector.getNextAlarmTimeMs(DEFAULT_NON_STRONG_BIOMETRIC_TIMEOUT_MS))
                .thenReturn(nextAlarmTime);
        mStrongAuth.reportSuccessfulBiometricUnlock(false /* isStrongBiometric */, PRIMARY_USER_ID);

        waitForIdle();
        NonStrongBiometricTimeoutAlarmListener alarm =
                mStrongAuth.mNonStrongBiometricTimeoutAlarmListener.get(PRIMARY_USER_ID);
        // verify that a new alarm for fallback timeout is added for the user
        assertNotNull(alarm);
        // verify that the alarm is scheduled
        verifyAlarm(nextAlarmTime, NON_STRONG_BIOMETRIC_TIMEOUT_ALARM_TAG, alarm);
    }

    @Test
    public void testRequireStrongAuth_nonStrongBiometric_fallbackTimeout() {
        mStrongAuth.requireStrongAuth(
                STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT /* strongAuthReason */,
                PRIMARY_USER_ID);

        waitForIdle();
        // verify that the StrongAuthFlags for the user contains the expected flag
        final int expectedFlag = STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT;
        verifyStrongAuthFlags(expectedFlag, PRIMARY_USER_ID);
    }

    @Test
    public void testReportSuccessfulBiometricUnlock_nonStrongBiometric_cancelIdleTimeout() {
        // lock device and schedule an alarm for non-strong biometric idle timeout
        mStrongAuth.scheduleNonStrongBiometricIdleTimeout(PRIMARY_USER_ID);
        // unlock with non-strong biometric
        mStrongAuth.reportSuccessfulBiometricUnlock(false /* isStrongBiometric */, PRIMARY_USER_ID);

        waitForIdle();

        // verify that the current alarm for idle timeout is cancelled after a successful unlock
        verify(mAlarmManager).cancel(any(NonStrongBiometricIdleTimeoutAlarmListener.class));
    }

    @Test
    public void testReportSuccessfulBiometricUnlock_strongBio_cancelAlarmsAndAllowNonStrongBio() {
        setupAlarms(PRIMARY_USER_ID);
        mStrongAuth.reportSuccessfulBiometricUnlock(true /* isStrongBiometric */, PRIMARY_USER_ID);

        waitForIdle();
        // verify that unlocking with strong biometric cancels alarms for fallback and idle timeout
        // and re-allow unlocking with non-strong biometric
        verifyAlarmsCancelledAndNonStrongBiometricAllowed(PRIMARY_USER_ID);
    }

    @Test
    public void testReportSuccessfulStrongAuthUnlock_schedulePrimaryAuthTimeout() {
        final long currentTime = 1000;
        final long timeout = 1000;
        final long nextAlarmTime = currentTime + timeout;
        when(mInjector.getElapsedRealtimeMs()).thenReturn(currentTime);
        when(mDPM.getRequiredStrongAuthTimeout(null, PRIMARY_USER_ID)).thenReturn(timeout);
        mStrongAuth.reportSuccessfulStrongAuthUnlock(PRIMARY_USER_ID);

        waitForIdle();
        StrongAuthTimeoutAlarmListener alarm =
                mStrongAuth.mStrongAuthTimeoutAlarmListenerForUser.get(PRIMARY_USER_ID);
        // verify that a new alarm for primary auth timeout is added for the user
        assertNotNull(alarm);
        // verify that the alarm is scheduled
        verifyAlarm(nextAlarmTime, STRONG_AUTH_TIMEOUT_ALARM_TAG, alarm);
    }

    @Test
    public void testReportSuccessfulStrongAuthUnlock_testRefreshStrongAuthTimeout() {
        final long currentTime = 1000;
        final long oldTimeout = 5000;
        final long nextAlarmTime = currentTime + oldTimeout;
        when(mInjector.getElapsedRealtimeMs()).thenReturn(currentTime);
        when(mDPM.getRequiredStrongAuthTimeout(null, PRIMARY_USER_ID)).thenReturn(oldTimeout);
        mStrongAuth.reportSuccessfulStrongAuthUnlock(PRIMARY_USER_ID);
        waitForIdle();

        StrongAuthTimeoutAlarmListener alarm =
                mStrongAuth.mStrongAuthTimeoutAlarmListenerForUser.get(PRIMARY_USER_ID);
        assertEquals(currentTime, alarm.getLatestStrongAuthTime());
        verifyAlarm(nextAlarmTime, STRONG_AUTH_TIMEOUT_ALARM_TAG, alarm);

        final long newTimeout = 3000;
        when(mDPM.getRequiredStrongAuthTimeout(null, PRIMARY_USER_ID)).thenReturn(newTimeout);
        mStrongAuth.refreshStrongAuthTimeout(PRIMARY_USER_ID);
        waitForIdle();
        verify(mAlarmManager).cancel(alarm);
        verifyAlarm(currentTime + newTimeout, STRONG_AUTH_TIMEOUT_ALARM_TAG, alarm);
    }

    @Test
    public void testReportSuccessfulStrongAuthUnlock_cancelAlarmsAndAllowNonStrongBio() {
        setupAlarms(PRIMARY_USER_ID);
        mStrongAuth.reportSuccessfulStrongAuthUnlock(PRIMARY_USER_ID);

        waitForIdle();
        // verify that unlocking with primary auth (PIN/pattern/password) cancels alarms
        // for fallback and idle timeout and re-allow unlocking with non-strong biometric
        verifyAlarmsCancelledAndNonStrongBiometricAllowed(PRIMARY_USER_ID);
    }

    @Test
    public void testFallbackTimeout_convenienceBiometric_weakBiometric() {
        // assume that unlock with convenience biometric
        mStrongAuth.reportSuccessfulBiometricUnlock(false /* isStrongBiometric */, PRIMARY_USER_ID);
        // assume that unlock again with weak biometric
        mStrongAuth.reportSuccessfulBiometricUnlock(false /* isStrongBiometric */, PRIMARY_USER_ID);

        waitForIdle();
        // verify that the fallback alarm scheduled when unlocking with convenience biometric is
        // not affected when unlocking again with weak biometric
        verify(mAlarmManager, never()).cancel(any(NonStrongBiometricTimeoutAlarmListener.class));
        assertNotNull(mStrongAuth.mNonStrongBiometricTimeoutAlarmListener.get(PRIMARY_USER_ID));
    }

    private void verifyAlarm(long when, String tag, AlarmManager.OnAlarmListener alarm) {
        verify(mAlarmManager).set(
                eq(AlarmManager.ELAPSED_REALTIME),
                eq(when),
                eq(tag),
                eq(alarm),
                eq(mStrongAuth.mHandler));
    }

    private void verifyStrongAuthFlags(int reason, int userId) {
        final int flags = mStrongAuth.mStrongAuthForUser.get(userId, mDefaultStrongAuthFlags);
        Log.d(TAG, "verifyStrongAuthFlags:"
                + " reason=" + Integer.toHexString(reason)
                + " userId=" + userId
                + " flags=" + Integer.toHexString(flags));
        assertTrue(containsFlag(flags, reason));
    }

    private void setupAlarms(int userId) {
        // schedule (a) an alarm for non-strong biometric fallback timeout and (b) an alarm for
        // non-strong biometric idle timeout, so later we can verify that unlocking with
        // strong biometric or primary auth will cancel those alarms
        mStrongAuth.reportSuccessfulBiometricUnlock(false /* isStrongBiometric */, PRIMARY_USER_ID);
        mStrongAuth.scheduleNonStrongBiometricIdleTimeout(PRIMARY_USER_ID);
    }

    private void verifyAlarmsCancelledAndNonStrongBiometricAllowed(int userId) {
        // verify that the current alarm for non-strong biometric fallback timeout is cancelled and
        // removed
        verify(mAlarmManager).cancel(any(NonStrongBiometricTimeoutAlarmListener.class));
        assertNull(mStrongAuth.mNonStrongBiometricTimeoutAlarmListener.get(userId));

        // verify that the current alarm for non-strong biometric idle timeout is cancelled
        verify(mAlarmManager).cancel(any(NonStrongBiometricIdleTimeoutAlarmListener.class));

        // verify that unlocking with non-strong biometrics is allowed
        assertTrue(mStrongAuth.mIsNonStrongBiometricAllowedForUser
                .get(userId, mDefaultIsNonStrongBiometricAllowed));
    }

    private static boolean containsFlag(int haystack, int needle) {
        return (haystack & needle) != 0;
    }

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
