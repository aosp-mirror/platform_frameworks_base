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

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_CREDENTIAL;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricManager.Authenticators;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;

import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTHENTICATED_PENDING_SYSUI;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_CALLED;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_PAUSED;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_PAUSED_RESUMING;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_PENDING_CONFIRM;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_STARTED;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_CLIENT_DIED_CANCELLING;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_ERROR_PENDING_SYSUI;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_SHOWING_DEVICE_CREDENTIAL;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.Flags;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.keymaster.HardwareAuthenticatorType;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.security.GateKeeper;
import android.security.KeyStore;
import android.security.authorization.IKeystoreAuthorization;
import android.service.gatekeeper.IGateKeeperService;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.statusbar.ISessionListener;
import com.android.internal.statusbar.IStatusBarService;
import com.android.server.biometrics.log.BiometricContextProvider;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.LockoutTracker;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Random;

@Presubmit
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class BiometricServiceTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final long TEST_REQUEST_ID = 44;

    private static final String ERROR_HW_UNAVAILABLE = "hw_unavailable";
    private static final String ERROR_NOT_RECOGNIZED = "not_recognized";
    private static final String ERROR_TIMEOUT = "error_timeout";
    private static final String ERROR_CANCELED = "error_canceled";
    private static final String ERROR_UNABLE_TO_PROCESS = "error_unable_to_process";
    private static final String ERROR_USER_CANCELED = "error_user_canceled";
    private static final String ERROR_LOCKOUT = "error_lockout";
    private static final String FACE_SUBTITLE = "face_subtitle";
    private static final String FINGERPRINT_SUBTITLE = "fingerprint_subtitle";
    private static final String CREDENTIAL_SUBTITLE = "credential_subtitle";
    private static final String DEFAULT_SUBTITLE = "default_subtitle";

    private static final String FINGERPRINT_ACQUIRED_SENSOR_DIRTY = "sensor_dirty";

    private static final int SENSOR_ID_FINGERPRINT = 0;
    private static final int SENSOR_ID_FACE = 1;

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
    IBiometricAuthenticator mCredentialAuthenticator;
    @Mock
    ITrustManager mTrustManager;
    @Mock
    DevicePolicyManager mDevicePolicyManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private IStatusBarService mStatusBarService;
    @Mock
    private ISessionListener mSessionListener;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    private UserManager mUserManager;
    @Mock
    private BiometricCameraManager mBiometricCameraManager;
    @Mock
    private BiometricHandlerProvider mBiometricHandlerProvider;

    @Mock
    private IKeystoreAuthorization mKeystoreAuthService;

    @Mock
    private IGateKeeperService mGateKeeperService;

    BiometricContextProvider mBiometricContextProvider;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        resetReceivers();

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
        when(mInjector.getDevicePolicyManager(any())).thenReturn(mDevicePolicyManager);
        when(mInjector.getRequestGenerator()).thenReturn(() -> TEST_REQUEST_ID);
        when(mInjector.getUserManager(any())).thenReturn(mUserManager);
        when(mInjector.getBiometricCameraManager(any())).thenReturn(mBiometricCameraManager);

        when(mResources.getString(R.string.biometric_error_hw_unavailable))
                .thenReturn(ERROR_HW_UNAVAILABLE);
        when(mResources.getString(R.string.biometric_not_recognized))
                .thenReturn(ERROR_NOT_RECOGNIZED);
        when(mResources.getString(R.string.biometric_face_not_recognized))
                .thenReturn(ERROR_NOT_RECOGNIZED);
        when(mResources.getString(R.string.fingerprint_error_not_match))
                .thenReturn(ERROR_NOT_RECOGNIZED);
        when(mResources.getString(R.string.biometric_error_user_canceled))
                .thenReturn(ERROR_USER_CANCELED);
        when(mContext.getString(R.string.face_dialog_default_subtitle))
                .thenReturn(FACE_SUBTITLE);
        when(mContext.getString(R.string.fingerprint_dialog_default_subtitle))
                .thenReturn(FINGERPRINT_SUBTITLE);
        when(mContext.getString(R.string.screen_lock_dialog_default_subtitle))
                .thenReturn(CREDENTIAL_SUBTITLE);
        when(mContext.getString(R.string.biometric_dialog_default_subtitle))
                .thenReturn(DEFAULT_SUBTITLE);

        when(mWindowManager.getDefaultDisplay()).thenReturn(
                new Display(DisplayManagerGlobal.getInstance(), Display.DEFAULT_DISPLAY,
                        new DisplayInfo(), DEFAULT_DISPLAY_ADJUSTMENTS));
        mBiometricContextProvider = new BiometricContextProvider(mContext, mWindowManager,
                mStatusBarService, null /* handler */,
                mAuthSessionCoordinator);
        when(mInjector.getBiometricContext(any())).thenReturn(mBiometricContextProvider);
        when(mInjector.getKeystoreAuthorizationService()).thenReturn(mKeystoreAuthService);
        when(mInjector.getGateKeeperService()).thenReturn(mGateKeeperService);
        when(mGateKeeperService.getSecureUserId(anyInt())).thenReturn(42L);

        if (com.android.server.biometrics.Flags.deHidl()) {
            when(mBiometricHandlerProvider.getBiometricCallbackHandler()).thenReturn(
                    new Handler(TestableLooper.get(this).getLooper()));
        } else {
            when(mBiometricHandlerProvider.getBiometricCallbackHandler()).thenReturn(
                    new Handler(Looper.getMainLooper()));
        }

        final String[] config = {
                "0:2:15",  // ID0:Fingerprint:Strong
                "1:8:15",  // ID1:Face:Strong
                "2:4:255", // ID2:Iris:Weak
        };

        when(mInjector.getConfiguration(any())).thenReturn(config);
    }

    @Test
    public void testClientBinderDied_whenPaused() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);

        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */, null /* authenticators */);
        waitForIdle();
        verify(mReceiver1.asBinder()).linkToDeath(eq(mBiometricService.mAuthSession),
                anyInt());

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FACE,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                0 /* vendorCode */);
        waitForIdle();

        assertEquals(STATE_AUTH_PAUSED, mBiometricService.mAuthSession.getState());

        mBiometricService.mAuthSession.binderDied();
        waitForIdle();

        assertNull(mBiometricService.mAuthSession);
        verify(mBiometricService.mStatusBarService).hideAuthenticationDialog(eq(TEST_REQUEST_ID));
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testClientBinderDied_whenAuthenticating() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);

        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */, null /* authenticators */);
        waitForIdle();
        verify(mReceiver1.asBinder()).linkToDeath(eq(mBiometricService.mAuthSession),
                anyInt());

        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());
        mBiometricService.mAuthSession.binderDied();
        waitForIdle();

        assertNotNull(mBiometricService.mAuthSession);
        verify(mBiometricService.mStatusBarService, never())
                .hideAuthenticationDialog(eq(TEST_REQUEST_ID));
        assertEquals(STATE_CLIENT_DIED_CANCELLING,
                mBiometricService.mAuthSession.getState());

        verify(mBiometricService.mAuthSession.mPreAuthInfo.eligibleSensors.get(0).impl)
                .cancelAuthenticationFromService(any(), any(), anyLong());

        // Simulate ERROR_CANCELED received from HAL
        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FACE,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                0 /* vendorCode */);
        waitForIdle();
        verify(mBiometricService.mStatusBarService).hideAuthenticationDialog(eq(TEST_REQUEST_ID));
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());
        assertNull(mBiometricService.mAuthSession);
    }

    @Test
    public void testAuthenticate_credentialAllowedButNotSetup_returnsNoDeviceCredential()
            throws Exception {
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(false);

        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_CREDENTIAL),
                eq(BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testAuthenticate_credentialAllowedAndSetup_callsSystemUI() throws Exception {
        // When no biometrics are enrolled, but credentials are set up, status bar should be
        // invoked right away with showAuthenticationDialog

        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(true);

        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();

        assertNotNull(mBiometricService.mAuthSession);
        assertEquals(STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.getState());
        // StatusBar showBiometricDialog invoked
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mAuthSession.mPromptInfo),
                any(IBiometricSysuiReceiver.class),
                AdditionalMatchers.aryEq(new int[0]) /* sensorIds */,
                eq(true) /* credentialAllowed */,
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                eq(TEST_PACKAGE_NAME),
                eq(TEST_REQUEST_ID));
    }

    @Test
    public void testAuthenticate_withoutHardware_returnsErrorHardwareNotPresent() throws
            Exception {
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_NONE),
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testAuthenticate_withoutEnrolled_returnsErrorNoBiometrics() throws Exception {
        when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);

        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();
        mBiometricService.mImpl.registerAuthenticator(0 /* id */,
                TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                mFingerprintAuthenticator);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testAuthenticate_notStrongEnough_returnsHardwareNotPresent() throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_WEAK);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                Authenticators.BIOMETRIC_STRONG, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
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
                TYPE_FINGERPRINT,
                TYPE_FACE,
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
                eq(mBiometricService.mAuthSession.mPromptInfo),
                any(IBiometricSysuiReceiver.class),
                AdditionalMatchers.aryEq(new int[] {SENSOR_ID_FACE}),
                eq(false) /* credentialAllowed */,
                eq(false) /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                eq(TEST_PACKAGE_NAME),
                eq(TEST_REQUEST_ID));
    }

    @Test
    public void testAuthenticate_whenHalIsDead_returnsErrorHardwareUnavailable() throws
            Exception {
        when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(false);

        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();
        mBiometricService.mImpl.registerAuthenticator(0 /* id */,
                TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                mFingerprintAuthenticator);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testAuthenticateFace_shouldShowSubtitleForFace() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, true /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();

        assertEquals(FACE_SUBTITLE, mBiometricService.mAuthSession.mPromptInfo.getSubtitle());
    }

    @Test
    public void testAuthenticateFingerprint_shouldShowSubtitleForFingerprint() throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, true /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();

        assertEquals(FINGERPRINT_SUBTITLE,
                mBiometricService.mAuthSession.mPromptInfo.getSubtitle());
    }

    @Test
    public void testAuthenticateFingerprint_shouldShowSubtitleForCredential() throws Exception {
        setupAuthForOnly(TYPE_CREDENTIAL, Authenticators.DEVICE_CREDENTIAL);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, true /* useDefaultSubtitle */,
                true /* deviceCredentialAllowed */);
        waitForIdle();

        assertEquals(CREDENTIAL_SUBTITLE,
                mBiometricService.mAuthSession.mPromptInfo.getSubtitle());
    }

    @Test
    public void testAuthenticateBothFpAndFace_shouldShowDefaultSubtitle() throws Exception {
        final int[] modalities = new int[] {
                TYPE_FINGERPRINT,
                TYPE_FACE,
        };

        final int[] strengths = new int[] {
                Authenticators.BIOMETRIC_WEAK,
                Authenticators.BIOMETRIC_STRONG,
        };

        setupAuthForMultiple(modalities, strengths);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, true /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();

        assertEquals(DEFAULT_SUBTITLE, mBiometricService.mAuthSession.mPromptInfo.getSubtitle());
    }

    @Test
    public void testAuthenticateFace_respectsUserSetting()
            throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);

        // Disabled in user settings receives onError
        when(mBiometricService.mSettingObserver.getEnabledForApps(anyInt())).thenReturn(false);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(BiometricAuthenticator.TYPE_NONE),
                eq(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE),
                eq(0 /* vendorCode */));

        // Enrolled, not disabled in settings, user requires confirmation in settings
        resetReceivers();
        when(mBiometricService.mSettingObserver.getEnabledForApps(anyInt())).thenReturn(true);
        when(mBiometricService.mSettingObserver.getConfirmationAlwaysRequired(
                anyInt() /* modality */, anyInt() /* userId */))
                .thenReturn(true);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());
        final byte[] HAT = generateRandomHAT();
        mBiometricService.mAuthSession.mSensorReceiver.onAuthenticationSucceeded(
                SENSOR_ID_FACE,
                HAT);
        waitForIdle();
        // Confirmation is required
        assertEquals(STATE_AUTH_PENDING_CONFIRM,
                mBiometricService.mAuthSession.getState());

        // Enrolled, not disabled in settings, user doesn't require confirmation in settings
        resetReceivers();
        when(mBiometricService.mSettingObserver.getConfirmationAlwaysRequired(
                anyInt() /* modality */, anyInt() /* userId */))
                .thenReturn(false);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();
        mBiometricService.mAuthSession.mSensorReceiver.onAuthenticationSucceeded(
                SENSOR_ID_FACE,
                HAT);
        waitForIdle();
        // Confirmation not required, waiting for dialog to dismiss
        assertEquals(STATE_AUTHENTICATED_PENDING_SYSUI,
                mBiometricService.mAuthSession.getState());

    }

    @Test
    public void testAuthenticate_happyPathWithoutConfirmation_strongBiometric() throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        testAuthenticate_happyPathWithoutConfirmation(true /* isStrongBiometric */);
    }

    @Test
    public void testAuthenticate_happyPathWithoutConfirmation_weakBiometric() throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_WEAK);
        testAuthenticate_happyPathWithoutConfirmation(false /* isStrongBiometric */);
    }

    private void testAuthenticate_happyPathWithoutConfirmation(boolean isStrongBiometric)
            throws Exception {
        // Start testing the happy path
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();

        // Creates a pending auth session with the correct initial states
        assertEquals(STATE_AUTH_CALLED, mBiometricService.mAuthSession.getState());

        // Invokes <Modality>Service#prepareForAuthentication
        ArgumentCaptor<Integer> cookieCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());
        verify(mBiometricService.mSensors.get(0).impl).prepareForAuthentication(
                eq(false) /* requireConfirmation */,
                any(IBinder.class),
                anyLong() /* sessionId */,
                anyInt() /* userId */,
                any(IBiometricSensorReceiver.class),
                anyString() /* opPackageName */,
                eq(TEST_REQUEST_ID),
                cookieCaptor.capture() /* cookie */,
                anyBoolean() /* allowBackgroundAuthentication */,
                anyBoolean() /* isForLegacyFingerprintManager */);

        // onReadyForAuthentication, mAuthSession state OK
        mBiometricService.mImpl.onReadyForAuthentication(TEST_REQUEST_ID, cookieCaptor.getValue());
        waitForIdle();
        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());

        // startPreparedClient invoked
        mBiometricService.mAuthSession.onDialogAnimatedIn(true /* startFingerprintNow */);
        verify(mBiometricService.mSensors.get(0).impl)
                .startPreparedClient(cookieCaptor.getValue());

        // StatusBar showBiometricDialog invoked
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mAuthSession.mPromptInfo),
                any(IBiometricSysuiReceiver.class),
                any(),
                eq(false) /* credentialAllowed */,
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                eq(TEST_PACKAGE_NAME),
                eq(TEST_REQUEST_ID));

        // Hardware authenticated
        final byte[] HAT = generateRandomHAT();
        mBiometricService.mAuthSession.mSensorReceiver.onAuthenticationSucceeded(
                SENSOR_ID_FINGERPRINT,
                HAT);
        waitForIdle();
        // Waiting for SystemUI to send dismissed callback
        assertEquals(STATE_AUTHENTICATED_PENDING_SYSUI,
                mBiometricService.mAuthSession.getState());
        // Notify SystemUI hardware authenticated
        verify(mBiometricService.mStatusBarService).onBiometricAuthenticated(TYPE_FINGERPRINT);

        // SystemUI sends callback with dismissed reason
        mBiometricService.mAuthSession.mSysuiReceiver.onDialogDismissed(
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
        assertNull(mBiometricService.mAuthSession);
    }

    @Test
    public void testAuthenticate_noBiometrics_credentialAllowed() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(false);
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(true);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK,
                false /* useDefaultSubtitle*/, false /* deviceCredentialAllowed */);
        waitForIdle();

        assertEquals(STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.getState());
        assertEquals(Authenticators.DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.mPromptInfo.getAuthenticators());
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mAuthSession.mPromptInfo),
                any(IBiometricSysuiReceiver.class),
                AdditionalMatchers.aryEq(new int[0]) /* sensorIds */,
                eq(true) /* credentialAllowed */,
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                eq(TEST_PACKAGE_NAME),
                eq(TEST_REQUEST_ID));
    }

    @Test
    public void testAuthenticate_happyPathWithConfirmation_strongBiometric() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        testAuthenticate_happyPathWithConfirmation(true /* isStrongBiometric */);
    }

    @Test
    public void testAuthenticate_happyPathWithConfirmation_weakBiometric() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_WEAK);
        testAuthenticate_happyPathWithConfirmation(false /* isStrongBiometric */);
    }

    private void testAuthenticate_happyPathWithConfirmation(boolean isStrongBiometric)
            throws Exception {
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */, null /* authenticators */);

        // Test authentication succeeded goes to PENDING_CONFIRMATION and that the HAT is not
        // sent to KeyStore yet
        final byte[] HAT = generateRandomHAT();
        mBiometricService.mAuthSession.mSensorReceiver.onAuthenticationSucceeded(
                SENSOR_ID_FACE,
                HAT);
        waitForIdle();
        // Waiting for SystemUI to send confirmation callback
        assertEquals(STATE_AUTH_PENDING_CONFIRM, mBiometricService.mAuthSession.getState());
        verify(mBiometricService.mKeyStore, never()).addAuthToken(any(byte[].class));

        // SystemUI sends confirm, HAT is sent to keystore and client is notified.
        mBiometricService.mAuthSession.mSysuiReceiver.onDialogDismissed(
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
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(false);
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(false);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_STRONG,
                false /* useDefaultSubtitle */, false /* deviceCredentialAllowed */);
        waitForIdle();

        verify(mReceiver1).onError(anyInt() /* modality */,
                eq(BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS)/* error */,
                eq(0) /* vendorCode */);
    }

    @Test
    public void testRejectFace_whenAuthenticating_notifiesSystemUIAndClient_thenPaused()
            throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSensorReceiver.onAuthenticationFailed(SENSOR_ID_FACE);
        waitForIdle();

        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(TYPE_FACE),
                eq(BiometricConstants.BIOMETRIC_PAUSED_REJECTED),
                eq(0 /* vendorCode */));
        verify(mReceiver1).onAuthenticationFailed();
        assertEquals(STATE_AUTH_PAUSED, mBiometricService.mAuthSession.getState());
    }

    @Test
    public void testRejectFingerprint_whenAuthenticating_notifiesAndKeepsAuthenticating()
            throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSensorReceiver
                .onAuthenticationFailed(SENSOR_ID_FINGERPRINT);
        waitForIdle();

        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_PAUSED_REJECTED),
                eq(0 /* vendorCode */));
        verify(mReceiver1).onAuthenticationFailed();
        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());
    }

    @Test
    public void testRequestAuthentication_whenAlreadyAuthenticating() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        invokeAuthenticate(mBiometricService.mImpl, mReceiver2, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();

        verify(mReceiver1).onError(
                eq(TYPE_FACE),
                eq(BiometricPrompt.BIOMETRIC_ERROR_CANCELED),
                eq(0) /* vendorCode */);
        verify(mBiometricService.mStatusBarService).hideAuthenticationDialog(eq(TEST_REQUEST_ID));

        verify(mReceiver2, never()).onError(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testErrorHalTimeout_whenAuthenticating_entersPausedState() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FACE,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                0 /* vendorCode */);
        waitForIdle();

        assertEquals(STATE_AUTH_PAUSED, mBiometricService.mAuthSession.getState());
        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(TYPE_FACE),
                eq(BiometricConstants.BIOMETRIC_ERROR_TIMEOUT),
                eq(0 /* vendorCode */));
        // Timeout does not count as fail as per BiometricPrompt documentation.
        verify(mReceiver1, never()).onAuthenticationFailed();

        // No auth session. Pressing try again will create one.
        assertEquals(STATE_AUTH_PAUSED, mBiometricService.mAuthSession.getState());

        // Pressing "Try again" on SystemUI
        mBiometricService.mAuthSession.mSysuiReceiver.onTryAgainPressed();
        waitForIdle();
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());

        // AuthSession is now resuming
        assertEquals(STATE_AUTH_PAUSED_RESUMING, mBiometricService.mAuthSession.getState());

        // Test resuming when hardware becomes ready. SystemUI should not be requested to
        // show another dialog since it's already showing.
        resetStatusBar();
        startPendingAuthSession(mBiometricService);
        waitForIdle();
        verify(mBiometricService.mStatusBarService, never()).showAuthenticationDialog(
                any(PromptInfo.class),
                any(IBiometricSysuiReceiver.class),
                any() /* sensorIds */,
                anyBoolean() /* credentialAllowed */,
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                anyString(),
                anyLong() /* requestId */);
    }

    @Test
    public void testErrorFromHal_whenPaused_notifiesSystemUIAndClient() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FACE,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                0 /* vendorCode */);
        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FACE,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                0 /* vendorCode */);
        waitForIdle();

        // Client receives error immediately
        verify(mReceiver1).onError(
                eq(TYPE_FACE),
                eq(BiometricConstants.BIOMETRIC_ERROR_CANCELED),
                eq(0 /* vendorCode */));
        // Dialog is hidden immediately
        verify(mBiometricService.mStatusBarService).hideAuthenticationDialog(eq(TEST_REQUEST_ID));
        // Auth session is over
        assertNull(mBiometricService.mAuthSession);
    }

    @Test
    public void testErrorFromHal_whileAuthenticating_waitsForSysUIBeforeNotifyingClient()
            throws Exception {
        // For errors that show in SystemUI, BiometricService stays in STATE_ERROR_PENDING_SYSUI
        // until SystemUI notifies us that the dialog is dismissed at which point the current
        // session is done.
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FINGERPRINT,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS,
                0 /* vendorCode */);
        waitForIdle();

        // Sends error to SystemUI and does not notify client yet
        assertEquals(STATE_ERROR_PENDING_SYSUI, mBiometricService.mAuthSession.getState());
        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS),
                eq(0 /* vendorCode */));
        verify(mBiometricService.mStatusBarService, never())
                .hideAuthenticationDialog(eq(TEST_REQUEST_ID));
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());

        // SystemUI animation completed, client is notified, auth session is over
        mBiometricService.mAuthSession.mSysuiReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_ERROR, null /* credentialAttestation */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS),
                eq(0 /* vendorCode */));
        assertNull(mBiometricService.mAuthSession);
    }

    @Test
    public void testErrorFromHal_whilePreparingAuthentication_credentialAllowed() throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK,
                false /* useDefaultSubtitle */, false /* deviceCredentialAllowed */);
        waitForIdle();

        assertEquals(STATE_AUTH_CALLED, mBiometricService.mAuthSession.getState());
        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FINGERPRINT,
                getCookieForPendingSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
                0 /* vendorCode */);
        waitForIdle();

        // We should be showing device credential now
        assertNotNull(mBiometricService.mAuthSession);
        assertEquals(STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.getState());
        assertEquals(Authenticators.DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.mPromptInfo.getAuthenticators());
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mAuthSession.mPromptInfo),
                any(IBiometricSysuiReceiver.class),
                AdditionalMatchers.aryEq(new int[0]) /* sensorIds */,
                eq(true) /* credentialAllowed */,
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                eq(TEST_PACKAGE_NAME),
                eq(TEST_REQUEST_ID));
    }

    @Test
    public void testErrorFromHal_whilePreparingAuthentication_credentialNotAllowed()
            throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FINGERPRINT,
                getCookieForPendingSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
                0 /* vendorCode */);
        waitForIdle();

        // Error is sent to client
        verify(mReceiver1).onError(eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_LOCKOUT),
                eq(0) /* vendorCode */);
        assertNull(mBiometricService.mAuthSession);
    }

    @Test
    public void testBiometricAuth_whenBiometricLockoutTimed_sendsErrorAndModality()
            throws Exception {
        testBiometricAuth_whenLockout(LockoutTracker.LOCKOUT_TIMED,
                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT);
    }

    @Test
    public void testBiometricAuth_whenBiometricLockoutPermanent_sendsErrorAndModality()
            throws Exception {
        testBiometricAuth_whenLockout(LockoutTracker.LOCKOUT_PERMANENT,
                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT);
    }

    private void testBiometricAuth_whenLockout(@LockoutTracker.LockoutMode int lockoutMode,
            int biometricPromptError) throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        when(mFingerprintAuthenticator.getLockoutModeForUser(anyInt()))
                .thenReturn(lockoutMode);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();

        // Modality and error are sent
        verify(mReceiver1).onError(eq(TYPE_FINGERPRINT),
                eq(biometricPromptError), eq(0) /* vendorCode */);
    }

    @Test
    public void testMultiBiometricAuth_whenLockoutTimed_sendsErrorAndModality()
            throws Exception {
        testMultiBiometricAuth_whenLockout(LockoutTracker.LOCKOUT_TIMED,
                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT);
    }

    @Test
    public void testMultiBiometricAuth_whenLockoutPermanent_sendsErrorAndModality()
            throws Exception {
        testMultiBiometricAuth_whenLockout(LockoutTracker.LOCKOUT_PERMANENT,
                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT);
    }

    private void testMultiBiometricAuth_whenLockout(@LockoutTracker.LockoutMode int lockoutMode,
            int biometricPromptError) throws Exception {
        final int[] modalities = new int[] {
                TYPE_FINGERPRINT,
                TYPE_FACE,
        };

        final int[] strengths = new int[] {
                Authenticators.BIOMETRIC_STRONG,
                Authenticators.BIOMETRIC_STRONG,
        };
        setupAuthForMultiple(modalities, strengths);

        when(mFingerprintAuthenticator.getLockoutModeForUser(anyInt()))
                .thenReturn(lockoutMode);
        when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(false);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1, false /* requireConfirmation */,
                null /* authenticators */, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();

        // The lockout error should be sent, instead of ERROR_NONE_ENROLLED. See b/286923477.
        verify(mReceiver1).onError(eq(TYPE_FINGERPRINT),
                eq(biometricPromptError), eq(0) /* vendorCode */);
    }

    @Test
    public void testBiometricOrCredentialAuth_whenBiometricLockout_showsCredential()
            throws Exception {
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt())).thenReturn(true);
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        when(mFingerprintAuthenticator.getLockoutModeForUser(anyInt()))
                .thenReturn(LockoutTracker.LOCKOUT_PERMANENT);
        invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_STRONG,
                false /* useDefaultSubtitle */, false /* deviceCredentialAllowed */);
        waitForIdle();

        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());
        assertNotNull(mBiometricService.mAuthSession);
        assertEquals(STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.getState());
        assertEquals(Authenticators.DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.mPromptInfo.getAuthenticators());
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mAuthSession.mPromptInfo),
                any(IBiometricSysuiReceiver.class),
                AdditionalMatchers.aryEq(new int[0]) /* sensorIds */,
                eq(true) /* credentialAllowed */,
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                eq(TEST_PACKAGE_NAME),
                eq(TEST_REQUEST_ID));
    }

    @Test
    public void testCombineAuthenticatorBundles_withKeyDeviceCredential_andKeyAuthenticators() {
        final boolean allowDeviceCredential = false;
        final @Authenticators.Types int authenticators =
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK;
        final PromptInfo promptInfo = new PromptInfo();

        promptInfo.setDeviceCredentialAllowed(allowDeviceCredential);
        promptInfo.setAuthenticators(authenticators);
        Utils.combineAuthenticatorBundles(promptInfo);

        assertFalse(promptInfo.isDeviceCredentialAllowed());
        assertEquals(authenticators, promptInfo.getAuthenticators());
    }

    @Test
    public void testCombineAuthenticatorBundles_withNoKeyDeviceCredential_andKeyAuthenticators() {
        final @Authenticators.Types int authenticators =
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK;
        final PromptInfo promptInfo = new PromptInfo();

        promptInfo.setAuthenticators(authenticators);
        Utils.combineAuthenticatorBundles(promptInfo);

        assertFalse(promptInfo.isDeviceCredentialAllowed());
        assertEquals(authenticators, promptInfo.getAuthenticators());
    }

    @Test
    public void testCombineAuthenticatorBundles_withKeyDeviceCredential_andNoKeyAuthenticators() {
        final boolean allowDeviceCredential = true;
        final PromptInfo promptInfo = new PromptInfo();

        promptInfo.setDeviceCredentialAllowed(allowDeviceCredential);
        Utils.combineAuthenticatorBundles(promptInfo);

        assertFalse(promptInfo.isDeviceCredentialAllowed());
        assertEquals(Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK,
                promptInfo.getAuthenticators());
    }

    @Test
    public void testCombineAuthenticatorBundles_withNoKeyDeviceCredential_andNoKeyAuthenticators() {
        final PromptInfo promptInfo = new PromptInfo();

        Utils.combineAuthenticatorBundles(promptInfo);

        assertFalse(promptInfo.isDeviceCredentialAllowed());
        assertEquals(Authenticators.BIOMETRIC_WEAK, promptInfo.getAuthenticators());
    }

    @Test
    public void testErrorFromHal_whileShowingDeviceCredential_doesntNotifySystemUI()
            throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK);

        mBiometricService.mAuthSession.mSysuiReceiver.onDeviceCredentialPressed();
        waitForIdle();

        assertEquals(STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.getState());
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FINGERPRINT,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                0 /* vendorCode */);
        waitForIdle();

        assertEquals(STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.getState());
        verify(mReceiver1, never()).onError(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testLockout_whileAuthenticating_credentialAllowed() throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK);

        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FINGERPRINT,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
                0 /* vendorCode */);
        waitForIdle();

        assertEquals(STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.getState());
        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_LOCKOUT),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testLockout_whenAuthenticating_credentialNotAllowed() throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FINGERPRINT,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS,
                0 /* vendorCode */);
        waitForIdle();

        assertEquals(STATE_ERROR_PENDING_SYSUI,
                mBiometricService.mAuthSession.getState());
        verify(mBiometricService.mStatusBarService).onBiometricError(
                eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_PROCESS),
                eq(0 /* vendorCode */));
    }

    @Test
    public void testDismissedReasonUserCancel_whileAuthenticating_cancelsHalAuthentication()
            throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSysuiReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_USER_CANCEL, null /* credentialAttestation */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED),
                eq(0 /* vendorCode */));
        verify(mBiometricService.mSensors.get(0).impl).cancelAuthenticationFromService(
                any(), any(), anyLong());
        assertNull(mBiometricService.mAuthSession);
    }

    @Test
    public void testDismissedReasonNegative_whilePaused_invokeHalCancel() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FACE,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                0 /* vendorCode */);
        mBiometricService.mAuthSession.mSysuiReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_NEGATIVE, null /* credentialAttestation */);
        waitForIdle();

        verify(mBiometricService.mSensors.get(0).impl)
                .cancelAuthenticationFromService(any(), any(), anyLong());
    }

    @Test
    public void testDismissedReasonUserCancel_whilePaused_invokesHalCancel() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FACE,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT,
                0 /* vendorCode */);
        mBiometricService.mAuthSession.mSysuiReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_USER_CANCEL, null /* credentialAttestation */);
        waitForIdle();

        verify(mBiometricService.mSensors.get(0).impl)
                .cancelAuthenticationFromService(any(), any(), anyLong());
    }

    @Test
    public void testDismissedReasonUserCancel_whenPendingConfirmation() throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                true /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSensorReceiver.onAuthenticationSucceeded(
                SENSOR_ID_FACE,
                new byte[69] /* HAT */);
        mBiometricService.mAuthSession.mSysuiReceiver.onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_USER_CANCEL, null /* credentialAttestation */);
        waitForIdle();

        verify(mBiometricService.mSensors.get(0).impl)
                .cancelAuthenticationFromService(any(), any(), anyLong());
        verify(mReceiver1).onError(
                eq(TYPE_FACE),
                eq(BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED),
                eq(0 /* vendorCode */));
        verify(mBiometricService.mKeyStore, never()).addAuthToken(any(byte[].class));
        assertNull(mBiometricService.mAuthSession);
    }

    @Test
    public void testAcquire_whenAuthenticating_sentToSystemUI() throws Exception {
        when(mContext.getResources().getString(anyInt())).thenReturn("test string");

        final int modality = TYPE_FINGERPRINT;
        setupAuthForOnly(modality, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mAuthSession.mSensorReceiver.onAcquired(
                SENSOR_ID_FINGERPRINT,
                FingerprintManager.FINGERPRINT_ACQUIRED_IMAGER_DIRTY,
                0 /* vendorCode */);
        waitForIdle();

        // Sends to SysUI and stays in authenticating state. We don't test that the correct
        // string is retrieved for now, but it's also very unlikely to break anyway.
        verify(mBiometricService.mStatusBarService)
                .onBiometricHelp(eq(modality), anyString());
        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());
    }

    @Test
    public void testCancel_whenAuthenticating() throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, null /* authenticators */);

        mBiometricService.mImpl.cancelAuthentication(mBiometricService.mAuthSession.mToken,
                TEST_PACKAGE_NAME, TEST_REQUEST_ID);
        waitForIdle();

        // Pretend that the HAL has responded to cancel with ERROR_CANCELED
        mBiometricService.mAuthSession.mSensorReceiver.onError(
                SENSOR_ID_FINGERPRINT,
                getCookieForCurrentSession(mBiometricService.mAuthSession),
                BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                0 /* vendorCode */);
        waitForIdle();

        // Hides system dialog and invokes the onError callback
        verify(mReceiver1).onError(eq(TYPE_FINGERPRINT),
                eq(BiometricConstants.BIOMETRIC_ERROR_CANCELED),
                eq(0 /* vendorCode */));
        verify(mBiometricService.mStatusBarService).hideAuthenticationDialog(eq(TEST_REQUEST_ID));
    }

    @Test
    public void testCanAuthenticate_whenDeviceHasRequestedBiometricStrength() throws Exception {
        // When only biometric is requested, and sensor is strong enough
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);

        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, Authenticators.BIOMETRIC_STRONG));
    }

    @Test
    public void testCanAuthenticate_whenDeviceDoesNotHaveRequestedBiometricStrength()
            throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_WEAK);

        // When only biometric is requested, and sensor is not strong enough
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(false);
        int authenticators = Authenticators.BIOMETRIC_STRONG;
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                invokeCanAuthenticate(mBiometricService, authenticators));

        // When credential and biometric are requested, and sensor is not strong enough
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(true);
        authenticators = Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL;
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
    }

    @Test
    public void testCanAuthenticate_onlyCredentialRequested() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        // Credential requested but not set up
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(false);
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
                invokeCanAuthenticate(mBiometricService, Authenticators.DEVICE_CREDENTIAL));

        // Credential requested and set up
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(true);
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, Authenticators.DEVICE_CREDENTIAL));
    }

    @Test
    public void testCanAuthenticate_whenNoBiometricsEnrolled() throws Exception {
        // With credential set up, test the following.
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt())).thenReturn(true);
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
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
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        when(mBiometricService.mSettingObserver.getEnabledForApps(anyInt())).thenReturn(false);
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(true);

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
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
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
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(true);
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
    }

    @Test
    public void testCanAuthenticate_whenLockoutTimed() throws Exception {
        testCanAuthenticate_whenLockedOut(LockoutTracker.LOCKOUT_TIMED);
    }

    @Test
    public void testCanAuthenticate_whenLockoutPermanent() throws Exception {
        testCanAuthenticate_whenLockedOut(LockoutTracker.LOCKOUT_PERMANENT);
    }

    private void testCanAuthenticate_whenLockedOut(@LockoutTracker.LockoutMode int lockoutMode)
            throws Exception {
        // When only biometric is requested, and sensor is strong enough
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);

        when(mFingerprintAuthenticator.getLockoutModeForUser(anyInt()))
                .thenReturn(lockoutMode);

        // Lockout is not considered an error for BiometricManager#canAuthenticate
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, Authenticators.BIOMETRIC_STRONG));
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
            final BiometricSensor sensor =
                    new BiometricSensor(mContext, 0 /* id */,
                            TYPE_FINGERPRINT,
                            testCases[i][0],
                            mock(IBiometricAuthenticator.class)) {
                        @Override
                        boolean confirmationAlwaysRequired(int userId) {
                            return false;
                        }

                        @Override
                        boolean confirmationSupported() {
                            return false;
                        }
                    };
            sensor.updateStrength(testCases[i][1]);
            assertEquals(testCases[i][2], sensor.getCurrentStrength());
        }
    }

    @Test
    public void testRegisterAuthenticator_updatesStrengths() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        verify(mBiometricService.mBiometricStrengthController).startListening();
        verify(mBiometricService.mBiometricStrengthController, never()).updateStrengths();

        when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any()))
                .thenReturn(true);
        when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
        mBiometricService.mImpl.registerAuthenticator(0 /* testId */,
                TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                mFingerprintAuthenticator);

        verify(mBiometricService.mBiometricStrengthController).updateStrengths();
    }

    @Test
    public void testWithDowngradedAuthenticator() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        final int testId = 0;

        when(mBiometricService.mSettingObserver.getEnabledForApps(anyInt())).thenReturn(true);

        when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any()))
                .thenReturn(true);
        when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
        mBiometricService.mImpl.registerAuthenticator(testId /* id */,
                TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG,
                mFingerprintAuthenticator);

        // Downgrade the authenticator
        for (BiometricSensor sensor : mBiometricService.mSensors) {
            if (sensor.id == testId) {
                sensor.updateStrength(Authenticators.BIOMETRIC_WEAK);
            }
        }

        // STRONG-only auth is not available
        int authenticators = Authenticators.BIOMETRIC_STRONG;
        assertEquals(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
                invokeCanAuthenticate(mBiometricService, authenticators));
        long requestId = invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, authenticators, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();
        verify(mReceiver1).onError(
                eq(TYPE_FINGERPRINT),
                eq(BiometricPrompt.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
                eq(0) /* vendorCode */);

        // Request for weak auth works
        resetReceivers();
        authenticators = Authenticators.BIOMETRIC_WEAK;
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
        requestId = invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                authenticators);
        waitForIdle();
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mAuthSession.mPromptInfo),
                any(IBiometricSysuiReceiver.class),
                AdditionalMatchers.aryEq(new int[] {testId}),
                eq(false) /* credentialAllowed */,
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                eq(TEST_PACKAGE_NAME),
                eq(requestId));

        // Requesting strong and credential, when credential is setup
        resetReceivers();
        authenticators = Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL;
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                .thenReturn(true);
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
        requestId = invokeAuthenticate(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */,
                authenticators, false /* useDefaultSubtitle */,
                false /* deviceCredentialAllowed */);
        waitForIdle();
        assertTrue(Utils.isCredentialRequested(mBiometricService.mAuthSession.mPromptInfo));
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mAuthSession.mPromptInfo),
                any(IBiometricSysuiReceiver.class),
                AdditionalMatchers.aryEq(new int[0]) /* sensorIds */,
                eq(true) /* credentialAllowed */,
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                eq(TEST_PACKAGE_NAME),
                eq(requestId));

        // Un-downgrading the authenticator allows successful strong auth
        for (BiometricSensor sensor : mBiometricService.mSensors) {
            if (sensor.id == testId) {
                sensor.updateStrength(Authenticators.BIOMETRIC_STRONG);
            }
        }

        resetReceivers();
        authenticators = Authenticators.BIOMETRIC_STRONG;
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
                invokeCanAuthenticate(mBiometricService, authenticators));
        requestId = invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, authenticators);
        waitForIdle();
        verify(mBiometricService.mStatusBarService).showAuthenticationDialog(
                eq(mBiometricService.mAuthSession.mPromptInfo),
                any(IBiometricSysuiReceiver.class),
                AdditionalMatchers.aryEq(new int[] {testId}) /* sensorIds */,
                eq(false) /* credentialAllowed */,
                anyBoolean() /* requireConfirmation */,
                anyInt() /* userId */,
                anyLong() /* operationId */,
                eq(TEST_PACKAGE_NAME),
                eq(requestId));
    }

    @Test(expected = IllegalStateException.class)
    public void testRegistrationWithDuplicateId_throwsIllegalStateException() throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        mBiometricService.mImpl.registerAuthenticator(
                0 /* id */, 2 /* modality */, 15 /* strength */,
                mFingerprintAuthenticator);
        mBiometricService.mImpl.registerAuthenticator(
                0 /* id */, 2 /* modality */, 15 /* strength */,
                mFingerprintAuthenticator);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegistrationWithNullAuthenticator_throwsIllegalArgumentException()
            throws Exception {
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        mBiometricService.mImpl.registerAuthenticator(
                0 /* id */, 2 /* modality */,
                Authenticators.BIOMETRIC_STRONG /* strength */,
                null /* authenticator */);
    }

    @Test
    public void testRegistrationHappyPath_isOk() throws Exception {
        // This is being tested in many of the other cases, but here's the base case.
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        for (String s : mInjector.getConfiguration(null)) {
            SensorConfig config = new SensorConfig(s);
            mBiometricService.mImpl.registerAuthenticator(config.id, config.modality,
                config.strength, mFingerprintAuthenticator);
        }
    }

    @Test
    public void testWorkAuthentication_fingerprintWorksIfNotDisabledByDevicePolicyManager()
            throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        when(mDevicePolicyManager
                .getKeyguardDisabledFeatures(any() /* admin */, anyInt() /* userHandle */))
                .thenReturn(~DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT);
        invokeAuthenticateForWorkApp(mBiometricService.mImpl, mReceiver1,
                Authenticators.BIOMETRIC_STRONG);
        waitForIdle();
        assertEquals(STATE_AUTH_CALLED, mBiometricService.mAuthSession.getState());
        startPendingAuthSession(mBiometricService);
        waitForIdle();
        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());
    }

    @Test
    public void testAuthentication_normalAppIgnoresDevicePolicy() throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        when(mDevicePolicyManager
                .getKeyguardDisabledFeatures(any() /* admin */, anyInt() /* userHandle */))
                .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT);
        invokeAuthenticateAndStart(mBiometricService.mImpl, mReceiver1,
                false /* requireConfirmation */, Authenticators.BIOMETRIC_STRONG);
        waitForIdle();
        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());
    }

    @Test
    public void testWorkAuthentication_faceWorksIfNotDisabledByDevicePolicyManager()
            throws Exception {
        setupAuthForOnly(TYPE_FACE, Authenticators.BIOMETRIC_STRONG);
        when(mDevicePolicyManager
                .getKeyguardDisabledFeatures(any() /* admin*/, anyInt() /* userHandle */))
                .thenReturn(~DevicePolicyManager.KEYGUARD_DISABLE_FACE);
        invokeAuthenticateForWorkApp(mBiometricService.mImpl, mReceiver1,
                Authenticators.BIOMETRIC_STRONG);
        waitForIdle();
        assertEquals(STATE_AUTH_CALLED, mBiometricService.mAuthSession.getState());
        startPendingAuthSession(mBiometricService);
        waitForIdle();
        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());
    }

    @Test
    public void testWorkAuthentication_fingerprintFailsIfDisabledByDevicePolicyManager()
            throws Exception {
        setupAuthForOnly(TYPE_FINGERPRINT, Authenticators.BIOMETRIC_STRONG);
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt())).thenReturn(true);
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
        assertNotNull(mBiometricService.mAuthSession);
        assertEquals(STATE_SHOWING_DEVICE_CREDENTIAL,
                mBiometricService.mAuthSession.getState());
        verify(mReceiver2, never()).onError(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testRegisterEnabledOnKeyguardCallback() throws RemoteException {
        final UserInfo userInfo1 = new UserInfo(0 /* userId */, "user1" /* name */, 0 /* flags */);
        final UserInfo userInfo2 = new UserInfo(10 /* userId */, "user2" /* name */, 0 /* flags */);
        final List<UserInfo> aliveUsers = List.of(userInfo1, userInfo2);
        final IBiometricEnabledOnKeyguardCallback callback =
                mock(IBiometricEnabledOnKeyguardCallback.class);

        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);

        when(mUserManager.getAliveUsers()).thenReturn(aliveUsers);
        when(mBiometricService.mSettingObserver.getEnabledOnKeyguard(userInfo1.id))
                .thenReturn(true);
        when(mBiometricService.mSettingObserver.getEnabledOnKeyguard(userInfo2.id))
                .thenReturn(false);
        when(callback.asBinder()).thenReturn(mock(IBinder.class));

        mBiometricService.mImpl.registerEnabledOnKeyguardCallback(callback);

        waitForIdle();

        verify(callback).asBinder();
        verify(callback).onChanged(true, userInfo1.id);
        verify(callback).onChanged(false, userInfo2.id);
        verifyNoMoreInteractions(callback);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testGetLastAuthenticationTime_flagOff_throwsUnsupportedOperationException()
            throws RemoteException {
        mSetFlagsRule.disableFlags(Flags.FLAG_LAST_AUTHENTICATION_TIME);

        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.mImpl.getLastAuthenticationTime(0, Authenticators.BIOMETRIC_STRONG);
    }

    @Test
    public void testGetLastAuthenticationTime_flagOn_callsKeystoreAuthorization()
            throws RemoteException {
        mSetFlagsRule.enableFlags(Flags.FLAG_LAST_AUTHENTICATION_TIME);

        final int[] hardwareAuthenticators = new int[] {
                HardwareAuthenticatorType.PASSWORD,
                HardwareAuthenticatorType.FINGERPRINT
        };

        final int userId = 0;
        final long secureUserId = mGateKeeperService.getSecureUserId(userId);

        assertNotEquals(GateKeeper.INVALID_SECURE_USER_ID, secureUserId);

        final long expectedResult = 31337L;

        when(mKeystoreAuthService.getLastAuthTime(eq(secureUserId), eq(hardwareAuthenticators)))
                .thenReturn(expectedResult);

        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);

        final long result = mBiometricService.mImpl.getLastAuthenticationTime(userId,
                Authenticators.BIOMETRIC_STRONG | Authenticators.DEVICE_CREDENTIAL);

        assertEquals(expectedResult, result);
        verify(mKeystoreAuthService).getLastAuthTime(eq(secureUserId), eq(hardwareAuthenticators));
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
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        when(mBiometricService.mSettingObserver.getEnabledForApps(anyInt())).thenReturn(true);

        if ((modality & TYPE_FINGERPRINT) != 0) {
            when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any()))
                    .thenReturn(enrolled);
            when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
            when(mFingerprintAuthenticator.getLockoutModeForUser(anyInt()))
                    .thenReturn(LockoutTracker.LOCKOUT_NONE);
            mBiometricService.mImpl.registerAuthenticator(SENSOR_ID_FINGERPRINT, modality, strength,
                    mFingerprintAuthenticator);
        }

        if ((modality & TYPE_FACE) != 0) {
            when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(enrolled);
            when(mFaceAuthenticator.isHardwareDetected(any())).thenReturn(true);
            when(mFaceAuthenticator.getLockoutModeForUser(anyInt()))
                    .thenReturn(LockoutTracker.LOCKOUT_NONE);
            mBiometricService.mImpl.registerAuthenticator(SENSOR_ID_FACE, modality, strength,
                    mFaceAuthenticator);
        }

        if ((modality & TYPE_CREDENTIAL) != 0) {
            when(mTrustManager.isDeviceSecure(anyInt(), anyInt()))
                    .thenReturn(true);
        }
    }

    // TODO: Reduce duplicated code, currently we cannot start the BiometricService in setUp() for
    // all tests.
    private void setupAuthForMultiple(int[] modalities, int[] strengths) throws RemoteException {
        mBiometricService = new BiometricService(mContext, mInjector, mBiometricHandlerProvider);
        mBiometricService.onStart();

        when(mBiometricService.mSettingObserver.getEnabledForApps(anyInt())).thenReturn(true);

        assertEquals(modalities.length, strengths.length);

        for (int i = 0; i < modalities.length; i++) {
            final int modality = modalities[i];
            final int strength = strengths[i];

            if ((modality & TYPE_FINGERPRINT) != 0) {
                when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any()))
                        .thenReturn(true);
                when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
                mBiometricService.mImpl.registerAuthenticator(SENSOR_ID_FINGERPRINT, modality,
                        strength, mFingerprintAuthenticator);
            }

            if ((modality & TYPE_FACE) != 0) {
                when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
                when(mFaceAuthenticator.isHardwareDetected(any())).thenReturn(true);
                mBiometricService.mImpl.registerAuthenticator(SENSOR_ID_FACE, modality,
                        strength, mFaceAuthenticator);
            }
        }
    }

    private void resetReceivers() {
        mReceiver1 = mock(IBiometricServiceReceiver.class);
        mReceiver2 = mock(IBiometricServiceReceiver.class);

        when(mReceiver1.asBinder()).thenReturn(mock(Binder.class));
        when(mReceiver2.asBinder()).thenReturn(mock(Binder.class));
    }

    private void resetStatusBar() {
        mBiometricService.mStatusBarService = mock(IStatusBarService.class);
    }

    private long invokeAuthenticateAndStart(IBiometricService.Stub service,
            IBiometricServiceReceiver receiver, boolean requireConfirmation,
            Integer authenticators) throws Exception {
        // Request auth, creates a pending session
        final long requestId = invokeAuthenticate(
                service, receiver, requireConfirmation, authenticators,
                false /* useDefaultSubtitle */, false /* deviceCredentialAllowed */);
        waitForIdle();

        startPendingAuthSession(mBiometricService);
        waitForIdle();

        assertNotNull(mBiometricService.mAuthSession);
        assertEquals(TEST_REQUEST_ID, mBiometricService.mAuthSession.getRequestId());
        assertEquals(STATE_AUTH_STARTED, mBiometricService.mAuthSession.getState());

        return requestId;
    }

    private static void startPendingAuthSession(BiometricService service) throws Exception {
        // Get the cookie so we can pretend the hardware is ready to authenticate
        // Currently we only support single modality per auth
        final PreAuthInfo preAuthInfo = service.mAuthSession.mPreAuthInfo;
        assertEquals(preAuthInfo.eligibleSensors.size(), 1);
        assertEquals(preAuthInfo.numSensorsWaitingForCookie(), 1);

        final int cookie = preAuthInfo.eligibleSensors.get(0).getCookie();
        assertNotEquals(cookie, 0);

        service.mImpl.onReadyForAuthentication(TEST_REQUEST_ID, cookie);
    }

    private static long invokeAuthenticate(IBiometricService.Stub service,
            IBiometricServiceReceiver receiver, boolean requireConfirmation,
            Integer authenticators, boolean useDefaultSubtitle,
            boolean deviceCredentialAllowed) throws Exception {
        return service.authenticate(
                new Binder() /* token */,
                0 /* operationId */,
                0 /* userId */,
                receiver,
                TEST_PACKAGE_NAME /* packageName */,
                createTestPromptInfo(requireConfirmation, authenticators,
                        false /* checkDevicePolicy */, useDefaultSubtitle,
                        deviceCredentialAllowed));
    }

    private static long invokeAuthenticateForWorkApp(IBiometricService.Stub service,
            IBiometricServiceReceiver receiver, Integer authenticators) throws Exception {
        return service.authenticate(
                new Binder() /* token */,
                0 /* operationId */,
                0 /* userId */,
                receiver,
                TEST_PACKAGE_NAME /* packageName */,
                createTestPromptInfo(false /* requireConfirmation */, authenticators,
                        true /* checkDevicePolicy */, false /* useDefaultSubtitle */,
                        false /* deviceCredentialAllowed */));
    }

    private static PromptInfo createTestPromptInfo(
            boolean requireConfirmation,
            Integer authenticators,
            boolean checkDevicePolicy,
            boolean useDefaultSubtitle,
            boolean deviceCredentialAllowed) {
        final PromptInfo promptInfo = new PromptInfo();
        promptInfo.setConfirmationRequested(requireConfirmation);
        promptInfo.setUseDefaultSubtitle(useDefaultSubtitle);

        if (authenticators != null) {
            promptInfo.setAuthenticators(authenticators);
        }
        if (checkDevicePolicy) {
            promptInfo.setDisallowBiometricsIfPolicyExists(checkDevicePolicy);
        }
        promptInfo.setDeviceCredentialAllowed(deviceCredentialAllowed);
        return promptInfo;
    }

    private static int getCookieForCurrentSession(AuthSession session) {
        // Currently only tests authentication with a single sensor
        final PreAuthInfo preAuthInfo = session.mPreAuthInfo;

        assertEquals(preAuthInfo.eligibleSensors.size(), 1);
        return preAuthInfo.eligibleSensors.get(0).getCookie();
    }

    private static int getCookieForPendingSession(AuthSession session) {
        // Currently only tests authentication with a single sensor
        final PreAuthInfo requestWrapper = session.mPreAuthInfo;

        assertEquals(requestWrapper.eligibleSensors.size(), 1);
        assertEquals(requestWrapper.eligibleSensors.get(0).getSensorState(),
                BiometricSensor.STATE_WAITING_FOR_COOKIE);
        return requestWrapper.eligibleSensors.get(0).getCookie();
    }

    private void waitForIdle() {
        if (com.android.server.biometrics.Flags.deHidl()) {
            TestableLooper.get(this).processAllMessages();
        } else {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }

    private byte[] generateRandomHAT() {
        byte[] HAT = new byte[69];
        Random random = new Random();
        random.nextBytes(HAT);
        return HAT;
    }
}
