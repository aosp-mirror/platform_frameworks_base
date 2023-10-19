/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;

import static com.android.server.biometrics.sensors.LockoutTracker.LOCKOUT_NONE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.PromptInfo;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

@Presubmit
@SmallTest
public class PreAuthInfoTest {
    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    private static final int SENSOR_ID_FACE = 1;
    private static final String TEST_PACKAGE_NAME = "PreAuthInfoTestPackage";

    @Mock
    IBiometricAuthenticator mFaceAuthenticator;
    @Mock
    Context mContext;
    @Mock
    ITrustManager mTrustManager;
    @Mock
    DevicePolicyManager mDevicePolicyManager;
    @Mock
    BiometricService.SettingObserver mSettingObserver;
    @Mock
    BiometricSensorPrivacy mBiometricSensorPrivacyUtil;

    @Before
    public void setup() throws RemoteException {
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt())).thenReturn(true);
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(any(), anyInt()))
                .thenReturn(KEYGUARD_DISABLE_FEATURES_NONE);
        when(mSettingObserver.getEnabledForApps(anyInt())).thenReturn(true);
        when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        when(mFaceAuthenticator.isHardwareDetected(any())).thenReturn(true);
        when(mFaceAuthenticator.getLockoutModeForUser(anyInt()))
                .thenReturn(LOCKOUT_NONE);
    }

    @Test
    public void testFaceAuthentication_whenCameraPrivacyIsEnabled() throws Exception {
        when(mBiometricSensorPrivacyUtil.isCameraPrivacyEnabled()).thenReturn(true);

        BiometricSensor sensor = new BiometricSensor(mContext, SENSOR_ID_FACE, TYPE_FACE,
                BiometricManager.Authenticators.BIOMETRIC_STRONG, mFaceAuthenticator) {
            @Override
            boolean confirmationAlwaysRequired(int userId) {
                return false;
            }

            @Override
            boolean confirmationSupported() {
                return false;
            }
        };
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setConfirmationRequested(false /* requireConfirmation */);
        promptInfo.setAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        promptInfo.setDisallowBiometricsIfPolicyExists(false /* checkDevicePolicy */);
        PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(sensor),
                0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricSensorPrivacyUtil);

        assertThat(preAuthInfo.eligibleSensors).isEmpty();
    }

    @Test
    public void testFaceAuthentication_whenCameraPrivacyIsDisabled() throws Exception {
        when(mBiometricSensorPrivacyUtil.isCameraPrivacyEnabled()).thenReturn(false);

        BiometricSensor sensor = new BiometricSensor(mContext, SENSOR_ID_FACE, TYPE_FACE,
                BiometricManager.Authenticators.BIOMETRIC_STRONG, mFaceAuthenticator) {
            @Override
            boolean confirmationAlwaysRequired(int userId) {
                return false;
            }

            @Override
            boolean confirmationSupported() {
                return false;
            }
        };
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setConfirmationRequested(false /* requireConfirmation */);
        promptInfo.setAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        promptInfo.setDisallowBiometricsIfPolicyExists(false /* checkDevicePolicy */);
        PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(sensor),
                0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricSensorPrivacyUtil);

        assertThat(preAuthInfo.eligibleSensors).hasSize(1);
    }
}
