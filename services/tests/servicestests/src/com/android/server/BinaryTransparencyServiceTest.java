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

package com.android.server;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorProperties;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.util.FrameworkStatsLog;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class BinaryTransparencyServiceTest {
    private static final String TAG = "BinaryTransparencyServiceTest";

    private Context mContext;
    private BinaryTransparencyService mBinaryTransparencyService;
    private BinaryTransparencyService.BinaryTransparencyServiceImpl mTestInterface;
    private DeviceConfig.Properties mOriginalBiometricsFlags;

    @Mock
    private BinaryTransparencyService.BiometricLogger mBiometricLogger;
    @Mock
    private FingerprintManager mFpManager;
    @Mock
    private FaceManager mFaceManager;
    @Mock
    private PackageManager mPackageManager;

    @Captor
    private ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback>
            mFpAuthenticatorsRegisteredCaptor;
    @Captor
    private ArgumentCaptor<IFaceAuthenticatorsRegisteredCallback>
            mFaceAuthenticatorsRegisteredCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = spy(ApplicationProvider.getApplicationContext());
        mBinaryTransparencyService = new BinaryTransparencyService(mContext, mBiometricLogger);
        mTestInterface = mBinaryTransparencyService.new BinaryTransparencyServiceImpl();
        mOriginalBiometricsFlags = DeviceConfig.getProperties(DeviceConfig.NAMESPACE_BIOMETRICS);
    }

    @After
    public void tearDown() throws Exception {
        try {
            DeviceConfig.setProperties(mOriginalBiometricsFlags);
        } catch (DeviceConfig.BadConfigException e) {
            Log.e(TAG, "Failed to reset biometrics flags to the original values before test. "
                    + e);
        }
    }

    private void prepSignedInfo() {
        // simulate what happens on boot completed phase
        // but we avoid calling JobScheduler.schedule by returning a null.
        doReturn(null).when(mContext).getSystemService(JobScheduler.class);
        mBinaryTransparencyService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
    }

    private void prepApexInfo() throws RemoteException {
        // simulates what happens to apex info after computations are done.
        String[] args = {"get", "apex_info"};
        mTestInterface.onShellCommand(FileDescriptor.in, FileDescriptor.out, FileDescriptor.err,
                args, null, new ResultReceiver(null));
    }

    private void prepBiometricsTesting() {
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)).thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true);
        when(mContext.getSystemService(FingerprintManager.class)).thenReturn(mFpManager);
        when(mContext.getSystemService(FaceManager.class)).thenReturn(mFaceManager);
    }

    @Test
    public void getSignedImageInfo_preInitialize_returnsUninitializedString() {
        String result = mTestInterface.getSignedImageInfo();
        Assert.assertNotNull("VBMeta digest value should not be null", result);
        Assert.assertEquals(BinaryTransparencyService.VBMETA_DIGEST_UNINITIALIZED, result);
    }

    @Test
    public void getSignedImageInfo_postInitialize_returnsNonErrorStrings() {
        prepSignedInfo();
        String result = mTestInterface.getSignedImageInfo();
        Assert.assertNotNull("Initialized VBMeta digest string should not be null", result);
        Assert.assertNotEquals("VBMeta digest value is uninitialized",
                BinaryTransparencyService.VBMETA_DIGEST_UNINITIALIZED, result);
        Assert.assertNotEquals("VBMeta value should not be unavailable",
                BinaryTransparencyService.VBMETA_DIGEST_UNAVAILABLE, result);
    }

    @Test
    public void getSignedImageInfo_postInitialize_returnsCorrectValue() {
        prepSignedInfo();
        String result = mTestInterface.getSignedImageInfo();
        Assert.assertEquals(
                SystemProperties.get(BinaryTransparencyService.SYSPROP_NAME_VBETA_DIGEST,
                        BinaryTransparencyService.VBMETA_DIGEST_UNAVAILABLE), result);
    }

    @Test
    public void getApexInfo_postInitialize_returnsValidEntries() throws RemoteException {
        prepApexInfo();
        List result = mTestInterface.getApexInfo();
        Assert.assertNotNull("Apex info map should not be null", result);
        Assert.assertFalse("Apex info map should not be empty", result.isEmpty());
    }

    @Test
    public void getApexInfo_postInitialize_returnsActualApexs()
            throws RemoteException, PackageManager.NameNotFoundException {
        prepApexInfo();
        List resultList = mTestInterface.getApexInfo();

        PackageManager pm = mContext.getPackageManager();
        Assert.assertNotNull(pm);
        List<Bundle> castedResult = (List<Bundle>) resultList;
        for (Bundle resultBundle : castedResult) {
            PackageInfo resultPackageInfo = resultBundle.getParcelable(
                    BinaryTransparencyService.BUNDLE_PACKAGE_INFO, PackageInfo.class);
            Assert.assertNotNull("PackageInfo for APEX should not be null",
                    resultPackageInfo);
            Assert.assertTrue(resultPackageInfo.packageName + "is not an APEX!",
                    resultPackageInfo.isApex);
        }
    }

    @Test
    public void testCollectBiometricProperties_disablesFeature() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_BIOMETRICS,
                BinaryTransparencyService.KEY_ENABLE_BIOMETRIC_PROPERTY_VERIFICATION,
                Boolean.FALSE.toString(),
                false /* makeDefault */);

        mBinaryTransparencyService.collectBiometricProperties();

        verify(mBiometricLogger, never()).logStats(anyInt(), anyInt(), anyInt(), anyInt(),
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void testCollectBiometricProperties_enablesFeature_logsFingerprintProperties()
            throws RemoteException {
        prepBiometricsTesting();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_BIOMETRICS,
                BinaryTransparencyService.KEY_ENABLE_BIOMETRIC_PROPERTY_VERIFICATION,
                Boolean.TRUE.toString(),
                false /* makeDefault */);
        final List<FingerprintSensorPropertiesInternal> props = List.of(
                new FingerprintSensorPropertiesInternal(
                        1 /* sensorId */,
                        SensorProperties.STRENGTH_STRONG,
                        5 /* maxEnrollmentsPerUser */,
                        List.of(new ComponentInfoInternal("sensor" /* componentId */,
                                "vendor/model/revision" /* hardwareVersion */,
                                "1.01" /* firmwareVersion */, "00000001" /* serialNumber */,
                                "" /* softwareVersion */)),
                        FingerprintSensorProperties.TYPE_REAR,
                        true /* resetLockoutRequiresHardwareAuthToken */));

        mBinaryTransparencyService.collectBiometricProperties();

        verify(mFpManager).addAuthenticatorsRegisteredCallback(mFpAuthenticatorsRegisteredCaptor
                .capture());
        mFpAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(props);

        verify(mBiometricLogger, times(1)).logStats(
                eq(1) /* sensorId */,
                eq(FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__MODALITY__MODALITY_FINGERPRINT),
                eq(FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_FP_REAR),
                eq(FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_STRENGTH__STRENGTH_STRONG),
                eq("sensor") /* componentId */,
                eq("vendor/model/revision") /* hardwareVersion */,
                eq("1.01") /* firmwareVersion */,
                eq("00000001") /* serialNumber */,
                eq("") /* softwareVersion */
        );
    }

    @Test
    public void testCollectBiometricProperties_enablesFeature_logsFaceProperties()
            throws RemoteException {
        prepBiometricsTesting();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_BIOMETRICS,
                BinaryTransparencyService.KEY_ENABLE_BIOMETRIC_PROPERTY_VERIFICATION,
                Boolean.TRUE.toString(),
                false /* makeDefault */);
        final List<FaceSensorPropertiesInternal> props = List.of(
                new FaceSensorPropertiesInternal(
                        1 /* sensorId */,
                        SensorProperties.STRENGTH_CONVENIENCE,
                        1 /* maxEnrollmentsPerUser */,
                        List.of(new ComponentInfoInternal("sensor" /* componentId */,
                                "vendor/model/revision" /* hardwareVersion */,
                                "1.01" /* firmwareVersion */, "00000001" /* serialNumber */,
                                "" /* softwareVersion */)),
                        FaceSensorProperties.TYPE_RGB,
                        true /* supportsFaceDetection */,
                        true /* supportsSelfIllumination */,
                        true /* resetLockoutRequiresHardwareAuthToken */));

        mBinaryTransparencyService.collectBiometricProperties();

        verify(mFaceManager).addAuthenticatorsRegisteredCallback(mFaceAuthenticatorsRegisteredCaptor
                .capture());
        mFaceAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(props);

        verify(mBiometricLogger, times(1)).logStats(
                eq(1) /* sensorId */,
                eq(FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__MODALITY__MODALITY_FACE),
                eq(FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_FACE_RGB),
                eq(FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_STRENGTH__STRENGTH_CONVENIENCE),
                eq("sensor") /* componentId */,
                eq("vendor/model/revision") /* hardwareVersion */,
                eq("1.01") /* firmwareVersion */,
                eq("00000001") /* serialNumber */,
                eq("") /* softwareVersion */
        );
    }
}
