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

import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.app.AppOpsManager.OP_USE_BIOMETRIC;
import static android.app.AppOpsManager.OP_USE_FINGERPRINT;
import static android.hardware.biometrics.SensorProperties.STRENGTH_STRONG;
import static android.hardware.fingerprint.FingerprintManager.SENSOR_ID_ANY;
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_REAR;
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_OPTICAL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.pm.PackageManager;
import android.hardware.biometrics.IBiometricService;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
    private static final List<FingerprintSensorPropertiesInternal> HIDL_AUTHENTICATORS =
            List.of();

    @Rule
    public final MockitoRule mMockito = MockitoJUnit.rule();
    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public final FakeSettingsProviderRule mSettingsRule = FakeSettingsProvider.rule();

    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private IBiometricService mIBiometricService;
    @Mock
    private FingerprintProvider mFingerprintDefault;
    @Mock
    private FingerprintProvider mFingerprintVirtual;
    @Mock
    private IFingerprintServiceReceiver mServiceReceiver;
    @Mock
    private IBinder mToken;

    @Captor
    private ArgumentCaptor<FingerprintAuthenticateOptions> mAuthenticateOptionsCaptor;

    private final FingerprintSensorPropertiesInternal mSensorPropsDefault =
            new FingerprintSensorPropertiesInternal(ID_DEFAULT, STRENGTH_STRONG,
                    2 /* maxEnrollmentsPerUser */,
                    List.of() /* componentInfo */,
                    TYPE_REAR,
                    false /* resetLockoutRequiresHardwareAuthToken */);
    private final FingerprintSensorPropertiesInternal mSensorPropsVirtual =
            new FingerprintSensorPropertiesInternal(ID_VIRTUAL, STRENGTH_STRONG,
                    2 /* maxEnrollmentsPerUser */,
                    List.of() /* componentInfo */,
                    TYPE_UDFPS_OPTICAL,
                    false /* resetLockoutRequiresHardwareAuthToken */);
    @Captor
    private ArgumentCaptor<FingerprintSensorPropertiesInternal> mPropsCaptor;
    private FingerprintService mService;

    @Before
    public void setup() throws Exception {
        when(mFingerprintDefault.getSensorProperties()).thenReturn(List.of(mSensorPropsDefault));
        when(mFingerprintVirtual.getSensorProperties()).thenReturn(List.of(mSensorPropsVirtual));
        when(mFingerprintDefault.containsSensor(anyInt()))
                .thenAnswer(i -> i.getArguments()[0].equals(ID_DEFAULT));
        when(mFingerprintVirtual.containsSensor(anyInt()))
                .thenAnswer(i -> i.getArguments()[0].equals(ID_VIRTUAL));

        mContext.addMockSystemService(AppOpsManager.class, mAppOpsManager);
        for (int permission : List.of(OP_USE_BIOMETRIC, OP_USE_FINGERPRINT)) {
            when(mAppOpsManager.noteOp(eq(permission), anyInt(), any(), any(), any()))
                    .thenReturn(AppOpsManager.MODE_ALLOWED);
        }

        for (String permission : List.of(USE_BIOMETRIC, USE_BIOMETRIC_INTERNAL)) {
            mContext.getTestablePermissions().setPermission(
                    permission, PackageManager.PERMISSION_GRANTED);
        }
    }

    private void initServiceWith(String... aidlInstances) {
        mService = new FingerprintService(mContext, mBiometricContext,
                () -> mIBiometricService,
                () -> aidlInstances,
                (name) -> {
                    if (NAME_DEFAULT.equals(name)) return mFingerprintDefault;
                    if (NAME_VIRTUAL.equals(name)) return mFingerprintVirtual;
                    return null;
                });
    }

    private void initServiceWithAndWait(String... aidlInstances) throws Exception {
        initServiceWith(aidlInstances);
        mService.mServiceWrapper.registerAuthenticators(HIDL_AUTHENTICATORS);
        waitForRegistration();
    }

    @Test
    public void registerAuthenticators_defaultOnly() throws Exception {
        initServiceWith(NAME_DEFAULT, NAME_VIRTUAL);

        mService.mServiceWrapper.registerAuthenticators(HIDL_AUTHENTICATORS);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(anyInt(), mPropsCaptor.capture(), any());
        assertThat(mPropsCaptor.getAllValues()).containsExactly(mSensorPropsDefault);
    }

    @Test
    public void registerAuthenticators_virtualOnly() throws Exception {
        initServiceWith(NAME_DEFAULT, NAME_VIRTUAL);
        Settings.Secure.putInt(mSettingsRule.mockContentResolver(mContext),
                Settings.Secure.BIOMETRIC_VIRTUAL_ENABLED, 1);

        mService.mServiceWrapper.registerAuthenticators(HIDL_AUTHENTICATORS);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(anyInt(), mPropsCaptor.capture(), any());
        assertThat(mPropsCaptor.getAllValues()).containsExactly(mSensorPropsVirtual);
    }

    @Test
    public void registerAuthenticators_virtualAlwaysWhenNoOther() throws Exception {
        initServiceWith(NAME_VIRTUAL);

        mService.mServiceWrapper.registerAuthenticators(HIDL_AUTHENTICATORS);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(anyInt(), mPropsCaptor.capture(), any());
        assertThat(mPropsCaptor.getAllValues()).containsExactly(mSensorPropsVirtual);
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

    @Test
    public void authenticateWithDefaultSensorId() throws Exception {
        initServiceWithAndWait(NAME_DEFAULT, NAME_VIRTUAL);

        final long operationId = 2;
        mService.mServiceWrapper.authenticate(mToken, operationId, mServiceReceiver,
                new FingerprintAuthenticateOptions.Builder()
                        .setSensorId(SENSOR_ID_ANY)
                        .build());

        final FingerprintAuthenticateOptions options =
                verifyAuthenticateWithNewRequestId(mFingerprintDefault, operationId);
        assertThat(options.getSensorId()).isEqualTo(ID_DEFAULT);
        verifyNoAuthenticate(mFingerprintVirtual);
    }


    private FingerprintAuthenticateOptions verifyAuthenticateWithNewRequestId(
            FingerprintProvider provider, long operationId) {
        return verifyAuthenticateWithNewRequestId(
                provider, operationId, true /* shouldSchedule */);
    }

    private void verifyNoAuthenticate(FingerprintProvider provider) {
        verifyAuthenticateWithNewRequestId(
                provider, 0 /* operationId */, false /* shouldSchedule */);
    }

    private FingerprintAuthenticateOptions verifyAuthenticateWithNewRequestId(
            FingerprintProvider provider, long operationId, boolean shouldSchedule) {
        verify(provider, shouldSchedule ? times(1) : never())
                .scheduleAuthenticate(eq(mToken), eq(operationId), anyInt(), any(),
                        mAuthenticateOptionsCaptor.capture(), anyBoolean(), anyInt(),
                        anyBoolean());
        verify(provider, never()).scheduleAuthenticate(eq(mToken), anyLong(),
                anyInt(), any(), mAuthenticateOptionsCaptor.capture(), anyLong(),
                anyBoolean(), anyInt(), anyBoolean());

        if (shouldSchedule) {
            return mAuthenticateOptionsCaptor.getValue();
        }
        return null;
    }
}
