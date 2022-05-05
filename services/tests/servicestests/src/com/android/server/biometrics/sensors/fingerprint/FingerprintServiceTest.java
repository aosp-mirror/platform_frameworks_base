/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint;

import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.common.CommonProps;
import android.hardware.biometrics.common.SensorStrength;
import android.hardware.biometrics.fingerprint.FingerprintSensorType;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.SensorLocation;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.biometrics.log.BiometricContext;

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
public class FingerprintServiceTest {

    private static final int ID_DEFAULT = 2;
    private static final int ID_VIRTUAL = 6;
    private static final String NAME_DEFAULT = "default";
    private static final String NAME_VIRTUAL = "virtual";

    @Rule
    public final MockitoRule mMockito = MockitoJUnit.rule();
    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public final FakeSettingsProviderRule mSettingsRule = FakeSettingsProvider.rule();

    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private IBiometricService mIBiometricService;
    @Mock
    private IFingerprint mIFingerprintDefault;
    @Mock
    private IFingerprint mIFingerprintVirtual;

    private final SensorProps mSensorPropsDefault = createProps(ID_DEFAULT,
            SensorStrength.STRONG, FingerprintSensorType.POWER_BUTTON);
    private final SensorProps mSensorPropsVirtual = createProps(ID_VIRTUAL,
            SensorStrength.STRONG, FingerprintSensorType.UNDER_DISPLAY_OPTICAL);
    private FingerprintService mService;

    @Before
    public void setup() throws Exception {
        when(mIFingerprintDefault.getSensorProps()).thenReturn(
                new SensorProps[]{mSensorPropsDefault});
        when(mIFingerprintVirtual.getSensorProps()).thenReturn(
                new SensorProps[]{mSensorPropsVirtual});

        mContext.getTestablePermissions().setPermission(
                USE_BIOMETRIC_INTERNAL, PackageManager.PERMISSION_GRANTED);
    }

    private void initServiceWith(String... aidlInstances) {
        mService = new FingerprintService(mContext, mBiometricContext,
                () -> mIBiometricService,
                () -> aidlInstances,
                (fqName) -> {
                    if (fqName.endsWith(NAME_DEFAULT)) return mIFingerprintDefault;
                    if (fqName.endsWith(NAME_VIRTUAL)) return mIFingerprintVirtual;
                    return null;
                });
    }

    @Test
    public void registerAuthenticators_defaultOnly() throws Exception {
        initServiceWith(NAME_DEFAULT, NAME_VIRTUAL);

        mService.mServiceWrapper.registerAuthenticators(List.of());
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_DEFAULT), anyInt(), anyInt(), any());
    }

    @Test
    public void registerAuthenticators_virtualOnly() throws Exception {
        initServiceWith(NAME_DEFAULT, NAME_VIRTUAL);
        Settings.Secure.putInt(mSettingsRule.mockContentResolver(mContext),
                Settings.Secure.BIOMETRIC_VIRTUAL_ENABLED, 1);

        mService.mServiceWrapper.registerAuthenticators(List.of());
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_VIRTUAL), anyInt(), anyInt(), any());
    }

    @Test
    public void registerAuthenticators_virtualAlwaysWhenNoOther() throws Exception {
        initServiceWith(NAME_VIRTUAL);

        mService.mServiceWrapper.registerAuthenticators(List.of());
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_VIRTUAL), anyInt(), anyInt(), any());
    }

    private void waitForRegistration() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mService.mServiceWrapper.addAuthenticatorsRegisteredCallback(
                new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                    @Override
                    public void onAllAuthenticatorsRegistered(
                            List<FingerprintSensorPropertiesInternal> sensors) {
                        latch.countDown();
                    }
                });
        latch.await(5, TimeUnit.SECONDS);
    }

    private static SensorProps createProps(int id, byte strength, byte type) {
        final SensorProps props = new SensorProps();
        props.commonProps = new CommonProps();
        props.commonProps.sensorId = id;
        props.commonProps.sensorStrength = strength;
        props.sensorType = type;
        props.sensorLocations = new SensorLocation[]{new SensorLocation()};
        return props;
    }
}
