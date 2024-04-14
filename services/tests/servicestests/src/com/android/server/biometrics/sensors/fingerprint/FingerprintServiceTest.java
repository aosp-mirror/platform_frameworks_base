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

import static junit.framework.Assert.assertEquals;

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
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.hardware.biometrics.IBiometricService;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintSensorConfigurations;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;

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
    private static final String OP_PACKAGE_NAME = "FingerprintServiceTest/SystemUi";

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
    @Mock
    private VirtualDeviceManagerInternal mVdmInternal;

    @Captor
    private ArgumentCaptor<FingerprintAuthenticateOptions> mAuthenticateOptionsCaptor;

    private final FingerprintSensorPropertiesInternal mSensorPropsDefault =
            new FingerprintSensorPropertiesInternal(ID_DEFAULT, STRENGTH_STRONG,
                    2 /* maxEnrollmentsPerUser */,
                    List.of(),
                    TYPE_REAR,
                    false /* resetLockoutRequiresHardwareAuthToken */);
    private final FingerprintSensorPropertiesInternal mSensorPropsVirtual =
            new FingerprintSensorPropertiesInternal(ID_VIRTUAL, STRENGTH_STRONG,
                    2 /* maxEnrollmentsPerUser */,
                    List.of(),
                    TYPE_UDFPS_OPTICAL,
                    false /* resetLockoutRequiresHardwareAuthToken */);
    private FingerprintSensorConfigurations mFingerprintSensorConfigurations;
    private FingerprintService mService;

    @Before
    public void setup() throws Exception {
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
        LocalServices.addService(VirtualDeviceManagerInternal.class, mVdmInternal);

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

        mFingerprintSensorConfigurations = new FingerprintSensorConfigurations(
                true /* resetLockoutRequiresHardwareAuthToken */);
        mFingerprintSensorConfigurations.addAidlSensors(new String[]{NAME_DEFAULT, NAME_VIRTUAL});
    }

    private void initServiceWith(String... aidlInstances) {
        mService = new FingerprintService(mContext, mBiometricContext,
                () -> mIBiometricService,
                () -> aidlInstances,
                (name) -> {
                    if (NAME_DEFAULT.equals(name)) return mFingerprintDefault;
                    if (NAME_VIRTUAL.equals(name)) return mFingerprintVirtual;
                    return null;
                }, (sensorPropsPair, resetLockoutRequiresHardwareAuthToken) -> {
                    if (NAME_DEFAULT.equals(sensorPropsPair.first)) return mFingerprintDefault;
                    if (NAME_VIRTUAL.equals(sensorPropsPair.first)) return mFingerprintVirtual;
                    return null;
                });
    }

    private void initServiceWithAndWait(String... aidlInstances) throws Exception {
        initServiceWith(aidlInstances);
        mService.mServiceWrapper.registerAuthenticators(mFingerprintSensorConfigurations);
        waitForRegistration();
    }

    @Test
    public void registerAuthenticators_defaultOnly() throws Exception {
        initServiceWith(NAME_DEFAULT, NAME_VIRTUAL);

        mService.mServiceWrapper.registerAuthenticators(mFingerprintSensorConfigurations);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_DEFAULT), anyInt(), anyInt(), any());
    }

    @Test
    public void registerAuthenticators_virtualOnly() throws Exception {
        initServiceWith(NAME_DEFAULT, NAME_VIRTUAL);
        Settings.Secure.putInt(mSettingsRule.mockContentResolver(mContext),
                Settings.Secure.BIOMETRIC_VIRTUAL_ENABLED, 1);

        mService.mServiceWrapper.registerAuthenticators(mFingerprintSensorConfigurations);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_VIRTUAL), anyInt(), anyInt(), any());
    }

    @Test
    public void registerAuthenticators_virtualFingerprintOnly() throws Exception {
        initServiceWith(NAME_DEFAULT, NAME_VIRTUAL);
        Settings.Secure.putInt(mSettingsRule.mockContentResolver(mContext),
                Settings.Secure.BIOMETRIC_FINGERPRINT_VIRTUAL_ENABLED, 1);

        mService.mServiceWrapper.registerAuthenticators(mFingerprintSensorConfigurations);
        waitForRegistration();

        verify(mIBiometricService).registerAuthenticator(eq(ID_VIRTUAL), anyInt(), anyInt(), any());
    }

    @Test
    public void registerAuthenticators_virtualAlwaysWhenNoOther() throws Exception {
        mFingerprintSensorConfigurations =
                new FingerprintSensorConfigurations(true);
        mFingerprintSensorConfigurations.addAidlSensors(new String[]{NAME_VIRTUAL});
        initServiceWith(NAME_VIRTUAL);

        mService.mServiceWrapper.registerAuthenticators(mFingerprintSensorConfigurations);
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

    @Test
    public void testAuthenticate_noVdmInternalService_noCrash() throws Exception {
        initServiceWithAndWait(NAME_DEFAULT, NAME_VIRTUAL);
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);

        final long operationId = 2;

        // This should not crash
        mService.mServiceWrapper.authenticate(mToken, operationId, mServiceReceiver,
                new FingerprintAuthenticateOptions.Builder()
                        .setSensorId(SENSOR_ID_ANY)
                        .build());
    }

    @Test
    public void testAuthenticate_callsVirtualDeviceManagerOnAuthenticationPrompt()
            throws Exception {
        initServiceWithAndWait(NAME_DEFAULT, NAME_VIRTUAL);

        final long operationId = 2;
        mService.mServiceWrapper.authenticate(mToken, operationId, mServiceReceiver,
                new FingerprintAuthenticateOptions.Builder()
                        .setSensorId(SENSOR_ID_ANY)
                        .build());

        ArgumentCaptor<Integer> uidCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mVdmInternal).onAuthenticationPrompt(uidCaptor.capture());
        assertEquals((int) (uidCaptor.getValue()), Binder.getCallingUid());
    }

    @Test
    public void testOptionsForDetect() throws Exception {
        FingerprintAuthenticateOptions fingerprintAuthenticateOptions =
                new FingerprintAuthenticateOptions.Builder()
                        .setOpPackageName(ComponentName.unflattenFromString(
                                OP_PACKAGE_NAME).getPackageName())
                        .build();

        mContext.getOrCreateTestableResources().addOverride(
                R.string.config_keyguardComponent,
                OP_PACKAGE_NAME);
        initServiceWithAndWait(NAME_DEFAULT);
        mService.mServiceWrapper.detectFingerprint(mToken, mServiceReceiver,
                fingerprintAuthenticateOptions);

        assertThat(fingerprintAuthenticateOptions.getSensorId()).isEqualTo(ID_DEFAULT);
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
