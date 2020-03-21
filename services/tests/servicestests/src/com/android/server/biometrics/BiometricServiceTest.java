/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.biometrics;

import static android.hardware.biometrics.BiometricManager.Authenticators;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertNotNull;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.security.KeyStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.statusbar.IStatusBarService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Random;

@SmallTest
public class BiometricServiceTest {

    private static final String TAG = "BiometricServiceTest";

    private static final String TEST_PACKAGE_NAME = "test_package";

    private static final String ERROR_HW_UNAVAILABLE = "hw_unavailable";
    private static final String ERROR_NOT_RECOGNIZED = "not_recognized";
    private static final String ERROR_TIMEOUT = "error_timeout";
    private static final String ERROR_CANCELED = "error_canceled";
    private static final String ERROR_UNABLE_TO_PROCESS = "error_unable_to_process";
    private static final String ERROR_USER_CANCELED = "error_user_canceled";
    private static final String ERROR_LOCKOUT = "error_lockout";

    private static final String FINGERPRINT_ACQUIRED_SENSOR_DIRTY = "sensor_dirty";

    private BiometricService mBiometricService;

    @Mock
    private Context mContext;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private Resources mResources;
    @Mock
    IBiometricServiceReceiver mReceiver1;
    @Mock
    IBiometricServiceReceiver mReceiver2;
    @Mock
    BiometricService.Injector mInjector;
    @Mock
    IBiometricAuthenticator mFingerprintAuthenticator;
    @Mock
    IBiometricAuthenticator mFaceAuthenticator;
    @Mock
    ITrustManager mTrustManager;
    @Mock
    DevicePolicyManager mDevicePolicyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE))
                .thenReturn(mDevicePolicyManager);

        when(mInjector.getActivityManagerService()).thenReturn(mock(IActivityManager.class));
        when(mInjector.getStatusBarService()).thenReturn(mock(IStatusBarService.class));
        when(mInjector.getSettingObserver(any(), any(), any()))
                .thenReturn(mock(BiometricService.SettingObserver.class));
        when(mInjector.getKeyStore()).thenReturn(mock(KeyStore.class));
        when(mInjector.isDebugEnabled(any(), anyInt())).thenReturn(false);
        when(mInjector.getBiometricStrengthController(any()))
                .thenReturn(mock(BiometricStrengthController.class));
        when(mInjector.getTrustManager()).thenReturn(mTrustManager);

        when(mResources.getString(R.string.biometric_error_hw_unavailable))
                .thenReturn(ERROR_HW_UNAVAILABLE);
        when(mResources.getString(R.string.biometric_not_recognized))
                .thenReturn(ERROR_NOT_RECOGNIZED);
        when(mResources.getString(R.string.biometric_error_user_canceled))
                .thenReturn(ERROR_USER_CANCELED);

        final String[] config = {
                "0:2:15",  // ID0:Fingerprint:Strong
                "1:8:15",  // ID1:Face:Strong
                "2:4:255", // ID2:Iris:Weak
        };

        when(mInjector.getConfiguration(any())).thenReturn(config);
    }

    @Test
    public void testAuthenticate_credentialAllowedButNotSetup_returnsNoDeviceCredential()
            throws Exception {
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(false);

        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_NONE),
                eq(BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testAuthenticate_credentialAllowedAndSetup_callsSystemUI() throws Exception {
        // When no biometrics are enrolled, but credentials are set up, status bar should be
        // invoked right away with showAuthenticationDialog

        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(true);

        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL);
        waitForIdle();

        assertNull(mBiometricService.mPendingAuthSession);
        // StatusBar showBiometricDialog invoked
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mCurrentAuthSession.mBundle),
                any(IBiometricServiceReceiverInternal.class),
                eq(0),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                eq(TEST_PACKAGE_NAME),
                anyLong() /* sessionId */);
    }

    @Test
    public void testAuthenticate_withoutHardware_returnsErrorHardwareNotPresent() throws
            Exception {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_NONE),
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testAuthenticate_withoutEnrolled_returnsErrorNoBiometrics() throws Exception {
        when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);

        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();
        mBiometricService.mImpl.registerAuthenticator(0 /* id */,
                BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                mFingerprintAuthenticator);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testAuthenticate_notStrongEnough_returnsHardwareNotPresent() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_WEAK);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                Authenticators.BIOMETRIC_STRONG);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_NONE),
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testAuthenticate_picksStrongIfAvailable() throws Exception {
        // If both strong and weak are available, and the caller requires STRONG, authentication
        // is able to proceed.

        final int[] modalities = new int[] {
                BiometricAuthenticator.TYPE_FINGERPRINT,
                BiometricAuthenticator.TYPE_FACE,
        };

        final int[] strengths = new int[] {
                Authenticators.BIOMETRIC_WEAK,
                Authenticators.BIOMETRIC_STRONG,
        };

        setupAuthForMultiple(modalities, strengths);

        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, Authenticators.BIOMETRIC_STRONG);
        waitForIdle();
        verify(mReceiver1, never()).onError(
                anyInt(),
                anyInt(),
                anyInt() /* vendorCode */);

        // StatusBar showBiometricDialog invoked with face, which was set up to be STRONG
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mCurrentAuthSession.mBundle),
                any(IBiometricServiceReceiverInternal.class),
                eq(BiometricAuthenticator.TYPE_FACE),
                eq(false) /* requireConfirmation */,
                anyInt() /* userId */,
                eq(TEST_PACKAGE_NAME),
                anyLong() /* sessionId */);
    }

    @Test
    public void testAuthenticate_whenHalIsDead_returnsErrorHardwareUnavailable() throws
            Exception {
        when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(false);

        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();
        mBiometricService.mImpl.registerAuthenticator(0 /* id */,
                BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                mFingerprintAuthenticator);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testAuthenticateFace_respectsUserSetting()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);

        // Disabled in user settings receives onError
        when(mBiometricService.mSettingObserver.getFaceEnabledForApps(anyInt())).thenReturn(false);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_NONE),
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE),
                eq(0 /* vendorCode */));

        // Enrolled, not disabled in settings, user requires confirmation in settings
        resetReceiver();
        when(mBiometricService.mSettingObserver.getFaceEnabledForApps(anyInt())).thenReturn(true);
        when(mBiometricService.mSettingObserver.getFaceAlwaysRequireConfirmation(anyInt()))
                .thenReturn(true);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */);
        waitForIdle();
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());
        verify(mBiometricService.mAuthenticators.get(0).impl).prepareForAuthentication(
                eq(true) /* requireConfirmation */,
                any(IBinder.class),
                anyLong() /* sessionId */,
                anyInt() /* userId */,
                any(IBiometricServiceReceiverInternal.class),
                anyString() /* opPackageName */,
                anyInt() /* cookie */,
                anyInt() /* callingUid */,
                anyInt() /* callingPid */,
                anyInt() /* callingUserId */);

        // Enrolled, not disabled in settings, user doesn't require confirmation in settings
        resetReceiver();
        when(mBiometricService.mSettingObserver.getFaceAlwaysRequireConfirmation(anyInt()))
                .thenReturn(false);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */);
        waitForIdle();
        verify(mBiometricService.mAuthenticators.get(0).impl).prepareForAuthentication(
                eq(false) /* requireConfirmation */,
                any(IBinder.class),
                anyLong() /* sessionId */,
                anyInt() /* userId */,
                any(IBiometricServiceReceiverInternal.class),
                anyString() /* opPackageName */,
                anyInt() /* cookie */,
                anyInt() /* callingUid */,
                anyInt() /* callingPid */,
                anyInt() /* callingUserId */);
    }

    @Test
    public void testAuthenticate_happyPathWithoutConfirmation_strongBiometric() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        testAuthenticate_happyPathWithoutConfirmation(true /* isStrongBiometric */);
    }

    @Test
    public void testAuthenticate_happyPathWithoutConfirmation_weakBiometric() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_WEAK);
        testAuthenticate_happyPathWithoutConfirmation(false /* isStrongBiometric */);
    }

    private void testAuthenticate_happyPathWithoutConfirmation(boolean isStrongBiometric)
            throws Exception {
        // Start testing the happy path
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */);
        waitForIdle();

        // Creates a pending auth session with the correct initial states
        assertEquals(mBiometricService.mPendingAuthSession.mState,
                BiometricService.STATE_AUTH_CALLED);

        // Invokes <Modality>Service#prepareForAuthentication
        ArgumentCaptor<Integer> cookieCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());
        verify(mBiometricService.mAuthenticators.get(0).impl).prepareForAuthentication(
                anyBoolean() /* requireConfirmation */,
                any(IBinder.class),
                anyLong() /* sessionId */,
                anyInt() /* userId */,
                any(IBiometricServiceReceiverInternal.class),
                anyString() /* opPackageName */,
                cookieCaptor.capture() /* cookie */,
                anyInt() /* callingUid */,
                anyInt() /* callingPid */,
                anyInt() /* callingUserId */);

        // onReadyForAuthentication, mCurrentAuthSession state OK
        mBiometricService.mImpl.onReadyForAuthentication(cookieCaptor.getValue(),
                anyBoolean() /* requireConfirmation */, anyInt() /* userId */);
        waitForIdle();
        assertNull(mBiometricService.mPendingAuthSession);
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_STARTED);

        // startPreparedClient invoked
        verify(mBiometricService.mAuthenticators.get(0).impl)
                .startPreparedClient(cookieCaptor.getValue());

        // StatusBar showBiometricDialog invoked
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mCurrentAuthSession.mBundle),
                any(IBiometricServiceReceiverInternal.class),
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                eq(TEST_PACKAGE_NAME),
                anyLong() /* sessionId */);

        // Hardware authenticated
        final byte[] HAT = generateRandomHAT();
        mBiometricService.mInternalReceiver.onAuthenticationSucceeded(
                false /* requireConfirmation */,
                HAT,
                isStrongBiometric /* isStrongBiometric */);
        waitForIdle();
        // Waiting for SystemUI to send dismissed callback
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTHENTICATED_PENDING_SYSUI);
        // Notify SystemUI hardware authenticated
        verify(mBiometricService.mStatusBarService).onBiometricAuthenticated();

        // SystemUI sends callback with dismissed reason
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED,
                null /* credentialAttestation */);
        waitForIdle();
        // HAT sent to keystore
        if (isStrongBiometric) {
            verify(mBiometricService.mKeyStore).addAuthToken(AdditionalMatchers.aryEq(HAT));
        } else {
            verify(mBiometricService.mKeyStore, never()).addAuthToken(any(byte[].class));
        }
        // Send onAuthenticated to client
        verify(mReceiver1).onAuthenticationSucceeded(
                BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC);
        // Current session becomes null
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testAuthenticate_noBiometrics_credentialAllowed() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(false);
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(true);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK);
        waitForIdle();

        assertEquals(BiometricService.STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mCurrentAuthSession.mState);
        assertEquals(Authenticators.DEVICE_CREDENTIAL,
                mBiometricService.mCurrentAuthSession.mBundle
                        .getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED));
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mCurrentAuthSession.mBundle),
                any(IBiometricServiceReceiverInternal.class),
                eq(0 /* biometricModality */),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                eq(TEST_PACKAGE_NAME),
                anyLong() /* sessionId */);
    }

    @Test
    public void testAuthenticate_happyPathWithConfirmation_strongBiometric() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        testAuthenticate_happyPathWithConfirmation(true /* isStrongBiometric */);
    }

    @Test
    public void testAuthenticate_happyPathWithConfirmation_weakBiometric() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_WEAK);
        testAuthenticate_happyPathWithConfirmation(false /* isStrongBiometric */);
    }

    private void testAuthenticate_happyPathWithConfirmation(boolean isStrongBiometric)
            throws Exception {
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */, null /* authenticators */);

        // Test authentication succeeded goes to PENDING_CONFIRMATION and that the HAT is not
        // sent to KeyStore yet
        final byte[] HAT = generateRandomHAT();
        mBiometricService.mInternalReceiver.onAuthenticationSucceeded(
                true /* requireConfirmation */,
                HAT,
                isStrongBiometric /* isStrongBiometric */);
        waitForIdle();
        // Waiting for SystemUI to send confirmation callback
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_PENDING_CONFIRM);
        verify(mBiometricService.mKeyStore, never()).addAuthToken(any(byte[].class));

        // SystemUI sends confirm, HAT is sent to keystore and client is notified.
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED,
                null /* credentialAttestation */);
        waitForIdle();
        if (isStrongBiometric) {
            verify(mBiometricService.mKeyStore).addAuthToken(AdditionalMatchers.aryEq(HAT));
        } else {
            verify(mBiometricService.mKeyStore, never()).addAuthToken(any(byte[].class));
        }
        verify(mReceiver1).onAuthenticationSucceeded(
                BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC);
    }

    @Test
    public void testAuthenticate_no_Biometrics_noCredential() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(false);
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(false);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_STRONG);
        waitForIdle();

        verify(mReceiver1).onError(anyInt() /* modality */,
                eq(BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS)/* error */,
                eq(0) /* vendorCode */);
    }

    @Test
    public void testRejectFace_whenAuthenticating_notifiesSystemUIAndClient_thenPaused()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onAuthenticationFailed();
        waitForIdle();

        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(BiometricAuthenticator.TYPE_NONE),
                eq(BiometricConstants.BIOMETRIC_PAUSED_REJECTED),
                eq(0 /* vendorCode */));
        verify(mReceiver1).onAuthenticationFailed();
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_PAUSED);
    }

    @Test
    public void testRejectFingerprint_whenAuthenticating_notifiesAndKeepsAuthenticating()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onAuthenticationFailed();
        waitForIdle();

        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(BiometricAuthenticator.TYPE_NONE),
                eq(BiometricConstants.BIOMETRIC_PAUSED_REJECTED),
                eq(0 /* vendorCode */));
        verify(mReceiver1).onAuthenticationFailed();
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_STARTED);
    }

    @Test
    public void testErrorHalTimeout_whenAuthenticating_entersPausedState() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricAuthenticator.TYPE_FACE,
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                0 /* vendorCode */);
        waitForIdle();

        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_PAUSED);
        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(BiometricAuthenticator.TYPE_FACE),
                eq(BiometricConstants.BIOMETRIC_ERROR_TIMEOUT),
                eq(0 /* vendorCode */));
        // Timeout does not count as fail as per BiometricPrompt documentation.
        verify(mReceiver1, never()).onAuthenticationFailed();

        // No pending auth session. Pressing try again will create one.
        assertNull(mBiometricService.mPendingAuthSession);

        // Pressing "Try again" on SystemUI starts a new auth session.
        mBiometricService.mInternalReceiver.onTryAgainPressed();
        waitForIdle();

        // The last one is still paused, and a new one has been created.
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_PAUSED);
        assertEquals(mBiometricService.mPendingAuthSession.mState,
                BiometricService.STATE_AUTH_CALLED);

        // Test resuming when hardware becomes ready. SystemUI should not be requested to
        // show another dialog since it's already showing.
        resetStatusBar();
        startPendingAuthSession(mBiometricService);
        waitForIdle();
        verify(mBiometricService.mStatusBarService, never()).showAuthenticationDialog(
                any(Bundle.class),
                any(IBiometricServiceReceiverInternal.class),
                anyInt(),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyString(),
                anyLong() /* sessionId */);
    }

    @Test
    public void testErrorFromHal_whenPaused_notifiesSystemUIAndClient() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricAuthenticator.TYPE_FACE,
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                0 /* vendorCode */);
        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricAuthenticator.TYPE_FACE,
                BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                0 /* vendorCode */);
        waitForIdle();

        // Client receives error immediately
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_FACE),
                eq(BiometricConstants.BIOMETRIC_ERROR_CANCELED),
                eq(0 /* vendorCode */));
        // Dialog is hidden immediately
        verify(mBiometricService.mStatusBarService).hideAuthenticationDialog();
        // Auth session is over
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testErrorFromHal_whileAuthenticating_waitsForSysUIBeforeNotifyingClient()
            throws Exception {
        // For errors that show in SystemUI, BiometricService stays in STATE_ERROR_PENDING_SYSUI
        // until SystemUI notifies us that the dialog is dismissed at which point the current
        // session is done.
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricAuthenticator.TYPE_FINGERPRINT,
                BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS,
                0 /* vendorCode */);
        waitForIdle();

        // Sends error to SystemUI and does not notify client yet
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_ERROR_PENDING_SYSUI);
        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS),
                eq(0 /* vendorCode */));
        verify(mBiometricService.mStatusBarService, never()).hideAuthenticationDialog();
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());

        // SystemUI animation completed, client is notified, auth session is over
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_ERROR, null /* credentialAttestation */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS),
                eq(0 /* vendorCode */));
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testErrorFromHal_whilePreparingAuthentication_credentialAllowed() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK);
        waitForIdle();

        mBiometricService.mInternalReceiver.onError(
                getCookieForPendingSession(mBiometricService.mPendingAuthSession),
                BiometricAuthenticator.TYPE_FACE,
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
                0 /* vendorCode */);
        waitForIdle();

        // Pending auth session becomes current auth session, since device credential should
        // be shown now.
        assertNull(mBiometricService.mPendingAuthSession);
        assertNotNull(mBiometricService.mCurrentAuthSession);
        assertEquals(BiometricService.STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mCurrentAuthSession.mState);
        assertEquals(Authenticators.DEVICE_CREDENTIAL,
                mBiometricService.mCurrentAuthSession.mBundle.getInt(
                        BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED));
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mCurrentAuthSession.mBundle),
                any(IBiometricServiceReceiverInternal.class),
                eq(0 /* biometricModality */),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                eq(TEST_PACKAGE_NAME),
                anyLong() /* sessionId */);
    }

    @Test
    public void testErrorFromHal_whilePreparingAuthentication_credentialNotAllowed()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);
        waitForIdle();

        mBiometricService.mInternalReceiver.onError(
                getCookieForPendingSession(mBiometricService.mPendingAuthSession),
                BiometricAuthenticator.TYPE_FINGERPRINT,
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
                0 /* vendorCode */);
        waitForIdle();

        // Error is sent to client
        assertNull(mBiometricService.mPendingAuthSession);
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testCombineAuthenticatorBundles_withKeyDeviceCredential_andKeyAuthenticators() {
        final boolean allowDeviceCredential = false;
        final @Authenticators.Types int authenticators =
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK;
        final Bundle bundle = new Bundle();

        bundle.putBoolean(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL, allowDeviceCredential);
        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        Utils.combineAuthenticatorBundles(bundle);

        assertNull(bundle.get(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL));
        assertEquals(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED), authenticators);
    }

    @Test
    public void testCombineAuthenticatorBundles_withNoKeyDeviceCredential_andKeyAuthenticators() {
        final @Authenticators.Types int authenticators =
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK;
        final Bundle bundle = new Bundle();

        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        Utils.combineAuthenticatorBundles(bundle);

        assertNull(bundle.get(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL));
        assertEquals(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED), authenticators);
    }

    @Test
    public void testCombineAuthenticatorBundles_withKeyDeviceCredential_andNoKeyAuthenticators() {
        final boolean allowDeviceCredential = true;
        final Bundle bundle = new Bundle();

        bundle.putBoolean(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL, allowDeviceCredential);
        Utils.combineAuthenticatorBundles(bundle);

        assertNull(bundle.get(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL));
        assertEquals(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED),
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK);
    }

    @Test
    public void testCombineAuthenticatorBundles_withNoKeyDeviceCredential_andNoKeyAuthenticators() {
        final Bundle bundle = new Bundle();

        Utils.combineAuthenticatorBundles(bundle);

        assertNull(bundle.get(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL));
        assertEquals(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED),
                Authenticators.BIOMETRIC_WEAK);
    }

    @Test
    public void testErrorFromHal_whileShowingDeviceCredential_doesntNotifySystemUI()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK);

        mBiometricService.mInternalReceiver.onDeviceCredentialPressed();
        waitForIdle();

        assertEquals(BiometricService.STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mCurrentAuthSession.mState);
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricAuthenticator.TYPE_FINGERPRINT,
                BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                0 /* vendorCode */);
        waitForIdle();

        assertEquals(BiometricService.STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mCurrentAuthSession.mState);
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testLockout_whileAuthenticating_credentialAllowed() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK);

        assertEquals(BiometricService.STATE_AUTH_STARTED,
                mBiometricService.mCurrentAuthSession.mState);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricAuthenticator.TYPE_FINGERPRINT,
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
                0 /* vendorCode */);
        waitForIdle();

        assertEquals(BiometricService.STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mCurrentAuthSession.mState);
        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_LOCKOUT),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testLockout_whenAuthenticating_credentialNotAllowed() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        assertEquals(BiometricService.STATE_AUTH_STARTED,
                mBiometricService.mCurrentAuthSession.mState);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricAuthenticator.TYPE_FINGERPRINT,
                BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS,
                0 /* vendorCode */);
        waitForIdle();

        assertEquals(BiometricService.STATE_ERROR_PENDING_SYSUI,
                mBiometricService.mCurrentAuthSession.mState);
        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testDismissedReasonUserCancel_whileAuthenticating_cancelsHalAuthentication()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_USER_CANCEL, null /* credentialAttestation */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED),
                eq(0 /* vendorCode */));
        verify(mBiometricService.mAuthenticators.get(0).impl).cancelAuthenticationFromService(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                eq(false) /* fromClient */);
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testDismissedReasonNegative_whilePaused_doesntInvokeHalCancel() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricAuthenticator.TYPE_FACE,
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                0 /* vendorCode */);
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_NEGATIVE, null /* credentialAttestation */);
        waitForIdle();

        verify(mBiometricService.mAuthenticators.get(0).impl,
                never()).cancelAuthenticationFromService(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean());
    }

    @Test
    public void testDismissedReasonUserCancel_whilePaused_doesntInvokeHalCancel() throws
            Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricAuthenticator.TYPE_FACE,
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                0 /* vendorCode */);
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_USER_CANCEL, null /* credentialAttestation */);
        waitForIdle();

        verify(mBiometricService.mAuthenticators.get(0).impl,
                never()).cancelAuthenticationFromService(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean());
    }

    @Test
    public void testDismissedReasonUserCancel_whenPendingConfirmation() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onAuthenticationSucceeded(
                true /* requireConfirmation */,
                new byte[69] /* HAT */,
                true /* isStrongBiometric */);
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_USER_CANCEL, null /* credentialAttestation */);
        waitForIdle();

        // doesn't send cancel to HAL
        verify(mBiometricService.mAuthenticators.get(0).impl,
                never()).cancelAuthenticationFromService(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean());
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_FACE),
                eq(BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED),
                eq(0 /* vendorCode */));
        verify(mBiometricService.mKeyStore, never()).addAuthToken(any(byte[].class));
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testAcquire_whenAuthenticating_sentToSystemUI() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mInternalReceiver.onAcquired(
                FingerprintManager.FINGERPRINT_ACQUIRED_IMAGER_DIRTY,
                FINGERPRINT_ACQUIRED_SENSOR_DIRTY);
        waitForIdle();

        // Sends to SysUI and stays in authenticating state
        verify(mBiometricService.mStatusBarService)
                .onBiometricHelp(eq(FINGERPRINT_ACQUIRED_SENSOR_DIRTY));
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_STARTED);
    }

    @Test
    public void testCancel_whenAuthenticating() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mImpl.cancelAuthentication(mBiometricService.mCurrentAuthSession.mToken,
                TEST_PACKAGE_NAME, 0 /* callingUId */, 0 /* callingPid */, 0 /* callingUserId */);
        waitForIdle();

        // Pretend that the HAL has responded to cancel with ERROR_CANCELED
        mBiometricService.mInternalReceiver.onError(getCookieForCurrentSession(
                mBiometricService.mCurrentAuthSession), BiometricAuthenticator.TYPE_FINGERPRINT,
                BiometricConstants.BIOMETRIC_ERROR_CANCELED, 0 /* vendorCode */);
        waitForIdle();

        // Hides system dialog and invokes the onError callback
        verify(mReceiver1).onError(eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_CANCELED),
                eq(0 /* vendorCode */));
        verify(mBiometricService.mStatusBarService).hideAuthenticationDialog();
    }

    @Test
    public void testCanAuthenticate_whenDeviceHasRequestedBiometricStrength() throws Exception {
        // When only biometric is requested, and sensor is strong enough
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);

        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, Authenticators.BIOMETRIC_STRONG));
    }

    @Test
    public void testCanAuthenticate_whenDeviceDoesNotHaveRequestedBiometricStrength()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_WEAK);

        // When only biometric is requested, and sensor is not strong enough
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(false);
        int authenticators = Authenticators.BIOMETRIC_STRONG;
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                invokeCanAuthenticate(mBiometricService, authenticators));

        // When credential and biometric are requested, and sensor is not strong enough
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(true);
        authenticators = Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL;
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
    }

    @Test
    public void testCanAuthenticate_onlyCredentialRequested() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        // Credential requested but not set up
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(false);
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
                invokeCanAuthenticate(mBiometricService, Authenticators.DEVICE_CREDENTIAL));

        // Credential requested and set up
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(true);
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, Authenticators.DEVICE_CREDENTIAL));
    }

    @Test
    public void testCanAuthenticate_whenNoBiometricsEnrolled() throws Exception {
        // With credential set up, test the following.
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(true);
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                false /* enrolled */);

        // When only biometric is requested
        int authenticators = Authenticators.BIOMETRIC_STRONG;
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
                invokeCanAuthenticate(mBiometricService, authenticators));

        // When credential and biometric are requested
        authenticators = Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL;
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
    }

    @Test
    public void testCanAuthenticate_whenBiometricsNotEnabledForApps() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        when(mBiometricService.mSettingObserver.getFaceEnabledForApps(anyInt())).thenReturn(false);
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(true);

        // When only biometric is requested
        int authenticators = Authenticators.BIOMETRIC_STRONG;
        assertEquals(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                invokeCanAuthenticate(mBiometricService, authenticators));

        // When credential and biometric are requested
        authenticators = Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL;
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
    }

    @Test
    public void testCanAuthenticate_whenNoBiometricSensor() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        // When only biometric is requested
        int authenticators = Authenticators.BIOMETRIC_STRONG;
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                invokeCanAuthenticate(mBiometricService, authenticators));

        // When credential and biometric are requested, and credential is not set up
        authenticators = Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL;
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
                invokeCanAuthenticate(mBiometricService, authenticators));

        // When credential and biometric are requested, and credential is set up
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(true);
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
    }

    @Test
    public void testAuthenticatorActualStrength() {
        // Tuple of OEM config, updatedStrength, and expectedStrength
        final int[][] testCases = {
                // Downgrades to the specified strength
                {Authenticators.BIOMETRIC_STRONG, Authenticators.BIOMETRIC_WEAK,
                        Authenticators.BIOMETRIC_WEAK},

                // Cannot be upgraded
                {Authenticators.BIOMETRIC_WEAK, Authenticators.BIOMETRIC_STRONG,
                        Authenticators.BIOMETRIC_WEAK},

                // Downgrades to convenience
                {Authenticators.BIOMETRIC_WEAK, Authenticators.BIOMETRIC_CONVENIENCE,
                        Authenticators.BIOMETRIC_CONVENIENCE},

                // EMPTY_SET does not modify specified strength
                {Authenticators.BIOMETRIC_WEAK, Authenticators.EMPTY_SET,
                        Authenticators.BIOMETRIC_WEAK},
        };

        for (int i = 0; i < testCases.length; i++) {
            final BiometricService.AuthenticatorWrapper authenticator =
                    new BiometricService.AuthenticatorWrapper(0 /* id */,
                            BiometricAuthenticator.TYPE_FINGERPRINT,
                            testCases[i][0],
                            null /* impl */);
            authenticator.updateStrength(testCases[i][1]);
            assertEquals(testCases[i][2], authenticator.getActualStrength());
        }
    }

    @Test
    public void testRegisterAuthenticator_updatesStrengths() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        verify(mBiometricService.mBiometricStrengthController).startListening();
        verify(mBiometricService.mBiometricStrengthController, never()).updateStrengths();

        when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any()))
                .thenReturn(true);
        when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
        mBiometricService.mImpl.registerAuthenticator(0 /* testId */,
                BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                mFingerprintAuthenticator);

        verify(mBiometricService.mBiometricStrengthController).updateStrengths();
    }

    @Test
    public void testWithDowngradedAuthenticator() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        final int testId = 0;

        when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any()))
                .thenReturn(true);
        when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
        mBiometricService.mImpl.registerAuthenticator(testId /* id */,
                BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                mFingerprintAuthenticator);

        // Downgrade the authenticator
        for (BiometricService.AuthenticatorWrapper wrapper : mBiometricService.mAuthenticators) {
            if (wrapper.id == testId) {
                wrapper.updateStrength(Authenticators.BIOMETRIC_WEAK);
            }
        }

        // STRONG-only auth is not available
        int authenticators = Authenticators.BIOMETRIC_STRONG;
        assertEquals(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
                invokeCanAuthenticate(mBiometricService, authenticators));
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                authenticators);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                eq(BiometricPrompt.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
                eq(0) /* vendorCode */);

        // Request for weak auth works
        resetReceiver();
        authenticators = Authenticators.BIOMETRIC_WEAK;
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                authenticators);
        waitForIdle();
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mCurrentAuthSession.mBundle),
                any(IBiometricServiceReceiverInternal.class),
                eq(BiometricAuthenticator.TYPE_FINGERPRINT /* biometricModality */),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                eq(TEST_PACKAGE_NAME),
                anyLong() /* sessionId */);

        // Requesting strong and credential, when credential is setup
        resetReceiver();
        authenticators = Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL;
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(true);
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                authenticators);
        waitForIdle();
        assertTrue(Utils.isCredentialRequested(mBiometricService.mCurrentAuthSession.mBundle));
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mCurrentAuthSession.mBundle),
                any(IBiometricServiceReceiverInternal.class),
                eq(BiometricAuthenticator.TYPE_NONE /* biometricModality */),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                eq(TEST_PACKAGE_NAME),
                anyLong() /* sessionId */);

        // Un-downgrading the authenticator allows successful strong auth
        for (BiometricService.AuthenticatorWrapper wrapper : mBiometricService.mAuthenticators) {
            if (wrapper.id == testId) {
                wrapper.updateStrength(Authenticators.BIOMETRIC_STRONG);
            }
        }

        resetReceiver();
        authenticators = Authenticators.BIOMETRIC_STRONG;
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, authenticators);
        waitForIdle();
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mCurrentAuthSession.mBundle),
                any(IBiometricServiceReceiverInternal.class),
                eq(BiometricAuthenticator.TYPE_FINGERPRINT /* biometricModality */),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                eq(TEST_PACKAGE_NAME),
                anyLong() /* sessionId */);
    }

    @Test(expected = IllegalStateException.class)
    public void testRegistrationWithDuplicateId_throwsIllegalStateException() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        mBiometricService.mImpl.registerAuthenticator(
                0 /* id */, 2 /* modality */, 15 /* strength */,
                mFingerprintAuthenticator);
        mBiometricService.mImpl.registerAuthenticator(
                0 /* id */, 2 /* modality */, 15 /* strength */,
                mFingerprintAuthenticator);
    }

    @Test(expected = IllegalStateException.class)
    public void testRegistrationWithUnknownId_throwsIllegalStateException() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        mBiometricService.mImpl.registerAuthenticator(
                100 /* id */, 2 /* modality */, 15 /* strength */,
                mFingerprintAuthenticator);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegistrationWithNullAuthenticator_throwsIllegalArgumentException()
            throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        mBiometricService.mImpl.registerAuthenticator(
                0 /* id */, 2 /* modality */,
                Authenticators.BIOMETRIC_STRONG /* strength */,
                null /* authenticator */);
    }

    @Test
    public void testRegistrationHappyPath_isOk() throws Exception {
        // This is being tested in many of the other cases, but here's the base case.
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        for (String s : mInjector.getConfiguration(null)) {
            SensorConfig config = new SensorConfig(s);
            mBiometricService.mImpl.registerAuthenticator(config.mId, config.mModality,
                config.mStrength, mFingerprintAuthenticator);
        }
    }

    @Test
    public void testWorkAuthentication_fingerprintWorksIfNotDisabledByDevicePolicyManager()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        when(mDevicePolicyManager
                .getKeyguardDisabledFeatures(any() /* admin */, anyInt() /* userHandle */))
                .thenReturn(~DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT);
        invokeAuthenticateForWorkApp(mBiometricService.mImpl, mReceiver1,
                Authenticators.BIOMETRIC_STRONG);
        waitForIdle();
        assertEquals(mBiometricService.mPendingAuthSession.mState,
                BiometricService.STATE_AUTH_CALLED);
        startPendingAuthSession(mBiometricService);
        waitForIdle();
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_STARTED);
    }

    @Test
    public void testAuthentication_normalAppIgnoresDevicePolicy() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        when(mDevicePolicyManager
                .getKeyguardDisabledFeatures(any() /* admin */, anyInt() /* userHandle */))
                .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, Authenticators.BIOMETRIC_STRONG);
        waitForIdle();
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_STARTED);
    }

    @Test
    public void testWorkAuthentication_faceWorksIfNotDisabledByDevicePolicyManager()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        when(mDevicePolicyManager
                .getKeyguardDisabledFeatures(any() /* admin*/, anyInt() /* userHandle */))
                .thenReturn(~DevicePolicyManager.KEYGUARD_DISABLE_FACE);
        invokeAuthenticateForWorkApp(mBiometricService.mImpl, mReceiver1,
                Authenticators.BIOMETRIC_STRONG);
        waitForIdle();
        assertEquals(mBiometricService.mPendingAuthSession.mState,
                BiometricService.STATE_AUTH_CALLED);
        startPendingAuthSession(mBiometricService);
        waitForIdle();
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_STARTED);
    }

    @Test
    public void testWorkAuthentication_fingerprintFailsIfDisabledByDevicePolicyManager()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        when(mTrustManager.isDeviceSecure(anyInt())).thenReturn(true);
        when(mDevicePolicyManager
                .getKeyguardDisabledFeatures(any() /* admin */, anyInt() /* userHandle */))
                .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT);

        invokeAuthenticateForWorkApp(mBiometricService.mImpl, mReceiver1,
                Authenticators.BIOMETRIC_STRONG);
        waitForIdle();
        verify(mReceiver1).onError(eq(BiometricAuthenticator.TYPE_NONE),
                eq(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE), eq(0) /* vendorCode */);

        invokeAuthenticateForWorkApp(mBiometricService.mImpl, mReceiver2,
                Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL);
        waitForIdle();
        assertNotNull(mBiometricService.mCurrentAuthSession);
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_SHOWING_DEVICE_CREDENTIAL);
        verify(mReceiver2, never()).onError(anyInt(), anyInt(), anyInt());
    }

    // Helper methods

    private int invokeCanAuthenticate(BiometricService service, int authenticators)
            throws Exception {
        return service.mImpl.canAuthenticate(
                TEST_PACKAGE_NAME, 0 /* userId */, 0 /* callingUserId */, authenticators);
    }

    private void setupAuthForOnly(int modality, int strength) throws Exception {
        setupAuthForOnly(modality, strength, true /* enrolled */);
    }

    // TODO: Reconcile the registration strength with the injector
    private void setupAuthForOnly(int modality, int strength, boolean enrolled) throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        when(mBiometricService.mSettingObserver.getFaceEnabledForApps(anyInt())).thenReturn(true);

        if ((modality & BiometricAuthenticator.TYPE_FINGERPRINT) != 0) {
            when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any()))
                    .thenReturn(enrolled);
            when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
            mBiometricService.mImpl.registerAuthenticator(0 /* id */, modality, strength,
                    mFingerprintAuthenticator);
        }

        if ((modality & BiometricAuthenticator.TYPE_FACE) != 0) {
            when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(enrolled);
            when(mFaceAuthenticator.isHardwareDetected(any())).thenReturn(true);
            mBiometricService.mImpl.registerAuthenticator(1 /* id */, modality, strength,
                    mFaceAuthenticator);
        }
    }

    // TODO: Reduce duplicated code, currently we cannot start the BiometricService in setUp() for
    // all tests.
    private void setupAuthForMultiple(int[] modalities, int[] strengths) throws RemoteException {
        mBiometricService = new BiometricService(mContext, mInjector);
        mBiometricService.onStart();

        when(mBiometricService.mSettingObserver.getFaceEnabledForApps(anyInt())).thenReturn(true);

        assertEquals(modalities.length, strengths.length);

        for (int i = 0; i < modalities.length; i++) {
            final int modality = modalities[i];
            final int strength = strengths[i];

            if ((modality & BiometricAuthenticator.TYPE_FINGERPRINT) != 0) {
                when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any()))
                        .thenReturn(true);
                when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
                mBiometricService.mImpl.registerAuthenticator(0 /* id */, modality, strength,
                        mFingerprintAuthenticator);
            }

            if ((modality & BiometricAuthenticator.TYPE_FACE) != 0) {
                when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
                when(mFaceAuthenticator.isHardwareDetected(any())).thenReturn(true);
                mBiometricService.mImpl.registerAuthenticator(1 /* id */, modality, strength,
                        mFaceAuthenticator);
            }
        }
    }

    private void resetReceiver() {
        mReceiver1 = mock(IBiometricServiceReceiver.class);
        mReceiver2 = mock(IBiometricServiceReceiver.class);
    }

    private void resetStatusBar() {
        mBiometricService.mStatusBarService = mock(IStatusBarService.class);
    }

    private void invokeAuthenticateAndStart(IBiometricService.Stub service,
            IBiometricServiceReceiver receiver, boolean requireConfirmation,
            Integer authenticators) throws Exception {
        // Request auth, creates a pending session
        invokeAuthenticate(service, receiver, requireConfirmation, authenticators);
        waitForIdle();

        startPendingAuthSession(mBiometricService);
        waitForIdle();
    }

    private static void startPendingAuthSession(BiometricService service) throws Exception {
        // Get the cookie so we can pretend the hardware is ready to authenticate
        // Currently we only support single modality per auth
        assertEquals(service.mPendingAuthSession.mModalitiesWaiting.values().size(), 1);
        final int cookie = service.mPendingAuthSession.mModalitiesWaiting.values()
                .iterator().next();
        assertNotEquals(cookie, 0);

        service.mImpl.onReadyForAuthentication(cookie,
                anyBoolean() /* requireConfirmation */, anyInt() /* userId */);
    }

    private static void invokeAuthenticate(IBiometricService.Stub service,
            IBiometricServiceReceiver receiver, boolean requireConfirmation,
            Integer authenticators) throws Exception {
        service.authenticate(
                new Binder() /* token */,
                0 /* sessionId */,
                0 /* userId */,
                receiver,
                TEST_PACKAGE_NAME /* packageName */,
                createTestBiometricPromptBundle(requireConfirmation, authenticators,
                        false /* checkDevicePolicy */),
                0 /* callingUid */,
                0 /* callingPid */,
                0 /* callingUserId */);
    }

    private static void invokeAuthenticateForWorkApp(IBiometricService.Stub service,
            IBiometricServiceReceiver receiver, Integer authenticators) throws Exception {
        service.authenticate(
                new Binder() /* token */,
                0 /* sessionId */,
                0 /* userId */,
                receiver,
                TEST_PACKAGE_NAME /* packageName */,
                createTestBiometricPromptBundle(false /* requireConfirmation */, authenticators,
                        true /* checkDevicePolicy */),
                0 /* callingUid */,
                0 /* callingPid */,
                0 /* callingUserId */);
    }

    private static Bundle createTestBiometricPromptBundle(
            boolean requireConfirmation,
            Integer authenticators,
            boolean checkDevicePolicy) {
        final Bundle bundle = new Bundle();
        bundle.putBoolean(BiometricPrompt.KEY_REQUIRE_CONFIRMATION, requireConfirmation);

        if (authenticators != null) {
            bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        }
        if (checkDevicePolicy) {
            bundle.putBoolean(BiometricPrompt.EXTRA_DISALLOW_BIOMETRICS_IF_POLICY_EXISTS, true);
        }
        return bundle;
    }

    private static int getCookieForCurrentSession(BiometricService.AuthSession session) {
        assertEquals(session.mModalitiesMatched.values().size(), 1);
        return session.mModalitiesMatched.values().iterator().next();
    }

    private static int getCookieForPendingSession(BiometricService.AuthSession session) {
        assertEquals(session.mModalitiesWaiting.values().size(), 1);
        return session.mModalitiesWaiting.values().iterator().next();
    }

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private byte[] generateRandomHAT() {
        byte[] HAT = new byte[69];
        Random random = new Random();
        random.nextBytes(HAT);
        return HAT;
    }


}
