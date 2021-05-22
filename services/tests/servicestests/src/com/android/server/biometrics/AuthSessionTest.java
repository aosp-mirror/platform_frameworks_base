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

import static com.android.server.biometrics.BiometricServiceStateProto.*;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.content.Context;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorProperties;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.security.KeyStore;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Presubmit
@SmallTest
public class AuthSessionTest {

    private static final String TEST_PACKAGE = "test_package";

    @Mock private Context mContext;
    @Mock private ITrustManager mTrustManager;
    @Mock private DevicePolicyManager mDevicePolicyManager;
    @Mock private BiometricService.SettingObserver mSettingObserver;
    @Mock private IBiometricSensorReceiver mSensorReceiver;
    @Mock private IBiometricServiceReceiver mClientReceiver;
    @Mock private IStatusBarService mStatusBarService;
    @Mock private IBiometricSysuiReceiver mSysuiReceiver;
    @Mock private KeyStore mKeyStore;
    @Mock private AuthSession.ClientDeathReceiver mClientDeathReceiver;

    private Random mRandom;
    private IBinder mToken;

    // Assume all tests can be done with the same set of sensors for now.
    @NonNull private List<BiometricSensor> mSensors;
    @NonNull private List<FingerprintSensorPropertiesInternal> mFingerprintSensorProps;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mClientReceiver.asBinder()).thenReturn(mock(Binder.class));
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
                    eq(sensor.getCookie()),
                    anyBoolean() /* allowBackgroundAuthentication */);
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
    public void testMultiAuth_singleSensor_fingerprintSensorStartsAfterDialogAnimationCompletes()
            throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
        testMultiAuth_fingerprintSensorStartsAfter(false /* fingerprintStartsAfterDelay */);
    }

    @Test
    public void testMultiAuth_fingerprintSensorStartsAfterDialogAnimationCompletes()
            throws Exception {
        setupFingerprint(0 /* id */, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
        setupFace(1 /* id */, false, mock(IBiometricAuthenticator.class));
        testMultiAuth_fingerprintSensorStartsAfter(true /* fingerprintStartsAfterDelay */);
    }

    public void testMultiAuth_fingerprintSensorStartsAfter(boolean fingerprintStartsAfterDelay)
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
                anyBoolean(), anyBoolean(), anyInt(), any(), anyLong(), anyInt());

        // Notify AuthSession that the UI is shown. Then, fingerprint sensor should be started.
        session.onDialogAnimatedIn();
        if (fingerprintStartsAfterDelay) {
            assertEquals(STATE_AUTH_STARTED_UI_SHOWING, session.getState());
            assertEquals(BiometricSensor.STATE_COOKIE_RETURNED,
                    session.mPreAuthInfo.eligibleSensors.get(fingerprintSensorId).getSensorState());
            session.onErrorReceived(fingerprintSensorId,
                    session.mPreAuthInfo.eligibleSensors.get(fingerprintSensorId).getCookie(),
                    BiometricConstants.BIOMETRIC_ERROR_VENDOR, 0 /* vendorCode */);
            session.onStartFingerprint();
        }
        assertEquals(STATE_AUTH_STARTED_UI_SHOWING, session.getState());
        assertEquals(BiometricSensor.STATE_AUTHENTICATING,
                session.mPreAuthInfo.eligibleSensors.get(fingerprintSensorId).getSensorState());
    }

    @Test
    public void testCancelAuthentication_whenStateAuthCalled_invokesCancel()
            throws RemoteException {
        final IBiometricAuthenticator faceAuthenticator = mock(IBiometricAuthenticator.class);

        setupFace(0 /* id */, false /* confirmationAlwaysRequired */, faceAuthenticator);
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                0 /* operationId */,
                0 /* userId */);

        session.goToInitialState();
        assertEquals(STATE_AUTH_CALLED, session.getState());
        session.onCancelAuthSession(false /* force */);

        verify(faceAuthenticator).cancelAuthenticationFromService(eq(mToken), eq(TEST_PACKAGE));
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
                checkDevicePolicyManager);
    }

    private AuthSession createAuthSession(List<BiometricSensor> sensors,
            boolean checkDevicePolicyManager, @Authenticators.Types int authenticators,
            long operationId, int userId) throws RemoteException {

        final PromptInfo promptInfo = createPromptInfo(authenticators);

        final PreAuthInfo preAuthInfo = createPreAuthInfo(sensors, userId, promptInfo,
                checkDevicePolicyManager);
        return new AuthSession(mContext, mStatusBarService, mSysuiReceiver, mKeyStore,
                mRandom, mClientDeathReceiver, preAuthInfo, mToken, operationId, userId,
                mSensorReceiver, mClientReceiver, TEST_PACKAGE, promptInfo,
                false /* debugEnabled */, mFingerprintSensorProps);
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
