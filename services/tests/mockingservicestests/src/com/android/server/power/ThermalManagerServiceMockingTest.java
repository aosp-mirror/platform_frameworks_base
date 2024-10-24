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

package com.android.server.power;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.hardware.thermal.CoolingType;
import android.hardware.thermal.IThermal;
import android.hardware.thermal.IThermalChangedCallback;
import android.hardware.thermal.TemperatureThreshold;
import android.hardware.thermal.TemperatureType;
import android.hardware.thermal.ThrottlingSeverity;
import android.os.Binder;
import android.os.CoolingDevice;
import android.os.Flags;
import android.os.RemoteException;
import android.os.Temperature;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


public class ThermalManagerServiceMockingTest {
    @ClassRule
    public static final SetFlagsRule.ClassRule mSetFlagsClassRule = new SetFlagsRule.ClassRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = mSetFlagsClassRule.createSetFlagsRule();

    @Mock
    private IThermal mAidlHalMock;
    private Binder mAidlBinder = new Binder();
    private CompletableFuture<Temperature> mTemperatureFuture;
    private CompletableFuture<TemperatureThreshold> mThresholdFuture;
    private ThermalManagerService.ThermalHalWrapper.WrapperThermalChangedCallback
            mTemperatureCallback =
            new ThermalManagerService.ThermalHalWrapper.WrapperThermalChangedCallback() {
                @Override
                public void onTemperatureChanged(Temperature temperature) {
                    mTemperatureFuture.complete(temperature);
                }

                @Override
                public void onThresholdChanged(TemperatureThreshold threshold) {
                    mThresholdFuture.complete(threshold);
                }
            };
    private ThermalManagerService.ThermalHalAidlWrapper mAidlWrapper;
    @Captor
    ArgumentCaptor<IThermalChangedCallback> mAidlCallbackCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Mockito.when(mAidlHalMock.asBinder()).thenReturn(mAidlBinder);
        mAidlBinder.attachInterface(mAidlHalMock, IThermal.class.getName());
        mTemperatureFuture = new CompletableFuture<>();
        mThresholdFuture = new CompletableFuture<>();
        mAidlWrapper = new ThermalManagerService.ThermalHalAidlWrapper(mTemperatureCallback);
        mAidlWrapper.initProxyAndRegisterCallback(mAidlBinder);
    }

    @Test
    @EnableFlags({Flags.FLAG_ALLOW_THERMAL_THRESHOLDS_CALLBACK})
    public void setCallback_aidl() throws Exception {
        Mockito.verify(mAidlHalMock, Mockito.times(1)).registerThermalChangedCallback(
                mAidlCallbackCaptor.capture());
        android.hardware.thermal.Temperature halTemperature =
                new android.hardware.thermal.Temperature();
        halTemperature.type = TemperatureType.SOC;
        halTemperature.name = "test";
        halTemperature.throttlingStatus = ThrottlingSeverity.SHUTDOWN;
        halTemperature.value = 99.0f;

        android.hardware.thermal.TemperatureThreshold halThreshold =
                new android.hardware.thermal.TemperatureThreshold();
        halThreshold.type = TemperatureType.SKIN;
        halThreshold.name = "test";
        halThreshold.hotThrottlingThresholds = new float[ThrottlingSeverity.SHUTDOWN + 1];
        Arrays.fill(halThreshold.hotThrottlingThresholds, Float.NaN);
        halThreshold.hotThrottlingThresholds[ThrottlingSeverity.SEVERE] = 44.0f;

        mAidlCallbackCaptor.getValue().notifyThrottling(halTemperature);
        mAidlCallbackCaptor.getValue().notifyThresholdChanged(halThreshold);

        Temperature temperature = mTemperatureFuture.get(100, TimeUnit.MILLISECONDS);
        assertEquals(halTemperature.name, temperature.getName());
        assertEquals(halTemperature.type, temperature.getType());
        assertEquals(halTemperature.value, temperature.getValue(), 0.1f);
        assertEquals(halTemperature.throttlingStatus, temperature.getStatus());

        TemperatureThreshold threshold = mThresholdFuture.get(100, TimeUnit.MILLISECONDS);
        assertEquals(halThreshold.name, threshold.name);
        assertEquals(halThreshold.type, threshold.type);
        assertArrayEquals(halThreshold.hotThrottlingThresholds, threshold.hotThrottlingThresholds,
                0.01f);
    }

    @Test
    @DisableFlags({Flags.FLAG_ALLOW_THERMAL_THRESHOLDS_CALLBACK})
    public void setCallback_aidl_allow_thermal_thresholds_callback_false() throws Exception {
        Mockito.verify(mAidlHalMock, Mockito.times(1)).registerThermalChangedCallback(
                mAidlCallbackCaptor.capture());
        android.hardware.thermal.TemperatureThreshold halThreshold =
                new android.hardware.thermal.TemperatureThreshold();
        halThreshold.type = TemperatureType.SOC;
        halThreshold.name = "test";
        halThreshold.hotThrottlingThresholds = new float[ThrottlingSeverity.SHUTDOWN + 1];
        Arrays.fill(halThreshold.hotThrottlingThresholds, Float.NaN);
        halThreshold.hotThrottlingThresholds[ThrottlingSeverity.SEVERE] = 44.0f;

        mAidlCallbackCaptor.getValue().notifyThresholdChanged(halThreshold);
        Thread.sleep(1000);
        assertFalse(mThresholdFuture.isDone());
    }

    @Test
    public void setCallback_illegalState_aidl() throws Exception {
        Mockito.doThrow(new IllegalStateException()).when(
                mAidlHalMock).registerThermalChangedCallback(Mockito.any());
        verifyWrapperStatusOnCallbackError();
    }

    @Test
    public void setCallback_illegalArgument_aidl() throws Exception {
        Mockito.doThrow(new IllegalStateException()).when(
                mAidlHalMock).registerThermalChangedCallback(Mockito.any());
        verifyWrapperStatusOnCallbackError();
    }


    void verifyWrapperStatusOnCallbackError() throws RemoteException {
        android.hardware.thermal.Temperature halT1 = new android.hardware.thermal.Temperature();
        halT1.type = TemperatureType.MODEM;
        halT1.name = "test1";
        Mockito.when(mAidlHalMock.getTemperaturesWithType(Mockito.anyInt())).thenReturn(
                new android.hardware.thermal.Temperature[]{
                        halT1
                });
        List<Temperature> ret = mAidlWrapper.getCurrentTemperatures(true, TemperatureType.MODEM);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperaturesWithType(
                TemperatureType.MODEM);
        assertNotNull(ret);
        Temperature expectedT1 = new Temperature(halT1.value, halT1.type, halT1.name,
                halT1.throttlingStatus);
        List<Temperature> expectedRet = List.of(expectedT1);
        // test that even if the callback fails to register without hal connection error, the
        // wrapper should still work
        assertTrue("Got temperature list as " + ret + " with different values compared to "
                + expectedRet, expectedRet.containsAll(ret));
    }

    @Test
    public void getCurrentTemperatures_aidl() throws RemoteException {
        android.hardware.thermal.Temperature halT1 = new android.hardware.thermal.Temperature();
        halT1.type = TemperatureType.MODEM;
        halT1.name = "test1";
        halT1.throttlingStatus = ThrottlingSeverity.EMERGENCY;
        halT1.value = 99.0f;
        android.hardware.thermal.Temperature halT2 = new android.hardware.thermal.Temperature();
        halT2.name = "test2";
        halT2.type = TemperatureType.SOC;
        halT2.throttlingStatus = ThrottlingSeverity.NONE;

        Mockito.when(mAidlHalMock.getTemperatures()).thenReturn(
                new android.hardware.thermal.Temperature[]{
                        halT2, halT1
                });
        List<Temperature> ret = mAidlWrapper.getCurrentTemperatures(false, TemperatureType.UNKNOWN);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatures();

        Temperature expectedT1 = new Temperature(halT1.value, halT1.type, halT1.name,
                halT1.throttlingStatus);
        Temperature expectedT2 = new Temperature(halT2.value, halT2.type, halT2.name,
                halT2.throttlingStatus);
        List<Temperature> expectedRet = List.of(expectedT1, expectedT2);
        assertTrue("Got temperature list as " + ret + " with different values compared to "
                + expectedRet, expectedRet.containsAll(ret));
    }

    @Test
    public void getCurrentTemperatures_withFilter_aidl() throws RemoteException {
        android.hardware.thermal.Temperature halT1 = new android.hardware.thermal.Temperature();
        halT1.type = TemperatureType.MODEM;
        halT1.name = "test1";
        halT1.throttlingStatus = ThrottlingSeverity.EMERGENCY;
        halT1.value = 99.0f;
        android.hardware.thermal.Temperature halT2 = new android.hardware.thermal.Temperature();
        halT2.name = "test2";
        halT2.type = TemperatureType.MODEM;
        halT2.throttlingStatus = ThrottlingSeverity.NONE;

        android.hardware.thermal.Temperature halT3WithDiffType =
                new android.hardware.thermal.Temperature();
        halT3WithDiffType.type = TemperatureType.BCL_CURRENT;
        halT3WithDiffType.throttlingStatus = ThrottlingSeverity.CRITICAL;

        Mockito.when(mAidlHalMock.getTemperaturesWithType(Mockito.anyInt())).thenReturn(
                new android.hardware.thermal.Temperature[]{
                        halT2, halT1, halT3WithDiffType,
                });
        List<Temperature> ret = mAidlWrapper.getCurrentTemperatures(true, TemperatureType.MODEM);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperaturesWithType(
                TemperatureType.MODEM);

        Temperature expectedT1 = new Temperature(halT1.value, halT1.type, halT1.name,
                halT1.throttlingStatus);
        Temperature expectedT2 = new Temperature(halT2.value, halT2.type, halT2.name,
                halT2.throttlingStatus);
        List<Temperature> expectedRet = List.of(expectedT1, expectedT2);
        assertTrue("Got temperature list as " + ret + " with different values compared to "
                + expectedRet, expectedRet.containsAll(ret));
    }

    @Test
    public void getCurrentTemperatures_nullResult_aidl() throws RemoteException {
        Mockito.when(mAidlHalMock.getTemperaturesWithType(Mockito.anyInt())).thenReturn(null);
        List<Temperature> ret = mAidlWrapper.getCurrentTemperatures(true,
                Temperature.TYPE_SOC);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperaturesWithType(
                Temperature.TYPE_SOC);
        assertNotNull(ret);
        assertEquals(0, ret.size());

        Mockito.when(mAidlHalMock.getTemperatures()).thenReturn(null);
        ret = mAidlWrapper.getCurrentTemperatures(false, Temperature.TYPE_SOC);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatures();
        assertNotNull(ret);
        assertEquals(0, ret.size());
    }

    @Test
    public void getCurrentTemperatures_invalidStatus_aidl() throws RemoteException {
        android.hardware.thermal.Temperature halTInvalid =
                new android.hardware.thermal.Temperature();
        halTInvalid.name = "test";
        halTInvalid.type = TemperatureType.MODEM;
        halTInvalid.throttlingStatus = 99;

        Mockito.when(mAidlHalMock.getTemperatures()).thenReturn(
                new android.hardware.thermal.Temperature[]{
                        halTInvalid
                });
        List<Temperature> ret = mAidlWrapper.getCurrentTemperatures(false, 0);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatures();

        List<Temperature> expectedRet = List.of(
                new Temperature(halTInvalid.value, halTInvalid.type, halTInvalid.name,
                        ThrottlingSeverity.NONE));
        assertTrue("Got temperature list as " + ret + " with different values compared to "
                + expectedRet, expectedRet.containsAll(ret));
    }

    @Test
    public void getCurrentTemperatures_illegalArgument_aidl() throws RemoteException {
        Mockito.when(mAidlHalMock.getTemperatures()).thenThrow(new IllegalArgumentException());
        List<Temperature> ret = mAidlWrapper.getCurrentTemperatures(false, 0);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatures();
        assertNotNull(ret);
        assertEquals(0, ret.size());

        Mockito.when(mAidlHalMock.getTemperaturesWithType(TemperatureType.MODEM)).thenThrow(
                new IllegalArgumentException());
        ret = mAidlWrapper.getCurrentTemperatures(true, TemperatureType.MODEM);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperaturesWithType(
                TemperatureType.MODEM);
        assertNotNull(ret);
        assertEquals(0, ret.size());
    }

    @Test
    public void getCurrentTemperatures_illegalState_aidl() throws RemoteException {
        Mockito.when(mAidlHalMock.getTemperatures()).thenThrow(new IllegalStateException());
        List<Temperature> ret = mAidlWrapper.getCurrentTemperatures(false, 0);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatures();
        assertNotNull(ret);
        assertEquals(0, ret.size());

        Mockito.when(mAidlHalMock.getTemperaturesWithType(TemperatureType.MODEM)).thenThrow(
                new IllegalStateException());
        ret = mAidlWrapper.getCurrentTemperatures(true, TemperatureType.MODEM);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperaturesWithType(
                TemperatureType.MODEM);
        assertNotNull(ret);
        assertEquals(0, ret.size());
    }

    @Test
    public void getCurrentCoolingDevices_aidl() throws RemoteException {
        android.hardware.thermal.CoolingDevice halC1 = new android.hardware.thermal.CoolingDevice();
        halC1.type = CoolingType.SPEAKER;
        halC1.name = "test1";
        halC1.value = 10;
        android.hardware.thermal.CoolingDevice halC2 = new android.hardware.thermal.CoolingDevice();
        halC2.type = CoolingType.MODEM;
        halC2.name = "test2";
        halC2.value = 110;

        Mockito.when(mAidlHalMock.getCoolingDevicesWithType(Mockito.anyInt())).thenReturn(
                new android.hardware.thermal.CoolingDevice[]{
                        halC1, halC2
                }
        );
        Mockito.when(mAidlHalMock.getCoolingDevices()).thenReturn(
                new android.hardware.thermal.CoolingDevice[]{
                        halC1, halC2
                }
        );
        List<CoolingDevice> ret = mAidlWrapper.getCurrentCoolingDevices(false,
                CoolingType.COMPONENT);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getCoolingDevices();

        CoolingDevice expectedC1 = new CoolingDevice(halC1.value, halC1.type, halC1.name);
        CoolingDevice expectedC2 = new CoolingDevice(halC2.value, halC2.type, halC2.name);
        List<CoolingDevice> expectedRet = List.of(expectedC1, expectedC2);
        assertTrue("Got cooling device list as " + ret + " with different values compared to "
                + expectedRet, expectedRet.containsAll(ret));
    }

    @Test
    public void getCurrentCoolingDevices_nullResult_aidl() throws RemoteException {
        Mockito.when(mAidlHalMock.getCoolingDevicesWithType(Mockito.anyInt())).thenReturn(null);
        List<CoolingDevice> ret = mAidlWrapper.getCurrentCoolingDevices(true,
                CoolingType.COMPONENT);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getCoolingDevicesWithType(
                CoolingType.COMPONENT);
        assertNotNull(ret);
        assertEquals(0, ret.size());

        Mockito.when(mAidlHalMock.getCoolingDevices()).thenReturn(null);
        ret = mAidlWrapper.getCurrentCoolingDevices(false, CoolingType.COMPONENT);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getCoolingDevices();
        assertNotNull(ret);
        assertEquals(0, ret.size());
    }

    @Test
    public void getCurrentCoolingDevices_withFilter_aidl() throws RemoteException {
        android.hardware.thermal.CoolingDevice halC1 = new android.hardware.thermal.CoolingDevice();
        halC1.type = CoolingType.SPEAKER;
        halC1.name = "test1";
        halC1.value = 10;
        android.hardware.thermal.CoolingDevice halC2 = new android.hardware.thermal.CoolingDevice();
        halC2.type = CoolingType.MODEM;
        halC2.name = "test2";
        halC2.value = 110;

        Mockito.when(mAidlHalMock.getCoolingDevicesWithType(Mockito.anyInt())).thenReturn(
                new android.hardware.thermal.CoolingDevice[]{
                        halC1, halC2
                }
        );
        List<CoolingDevice> ret = mAidlWrapper.getCurrentCoolingDevices(true, CoolingType.SPEAKER);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getCoolingDevicesWithType(
                CoolingType.SPEAKER);

        CoolingDevice expectedC1 = new CoolingDevice(halC1.value, halC1.type, halC1.name);
        List<CoolingDevice> expectedRet = List.of(expectedC1);
        assertTrue("Got cooling device list as " + ret + " with different values compared to "
                + expectedRet, expectedRet.containsAll(ret));
    }

    @Test
    public void getCurrentCoolingDevices_invalidType_aidl() throws RemoteException {
        android.hardware.thermal.CoolingDevice halC1 = new android.hardware.thermal.CoolingDevice();
        halC1.type = 99;
        halC1.name = "test1";
        halC1.value = 10;
        android.hardware.thermal.CoolingDevice halC2 = new android.hardware.thermal.CoolingDevice();
        halC2.type = -1;
        halC2.name = "test2";
        halC2.value = 110;

        Mockito.when(mAidlHalMock.getCoolingDevices()).thenReturn(
                new android.hardware.thermal.CoolingDevice[]{
                        halC1, halC2
                }
        );
        List<CoolingDevice> ret = mAidlWrapper.getCurrentCoolingDevices(false, 0);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getCoolingDevices();

        assertTrue("Got cooling device list as " + ret + ", expecting empty list", ret.isEmpty());
    }

    @Test
    public void getCurrentCoolingDevices_illegalArgument_aidl() throws RemoteException {
        Mockito.when(mAidlHalMock.getCoolingDevices()).thenThrow(new IllegalArgumentException());
        List<CoolingDevice> ret = mAidlWrapper.getCurrentCoolingDevices(false, 0);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getCoolingDevices();
        assertNotNull(ret);
        assertEquals(0, ret.size());

        Mockito.when(mAidlHalMock.getCoolingDevicesWithType(Mockito.anyInt())).thenThrow(
                new IllegalArgumentException());
        ret = mAidlWrapper.getCurrentCoolingDevices(true, CoolingType.SPEAKER);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getCoolingDevicesWithType(
                CoolingType.SPEAKER);
        assertNotNull(ret);
        assertEquals(0, ret.size());
    }

    @Test
    public void getCurrentCoolingDevices_illegalState_aidl() throws RemoteException {
        Mockito.when(mAidlHalMock.getCoolingDevices()).thenThrow(new IllegalStateException());
        List<CoolingDevice> ret = mAidlWrapper.getCurrentCoolingDevices(false, 0);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getCoolingDevices();
        assertNotNull(ret);
        assertEquals(0, ret.size());

        Mockito.when(mAidlHalMock.getCoolingDevicesWithType(Mockito.anyInt())).thenThrow(
                new IllegalStateException());
        ret = mAidlWrapper.getCurrentCoolingDevices(true, CoolingType.SPEAKER);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getCoolingDevicesWithType(
                CoolingType.SPEAKER);
        assertNotNull(ret);
        assertEquals(0, ret.size());
    }

    @Test
    public void getTemperatureThresholds_aidl() throws RemoteException {
        TemperatureThreshold halT1 = new TemperatureThreshold();
        halT1.name = "test1";
        halT1.type = Temperature.TYPE_SKIN;
        halT1.hotThrottlingThresholds = new float[]{1, 2, 3};
        halT1.coldThrottlingThresholds = new float[]{};

        TemperatureThreshold halT2 = new TemperatureThreshold();
        halT2.name = "test2";
        halT2.type = Temperature.TYPE_SOC;
        halT2.hotThrottlingThresholds = new float[]{};
        halT2.coldThrottlingThresholds = new float[]{3, 2, 1};

        Mockito.when(mAidlHalMock.getTemperatureThresholds()).thenReturn(
                new TemperatureThreshold[]{halT1, halT2}
        );
        List<TemperatureThreshold> ret = mAidlWrapper.getTemperatureThresholds(false,
                Temperature.TYPE_UNKNOWN);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatureThresholds();

        assertEquals("Got unexpected temperature thresholds size", 2, ret.size());
        TemperatureThreshold threshold = ret.get(0).type == Temperature.TYPE_SKIN ? ret.get(0)
                : ret.get(1);

        assertEquals(halT1.name, threshold.name);
        assertEquals(halT1.type, threshold.type);
        assertArrayEquals(halT1.hotThrottlingThresholds, threshold.hotThrottlingThresholds, 0.1f);
        assertArrayEquals(halT1.coldThrottlingThresholds, threshold.coldThrottlingThresholds, 0.1f);

        threshold = ret.get(0).type == Temperature.TYPE_SOC ? ret.get(0) : ret.get(1);

        assertEquals(halT2.name, threshold.name);
        assertEquals(halT2.type, threshold.type);
        assertArrayEquals(halT2.hotThrottlingThresholds, threshold.hotThrottlingThresholds, 0.1f);
        assertArrayEquals(halT2.coldThrottlingThresholds, threshold.coldThrottlingThresholds, 0.1f);
    }

    @Test
    public void getTemperatureThresholds_withFilter_aidl() throws RemoteException {
        TemperatureThreshold halT1 = new TemperatureThreshold();
        halT1.name = "test1";
        halT1.type = Temperature.TYPE_SKIN;
        halT1.hotThrottlingThresholds = new float[]{1, 2, 3};
        halT1.coldThrottlingThresholds = new float[]{};

        TemperatureThreshold halT2 = new TemperatureThreshold();
        halT2.name = "test2";
        halT2.type = Temperature.TYPE_SOC;
        halT2.hotThrottlingThresholds = new float[]{};
        halT2.coldThrottlingThresholds = new float[]{3, 2, 1};

        Mockito.when(mAidlHalMock.getTemperatureThresholdsWithType(Mockito.anyInt())).thenReturn(
                new TemperatureThreshold[]{halT1, halT2}
        );
        List<TemperatureThreshold> ret = mAidlWrapper.getTemperatureThresholds(true,
                Temperature.TYPE_SKIN);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatureThresholdsWithType(
                Temperature.TYPE_SKIN);

        assertEquals("Got unexpected temperature thresholds size", 1, ret.size());
        TemperatureThreshold threshold = ret.get(0);
        assertEquals(halT1.name, threshold.name);
        assertEquals(halT1.type, threshold.type);
        assertArrayEquals(halT1.hotThrottlingThresholds, threshold.hotThrottlingThresholds, 0.1f);
        assertArrayEquals(halT1.coldThrottlingThresholds, threshold.coldThrottlingThresholds, 0.1f);
    }

    @Test
    public void getTemperatureThresholds_nullResult_aidl() throws RemoteException {
        Mockito.when(mAidlHalMock.getTemperatureThresholdsWithType(Mockito.anyInt())).thenReturn(
                null);
        List<TemperatureThreshold> ret = mAidlWrapper.getTemperatureThresholds(true,
                Temperature.TYPE_SOC);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatureThresholdsWithType(
                Temperature.TYPE_SOC);
        assertNotNull(ret);
        assertEquals(0, ret.size());

        Mockito.when(mAidlHalMock.getTemperatureThresholds()).thenReturn(null);
        ret = mAidlWrapper.getTemperatureThresholds(false, Temperature.TYPE_SOC);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatureThresholds();
        assertNotNull(ret);
        assertEquals(0, ret.size());
    }

    @Test
    public void getTemperatureThresholds_illegalArgument_aidl() throws RemoteException {
        Mockito.when(mAidlHalMock.getTemperatureThresholdsWithType(Mockito.anyInt())).thenThrow(
                new IllegalArgumentException());
        List<TemperatureThreshold> ret = mAidlWrapper.getTemperatureThresholds(true,
                Temperature.TYPE_SOC);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatureThresholdsWithType(
                Temperature.TYPE_SOC);
        assertNotNull(ret);
        assertEquals(0, ret.size());

        Mockito.when(mAidlHalMock.getTemperatureThresholds()).thenThrow(
                new IllegalArgumentException());
        ret = mAidlWrapper.getTemperatureThresholds(false,
                Temperature.TYPE_SOC);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatureThresholds();
        assertNotNull(ret);
        assertEquals(0, ret.size());
    }

    @Test
    public void getTemperatureThresholds_illegalState_aidl() throws RemoteException {
        Mockito.when(mAidlHalMock.getTemperatureThresholdsWithType(Mockito.anyInt())).thenThrow(
                new IllegalStateException());
        List<TemperatureThreshold> ret = mAidlWrapper.getTemperatureThresholds(true,
                Temperature.TYPE_SOC);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatureThresholdsWithType(
                Temperature.TYPE_SOC);
        assertNotNull(ret);
        assertEquals(0, ret.size());

        Mockito.when(mAidlHalMock.getTemperatureThresholds()).thenThrow(
                new IllegalStateException());
        ret = mAidlWrapper.getTemperatureThresholds(false,
                Temperature.TYPE_SOC);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatureThresholds();
        assertNotNull(ret);
        assertEquals(0, ret.size());
    }
}
