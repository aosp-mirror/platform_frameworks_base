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

package com.android.server.biometrics.sensors.face;

import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.hardware.biometrics.SensorProperties.STRENGTH_STRONG;
import static android.hardware.face.FaceSensorProperties.TYPE_UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.IBiometricService;
import android.hardware.face.FaceAuthenticateOptions;
import android.hardware.face.FaceSensorConfigurations;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.face.IFaceServiceReceiver;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.face.aidl.FaceProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Presubmit
@SmallTest
public class FaceServiceTest {
    private static final int ID_DEFAULT = 2;
    private static final int ID_VIRTUAL = 6;
    private static final String NAME_DEFAULT = "default";
    private static final String NAME_VIRTUAL = "virtual";
    private static final String OP_PACKAGE_NAME = "FaceServiceTest/SystemUi";

    @Rule
    public final MockitoRule mMockito = MockitoJUnit.rule();
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();
    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public final FakeSettingsProviderRule mSettingsRule = FakeSettingsProvider.rule();
    @Mock
    private FaceProvider mFaceProviderDefault;
    @Mock
    private FaceProvider mFaceProviderVirtual;
    @Mock
    private IBiometricService mIBiometricService;
    @Mock
    private IBinder mToken;
    @Mock
    private IFaceServiceReceiver mFaceServiceReceiver;

    private FaceService mFaceService;
    private final FaceSensorPropertiesInternal mSensorPropsDefault =
            new FaceSensorPropertiesInternal(ID_DEFAULT, STRENGTH_STRONG,
                    2 /* maxEnrollmentsPerUser */,
                    List.of(),
                    TYPE_UNKNOWN,
                    true /* supportsFaceDetection */,
                    true /* supportsSelfIllumination */,
                    false /* resetLockoutRequiresChallenge */);
    private final FaceSensorPropertiesInternal mSensorPropsVirtual =
            new FaceSensorPropertiesInternal(ID_VIRTUAL, STRENGTH_STRONG,
                    2 /* maxEnrollmentsPerUser */,
                    List.of(),
                    TYPE_UNKNOWN,
                    true /* supportsFaceDetection */,
                    true /* supportsSelfIllumination */,
                    false /* resetLockoutRequiresChallenge */);
    private FaceSensorConfigurations mFaceSensorConfigurations;

    @Before
    public void setUp() throws RemoteException {
        when(mFaceProviderDefault.getSensorProperties()).thenReturn(List.of(mSensorPropsDefault));
        when(mFaceProviderVirtual.getSensorProperties()).thenReturn(List.of(mSensorPropsVirtual));
        when(mFaceProviderDefault.containsSensor(anyInt()))
                .thenAnswer(i -> i.getArguments()[0].equals(ID_DEFAULT));
        when(mFaceProviderVirtual.containsSensor(anyInt()))
                .thenAnswer(i -> i.getArguments()[0].equals(ID_VIRTUAL));

        mContext.getTestablePermissions().setPermission(
                USE_BIOMETRIC_INTERNAL, PackageManager.PERMISSION_GRANTED);
        mFaceSensorConfigurations = new FaceSensorConfigurations(false);
        mFaceSensorConfigurations.addAidlConfigs(new String[]{NAME_DEFAULT, NAME_VIRTUAL});
    }

    private void initService() {
        mFaceService = new FaceService(mContext,
                (filteredSensorProps, resetLockoutRequiresChallenge) -> {
                    if (NAME_DEFAULT.equals(filteredSensorProps.first)) return mFaceProviderDefault;
                    if (NAME_VIRTUAL.equals(filteredSensorProps.first)) return mFaceProviderVirtual;
                    return null;
                }, () -> mIBiometricService,
                (name) -> {
                    if (NAME_DEFAULT.equals(name)) return mFaceProviderDefault;
                    if (NAME_VIRTUAL.equals(name)) return mFaceProviderVirtual;
                    return null;
                },
                () -> new String[]{NAME_DEFAULT, NAME_VIRTUAL});
    }

    @Test
    public void registerAuthenticators_defaultOnly() throws Exception {
        initService();

        mFaceService.mServiceWrapper.registerAuthenticators(mFaceSensorConfigurations);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_DEFAULT),
                eq(BiometricAuthenticator.TYPE_FACE),
                eq(Utils.propertyStrengthToAuthenticatorStrength(STRENGTH_STRONG)),
                any());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FACE_VHAL_FEATURE)
    public void registerAuthenticatorsLegacy_virtualOnly() throws Exception {
        initService();
        Settings.Secure.putInt(mSettingsRule.mockContentResolver(mContext),
                Settings.Secure.BIOMETRIC_VIRTUAL_ENABLED, 1);

        mFaceService.mServiceWrapper.registerAuthenticators(mFaceSensorConfigurations);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_VIRTUAL),
                eq(BiometricAuthenticator.TYPE_FACE),
                eq(Utils.propertyStrengthToAuthenticatorStrength(STRENGTH_STRONG)), any());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FACE_VHAL_FEATURE)
    public void registerAuthenticators_virtualFaceOnly() throws Exception {
        initService();
        Settings.Secure.putInt(mSettingsRule.mockContentResolver(mContext),
                Settings.Secure.BIOMETRIC_FACE_VIRTUAL_ENABLED, 1);

        mFaceService.mServiceWrapper.registerAuthenticators(mFaceSensorConfigurations);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_VIRTUAL),
                eq(BiometricAuthenticator.TYPE_FACE),
                eq(Utils.propertyStrengthToAuthenticatorStrength(STRENGTH_STRONG)), any());
    }

    @Test
    public void registerAuthenticators_virtualAlwaysWhenNoOther() throws Exception {
        mFaceSensorConfigurations = new FaceSensorConfigurations(false);
        mFaceSensorConfigurations.addAidlConfigs(new String[]{NAME_VIRTUAL});
        initService();

        mFaceService.mServiceWrapper.registerAuthenticators(mFaceSensorConfigurations);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_VIRTUAL),
                eq(BiometricAuthenticator.TYPE_FACE),
                eq(Utils.propertyStrengthToAuthenticatorStrength(STRENGTH_STRONG)), any());
    }

    @Test
    public void testOptionsForAuthentication() throws Exception {
        FaceAuthenticateOptions faceAuthenticateOptions = new FaceAuthenticateOptions.Builder()
                .build();
        initService();
        mFaceService.mServiceWrapper.registerAuthenticators(mFaceSensorConfigurations);
        waitForRegistration();

        final long operationId = 5;
        mFaceService.mServiceWrapper.authenticate(mToken, operationId, mFaceServiceReceiver,
                faceAuthenticateOptions);

        assertThat(faceAuthenticateOptions.getSensorId()).isEqualTo(ID_DEFAULT);
    }

    @Test
    public void testOptionsForDetect() throws Exception {
        FaceAuthenticateOptions faceAuthenticateOptions = new FaceAuthenticateOptions.Builder()
                .setOpPackageName(ComponentName.unflattenFromString(OP_PACKAGE_NAME)
                        .getPackageName())
                .build();
        mContext.getOrCreateTestableResources().addOverride(
                R.string.config_keyguardComponent,
                OP_PACKAGE_NAME);
        initService();
        mFaceService.mServiceWrapper.registerAuthenticators(mFaceSensorConfigurations);
        waitForRegistration();
        mFaceService.mServiceWrapper.detectFace(mToken, mFaceServiceReceiver,
                faceAuthenticateOptions);

        assertThat(faceAuthenticateOptions.getSensorId()).isEqualTo(ID_DEFAULT);
    }

    private void waitForRegistration() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mFaceService.mServiceWrapper.addAuthenticatorsRegisteredCallback(
                new IFaceAuthenticatorsRegisteredCallback.Stub() {
                    public void onAllAuthenticatorsRegistered(
                            List<FaceSensorPropertiesInternal> sensors) {
                        latch.countDown();
                    }
                });
        latch.await(10, TimeUnit.SECONDS);
    }
}
