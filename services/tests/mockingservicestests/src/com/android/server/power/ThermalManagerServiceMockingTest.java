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
import static org.junit.Assert.assertTrue;

import android.hardware.thermal.CoolingType;
import android.hardware.thermal.IThermal;
import android.hardware.thermal.IThermalChangedCallback;
import android.hardware.thermal.TemperatureThreshold;
import android.hardware.thermal.TemperatureType;
import android.hardware.thermal.ThrottlingSeverity;
import android.os.Binder;
import android.os.CoolingDevice;
import android.os.RemoteException;
import android.os.Temperature;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


public class ThermalManagerServiceMockingTest {
    @Mock private IThermal mAidlHalMock;
    private Binder mAidlBinder = new Binder();
    private CompletableFuture<Temperature> mTemperatureFuture;
    private ThermalManagerService.ThermalHalWrapper.TemperatureChangedCallback mTemperatureCallback;
    private ThermalManagerService.ThermalHalAidlWrapper mAidlWrapper;
    @Captor
    ArgumentCaptor<IThermalChangedCallback> mAidlCallbackCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Mockito.when(mAidlHalMock.asBinder()).thenReturn(mAidlBinder);
        mAidlBinder.attachInterface(mAidlHalMock, IThermal.class.getName());
        mTemperatureFuture = new CompletableFuture<>();
        mTemperatureCallback = temperature -> mTemperatureFuture.complete(temperature);
        mAidlWrapper = new ThermalManagerService.ThermalHalAidlWrapper();
        mAidlWrapper.setCallback(mTemperatureCallback);
        mAidlWrapper.initProxyAndRegisterCallback(mAidlBinder);
    }

    @Test
    public void setCallback_aidl() throws Exception {
        Mockito.verify(mAidlHalMock, Mockito.times(1)).registerThermalChangedCallback(
                mAidlCallbackCaptor.capture());
        android.hardware.thermal.Temperature halT =
                new android.hardware.thermal.Temperature();
        halT.type = TemperatureType.SOC;
        halT.name = "test";
        halT.throttlingStatus = ThrottlingSeverity.SHUTDOWN;
        halT.value = 99.0f;
        mAidlCallbackCaptor.getValue().notifyThrottling(halT);
        Temperature temperature = mTemperatureFuture.get(100, TimeUnit.MILLISECONDS);
        assertEquals(halT.name, temperature.getName());
        assertEquals(halT.type, temperature.getType());
        assertEquals(halT.value, temperature.getValue(), 0.1f);
        assertEquals(halT.throttlingStatus, temperature.getStatus());
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
        assertEquals(expectedRet, ret);
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
    public void getTemperatureThresholds_withFilter_aidl() throws RemoteException {
        TemperatureThreshold halT1 = new TemperatureThreshold();
        halT1.name = "test1";
        halT1.type = Temperature.TYPE_SKIN;
        halT1.hotThrottlingThresholds = new float[]{1, 2, 3};
        halT1.coldThrottlingThresholds = new float[]{};

        TemperatureThreshold halT2 = new TemperatureThreshold();
        halT1.name = "test2";
        halT1.type = Temperature.TYPE_SOC;
        halT1.hotThrottlingThresholds = new float[]{};
        halT1.coldThrottlingThresholds = new float[]{3, 2, 1};

        Mockito.when(mAidlHalMock.getTemperatureThresholdsWithType(Mockito.anyInt())).thenReturn(
                new TemperatureThreshold[]{halT1, halT2}
        );
        List<TemperatureThreshold> ret = mAidlWrapper.getTemperatureThresholds(true,
                Temperature.TYPE_SOC);
        Mockito.verify(mAidlHalMock, Mockito.times(1)).getTemperatureThresholdsWithType(
                Temperature.TYPE_SOC);

        assertEquals("Got unexpected temperature thresholds size", 1, ret.size());
        TemperatureThreshold threshold = ret.get(0);
        assertEquals(halT1.name, threshold.name);
        assertEquals(halT1.type, threshold.type);
        assertArrayEquals(halT1.hotThrottlingThresholds, threshold.hotThrottlingThresholds, 0.1f);
        assertArrayEquals(halT1.coldThrottlingThresholds, threshold.coldThrottlingThresholds, 0.1f);
    }
}
