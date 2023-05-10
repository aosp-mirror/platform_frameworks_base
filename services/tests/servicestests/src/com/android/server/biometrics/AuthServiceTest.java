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

import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.TEST_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_NONE;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_CANCELED;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_SUCCESS;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.iris.IIrisService;
import android.os.Binder;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Stubber;

import java.util.List;

@Presubmit
@SmallTest
public class AuthServiceTest {

    private static final String TEST_OP_PACKAGE_NAME = "test_package";

    private AuthService mAuthService;

    @Rule
    public MockitoRule mockitorule = MockitoJUnit.rule();

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    IBiometricServiceReceiver mReceiver;
    @Mock
    AuthService.Injector mInjector;
    @Mock
    IBiometricService mBiometricService;
    @Mock
    IFingerprintService mFingerprintService;
    @Mock
    IIrisService mIrisService;
    @Mock
    IFaceService mFaceService;
    @Mock
    AppOpsManager mAppOpsManager;
    @Captor
    private ArgumentCaptor<List<FingerprintSensorPropertiesInternal>> mFingerprintPropsCaptor;
    @Captor
    private ArgumentCaptor<List<FaceSensorPropertiesInternal>> mFacePropsCaptor;

    @Before
    public void setUp() {
        // Placeholder test config
        final String[] config = {
                "0:2:15", // ID0:Fingerprint:Strong
                "1:4:15", // ID1:Iris:Strong
                "2:8:15", // ID2:Face:Strong
        };

        when(mResources.getIntArray(eq(R.array.config_udfps_sensor_props))).thenReturn(new int[0]);
        when(mResources.getBoolean(eq(R.bool.config_is_powerbutton_fps))).thenReturn(false);
        when(mResources.getInteger(eq(R.integer.config_fingerprintMaxTemplatesPerUser))).thenReturn(
                1);
        when(mResources.getBoolean(eq(R.bool.config_faceAuthSupportsSelfIllumination))).thenReturn(
                false);
        when(mResources.getInteger(eq(R.integer.config_faceMaxTemplatesPerUser))).thenReturn(1);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getResources()).thenReturn(mResources);
        when(mInjector.getBiometricService()).thenReturn(mBiometricService);
        when(mInjector.getConfiguration(any())).thenReturn(config);
        when(mInjector.getFingerprintService()).thenReturn(mFingerprintService);
        when(mInjector.getFaceService()).thenReturn(mFaceService);
        when(mInjector.getIrisService()).thenReturn(mIrisService);
        when(mInjector.getAppOps(any())).thenReturn(mAppOpsManager);
        when(mInjector.isHidlDisabled(any())).thenReturn(false);

        setInternalAndTestBiometricPermissions(mContext, false /* hasPermission */);
    }

    @Test
    public void testRegisterNullService_doesNotRegister() throws Exception {
        setInternalAndTestBiometricPermissions(mContext, true /* hasPermission */);

        // Config contains Fingerprint, Iris, Face, but services are all null

        when(mInjector.getFingerprintService()).thenReturn(null);
        when(mInjector.getFaceService()).thenReturn(null);
        when(mInjector.getIrisService()).thenReturn(null);

        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        verify(mBiometricService, never()).registerAuthenticator(
                anyInt(),
                any(),
                any());
    }

    @Test
    public void testRegisterAuthenticator_registerAuthenticators() throws Exception {
        final int fingerprintId = 0;
        final int fingerprintStrength = 15;

        final int faceId = 1;
        final int faceStrength = 4095;

        final String[] config = {
                // ID0:Fingerprint:Strong
                String.format("%d:2:%d", fingerprintId, fingerprintStrength),
                // ID2:Face:Convenience
                String.format("%d:8:%d", faceId, faceStrength)
        };

        when(mInjector.getConfiguration(any())).thenReturn(config);

        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        verify(mFingerprintService).registerAuthenticators(mFingerprintPropsCaptor.capture());
        final FingerprintSensorPropertiesInternal fingerprintProp =
                mFingerprintPropsCaptor.getValue().get(0);
        assertEquals(fingerprintProp.sensorId, fingerprintId);
        assertEquals(fingerprintProp.sensorStrength,
                Utils.authenticatorStrengthToPropertyStrength(fingerprintStrength));

        verify(mFaceService).registerAuthenticators(mFacePropsCaptor.capture());
        final FaceSensorPropertiesInternal faceProp = mFacePropsCaptor.getValue().get(0);
        assertEquals(faceProp.sensorId, faceId);
        assertEquals(faceProp.sensorStrength,
                Utils.authenticatorStrengthToPropertyStrength(faceStrength));
    }


    // TODO(b/141025588): Check that an exception is thrown when the userId != callingUserId
    @Test
    public void testAuthenticate_appOpsOk_callsBiometricServiceAuthenticate() throws Exception {
        when(mAppOpsManager.noteOp(eq(AppOpsManager.OP_USE_BIOMETRIC), anyInt(), any(), any(),
                any())).thenReturn(AppOpsManager.MODE_ALLOWED);
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final Binder token = new Binder();
        final PromptInfo promptInfo = new PromptInfo();
        final long sessionId = 0;
        final int userId = 0;

        mAuthService.mImpl.authenticate(
                token,
                sessionId,
                userId,
                mReceiver,
                TEST_OP_PACKAGE_NAME,
                promptInfo);
        waitForIdle();
        verify(mBiometricService).authenticate(
                eq(token),
                eq(sessionId),
                eq(userId),
                eq(mReceiver),
                eq(TEST_OP_PACKAGE_NAME),
                eq(promptInfo));
    }

    @Test
    public void testAuthenticate_appOpsDenied_doesNotCallBiometricService() throws Exception {
        when(mAppOpsManager.noteOp(eq(AppOpsManager.OP_USE_BIOMETRIC), anyInt(), any(), any(),
                any())).thenReturn(AppOpsManager.MODE_ERRORED);
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final Binder token = new Binder();
        final PromptInfo promptInfo = new PromptInfo();
        final long sessionId = 0;
        final int userId = 0;

        mAuthService.mImpl.authenticate(
                token,
                sessionId,
                userId,
                mReceiver,
                TEST_OP_PACKAGE_NAME,
                promptInfo);
        waitForIdle();
        verify(mBiometricService, never()).authenticate(
                eq(token),
                eq(sessionId),
                eq(userId),
                eq(mReceiver),
                eq(TEST_OP_PACKAGE_NAME),
                eq(promptInfo));
        verify(mReceiver).onError(eq(TYPE_NONE), eq(BIOMETRIC_ERROR_CANCELED), anyInt());
    }

    @Test
    public void testAuthenticate_missingRequiredParam() throws Exception {
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final PromptInfo promptInfo = new PromptInfo();
        final long sessionId = 0;
        final int userId = 0;

        mAuthService.mImpl.authenticate(
                null /* token */,
                sessionId,
                userId,
                mReceiver,
                TEST_OP_PACKAGE_NAME,
                promptInfo);
        waitForIdle();
        verify(mReceiver).onError(eq(TYPE_NONE), eq(BIOMETRIC_ERROR_CANCELED), anyInt());
    }

    @Test
    public void testAuthenticate_throwsWhenUsingTestConfigurations() {
        final PromptInfo promptInfo = mock(PromptInfo.class);
        when(promptInfo.containsPrivateApiConfigurations()).thenReturn(false);
        when(promptInfo.containsTestConfigurations()).thenReturn(true);

        testAuthenticate_throwsWhenUsingTestConfigurations(promptInfo);
    }

    @Test
    public void testAuthenticate_throwsWhenUsingPrivateApis() {
        final PromptInfo promptInfo = mock(PromptInfo.class);
        when(promptInfo.containsPrivateApiConfigurations()).thenReturn(true);
        when(promptInfo.containsTestConfigurations()).thenReturn(false);

        testAuthenticate_throwsWhenUsingTestConfigurations(promptInfo);
    }

    private void testAuthenticate_throwsWhenUsingTestConfigurations(PromptInfo promptInfo) {
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        assertThrows(SecurityException.class, () -> {
            mAuthService.mImpl.authenticate(
                    null /* token */,
                    10 /* sessionId */,
                    2 /* userId */,
                    mReceiver,
                    TEST_OP_PACKAGE_NAME,
                    promptInfo);
            waitForIdle();
        });
    }

    @Test
    public void testCanAuthenticate_callsBiometricServiceCanAuthenticate() throws Exception {
        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final int userId = 0;
        final int expectedResult = BIOMETRIC_SUCCESS;
        final int authenticators = 0;
        when(mBiometricService.canAuthenticate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(expectedResult);

        final int result = mAuthService.mImpl
                .canAuthenticate(TEST_OP_PACKAGE_NAME, userId, authenticators);

        assertEquals(expectedResult, result);
        waitForIdle();
        verify(mBiometricService).canAuthenticate(
                eq(TEST_OP_PACKAGE_NAME),
                eq(userId),
                eq(UserHandle.getCallingUserId()),
                eq(authenticators));
    }

    @Test
    public void testHasEnrolledBiometrics_callsBiometricServiceHasEnrolledBiometrics()
            throws Exception {
        setInternalAndTestBiometricPermissions(mContext, true /* hasPermission */);

        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final int userId = 0;
        final boolean expectedResult = true;
        when(mBiometricService.hasEnrolledBiometrics(anyInt(), anyString())).thenReturn(
                expectedResult);

        final boolean result = mAuthService.mImpl.hasEnrolledBiometrics(userId,
                TEST_OP_PACKAGE_NAME);

        assertEquals(expectedResult, result);
        waitForIdle();
        verify(mBiometricService).hasEnrolledBiometrics(
                eq(userId),
                eq(TEST_OP_PACKAGE_NAME));
    }


    @Test
    public void testRegisterKeyguardCallback_callsBiometricServiceRegisterKeyguardCallback()
            throws Exception {
        setInternalAndTestBiometricPermissions(mContext, true /* hasPermission */);

        mAuthService = new AuthService(mContext, mInjector);
        mAuthService.onStart();

        final IBiometricEnabledOnKeyguardCallback callback =
                new IBiometricEnabledOnKeyguardCallback.Default();

        mAuthService.mImpl.registerEnabledOnKeyguardCallback(callback);

        waitForIdle();
        verify(mBiometricService).registerEnabledOnKeyguardCallback(
                eq(callback));
    }

    private static void setInternalAndTestBiometricPermissions(
            Context context, boolean hasPermission) {
        for (String p : List.of(TEST_BIOMETRIC, MANAGE_BIOMETRIC, USE_BIOMETRIC_INTERNAL)) {
            when(context.checkCallingPermission(eq(p))).thenReturn(hasPermission
                    ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
            when(context.checkCallingOrSelfPermission(eq(p))).thenReturn(hasPermission
                    ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
            final Stubber doPermCheck =
                    hasPermission ? doNothing() : doThrow(SecurityException.class);
            doPermCheck.when(context).enforceCallingPermission(eq(p), any());
            doPermCheck.when(context).enforceCallingOrSelfPermission(eq(p), any());
        }
    }

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
