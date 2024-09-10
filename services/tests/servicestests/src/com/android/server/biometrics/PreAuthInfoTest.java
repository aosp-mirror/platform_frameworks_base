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
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
import static android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NOT_ENABLED_FOR_APPS;

import static com.android.server.biometrics.sensors.LockoutTracker.LOCKOUT_NONE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustManager;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.Flags;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.PromptInfo;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

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
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final int SENSOR_ID_FINGERPRINT = 0;
    private static final int SENSOR_ID_FACE = 1;
    private static final String TEST_PACKAGE_NAME = "PreAuthInfoTestPackage";

    @Mock
    IBiometricAuthenticator mFaceAuthenticator;
    @Mock
    IBiometricAuthenticator mFingerprintAuthenticator;
    @Mock
    Context mContext;
    @Mock
    Resources mResources;
    @Mock
    ITrustManager mTrustManager;
    @Mock
    DevicePolicyManager mDevicePolicyManager;
    @Mock
    BiometricService.SettingObserver mSettingObserver;
    @Mock
    BiometricCameraManager mBiometricCameraManager;

    @Before
    public void setup() throws RemoteException {
        when(mTrustManager.isDeviceSecure(anyInt(), anyInt())).thenReturn(true);
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(any(), anyInt()))
                .thenReturn(KEYGUARD_DISABLE_FEATURES_NONE);
        when(mSettingObserver.getEnabledForApps(anyInt())).thenReturn(true);
        when(mSettingObserver.getMandatoryBiometricsEnabledAndRequirementsSatisfiedForUser(
                anyInt())).thenReturn(true);
        when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(true);
        when(mFaceAuthenticator.isHardwareDetected(any())).thenReturn(true);
        when(mFaceAuthenticator.getLockoutModeForUser(anyInt()))
                .thenReturn(LOCKOUT_NONE);
        when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any()))
                .thenReturn(true);
        when(mFingerprintAuthenticator.isHardwareDetected(any())).thenReturn(true);
        when(mFingerprintAuthenticator.getLockoutModeForUser(anyInt()))
                .thenReturn(LOCKOUT_NONE);
        when(mBiometricCameraManager.isCameraPrivacyEnabled()).thenReturn(false);
        when(mBiometricCameraManager.isAnyCameraUnavailable()).thenReturn(false);
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getString(anyInt())).thenReturn(TEST_PACKAGE_NAME);
    }

    @Test
    public void testFaceAuthentication_whenCameraPrivacyIsEnabled() throws Exception {
        when(mBiometricCameraManager.isCameraPrivacyEnabled()).thenReturn(true);

        BiometricSensor sensor = getFaceSensor();
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setConfirmationRequested(false /* requireConfirmation */);
        promptInfo.setAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        promptInfo.setDisallowBiometricsIfPolicyExists(false /* checkDevicePolicy */);
        PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(sensor),
                0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.eligibleSensors).isEmpty();
    }

    @Test
    public void testFaceAuthentication_whenCameraPrivacyIsDisabledAndCameraIsAvailable()
            throws Exception {
        BiometricSensor sensor = getFaceSensor();
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setConfirmationRequested(false /* requireConfirmation */);
        promptInfo.setAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        promptInfo.setDisallowBiometricsIfPolicyExists(false /* checkDevicePolicy */);
        PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(sensor),
                0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.eligibleSensors).hasSize(1);
    }

    @Test
    public void testFaceAuthentication_whenCameraIsUnavailable() throws RemoteException {
        when(mBiometricCameraManager.isAnyCameraUnavailable()).thenReturn(true);

        BiometricSensor sensor = getFaceSensor();
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setConfirmationRequested(false /* requireConfirmation */);
        promptInfo.setAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        promptInfo.setDisallowBiometricsIfPolicyExists(false /* checkDevicePolicy */);
        PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(sensor),
                0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.eligibleSensors).hasSize(0);
    }

    @Test
    public void testCanAuthenticateResult_whenCameraUnavailableAndNoFingerprintsEnrolled()
            throws RemoteException {
        when(mBiometricCameraManager.isAnyCameraUnavailable()).thenReturn(true);
        when(mFingerprintAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(false);

        BiometricSensor faceSensor = getFaceSensor();
        BiometricSensor fingerprintSensor = getFingerprintSensor();
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setConfirmationRequested(false /* requireConfirmation */);
        promptInfo.setAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        promptInfo.setDisallowBiometricsIfPolicyExists(false /* checkDevicePolicy */);
        PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(faceSensor, fingerprintSensor),
                0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.eligibleSensors).hasSize(0);
        assertThat(preAuthInfo.getCanAuthenticateResult()).isEqualTo(
                BIOMETRIC_ERROR_HW_UNAVAILABLE);
    }

    @Test
    public void testFingerprintAuthentication_whenCameraIsUnavailable() throws RemoteException {
        when(mBiometricCameraManager.isAnyCameraUnavailable()).thenReturn(true);

        BiometricSensor faceSensor = getFaceSensor();
        BiometricSensor fingerprintSensor = getFingerprintSensor();
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setConfirmationRequested(false /* requireConfirmation */);
        promptInfo.setAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        promptInfo.setDisallowBiometricsIfPolicyExists(false /* checkDevicePolicy */);
        PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(faceSensor, fingerprintSensor),
                0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.eligibleSensors).hasSize(1);
        assertThat(preAuthInfo.eligibleSensors.get(0).modality).isEqualTo(TYPE_FINGERPRINT);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_MANDATORY_BIOMETRICS)
    public void testMandatoryBiometricsStatus_whenAllRequirementsSatisfiedAndSensorAvailable()
            throws Exception {
        when(mTrustManager.isInSignificantPlace()).thenReturn(false);

        final BiometricSensor sensor = getFaceSensor();
        final PromptInfo promptInfo = new PromptInfo();
        promptInfo.setAuthenticators(BiometricManager.Authenticators.MANDATORY_BIOMETRICS);
        final PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(sensor), 0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.eligibleSensors).hasSize(1);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_MANDATORY_BIOMETRICS)
    public void testMandatoryBiometricsStatus_whenAllRequirementsSatisfiedAndSensorUnavailable()
            throws Exception {
        when(mTrustManager.isInSignificantPlace()).thenReturn(false);

        final PromptInfo promptInfo = new PromptInfo();
        promptInfo.setAuthenticators(BiometricManager.Authenticators.MANDATORY_BIOMETRICS);
        final PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(), 0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.eligibleSensors).hasSize(0);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_MANDATORY_BIOMETRICS)
    public void testMandatoryBiometricsAndStrongBiometricsStatus_whenRequirementsNotSatisfied()
            throws Exception {
        when(mTrustManager.isInSignificantPlace()).thenReturn(true);

        final BiometricSensor sensor = getFaceSensor();
        final PromptInfo promptInfo = new PromptInfo();
        promptInfo.setAuthenticators(BiometricManager.Authenticators.MANDATORY_BIOMETRICS
                | BiometricManager.Authenticators.BIOMETRIC_STRONG);
        final PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(sensor), 0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.eligibleSensors).hasSize(1);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_MANDATORY_BIOMETRICS)
    public void testMandatoryBiometricsStatus_whenRequirementsNotSatisfiedAndSensorAvailable()
            throws Exception {
        when(mTrustManager.isInSignificantPlace()).thenReturn(true);

        final BiometricSensor sensor = getFaceSensor();
        final PromptInfo promptInfo = new PromptInfo();
        promptInfo.setAuthenticators(BiometricManager.Authenticators.MANDATORY_BIOMETRICS);
        final PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(sensor), 0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.getCanAuthenticateResult()).isEqualTo(
                BiometricManager.BIOMETRIC_ERROR_MANDATORY_NOT_ACTIVE);
        assertThat(preAuthInfo.eligibleSensors).hasSize(0);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_MANDATORY_BIOMETRICS)
    public void testCalculateByPriority()
            throws Exception {
        when(mFaceAuthenticator.hasEnrolledTemplates(anyInt(), any())).thenReturn(false);
        when(mSettingObserver.getEnabledForApps(anyInt())).thenReturn(false);

        BiometricSensor faceSensor = getFaceSensor();
        BiometricSensor fingerprintSensor = getFingerprintSensor();
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setConfirmationRequested(false /* requireConfirmation */);
        promptInfo.setAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        promptInfo.setDisallowBiometricsIfPolicyExists(false /* checkDevicePolicy */);
        PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(faceSensor, fingerprintSensor),
                0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);

        assertThat(preAuthInfo.eligibleSensors).hasSize(0);
        assertThat(preAuthInfo.getCanAuthenticateResult()).isEqualTo(
                BIOMETRIC_ERROR_NOT_ENABLED_FOR_APPS);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_MANDATORY_BIOMETRICS)
    public void testMandatoryBiometricsNegativeButtonText_whenSet()
            throws Exception {
        when(mTrustManager.isInSignificantPlace()).thenReturn(false);

        final BiometricSensor sensor = getFaceSensor();
        final PromptInfo promptInfo = new PromptInfo();
        promptInfo.setAuthenticators(BiometricManager.Authenticators.MANDATORY_BIOMETRICS);
        promptInfo.setNegativeButtonText(TEST_PACKAGE_NAME);
        final PreAuthInfo preAuthInfo = PreAuthInfo.create(mTrustManager, mDevicePolicyManager,
                mSettingObserver, List.of(sensor), 0 /* userId */, promptInfo, TEST_PACKAGE_NAME,
                false /* checkDevicePolicyManager */, mContext, mBiometricCameraManager);
        assertThat(promptInfo.getNegativeButtonText()).isEqualTo(TEST_PACKAGE_NAME);
    }

    private BiometricSensor getFingerprintSensor() {
        BiometricSensor sensor = new BiometricSensor(mContext, SENSOR_ID_FINGERPRINT,
                TYPE_FINGERPRINT, BiometricManager.Authenticators.BIOMETRIC_STRONG,
                mFingerprintAuthenticator) {
            @Override
            boolean confirmationAlwaysRequired(int userId) {
                return false;
            }

            @Override
            boolean confirmationSupported() {
                return false;
            }
        };

        return sensor;
    }

    private BiometricSensor getFaceSensor() {
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

        return sensor;
    }
}
