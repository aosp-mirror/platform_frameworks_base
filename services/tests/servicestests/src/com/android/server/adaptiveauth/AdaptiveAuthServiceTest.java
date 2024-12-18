/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.adaptiveauth;

import static android.adaptiveauth.Flags.FLAG_ENABLE_ADAPTIVE_AUTH;
import static android.adaptiveauth.Flags.FLAG_REPORT_BIOMETRIC_AUTH_ATTEMPTS;
import static android.security.Flags.FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST;
import static com.android.server.adaptiveauth.AdaptiveAuthService.MAX_ALLOWED_FAILED_AUTH_ATTEMPTS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.AuthenticationStateListener;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.events.AuthenticationFailedInfo;
import android.hardware.biometrics.events.AuthenticationSucceededInfo;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.internal.widget.LockSettingsStateListener;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * atest FrameworksServicesTests:AdaptiveAuthServiceTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdaptiveAuthServiceTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final int PRIMARY_USER_ID = 0;
    private static final int MANAGED_PROFILE_USER_ID = 12;
    private static final int DEFAULT_COUNT_FAILED_AUTH_ATTEMPTS = 0;
    private static final int REASON_UNKNOWN = 0; // BiometricRequestConstants.RequestReason

    private Context mContext;
    private AdaptiveAuthService mAdaptiveAuthService;

    @Mock
    LockPatternUtils mLockPatternUtils;
    @Mock
    private LockSettingsInternal mLockSettings;
    @Mock
    private BiometricManager mBiometricManager;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private WindowManagerInternal mWindowManager;
    @Mock
    private UserManagerInternal mUserManager;

    @Captor
    ArgumentCaptor<LockSettingsStateListener> mLockSettingsStateListenerCaptor;
    @Captor
    ArgumentCaptor<AuthenticationStateListener> mAuthenticationStateListenerCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mSetFlagsRule.enableFlags(FLAG_ENABLE_ADAPTIVE_AUTH);
        mSetFlagsRule.enableFlags(FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS);
        mSetFlagsRule.enableFlags(FLAG_REPORT_BIOMETRIC_AUTH_ATTEMPTS);

        mContext = spy(ApplicationProvider.getApplicationContext());

        assumeTrue("Adaptive auth is disabled on device",
                !mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE));

        when(mContext.getSystemService(BiometricManager.class)).thenReturn(mBiometricManager);
        when(mContext.getSystemService(KeyguardManager.class)).thenReturn(mKeyguardManager);

        LocalServices.removeServiceForTest(LockSettingsInternal.class);
        LocalServices.addService(LockSettingsInternal.class, mLockSettings);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mWindowManager);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mUserManager);

        mAdaptiveAuthService = new AdaptiveAuthService(mContext, mLockPatternUtils);
        mAdaptiveAuthService.init();

        verify(mLockSettings).registerLockSettingsStateListener(
                mLockSettingsStateListenerCaptor.capture());
        verify(mBiometricManager).registerAuthenticationStateListener(
                mAuthenticationStateListenerCaptor.capture());

        // Set PRIMARY_USER_ID as the parent of MANAGED_PROFILE_USER_ID
        when(mUserManager.getProfileParentId(eq(MANAGED_PROFILE_USER_ID)))
                .thenReturn(PRIMARY_USER_ID);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(LockSettingsInternal.class);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
    }

    @Test
    public void testReportAuthAttempt_primaryAuthSucceeded()
            throws RemoteException {
        mLockSettingsStateListenerCaptor.getValue().onAuthenticationSucceeded(PRIMARY_USER_ID);
        waitForAuthCompletion();

        verifyNotLockDevice(DEFAULT_COUNT_FAILED_AUTH_ATTEMPTS /* expectedCntFailedAttempts */,
                PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_primaryAuthFailed_once()
            throws RemoteException {
        mLockSettingsStateListenerCaptor.getValue().onAuthenticationFailed(PRIMARY_USER_ID);
        waitForAuthCompletion();

        verifyNotLockDevice(1 /* expectedCntFailedAttempts */, PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_primaryAuthFailed_multiple_deviceCurrentlyLocked()
            throws RemoteException {
        // Device is currently locked and Keyguard is showing
        when(mKeyguardManager.isDeviceLocked(PRIMARY_USER_ID)).thenReturn(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);

        for (int i = 0; i < MAX_ALLOWED_FAILED_AUTH_ATTEMPTS; i++) {
            mLockSettingsStateListenerCaptor.getValue().onAuthenticationFailed(PRIMARY_USER_ID);
        }
        waitForAuthCompletion();

        verifyNotLockDevice(MAX_ALLOWED_FAILED_AUTH_ATTEMPTS /* expectedCntFailedAttempts */,
                PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_primaryAuthFailed_multiple_deviceCurrentlyNotLocked()
            throws RemoteException {
        // Device is currently not locked and Keyguard is not showing
        when(mKeyguardManager.isDeviceLocked(PRIMARY_USER_ID)).thenReturn(false);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);

        for (int i = 0; i < MAX_ALLOWED_FAILED_AUTH_ATTEMPTS; i++) {
            mLockSettingsStateListenerCaptor.getValue().onAuthenticationFailed(PRIMARY_USER_ID);
        }
        waitForAuthCompletion();

        verifyLockDevice(PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_biometricAuthSucceeded()
            throws RemoteException {
        mAuthenticationStateListenerCaptor.getValue().onAuthenticationSucceeded(
                authSuccessInfo(PRIMARY_USER_ID));
        waitForAuthCompletion();

        verifyNotLockDevice(DEFAULT_COUNT_FAILED_AUTH_ATTEMPTS /* expectedCntFailedAttempts */,
                PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_biometricAuthFailed_once()
            throws RemoteException {
        mAuthenticationStateListenerCaptor.getValue().onAuthenticationFailed(
                authFailedInfo(PRIMARY_USER_ID));
        waitForAuthCompletion();

        verifyNotLockDevice(1 /* expectedCntFailedAttempts */, PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_biometricAuthFailed_multiple_deviceCurrentlyLocked()
            throws RemoteException {
        // Device is currently locked and Keyguard is showing
        when(mKeyguardManager.isDeviceLocked(PRIMARY_USER_ID)).thenReturn(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);

        for (int i = 0; i < MAX_ALLOWED_FAILED_AUTH_ATTEMPTS; i++) {
            mAuthenticationStateListenerCaptor.getValue().onAuthenticationFailed(
                    authFailedInfo(PRIMARY_USER_ID));
        }
        waitForAuthCompletion();

        verifyNotLockDevice(MAX_ALLOWED_FAILED_AUTH_ATTEMPTS /* expectedCntFailedAttempts */,
                PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_biometricAuthFailed_multiple_deviceCurrentlyNotLocked()
            throws RemoteException {
        // Device is currently not locked and Keyguard is not showing
        when(mKeyguardManager.isDeviceLocked(PRIMARY_USER_ID)).thenReturn(false);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);

        for (int i = 0; i < MAX_ALLOWED_FAILED_AUTH_ATTEMPTS; i++) {
            mAuthenticationStateListenerCaptor.getValue().onAuthenticationFailed(
                    authFailedInfo(PRIMARY_USER_ID));
        }
        waitForAuthCompletion();

        verifyLockDevice(PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_biometricAuthFailedThenPrimaryAuthSucceeded()
            throws RemoteException {
        // Three failed biometric auth attempts
        for (int i = 0; i < 3; i++) {
            mAuthenticationStateListenerCaptor.getValue().onAuthenticationFailed(
                    authFailedInfo(PRIMARY_USER_ID));
        }
        // One successful primary auth attempt
        mLockSettingsStateListenerCaptor.getValue().onAuthenticationSucceeded(PRIMARY_USER_ID);
        waitForAuthCompletion();

        verifyNotLockDevice(DEFAULT_COUNT_FAILED_AUTH_ATTEMPTS /* expectedCntFailedAttempts */,
                PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_primaryAuthFailedThenBiometricAuthSucceeded()
            throws RemoteException {
        // Three failed primary auth attempts
        for (int i = 0; i < 3; i++) {
            mLockSettingsStateListenerCaptor.getValue().onAuthenticationFailed(PRIMARY_USER_ID);
        }
        // One successful biometric auth attempt
        mAuthenticationStateListenerCaptor.getValue().onAuthenticationSucceeded(
                authSuccessInfo(PRIMARY_USER_ID));
        waitForAuthCompletion();

        verifyNotLockDevice(DEFAULT_COUNT_FAILED_AUTH_ATTEMPTS /* expectedCntFailedAttempts */,
                PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_primaryAuthAndBiometricAuthFailed_primaryUser()
            throws RemoteException {
        // Three failed primary auth attempts
        for (int i = 0; i < 3; i++) {
            mLockSettingsStateListenerCaptor.getValue().onAuthenticationFailed(PRIMARY_USER_ID);
        }
        // Two failed biometric auth attempts
        for (int i = 0; i < 2; i++) {
            mAuthenticationStateListenerCaptor.getValue().onAuthenticationFailed(
                    authFailedInfo(PRIMARY_USER_ID));
        }
        waitForAuthCompletion();

        verifyLockDevice(PRIMARY_USER_ID);
    }

    @Test
    public void testReportAuthAttempt_primaryAuthAndBiometricAuthFailed_profileOfPrimaryUser()
            throws RemoteException {
        // Three failed primary auth attempts
        for (int i = 0; i < 3; i++) {
            mLockSettingsStateListenerCaptor.getValue()
                    .onAuthenticationFailed(MANAGED_PROFILE_USER_ID);
        }
        // Two failed biometric auth attempts
        for (int i = 0; i < 2; i++) {
            mAuthenticationStateListenerCaptor.getValue().onAuthenticationFailed(
                    authFailedInfo(MANAGED_PROFILE_USER_ID));
        }
        waitForAuthCompletion();

        verifyLockDevice(MANAGED_PROFILE_USER_ID);
    }

    private void verifyNotLockDevice(int expectedCntFailedAttempts, int userId) {
        assertEquals(expectedCntFailedAttempts,
                mAdaptiveAuthService.mFailedAttemptsForUser.get(userId));
        verify(mWindowManager, never()).lockNow();
    }

    private void verifyLockDevice(int userId) {
        assertEquals(MAX_ALLOWED_FAILED_AUTH_ATTEMPTS,
                mAdaptiveAuthService.mFailedAttemptsForUser.get(userId));
        verify(mLockPatternUtils).requireStrongAuth(
                eq(SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST), eq(userId));
        // If userId is MANAGED_PROFILE_USER_ID, the StrongAuthFlag of its parent (PRIMARY_USER_ID)
        // should also be verified
        if (userId == MANAGED_PROFILE_USER_ID) {
            verify(mLockPatternUtils).requireStrongAuth(
                    eq(SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST), eq(PRIMARY_USER_ID));
        }
        verify(mWindowManager).lockNow();
    }

    /**
     * Wait for all auth events to complete before verification
     */
    private static void waitForAuthCompletion() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private AuthenticationSucceededInfo authSuccessInfo(int userId) {
        return new AuthenticationSucceededInfo.Builder(BiometricSourceType.FINGERPRINT,
                REASON_UNKNOWN, true, userId).build();
    }


    private AuthenticationFailedInfo authFailedInfo(int userId) {
        return new AuthenticationFailedInfo.Builder(BiometricSourceType.FINGERPRINT, REASON_UNKNOWN,
                userId).build();
    }

}
