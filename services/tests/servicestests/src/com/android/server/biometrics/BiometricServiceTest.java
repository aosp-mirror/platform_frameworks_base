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

import static junit.framework.Assert.assertEquals;
import static junit.framework.TestCase.assertNotNull;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
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

import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.security.KeyStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.statusbar.IStatusBarService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

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

    private static final String FINGERPRINT_ACQUIRED_SENSOR_DIRTY = "sensor_dirty";

    private BiometricService mBiometricService;

    @Mock
    private Context mContext;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private Resources mResources;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    IBiometricServiceReceiver mReceiver1;
    @Mock
    IBiometricServiceReceiver mReceiver2;
    @Mock
    FingerprintManager mFingerprintManager;
    @Mock
    FaceManager mFaceManager;

    private static class MockInjector extends BiometricService.Injector {
        @Override
        IActivityManager getActivityManagerService() {
            return mock(IActivityManager.class);
        }

        @Override
        IStatusBarService getStatusBarService() {
            return mock(IStatusBarService.class);
        }

        @Override
        IFingerprintService getFingerprintService() {
            return mock(IFingerprintService.class);
        }

        @Override
        IFaceService getFaceService() {
            return mock(IFaceService.class);
        }

        @Override
        BiometricService.SettingObserver getSettingObserver(Context context, Handler handler,
                List<BiometricService.EnabledOnKeyguardCallback> callbacks) {
            return mock(BiometricService.SettingObserver.class);
        }

        @Override
        KeyStore getKeyStore() {
            return mock(KeyStore.class);
        }

        @Override
        boolean isDebugEnabled(Context context, int userId) {
            return false;
        }

        @Override
        void publishBinderService(BiometricService service, IBiometricService.Stub impl) {
            // no-op for test
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);
        when(mContext.getSystemService(Context.FINGERPRINT_SERVICE))
                .thenReturn(mFingerprintManager);
        when(mContext.getSystemService(Context.FACE_SERVICE)).thenReturn(mFaceManager);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getResources()).thenReturn(mResources);

        when(mResources.getString(R.string.biometric_error_hw_unavailable))
                .thenReturn(ERROR_HW_UNAVAILABLE);
        when(mResources.getString(R.string.biometric_not_recognized))
                .thenReturn(ERROR_NOT_RECOGNIZED);
        when(mResources.getString(R.string.biometric_error_user_canceled))
                .thenReturn(ERROR_USER_CANCELED);
    }

    @Test
    public void testAuthenticate_withoutHardware_returnsErrorHardwareNotPresent() throws Exception {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_IRIS)).thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(false);

        mBiometricService = new BiometricService(mContext, new MockInjector());
        mBiometricService.onStart();

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT), eq(ERROR_HW_UNAVAILABLE));
    }

    @Test
    public void testAuthenticate_withoutEnrolled_returnsErrorNoBiometrics() throws Exception {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)).thenReturn(true);
        when(mFingerprintManager.isHardwareDetected()).thenReturn(true);

        mBiometricService = new BiometricService(mContext, new MockInjector());
        mBiometricService.onStart();

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS), any());
    }

    @Test
    public void testAuthenticate_whenHalIsDead_returnsErrorHardwareUnavailable() throws Exception {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)).thenReturn(true);
        when(mFingerprintManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        when(mFingerprintManager.isHardwareDetected()).thenReturn(false);

        mBiometricService = new BiometricService(mContext, new MockInjector());
        mBiometricService.onStart();

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE), eq(ERROR_HW_UNAVAILABLE));
    }

    @Test
    public void testAuthenticateFace_respectsUserSetting()
            throws Exception {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        when(mFaceManager.isHardwareDetected()).thenReturn(true);

        mBiometricService = new BiometricService(mContext, new MockInjector());
        mBiometricService.onStart();

        // Disabled in user settings receives onError
        when(mBiometricService.mSettingObserver.getFaceEnabledForApps(anyInt())).thenReturn(false);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE), eq(ERROR_HW_UNAVAILABLE));

        // Enrolled, not disabled in settings, user requires confirmation in settings
        resetReceiver();
        when(mBiometricService.mSettingObserver.getFaceEnabledForApps(anyInt())).thenReturn(true);
        when(mBiometricService.mSettingObserver.getFaceAlwaysRequireConfirmation(anyInt()))
                .thenReturn(true);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */);
        waitForIdle();
        verify(mReceiver1, never()).onError(anyInt(), any(String.class));
        verify(mBiometricService.mFaceService).prepareForAuthentication(
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
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */);
        waitForIdle();
        verify(mBiometricService.mFaceService).prepareForAuthentication(
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
    public void testAuthenticate_happyPathWithoutConfirmation() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT);
        mBiometricService = new BiometricService(mContext, new MockInjector());
        mBiometricService.onStart();

        // Start testing the happy path
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */);
        waitForIdle();

        // Creates a pending auth session with the correct initial states
        assertEquals(mBiometricService.mPendingAuthSession.mState,
                BiometricService.STATE_AUTH_CALLED);

        // Invokes <Modality>Service#prepareForAuthentication
        ArgumentCaptor<Integer> cookieCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mReceiver1, never()).onError(anyInt(), any(String.class));
        verify(mBiometricService.mFingerprintService).prepareForAuthentication(
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
        verify(mBiometricService.mFingerprintService)
                .startPreparedClient(cookieCaptor.getValue());

        // StatusBar showBiometricDialog invoked
        verify(mBiometricService.mStatusBarService).showBiometricDialog(
                eq(mBiometricService.mCurrentAuthSession.mBundle),
                any(IBiometricServiceReceiverInternal.class),
                eq(BiometricAuthenticator.TYPE_FINGERPRINT),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                eq(TEST_PACKAGE_NAME));

        // Hardware authenticated
        mBiometricService.mInternalReceiver.onAuthenticationSucceeded(
                false /* requireConfirmation */,
                new byte[69] /* HAT */);
        waitForIdle();
        // Waiting for SystemUI to send dismissed callback
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTHENTICATED_PENDING_SYSUI);
        // Notify SystemUI hardware authenticated
        verify(mBiometricService.mStatusBarService).onBiometricAuthenticated(
                eq(true) /* authenticated */, eq(null) /* failureReason */);

        // SystemUI sends callback with dismissed reason
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED);
        waitForIdle();
        // HAT sent to keystore
        verify(mBiometricService.mKeyStore).addAuthToken(any(byte[].class));
        // Send onAuthenticated to client
        verify(mReceiver1).onAuthenticationSucceeded();
        // Current session becomes null
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testAuthenticate_happyPathWithConfirmation() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */);

        // Test authentication succeeded goes to PENDING_CONFIRMATION and that the HAT is not
        // sent to KeyStore yet
        mBiometricService.mInternalReceiver.onAuthenticationSucceeded(
                true /* requireConfirmation */,
                new byte[69] /* HAT */);
        waitForIdle();
        // Waiting for SystemUI to send confirmation callback
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_PENDING_CONFIRM);
        verify(mBiometricService.mKeyStore, never()).addAuthToken(any(byte[].class));

        // SystemUI sends confirm, HAT is sent to keystore and client is notified.
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED);
        waitForIdle();
        verify(mBiometricService.mKeyStore).addAuthToken(any(byte[].class));
        verify(mReceiver1).onAuthenticationSucceeded();
    }

    @Test
    public void testRejectFace_whenAuthenticating_notifiesSystemUIAndClient_thenPaused()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

        mBiometricService.mInternalReceiver.onAuthenticationFailed();
        waitForIdle();

        verify(mBiometricService.mStatusBarService)
                .onBiometricAuthenticated(eq(false), eq(ERROR_NOT_RECOGNIZED));
        verify(mReceiver1).onAuthenticationFailed();
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_PAUSED);
    }

    @Test
    public void testRejectFingerprint_whenAuthenticating_notifiesAndKeepsAuthenticating()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

        mBiometricService.mInternalReceiver.onAuthenticationFailed();
        waitForIdle();

        verify(mBiometricService.mStatusBarService)
                .onBiometricAuthenticated(eq(false), eq(ERROR_NOT_RECOGNIZED));
        verify(mReceiver1).onAuthenticationFailed();
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_STARTED);
    }

    @Test
    public void testErrorCanceled_whenAuthenticating_notifiesSystemUIAndClient() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

        // Create a new pending auth session but don't start it yet. HAL contract is that previous
        // one must get ERROR_CANCELED. Simulate that here by creating the pending auth session,
        // sending ERROR_CANCELED to the current auth session, and then having the second one
        // onReadyForAuthentication.
        invokeAuthenticate(mBiometricService.mImpl, mReceiver2, false /* requireConfirmation */);
        waitForIdle();

        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_STARTED);
        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_CANCELED, ERROR_CANCELED);
        waitForIdle();

        // Auth session doesn't become null until SystemUI responds that the animation is completed
        assertNotNull(mBiometricService.mCurrentAuthSession);
        // ERROR_CANCELED is not sent until SystemUI responded that animation is completed
        verify(mReceiver1, never()).onError(
                anyInt(), anyString());
        verify(mReceiver2, never()).onError(anyInt(), any(String.class));

        // SystemUI dialog closed
        verify(mBiometricService.mStatusBarService).hideBiometricDialog();

        // After SystemUI notifies that the animation has completed
        mBiometricService.mInternalReceiver
                .onDialogDismissed(BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricConstants.BIOMETRIC_ERROR_CANCELED),
                eq(ERROR_CANCELED));
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testErrorHalTimeout_whenAuthenticating_entersPausedState() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                ERROR_TIMEOUT);
        waitForIdle();

        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_AUTH_PAUSED);
        verify(mBiometricService.mStatusBarService)
                .onBiometricAuthenticated(eq(false), eq(ERROR_TIMEOUT));
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
        verify(mBiometricService.mStatusBarService, never()).showBiometricDialog(
                any(Bundle.class),
                any(IBiometricServiceReceiverInternal.class),
                anyInt(),
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyString());
    }

    @Test
    public void testErrorFromHal_whenPaused_notifiesSystemUIAndClient() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireCOnfirmation */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                ERROR_TIMEOUT);
        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                ERROR_CANCELED);
        waitForIdle();

        // Client receives error immediately
        verify(mReceiver1).onError(
                eq(BiometricConstants.BIOMETRIC_ERROR_CANCELED),
                eq(ERROR_CANCELED));
        // Dialog is hidden immediately
        verify(mBiometricService.mStatusBarService).hideBiometricDialog();
        // Auth session is over
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testErrorFromHal_whileAuthenticating_waitsForSysUIBeforeNotifyingClient()
            throws Exception {
        // For errors that show in SystemUI, BiometricService stays in STATE_ERROR_PENDING_SYSUI
        // until SystemUI notifies us that the dialog is dismissed at which point the current
        // session is done.
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS,
                ERROR_UNABLE_TO_PROCESS);
        waitForIdle();

        // Sends error to SystemUI and does not notify client yet
        assertEquals(mBiometricService.mCurrentAuthSession.mState,
                BiometricService.STATE_ERROR_PENDING_SYSUI);
        verify(mBiometricService.mStatusBarService)
                .onBiometricError(eq(ERROR_UNABLE_TO_PROCESS));
        verify(mBiometricService.mStatusBarService, never()).hideBiometricDialog();
        verify(mReceiver1, never()).onError(anyInt(), anyString());

        // SystemUI animation completed, client is notified, auth session is over
        mBiometricService.mInternalReceiver
                .onDialogDismissed(BiometricPrompt.DISMISSED_REASON_ERROR);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS),
                eq(ERROR_UNABLE_TO_PROCESS));
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testErrorFromHal_whileShowingDeviceCredential_doesntNotifySystemUI()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

        mBiometricService.mInternalReceiver.onDeviceCredentialPressed();
        waitForIdle();

        assertEquals(BiometricService.STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mCurrentAuthSession.mState);
        verify(mReceiver1, never()).onError(anyInt(), anyString());

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                ERROR_CANCELED);
        waitForIdle();

        assertEquals(BiometricService.STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mCurrentAuthSession.mState);
        verify(mReceiver1, never()).onError(anyInt(), anyString());
    }

    @Test
    public void testDismissedReasonUserCancel_whileAuthenticating_cancelsHalAuthentication()
            throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

        mBiometricService.mInternalReceiver
                .onDialogDismissed(BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED),
                eq(ERROR_USER_CANCELED));
        verify(mBiometricService.mFingerprintService).cancelAuthenticationFromService(
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
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                ERROR_TIMEOUT);
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_NEGATIVE);
        waitForIdle();

        verify(mBiometricService.mFaceService, never()).cancelAuthenticationFromService(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean());
    }

    @Test
    public void testDismissedReasonUserCancel_whilePaused_doesntInvokeHalCancel() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

        mBiometricService.mInternalReceiver.onError(
                getCookieForCurrentSession(mBiometricService.mCurrentAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                ERROR_TIMEOUT);
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
        waitForIdle();

        verify(mBiometricService.mFaceService, never()).cancelAuthenticationFromService(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean());
    }

    @Test
    public void testDismissedReasonUserCancel_whenPendingConfirmation() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FACE);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */);

        mBiometricService.mInternalReceiver.onAuthenticationSucceeded(
                true /* requireConfirmation */,
                new byte[69] /* HAT */);
        mBiometricService.mInternalReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
        waitForIdle();

        // doesn't send cancel to HAL
        verify(mBiometricService.mFaceService, never()).cancelAuthenticationFromService(
                any(),
                any(),
                anyInt(),
                anyInt(),
                anyInt(),
                anyBoolean());
        verify(mReceiver1).onError(
                eq(BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED),
                eq(ERROR_USER_CANCELED));
        assertNull(mBiometricService.mCurrentAuthSession);
    }

    @Test
    public void testAcquire_whenAuthenticating_sentToSystemUI() throws Exception {
        setupAuthForOnly(BiometricAuthenticator.TYPE_FINGERPRINT);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */);

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

    // Helper methods

    private void setupAuthForOnly(int modality) {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(false);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(false);

        if (modality == BiometricAuthenticator.TYPE_FINGERPRINT) {
            when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                    .thenReturn(true);
            when(mFingerprintManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
            when(mFingerprintManager.isHardwareDetected()).thenReturn(true);
        } else if (modality == BiometricAuthenticator.TYPE_FACE) {
            when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true);
            when(mFaceManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
            when(mFaceManager.isHardwareDetected()).thenReturn(true);
        } else {
            fail("Unknown modality: " + modality);
        }

        mBiometricService = new BiometricService(mContext, new MockInjector());
        mBiometricService.onStart();

        when(mBiometricService.mSettingObserver.getFaceEnabledForApps(anyInt())).thenReturn(true);
    }

    private void resetReceiver() {
        mReceiver1 = mock(IBiometricServiceReceiver.class);
        mReceiver2 = mock(IBiometricServiceReceiver.class);
    }

    private void resetStatusBar() {
        mBiometricService.mStatusBarService = mock(IStatusBarService.class);
    }

    private void invokeAuthenticateAndStart(IBiometricService.Stub service,
            IBiometricServiceReceiver receiver, boolean requireConfirmation) throws Exception {
        // Request auth, creates a pending session
        invokeAuthenticate(service, receiver, requireConfirmation);
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
            IBiometricServiceReceiver receiver, boolean requireConfirmation) throws Exception {
        service.authenticate(
                new Binder() /* token */,
                0 /* sessionId */,
                0 /* userId */,
                receiver,
                TEST_PACKAGE_NAME /* packageName */,
                createTestBiometricPromptBundle(requireConfirmation));
    }

    private static Bundle createTestBiometricPromptBundle(boolean requireConfirmation) {
        final Bundle bundle = new Bundle();
        bundle.putBoolean(BiometricPrompt.KEY_REQUIRE_CONFIRMATION, requireConfirmation);
        return bundle;
    }

    private static int getCookieForCurrentSession(BiometricService.AuthSession session) {
        assertEquals(session.mModalitiesMatched.values().size(), 1);
        return session.mModalitiesMatched.values().iterator().next();
    }

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
