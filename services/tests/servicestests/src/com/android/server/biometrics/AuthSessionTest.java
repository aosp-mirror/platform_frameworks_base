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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

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

    @Mock private ITrustManager mTrustManager;
    @Mock private DevicePolicyManager mDevicePolicyManager;
    @Mock private BiometricService.SettingObserver mSettingObserver;
    @Mock private IBiometricServiceReceiverInternal mInternalReceiver;
    @Mock private IBiometricServiceReceiver mClientReceiver;

    private Random mRandom;
    private IBinder mToken;

    // Assume all tests can be done with the same set of sensors for now.
    private List<BiometricSensor> mSensors;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mRandom = new Random();
        mToken = new Binder();

        mSensors = new ArrayList<>();

        IBiometricAuthenticator fingerprintAuthenticator = mock(IBiometricAuthenticator.class);
        when(fingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
        when(fingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        mSensors.add(new BiometricSensor(0 /* id */,
                TYPE_FINGERPRINT /* modality */,
                Authenticators.BIOMETRIC_STRONG /* strength */,
                fingerprintAuthenticator));

        IBiometricAuthenticator  faceAuthenticator = mock(IBiometricAuthenticator.class);
        when(faceAuthenticator.isHardwareDetected(any())).thenReturn(true);
        when(faceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        mSensors.add(new BiometricSensor(1 /* id */,
                TYPE_FACE /* modality */,
                Authenticators.BIOMETRIC_STRONG /* strength */,
                faceAuthenticator));

        when(mSettingObserver.getFaceEnabledForApps(anyInt())).thenReturn(true);
    }

    @Test
    public void testNewAuthSession_eligibleSensorsSetToStateUnknown() throws RemoteException {
        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                0 /* operationId */,
                0 /* userId */,
                false /* requireConfirmation */,
                0 /* callingUid */,
                0 /* callingPid */,
                0 /* callingUserId */);

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_UNKNOWN, sensor.getSensorState());
        }
    }

    @Test
    public void testStartNewAuthSession()
            throws RemoteException {
        final boolean requireConfirmation = true;
        final long operationId = 123;
        final int userId = 10;
        final int callingUid = 100;
        final int callingPid = 1000;
        final int callingUserId = 10000;

        final AuthSession session = createAuthSession(mSensors,
                false /* checkDevicePolicyManager */,
                Authenticators.BIOMETRIC_STRONG,
                operationId,
                userId,
                requireConfirmation,
                callingUid,
                callingPid,
                callingUserId);
        assertEquals(mSensors.size(), session.mPreAuthInfo.eligibleSensors.size());

        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_UNKNOWN, sensor.getSensorState());
            assertEquals(0, sensor.getCookie());
        }

        session.prepareAllSensorsForAuthentication();
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_WAITING_FOR_COOKIE, sensor.getSensorState());
            assertTrue("Cookie must be >0", sensor.getCookie() > 0);
            verify(sensor.impl).prepareForAuthentication(
                    eq(requireConfirmation),
                    eq(mToken),
                    eq(operationId),
                    eq(userId),
                    eq(mInternalReceiver),
                    eq(TEST_PACKAGE),
                    eq(sensor.getCookie()),
                    eq(callingUid),
                    eq(callingPid),
                    eq(callingUserId));
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
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            assertEquals(BiometricSensor.STATE_COOKIE_RETURNED, sensor.getSensorState());
        }
        assertTrue(session.allCookiesReceived());

        session.startAllPreparedSensors();
        for (BiometricSensor sensor : session.mPreAuthInfo.eligibleSensors) {
            verify(sensor.impl).startPreparedClient(eq(sensor.getCookie()));
            assertEquals(BiometricSensor.STATE_AUTHENTICATING, sensor.getSensorState());
        }
    }

    private PreAuthInfo createPreAuthInfo(List<BiometricSensor> sensors, int userId, Bundle bundle,
            boolean checkDevicePolicyManager) throws RemoteException {
        return PreAuthInfo.create(mTrustManager,
                mDevicePolicyManager,
                mSettingObserver,
                sensors,
                userId,
                bundle,
                TEST_PACKAGE,
                checkDevicePolicyManager);
    }

    private AuthSession createAuthSession(List<BiometricSensor> sensors,
            boolean checkDevicePolicyManager, @Authenticators.Types int authenticators,
            long operationId, int userId, boolean requireConfirmation,
            int callingUid, int callingPid, int callingUserId)
            throws RemoteException {

        final Bundle bundle = createBiometricPromptBundle(authenticators);

        final PreAuthInfo preAuthInfo = createPreAuthInfo(sensors, userId, bundle,
                checkDevicePolicyManager);

        return new AuthSession(mRandom, preAuthInfo, mToken, operationId, userId, mInternalReceiver,
                mClientReceiver, TEST_PACKAGE, bundle, callingUid,
                callingPid, callingUserId, requireConfirmation);
    }

    private Bundle createBiometricPromptBundle(@Authenticators.Types int authenticators) {
        Bundle bundle = new Bundle();
        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        return bundle;
    }

}
