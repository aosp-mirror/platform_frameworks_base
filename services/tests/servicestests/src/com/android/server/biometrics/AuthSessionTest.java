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

package com.android.server.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_NEGATIVE_BUTTON;
import static android.hardware.biometrics.BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED;
import static android.hardware.biometrics.BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED;
import static android.hardware.biometrics.BiometricPrompt.DISMISSED_REASON_NEGATIVE;
import static android.hardware.biometrics.BiometricPrompt.DISMISSED_REASON_USER_CANCEL;

import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_CALLED;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_PAUSED;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_STARTED;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_AUTH_STARTED_UI_SHOWING;
import static com.android.server.biometrics.BiometricServiceStateProto.STATE_ERROR_PENDING_SYSUI;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorProperties;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.security.KeyStoreAuthorization;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricFrameworkStatsLogger;
import com.android.server.biometrics.log.OperationContextExt;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

@Presubmit
@SmallTest
public class AuthSessionTest {

    private static final String TEST_PACKAGE = "test_package";
    private static final long TEST_REQUEST_ID = 22;
    private static final String ACQUIRED_STRING = "test_acquired_info_callback";
    private static final String ACQUIRED_STRING_VENDOR = "test_acquired_info_callback_vendor";

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Mock private Resources mResources;
    @Mock private BiometricContext mBiometricContext;
    @Mock private ITrustManager mTrustManager;
    @Mock private DevicePolicyManager mDevicePolicyManager;
    @Mock private BiometricService.SettingObserver mSettingObserver;
    @Mock private IBiometricSensorReceiver mSensorReceiver;
    @Mock private IBiometricServiceReceiver mClientReceiver;
    @Mock private IStatusBarService mStatusBarService;
    @Mock private IBiometricSysuiReceiver mSysuiReceiver;
    @Mock private KeyStoreAuthorization mKeyStoreAuthorization;
    @Mock private AuthSession.ClientDeathReceiver mClientDeathReceiver;
    @Mock private BiometricFrameworkStatsLogger mBiometricFrameworkStatsLogger;
    @Mock private BiometricCameraManager mBiometricCameraManager;
    @Mock private BiometricManager mBiometricManager;

    private Random mRandom;
    private IBinder mToken;

    // Assume all tests can be done with the same set of sensors for now.
    @NonNull private List<BiometricSensor> mSensors;
    @NonNull private List<FingerprintSensorPropertiesInternal> mFingerprintSensorProps;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(BiometricManager.class, mBiometricManager);
        mContext.getOrCreateTestableResources().addOverride(R.string.fingerprint_acquired_partial,
                ACQUIRED_STRING);
        mContext.getOrCreateTestableResources().addOverride(R.array.fingerprint_acquired_vendor,
                new String[]{ACQUIRED_STRING_VENDOR});
        when(mClientReceiver.asBinder()).thenReturn(mock(Binder.class));
        when(mBiometricContext.updateContext(any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        mRandom = new Random();
        mToken = new Binder();
        mSensors = new ArrayList<>();
        mFingerprintSensorProps = new ArrayList<>();
    }

    @Test
    public void testNewAuthSession_eligibleSensorsSetToStateUnknown() throws RemoteException {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_REAR);
        setupFace(1 /* id */, false /* confirmationAlwaysRequired */,
                mock(IBiometricAuthenticator.class));

        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                0 /* operationId */,
                0 /* userId */);

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_UNKNOWN, sensor.getSensorState());
        }
    }

    @Test
    public void testStartNewAuthSession() throws RemoteException {
        setupFace(0 /* id */, false /* confirmationAlwaysRequired */,
                mock(IBiometricAuthenticator.class));
        setupFingerprint(1 /* id */, FingerprintSensorProperties.TYPE_REAR);

        final boolean requireConfirmation = true;
        final long operationId = 123;
        final int userId = 10;

        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                operationId,
                userId);
        assertEquals(mSensors.size(), session.mPreAuthInfo.eligibleSensors.size());

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_UNKNOWN, sensor.getSensorState());
            assertEquals(0, sensor.getCookie());
        }

        session.goToInitialState();
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_WAITING_FOR_COOKIE, sensor.getSensorState());
            assertTrue("Cookie must be >0", sensor.getCookie() > 0);
            verify(sensor.impl).prepareForAuthentication(
                    eq(sensor.confirmationSupported() && requireConfirmation),
                    eq(mToken),
                    eq(operationId),
                    eq(userId),
                    eq(mSensorReceiver),
                    eq(TEST_PACKAGE),
                    eq(TEST_REQUEST_ID),
                    eq(sensor.getCookie()),
                    anyBoolean() /* allowBackgroundAuthentication */,
                    anyBoolean() /* isForLegacyFingerprintManager */);
        }

        final int cookie1 = session.mPreAuthInfo.eligibleSensors.get(0).getCookie();
        session.onCookieReceived(cookie1);
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            if (cookie1 == sensor.getCookie()) {
                assertEquals(BiometricSensor.STATE_COOKIE_RETURNED, sensor.getSensorState());
            } else {
                assertEquals(BiometricSensor.STATE_WAITING_FOR_COOKIE, sensor.getSensorState());
            }
        }
        assertFalse(session.allCookiesReceived());

        final int cookie2 = session.mPreAuthInfo.eligibleSensors.get(1).getCookie();
        session.onCookieReceived(cookie2);
        assertTrue(session.allCookiesReceived());


        // for multi-sensor face then fingerprint is the default policy
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            if (sensor.modality == TYPE_FACE) {
                verify(sensor.impl).startPreparedClient(eq(sensor.getCookie()));
                assertEquals(BiometricSensor.STATE_AUTHENTICATING, sensor.getSensorState());
            } else if (sensor.modality == TYPE_FINGERPRINT) {
                assertEquals(BiometricSensor.STATE_COOKIE_RETURNED, sensor.getSensorState());
            }
        }
    }

    @Test
    public void testOnErrorReceived_lockoutError() throws RemoteException {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_REAR);
        setupFace(1 /* id */, false /* confirmationAlwaysRequired */,
                mock(IBiometricAuthenticator.class));
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                0 /* operationId */,
                0 /* userId */);
        session.goToInitialState();
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_WAITING_FOR_COOKIE, sensor.getSensorState());
            session.onCookieReceived(
                    session.mPreAuthInfo.eligibleSensors.get(sensor.id).getCookie());
        }
        assertTrue(session.allCookiesReceived());
        assertEquals(STATE_AUTH_STARTED, session.getState());

        // Either of strong sensor's lockout should cancel both sensors.
        final int cookie1 = session.mPreAuthInfo.eligibleSensors.get(0).getCookie();
        session.onErrorReceived(0, cookie1, BiometricConstants.BIOMETRIC_ERROR_LOCKOUT, 0);
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_CANCELING, sensor.getSensorState());
        }
        assertEquals(STATE_ERROR_PENDING_SYSUI, session.getState());

        // If the sensor is STATE_CANCELING, delayed onAuthenticationRejected() shouldn't change the
        // session state to STATE_AUTH_PAUSED.
        session.onAuthenticationRejected(1);
        assertEquals(STATE_ERROR_PENDING_SYSUI, session.getState());
    }

    @Test
    public void testOnErrorReceivedBeforeOnDialogAnimatedIn() throws RemoteException {
        final int fingerprintId = 0;
        final int faceId = 1;
        setupFingerprint(fingerprintId, FingerprintSensorProperties.TYPE_REAR);
        setupFace(faceId, true /* confirmationAlwaysRequired */,
                mock(IBiometricAuthenticator.class));
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                0 /* operationId */,
                0 /* userId */);
        session.goToInitialState();

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertThat(sensor.getSensorState()).isEqualTo(BiometricSensor.STATE_WAITING_FOR_COOKIE);
            session.onCookieReceived(
                    session.mPreAuthInfo.eligibleSensors.get(sensor.id).getCookie());
        }
        assertThat(session.allCookiesReceived()).isTrue();
        assertThat(session.getState()).isEqualTo(STATE_AUTH_STARTED);

        final BiometricSensor faceSensor = session.mPreAuthInfo.eligibleSensors.get(faceId);
        final BiometricSensor fingerprintSensor = session.mPreAuthInfo.eligibleSensors.get(
                fingerprintId);
        final int cookie = faceSensor.getCookie();
        session.onErrorReceived(0, cookie, BiometricConstants.BIOMETRIC_ERROR_RE_ENROLL, 0);

        assertThat(faceSensor.getSensorState()).isEqualTo(BiometricSensor.STATE_STOPPED);
        assertThat(session.getState()).isEqualTo(STATE_ERROR_PENDING_SYSUI);

        session.onDialogAnimatedIn(true);

        assertThat(session.getState()).isEqualTo(STATE_AUTH_STARTED_UI_SHOWING);
        assertThat(fingerprintSensor.getSensorState()).isEqualTo(
                BiometricSensor.STATE_AUTHENTICATING);
    }

    @Test
    public void testOnRejectionReceivedBeforeOnDialogAnimatedIn() throws RemoteException {
        final int fingerprintId = 0;
        final int faceId = 1;
        setupFingerprint(fingerprintId, FingerprintSensorProperties.TYPE_REAR);
        setupFace(faceId, false /* confirmationAlwaysRequired */,
                mock(IBiometricAuthenticator.class));
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                0 /* operationId */,
                0 /* userId */);
        session.goToInitialState();

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertThat(sensor.getSensorState()).isEqualTo(BiometricSensor.STATE_WAITING_FOR_COOKIE);
            session.onCookieReceived(
                    session.mPreAuthInfo.eligibleSensors.get(sensor.id).getCookie());
        }
        assertThat(session.allCookiesReceived()).isTrue();
        assertThat(session.getState()).isEqualTo(STATE_AUTH_STARTED);

        final BiometricSensor faceSensor = session.mPreAuthInfo.eligibleSensors.get(faceId);
        final BiometricSensor fingerprintSensor = session.mPreAuthInfo.eligibleSensors.get(
                fingerprintId);
        session.onAuthenticationRejected(faceId);

        assertThat(faceSensor.getSensorState()).isEqualTo(BiometricSensor.STATE_CANCELING);
        assertThat(session.getState()).isEqualTo(STATE_AUTH_PAUSED);

        session.onDialogAnimatedIn(true);

        assertThat(session.getState()).isEqualTo(STATE_AUTH_STARTED_UI_SHOWING);
        assertThat(fingerprintSensor.getSensorState()).isEqualTo(
                BiometricSensor.STATE_AUTHENTICATING);
    }

    @Test
    public void testCancelReducesAppetiteForCookies() throws Exception {
        setupFace(0 /* id */, false /* confirmationAlwaysRequired */,
                mock(IBiometricAuthenticator.class));
        setupFingerprint(1 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);

        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                44 /* operationId */,
                2 /* userId */);

        session.goToInitialState();

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_WAITING_FOR_COOKIE, sensor.getSensorState());
        }

        session.onCancelAuthSession(false /* force */);

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            session.onCookieReceived(sensor.getCookie());
            assertEquals(BiometricSensor.STATE_CANCELING, sensor.getSensorState());
        }
    }

    @Test
    public void testMultiAuth_singleSensor_fingerprintSensorStartsAfterDialogAnimationCompletes()
            throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
        testMultiAuth_fingerprintSensorStartsAfterUINotifies(true /* startFingerprintNow */);
    }

    @Test
    public void testMultiAuth_singleSensor_fingerprintSensorDoesNotStartAfterDialogAnimationCompletes()
            throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
        testMultiAuth_fingerprintSensorStartsAfterUINotifies(false /* startFingerprintNow */);
    }

    @Test
    public void testMultiAuth_fingerprintSensorStartsAfterDialogAnimationCompletes()
            throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
        setupFace(1 /* id */, false, mock(IBiometricAuthenticator.class));
        testMultiAuth_fingerprintSensorStartsAfterUINotifies(true /* startFingerprintNow */);
    }

    @Test
    public void testMultiAuth_fingerprintSensorDoesNotStartAfterDialogAnimationCompletes()
            throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
        setupFace(1 /* id */, false, mock(IBiometricAuthenticator.class));
        testMultiAuth_fingerprintSensorStartsAfterUINotifies(false /* startFingerprintNow */);
    }

    public void testMultiAuth_fingerprintSensorStartsAfterUINotifies(boolean startFingerprintNow)
            throws Exception {
        final long operationId = 123;
        final int userId = 10;
        final int fingerprintSensorId = mSensors.stream()
                .filter(s -> s.modality == TYPE_FINGERPRINT)
                .map(s -> s.id)
                .findFirst()
                .orElse(-1);

        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                operationId,
                userId);
        assertEquals(mSensors.size(), session.mPreAuthInfo.eligibleSensors.size());

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_UNKNOWN, sensor.getSensorState());
            assertEquals(0, sensor.getCookie());
        }

        session.goToInitialState();

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_WAITING_FOR_COOKIE, sensor.getSensorState());
            session.onCookieReceived(
                    session.mPreAuthInfo.eligibleSensors.get(sensor.id).getCookie());
            if (fingerprintSensorId == sensor.id) {
                assertEquals(BiometricSensor.STATE_COOKIE_RETURNED, sensor.getSensorState());
            } else {
                assertEquals(BiometricSensor.STATE_AUTHENTICATING, sensor.getSensorState());
            }
        }
        assertTrue(session.allCookiesReceived());

        // fingerprint sensor does not start even if all cookies are received
        assertEquals(STATE_AUTH_STARTED, session.getState());
        verify(mStatusBarService).showAuthenticationDialog(any(), any(), any(),
                anyBoolean(), anyBoolean(), anyInt(), anyLong(), any(), anyLong());

        // Notify AuthSession that the UI is shown. Then, fingerprint sensor should be started.
        session.onDialogAnimatedIn(startFingerprintNow);
        assertEquals(STATE_AUTH_STARTED_UI_SHOWING, session.getState());
        assertEquals(startFingerprintNow ? BiometricSensor.STATE_AUTHENTICATING
                        : BiometricSensor.STATE_COOKIE_RETURNED,
                session.mPreAuthInfo.eligibleSensors.get(fingerprintSensorId).getSensorState());
        verify(mBiometricContext).updateContext((OperationContextExt) anyObject(),
                eq(session.isCrypto()));

        // start fingerprint sensor if it was delayed
        if (!startFingerprintNow) {
            session.onStartFingerprint();
            assertEquals(BiometricSensor.STATE_AUTHENTICATING,
                    session.mPreAuthInfo.eligibleSensors.get(fingerprintSensorId).getSensorState());
        }
    }

    @Test
    public void testOnDialogAnimatedInDoesNothingDuringInvalidState() throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
        final long operationId = 123;
        final int userId = 10;

        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                operationId,
                userId);
        final IBiometricAuthenticator impl = session.mPreAuthInfo.eligibleSensors.get(0).impl;

        session.goToInitialState();
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_WAITING_FOR_COOKIE, sensor.getSensorState());
            session.onCookieReceived(
                    session.mPreAuthInfo.eligibleSensors.get(sensor.id).getCookie());
        }
        assertTrue(session.allCookiesReceived());
        assertEquals(STATE_AUTH_STARTED, session.getState());
        verify(impl, never()).startPreparedClient(anyInt());

        // First invocation should start the client monitor.
        session.onDialogAnimatedIn(true /* startFingerprintNow */);
        assertEquals(STATE_AUTH_STARTED_UI_SHOWING, session.getState());
        verify(impl).startPreparedClient(anyInt());

        // Subsequent invocations should not start the client monitor again.
        session.onDialogAnimatedIn(true /* startFingerprintNow */);
        session.onDialogAnimatedIn(false /* startFingerprintNow */);
        session.onDialogAnimatedIn(true /* startFingerprintNow */);
        assertEquals(STATE_AUTH_STARTED_UI_SHOWING, session.getState());
        verify(impl, times(1)).startPreparedClient(anyInt());
    }

    @Test
    public void testCancelAuthentication_whenStateAuthCalled_invokesCancel()
            throws RemoteException {
        testInvokesCancel(session -> session.onCancelAuthSession(false /* force */));
    }

    @Test
    public void testCancelAuthentication_whenStateAuthForcedCalled_invokesCancel()
            throws RemoteException {
        testInvokesCancel(session -> session.onCancelAuthSession(true /* force */));
    }

    @Test
    public void testCancelAuthentication_whenDialogDismissed() throws RemoteException {
        testInvokesCancel(session -> session.onDialogDismissed(DISMISSED_REASON_NEGATIVE, null));
    }

    @Test
    public void testCallbackOnAcquired() throws RemoteException {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_REAR);

        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                0 /* operationId */,
                0 /* userId */);

        session.onAcquired(0, FingerprintManager.FINGERPRINT_ACQUIRED_PARTIAL, 0);
        verify(mStatusBarService).onBiometricHelp(anyInt(), eq(ACQUIRED_STRING));
        verify(mClientReceiver).onAcquired(eq(1), eq(ACQUIRED_STRING));

        session.onAcquired(0, FingerprintManager.FINGERPRINT_ACQUIRED_VENDOR, 0);
        verify(mStatusBarService).onBiometricHelp(anyInt(), eq(ACQUIRED_STRING_VENDOR));
        verify(mClientReceiver).onAcquired(
                eq(FingerprintManager.FINGERPRINT_ACQUIRED_VENDOR_BASE),
                eq(ACQUIRED_STRING_VENDOR));
    }

    @Test
    public void testLogOnDialogDismissed_authenticatedWithConfirmation() throws RemoteException {
        final IBiometricAuthenticator faceAuthenticator = mock(IBiometricAuthenticator.class);

        setupFace(0 /* id */, false /* confirmationAlwaysRequired */, faceAuthenticator);
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                0 /* operationId */,
                0 /* userId */);
        session.goToInitialState();
        assertEquals(STATE_AUTH_CALLED, session.getState());

        session.onDialogDismissed(DISMISSED_REASON_BIOMETRIC_CONFIRMED, null);
        verify(mBiometricFrameworkStatsLogger, times(1)).authenticate(
                (OperationContextExt) anyObject(),
                eq(BiometricsProtoEnums.MODALITY_FACE),
                eq(BiometricsProtoEnums.ACTION_UNKNOWN),
                eq(BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT),
                eq(false), /* debugEnabled */
                anyLong(), /* latency */
                eq(FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED),
                eq(true), /* confirmationRequired */
                eq(0) /* userId */,
                eq(-1f) /* ambientLightLux */);
    }

    @Test
    public void testLogOnDialogDismissed_authenticatedWithoutConfirmation() throws RemoteException {
        final IBiometricAuthenticator faceAuthenticator = mock(IBiometricAuthenticator.class);

        setupFace(0 /* id */, false /* confirmationAlwaysRequired */, faceAuthenticator);
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                0 /* operationId */,
                0 /* userId */);
        session.goToInitialState();
        assertEquals(STATE_AUTH_CALLED, session.getState());

        session.onDialogDismissed(DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED, null);
        verify(mBiometricFrameworkStatsLogger, never()).authenticate(
                anyObject(), anyInt(), anyInt(), anyInt(), anyBoolean(), anyLong(), anyInt(),
                anyBoolean(), anyInt(), eq(-1f));
        verify(mBiometricFrameworkStatsLogger, never()).error(
                anyObject(), anyInt(), anyInt(), anyInt(), anyBoolean(), anyLong(), anyInt(),
                anyInt(), anyInt());
    }

    @Test
    public void testLogOnDialogDismissed_error() throws RemoteException {
        final IBiometricAuthenticator faceAuthenticator = mock(IBiometricAuthenticator.class);

        setupFace(0 /* id */, false /* confirmationAlwaysRequired */, faceAuthenticator);
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                0 /* operationId */,
                0 /* userId */);
        session.goToInitialState();
        assertEquals(STATE_AUTH_CALLED, session.getState());

        session.onDialogDismissed(DISMISSED_REASON_NEGATIVE, null);
        verify(mBiometricFrameworkStatsLogger, times(1)).error(
                (OperationContextExt) anyObject(),
                eq(BiometricsProtoEnums.MODALITY_FACE),
                eq(BiometricsProtoEnums.ACTION_AUTHENTICATE),
                eq(BiometricsProtoEnums.CLIENT_BIOMETRIC_PROMPT),
                eq(false),
                anyLong(),
                eq(BIOMETRIC_ERROR_NEGATIVE_BUTTON),
                eq(0) /* vendorCode */,
                eq(0) /* userId */);
    }

    @Test
    public void onErrorReceivedAfterOnTryAgainPressedWhenSensorsAuthenticating() throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
        setupFace(1 /* id */, false, mock(IBiometricAuthenticator.class));
        final long operationId = 123;
        final int userId = 10;
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                operationId,
                userId);
        session.goToInitialState();
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            session.onCookieReceived(
                    session.mPreAuthInfo.eligibleSensors.get(sensor.id).getCookie());
        }
        session.onDialogAnimatedIn(true /* startFingerprintNow */);

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_AUTHENTICATING, sensor.getSensorState());
        }
        session.onTryAgainPressed();
        session.onErrorReceived(0 /* sensorId */,
                session.mPreAuthInfo.eligibleSensors.get(0 /* sensorId */).getCookie(),
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT, 0);

        verify(mStatusBarService).onBiometricError(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onErrorReceivedAfterOnTryAgainPressedWhenSensorStopped() throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
        setupFace(1 /* id */, false, mock(IBiometricAuthenticator.class));
        final long operationId = 123;
        final int userId = 10;
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                operationId,
                userId);
        session.goToInitialState();
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            session.onCookieReceived(
                    session.mPreAuthInfo.eligibleSensors.get(sensor.id).getCookie());
        }
        session.onDialogAnimatedIn(true /* startFingerprintNow */);

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            sensor.goToStoppedStateIfCookieMatches(sensor.getCookie(),
                    BiometricConstants.BIOMETRIC_ERROR_TIMEOUT);
            assertEquals(BiometricSensor.STATE_STOPPED, sensor.getSensorState());
        }

        session.onTryAgainPressed();
        session.onErrorReceived(0 /* sensorId */,
                session.mPreAuthInfo.eligibleSensors.get(0 /* sensorId */).getCookie(),
                BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT, 0);

        verify(mStatusBarService, never()).onBiometricError(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onAuthReceivedWhileWaitingForConfirmation_SFPS() throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_POWER_BUTTON);
        setupFace(1 /* id */, false, mock(IBiometricAuthenticator.class));
        final long operationId = 123;
        final int userId = 10;
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                operationId,
                userId);
        session.goToInitialState();
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            session.onCookieReceived(
                    session.mPreAuthInfo.eligibleSensors.get(sensor.id).getCookie());
        }
        session.onDialogAnimatedIn(true /* startFingerprintNow */);

        // Face succeeds
        session.onAuthenticationSucceeded(1, true, null);
        verify(mStatusBarService).onBiometricAuthenticated(TYPE_FACE);
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            if (sensor.modality == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                assertEquals(BiometricSensor.STATE_AUTHENTICATING, sensor.getSensorState());
            }
        }

        // SFPS succeeds
        session.onAuthenticationSucceeded(0, true, null);
        verify(mStatusBarService).onBiometricAuthenticated(TYPE_FINGERPRINT);
    }

    @Test
    public void onDialogDismissedResetLockout_Confirmed() throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_POWER_BUTTON);
        setupFace(1 /* id */, false, mock(IBiometricAuthenticator.class));
        final long operationId = 123;
        final int userId = 10;
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                operationId,
                userId);
        session.goToInitialState();
        session.onDialogAnimatedIn(true /* startFingerprintNow */);

        // Face succeeds
        session.onAuthenticationSucceeded(1, true, new byte[1]);

        // Dismiss through confirmation
        session.onDialogDismissed(DISMISSED_REASON_BIOMETRIC_CONFIRMED, null);

        verify(mBiometricManager).resetLockoutTimeBound(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    public void onDialogDismissedResetLockout_Cancelled() throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_POWER_BUTTON);
        setupFace(1 /* id */, false, mock(IBiometricAuthenticator.class));
        final long operationId = 123;
        final int userId = 10;
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                operationId,
                userId);
        session.goToInitialState();
        session.onDialogAnimatedIn(true /* startFingerprintNow */);

        // Face succeeds
        session.onAuthenticationSucceeded(1, true, new byte[1]);

        // User cancel after success
        session.onDialogDismissed(DISMISSED_REASON_USER_CANCEL, null);

        verify(mBiometricManager).resetLockoutTimeBound(any(), any(), anyInt(), anyInt(), any());
    }

    // TODO (b/208484275) : Enable these tests
    // @Test
    // public void testPreAuth_canAuthAndPrivacyDisabled() throws Exception {
    //     SensorPrivacyManager manager = ExtendedMockito.mock(SensorPrivacyManager.class);
    //     when(manager
    //             .isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.CAMERA, anyInt()))
    //             .thenReturn(false);
    //     when(mContext.getSystemService(SensorPrivacyManager.class))
    //             .thenReturn(manager);
    //     setupFace(1 /* id */, false /* confirmationAlwaysRequired */,
    //             mock(IBiometricAuthenticator.class));
    //     final PromptInfo promptInfo = createPromptInfo(Authenticators.BIOMETRIC_STRONG);
    //     final PreAuthInfo preAuthInfo = createPreAuthInfo(mSensors, 0, promptInfo, false);
    //     assertEquals(BiometricManager.BIOMETRIC_SUCCESS, preAuthInfo.getCanAuthenticateResult());
    //     for (BiometricSensor sensor : preAuthInfo.eligibleSensors) {
    //         assertEquals(BiometricSensor.STATE_UNKNOWN, sensor.getSensorState());
    //     }
    // }

    // @Test
    // public void testPreAuth_cannotAuthAndPrivacyEnabled() throws Exception {
    //     SensorPrivacyManager manager = ExtendedMockito.mock(SensorPrivacyManager.class);
    //     when(manager
    //             .isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.CAMERA, anyInt()))
    //             .thenReturn(true);
    //     when(mContext.getSystemService(SensorPrivacyManager.class))
    //             .thenReturn(manager);
    //     setupFace(1 /* id */, false /* confirmationAlwaysRequired */,
    //             mock(IBiometricAuthenticator.class));
    //     final PromptInfo promptInfo = createPromptInfo(Authenticators.BIOMETRIC_STRONG);
    //     final PreAuthInfo preAuthInfo = createPreAuthInfo(mSensors, 0, promptInfo, false);
    //     assertEquals(BiometricManager.BIOMETRIC_ERROR_SENSOR_PRIVACY_ENABLED,
    //             preAuthInfo.getCanAuthenticateResult());
    //     // Even though canAuth returns privacy enabled, we should still be able to authenticate.
    //     for (BiometricSensor sensor : preAuthInfo.eligibleSensors) {
    //         assertEquals(BiometricSensor.STATE_UNKNOWN, sensor.getSensorState());
    //     }
    // }

    // @Test
    // public void testPreAuth_canAuthAndPrivacyEnabledCredentialEnabled() throws Exception {
    //     SensorPrivacyManager manager = ExtendedMockito.mock(SensorPrivacyManager.class);
    //     when(manager
    //             .isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.CAMERA, anyInt()))
    //             .thenReturn(true);
    //     when(mContext.getSystemService(SensorPrivacyManager.class))
    //             .thenReturn(manager);
    //     setupFace(1 /* id */, false /* confirmationAlwaysRequired */,
    //             mock(IBiometricAuthenticator.class));
    //     final PromptInfo promptInfo =
    //             createPromptInfo(Authenticators.BIOMETRIC_STRONG
    //             | Authenticators. DEVICE_CREDENTIAL);
    //     final PreAuthInfo preAuthInfo = createPreAuthInfo(mSensors, 0, promptInfo, false);
    //     assertEquals(BiometricManager.BIOMETRIC_SUCCESS, preAuthInfo.getCanAuthenticateResult());
    //     for (BiometricSensor sensor : preAuthInfo.eligibleSensors) {
    //         assertEquals(BiometricSensor.STATE_UNKNOWN, sensor.getSensorState());
    //     }
    // }

    private void testInvokesCancel(Consumer<AuthSession> sessionConsumer) throws RemoteException {
        final IBiometricAuthenticator faceAuthenticator = mock(IBiometricAuthenticator.class);

        setupFace(0 /* id */, false /* confirmationAlwaysRequired */, faceAuthenticator);
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                TEST_REQUEST_ID,
                0 /* operationId */,
                0 /* userId */);

        session.goToInitialState();
        assertEquals(STATE_AUTH_CALLED, session.getState());

        sessionConsumer.accept(session);

        verify(faceAuthenticator).cancelAuthenticationFromService(
                eq(mToken), eq(TEST_PACKAGE), eq(TEST_REQUEST_ID));
    }

    private PreAuthInfo createPreAuthInfo(List<BiometricSensor> sensors, int userId,
            PromptInfo promptInfo, boolean checkDevicePolicyManager) throws RemoteException {
        return PreAuthInfo.create(mTrustManager,
                mDevicePolicyManager,
                mSettingObserver,
                sensors,
                userId,
                promptInfo,
                TEST_PACKAGE,
                checkDevicePolicyManager,
                mContext,
                mBiometricCameraManager);
    }

    private AuthSession createAuthSession(List<BiometricSensor> sensors,
            boolean checkDevicePolicyManager, @Authenticators.Types int authenticators,
            long requestId, long operationId, int userId) throws RemoteException {

        final PromptInfo promptInfo = createPromptInfo(authenticators);

        final PreAuthInfo preAuthInfo = createPreAuthInfo(sensors, userId, promptInfo,
                checkDevicePolicyManager);
        return new AuthSession(mContext, mBiometricContext, mStatusBarService, mSysuiReceiver,
                mKeyStoreAuthorization, mRandom, mClientDeathReceiver, preAuthInfo, mToken,
                requestId, operationId, userId, mSensorReceiver, mClientReceiver, TEST_PACKAGE,
                promptInfo, false /* debugEnabled */, mFingerprintSensorProps,
                mBiometricFrameworkStatsLogger);
    }

    private PromptInfo createPromptInfo(@Authenticators.Types int authenticators) {
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setAuthenticators(authenticators);
        return promptInfo;
    }

    private void setupFingerprint(int id, @FingerprintSensorProperties.SensorType int type)
            throws RemoteException {
        IBiometricAuthenticator fingerprintAuthenticator = mock(IBiometricAuthenticator.class);
        when(fingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
        when(fingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        mSensors.add(new BiometricSensor(mContext, id,
                TYPE_FINGERPRINT /* modality */,
                Authenticators.BIOMETRIC_STRONG /* strength */,
                fingerprintAuthenticator) {
            @Override
            boolean confirmationAlwaysRequired(int userId) {
                return false; // no-op / unsupported
            }

            @Override
            boolean confirmationSupported() {
                return false; // fingerprint does not support confirmation
            }
        });

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));

        mFingerprintSensorProps.add(new FingerprintSensorPropertiesInternal(id,
                SensorProperties.STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */,
                componentInfo,
                type,
                false /* resetLockoutRequiresHardwareAuthToken */));

        when(mSettingObserver.getEnabledForApps(anyInt())).thenReturn(true);
    }

    private void setupFace(int id, boolean confirmationAlwaysRequired,
            IBiometricAuthenticator authenticator) throws RemoteException {
        when(authenticator.isHardwareDetected(any())).thenReturn(true);
        when(authenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        mSensors.add(new BiometricSensor(mContext, id,
                TYPE_FACE /* modality */,
                Authenticators.BIOMETRIC_STRONG /* strength */,
                authenticator) {
            @Override
            boolean confirmationAlwaysRequired(int userId) {
                return confirmationAlwaysRequired;
            }

            @Override
            boolean confirmationSupported() {
                return true;
            }
        });

        when(mSettingObserver.getEnabledForApps(anyInt())).thenReturn(true);
    }
}
