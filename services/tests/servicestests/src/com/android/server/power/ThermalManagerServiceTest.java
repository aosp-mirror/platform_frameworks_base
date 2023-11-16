/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.thermal.TemperatureThreshold;
import android.hardware.thermal.ThrottlingSeverity;
import android.os.CoolingDevice;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.IThermalStatusListener;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Temperature;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.SystemService;
import com.android.server.power.ThermalManagerService.TemperatureWatcher;
import com.android.server.power.ThermalManagerService.ThermalHalWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server
 * /power/ThermalManagerServiceTest.java
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ThermalManagerServiceTest {
    private static final long CALLBACK_TIMEOUT_MILLI_SEC = 5000;
    private ThermalManagerService mService;
    private ThermalHalFake mFakeHal;
    private PowerManager mPowerManager;
    @Mock
    private Context mContext;
    @Mock
    private IPowerManager mIPowerManagerMock;
    @Mock
    private IThermalService mIThermalServiceMock;
    @Mock
    private IThermalEventListener mEventListener1;
    @Mock
    private IThermalEventListener mEventListener2;
    @Mock
    private IThermalStatusListener mStatusListener1;
    @Mock
    private IThermalStatusListener mStatusListener2;

    /**
     * Fake Hal class.
     */
    private class ThermalHalFake extends ThermalHalWrapper {
        private static final int INIT_STATUS = Temperature.THROTTLING_NONE;
        private ArrayList<Temperature> mTemperatureList = new ArrayList<>();
        private ArrayList<CoolingDevice> mCoolingDeviceList = new ArrayList<>();
        private ArrayList<TemperatureThreshold> mTemperatureThresholdList = initializeThresholds();

        private Temperature mSkin1 = new Temperature(0, Temperature.TYPE_SKIN, "skin1",
                INIT_STATUS);
        private Temperature mSkin2 = new Temperature(0, Temperature.TYPE_SKIN, "skin2",
                INIT_STATUS);
        private Temperature mBattery = new Temperature(0, Temperature.TYPE_BATTERY, "batt",
                INIT_STATUS);
        private Temperature mUsbPort = new Temperature(0, Temperature.TYPE_USB_PORT, "usbport",
                INIT_STATUS);
        private CoolingDevice mCpu = new CoolingDevice(0, CoolingDevice.TYPE_BATTERY, "cpu");
        private CoolingDevice mGpu = new CoolingDevice(0, CoolingDevice.TYPE_BATTERY, "gpu");

        private ArrayList<TemperatureThreshold> initializeThresholds() {
            ArrayList<TemperatureThreshold> thresholds = new ArrayList<>();

            TemperatureThreshold skinThreshold = new TemperatureThreshold();
            skinThreshold.type = Temperature.TYPE_SKIN;
            skinThreshold.name = "skin1";
            skinThreshold.hotThrottlingThresholds = new float[7 /*ThrottlingSeverity#len*/];
            skinThreshold.coldThrottlingThresholds = new float[7 /*ThrottlingSeverity#len*/];
            for (int i = 0; i < skinThreshold.hotThrottlingThresholds.length; ++i) {
                // Sets NONE to 25.0f, SEVERE to 40.0f, and SHUTDOWN to 55.0f
                skinThreshold.hotThrottlingThresholds[i] = 25.0f + 5.0f * i;
            }
            thresholds.add(skinThreshold);

            TemperatureThreshold cpuThreshold = new TemperatureThreshold();
            cpuThreshold.type = Temperature.TYPE_CPU;
            cpuThreshold.name = "cpu";
            cpuThreshold.hotThrottlingThresholds = new float[7 /*ThrottlingSeverity#len*/];
            cpuThreshold.coldThrottlingThresholds = new float[7 /*ThrottlingSeverity#len*/];
            for (int i = 0; i < cpuThreshold.hotThrottlingThresholds.length; ++i) {
                if (i == ThrottlingSeverity.SEVERE) {
                    cpuThreshold.hotThrottlingThresholds[i] = 95.0f;
                } else {
                    cpuThreshold.hotThrottlingThresholds[i] = Float.NaN;
                }
            }
            thresholds.add(cpuThreshold);

            return thresholds;
        }

        ThermalHalFake() {
            mTemperatureList.add(mSkin1);
            mTemperatureList.add(mSkin2);
            mTemperatureList.add(mBattery);
            mTemperatureList.add(mUsbPort);
            mCoolingDeviceList.add(mCpu);
            mCoolingDeviceList.add(mGpu);
        }

        @Override
        protected List<Temperature> getCurrentTemperatures(boolean shouldFilter, int type) {
            List<Temperature> ret = new ArrayList<>();
            for (Temperature temperature : mTemperatureList) {
                if (shouldFilter && type != temperature.getType()) {
                    continue;
                }
                ret.add(temperature);
            }
            return ret;
        }

        @Override
        protected List<CoolingDevice> getCurrentCoolingDevices(boolean shouldFilter, int type) {
            List<CoolingDevice> ret = new ArrayList<>();
            for (CoolingDevice cdev : mCoolingDeviceList) {
                if (shouldFilter && type != cdev.getType()) {
                    continue;
                }
                ret.add(cdev);
            }
            return ret;
        }

        @Override
        protected List<TemperatureThreshold> getTemperatureThresholds(boolean shouldFilter,
                int type) {
            List<TemperatureThreshold> ret = new ArrayList<>();
            for (TemperatureThreshold threshold : mTemperatureThresholdList) {
                if (shouldFilter && type != threshold.type) {
                    continue;
                }
                ret.add(threshold);
            }
            return ret;
        }

        @Override
        protected boolean connectToHal() {
            return true;
        }

        @Override
        protected void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.println("ThermalHAL AIDL 1  connected: yes");
        }
    }

    private void assertListEqualsIgnoringOrder(List<?> actual, List<?> expected) {
        HashSet<?> actualSet = new HashSet<>(actual);
        HashSet<?> expectedSet = new HashSet<>(expected);
        assertEquals(expectedSet, actualSet);
    }

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mFakeHal = new ThermalHalFake();
        mPowerManager = new PowerManager(mContext, mIPowerManagerMock, mIThermalServiceMock, null);
        when(mContext.getSystemServiceName(PowerManager.class)).thenReturn(Context.POWER_SERVICE);
        when(mContext.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        resetListenerMock();
        mService = new ThermalManagerService(mContext, mFakeHal);
        // Register callbacks before AMS ready and no callback sent
        assertTrue(mService.mService.registerThermalEventListener(mEventListener1));
        assertTrue(mService.mService.registerThermalStatusListener(mStatusListener1));
        assertTrue(mService.mService.registerThermalEventListenerWithType(mEventListener2,
                Temperature.TYPE_SKIN));
        assertTrue(mService.mService.registerThermalStatusListener(mStatusListener2));
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).notifyThrottling(any(Temperature.class));
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(Temperature.THROTTLING_NONE);
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).notifyThrottling(any(Temperature.class));
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(Temperature.THROTTLING_NONE);
        resetListenerMock();
        mService.onBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);
        ArgumentCaptor<Temperature> captor = ArgumentCaptor.forClass(Temperature.class);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(4)).notifyThrottling(captor.capture());
        assertListEqualsIgnoringOrder(mFakeHal.mTemperatureList, captor.getAllValues());
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onStatusChange(Temperature.THROTTLING_NONE);
        captor = ArgumentCaptor.forClass(Temperature.class);
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(2)).notifyThrottling(captor.capture());
        assertListEqualsIgnoringOrder(
                new ArrayList<>(Arrays.asList(mFakeHal.mSkin1, mFakeHal.mSkin2)),
                captor.getAllValues());
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onStatusChange(Temperature.THROTTLING_NONE);
    }

    private void resetListenerMock() {
        reset(mEventListener1);
        reset(mStatusListener1);
        reset(mEventListener2);
        reset(mStatusListener2);
        doReturn(mock(IBinder.class)).when(mEventListener1).asBinder();
        doReturn(mock(IBinder.class)).when(mStatusListener1).asBinder();
        doReturn(mock(IBinder.class)).when(mEventListener2).asBinder();
        doReturn(mock(IBinder.class)).when(mStatusListener2).asBinder();
    }

    @Test
    public void testRegister() throws RemoteException {
        resetListenerMock();
        // Register callbacks and verify they are called
        assertTrue(mService.mService.registerThermalEventListener(mEventListener1));
        assertTrue(mService.mService.registerThermalStatusListener(mStatusListener1));
        ArgumentCaptor<Temperature> captor = ArgumentCaptor.forClass(Temperature.class);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(4)).notifyThrottling(captor.capture());
        assertListEqualsIgnoringOrder(mFakeHal.mTemperatureList, captor.getAllValues());
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(Temperature.THROTTLING_NONE);
        // Register new callbacks and verify old ones are not called (remained same) while new
        // ones are called
        assertTrue(mService.mService.registerThermalEventListenerWithType(mEventListener2,
                Temperature.TYPE_SKIN));
        assertTrue(mService.mService.registerThermalStatusListener(mStatusListener2));
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(4)).notifyThrottling(any(Temperature.class));
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(Temperature.THROTTLING_NONE);
        captor = ArgumentCaptor.forClass(Temperature.class);
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(2)).notifyThrottling(captor.capture());
        assertListEqualsIgnoringOrder(
                new ArrayList<>(Arrays.asList(mFakeHal.mSkin1, mFakeHal.mSkin2)),
                captor.getAllValues());
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(Temperature.THROTTLING_NONE);
    }

    @Test
    public void testNotify() throws RemoteException {
        int status = Temperature.THROTTLING_SEVERE;
        // Should only notify event not status
        Temperature newBattery = new Temperature(50, Temperature.TYPE_BATTERY, "batt", status);
        mFakeHal.mCallback.onValues(newBattery);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).notifyThrottling(newBattery);
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onStatusChange(anyInt());
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).notifyThrottling(newBattery);
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onStatusChange(anyInt());
        resetListenerMock();
        // Notify both event and status
        Temperature newSkin = new Temperature(50, Temperature.TYPE_SKIN, "skin1", status);
        mFakeHal.mCallback.onValues(newSkin);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).notifyThrottling(newSkin);
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(status);
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).notifyThrottling(newSkin);
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(status);
        resetListenerMock();
        // Back to None, should only notify event not status
        status = Temperature.THROTTLING_NONE;
        newBattery = new Temperature(50, Temperature.TYPE_BATTERY, "batt", status);
        mFakeHal.mCallback.onValues(newBattery);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).notifyThrottling(newBattery);
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onStatusChange(anyInt());
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).notifyThrottling(newBattery);
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onStatusChange(anyInt());
        resetListenerMock();
        // Should also notify status
        newSkin = new Temperature(50, Temperature.TYPE_SKIN, "skin1", status);
        mFakeHal.mCallback.onValues(newSkin);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).notifyThrottling(newSkin);
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(status);
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).notifyThrottling(newSkin);
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(status);
    }

    @Test
    public void testGetCurrentTemperatures() throws RemoteException {
        assertListEqualsIgnoringOrder(mFakeHal.getCurrentTemperatures(false, 0),
                Arrays.asList(mService.mService.getCurrentTemperatures()));
        assertListEqualsIgnoringOrder(
                mFakeHal.getCurrentTemperatures(true, Temperature.TYPE_SKIN),
                Arrays.asList(mService.mService.getCurrentTemperaturesWithType(
                        Temperature.TYPE_SKIN)));
    }

    @Test
    public void testGetCurrentStatus() throws RemoteException {
        int status = Temperature.THROTTLING_SEVERE;
        Temperature newSkin = new Temperature(100, Temperature.TYPE_SKIN, "skin1", status);
        mFakeHal.mCallback.onValues(newSkin);
        assertEquals(status, mService.mService.getCurrentThermalStatus());
        int battStatus = Temperature.THROTTLING_EMERGENCY;
        Temperature newBattery = new Temperature(60, Temperature.TYPE_BATTERY, "batt", battStatus);
        assertEquals(status, mService.mService.getCurrentThermalStatus());
    }

    @Test
    public void testThermalShutdown() throws RemoteException {
        int status = Temperature.THROTTLING_SHUTDOWN;
        Temperature newSkin = new Temperature(100, Temperature.TYPE_SKIN, "skin1", status);
        mFakeHal.mCallback.onValues(newSkin);
        verify(mIPowerManagerMock, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).shutdown(false, PowerManager.SHUTDOWN_THERMAL_STATE, false);
        Temperature newBattery = new Temperature(60, Temperature.TYPE_BATTERY, "batt", status);
        mFakeHal.mCallback.onValues(newBattery);
        verify(mIPowerManagerMock, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).shutdown(false, PowerManager.SHUTDOWN_BATTERY_THERMAL_STATE, false);
    }

    @Test
    public void testNoHal() throws RemoteException {
        mService = new ThermalManagerService(mContext);
        // Do no call onActivityManagerReady to skip connect HAL
        assertTrue(mService.mService.registerThermalEventListener(mEventListener1));
        assertTrue(mService.mService.registerThermalStatusListener(mStatusListener1));
        assertTrue(mService.mService.unregisterThermalEventListener(mEventListener1));
        assertTrue(mService.mService.unregisterThermalStatusListener(mStatusListener1));
        assertEquals(0, Arrays.asList(mService.mService.getCurrentTemperatures()).size());
        assertEquals(0, Arrays.asList(mService.mService.getCurrentTemperaturesWithType(
                        Temperature.TYPE_SKIN)).size());
        assertEquals(Temperature.THROTTLING_NONE, mService.mService.getCurrentThermalStatus());
        assertTrue(Float.isNaN(mService.mService.getThermalHeadroom(0)));
    }

    @Test
    public void testGetCurrentCoolingDevices() throws RemoteException {
        assertListEqualsIgnoringOrder(mFakeHal.getCurrentCoolingDevices(false, 0),
                Arrays.asList(mService.mService.getCurrentCoolingDevices()));
        assertListEqualsIgnoringOrder(
                mFakeHal.getCurrentCoolingDevices(false, CoolingDevice.TYPE_BATTERY),
                Arrays.asList(mService.mService.getCurrentCoolingDevices()));
        assertListEqualsIgnoringOrder(
                mFakeHal.getCurrentCoolingDevices(true, CoolingDevice.TYPE_CPU),
                Arrays.asList(mService.mService.getCurrentCoolingDevicesWithType(
                        CoolingDevice.TYPE_CPU)));
    }

    @Test
    public void testGetThermalHeadroomInputRange() throws RemoteException {
        assertTrue(Float.isNaN(mService.mService.getThermalHeadroom(
                ThermalManagerService.MIN_FORECAST_SEC - 1)));
        assertTrue(Float.isNaN(mService.mService.getThermalHeadroom(
                ThermalManagerService.MAX_FORECAST_SEC + 1)));
    }

    @Test
    public void testTemperatureWatcherUpdateSevereThresholds() throws RemoteException {
        TemperatureWatcher watcher = mService.mTemperatureWatcher;
        watcher.mSevereThresholds.erase();
        watcher.updateThresholds();
        assertEquals(1, watcher.mSevereThresholds.size());
        assertEquals("skin1", watcher.mSevereThresholds.keyAt(0));
        Float threshold = watcher.mSevereThresholds.get("skin1");
        assertNotNull(threshold);
        assertEquals(40.0f, threshold, 0.0f);
    }

    @Test
    public void testTemperatureWatcherUpdateHeadroomThreshold() {
        TemperatureWatcher watcher = mService.mTemperatureWatcher;
        synchronized (watcher.mSamples) {
            Arrays.fill(watcher.mHeadroomThresholds, Float.NaN);
        }
        watcher.updateHeadroomThreshold(ThrottlingSeverity.LIGHT, 40, 49);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.MODERATE, 46, 49);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.SEVERE, 49, 49);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.CRITICAL, 64, 49);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.EMERGENCY, 70, 49);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.SHUTDOWN, 79, 49);
        synchronized (watcher.mSamples) {
            assertArrayEquals(new float[]{Float.NaN, 0.7f, 0.9f, 1.0f, 1.5f, 1.7f, 2.0f},
                    watcher.mHeadroomThresholds, 0.01f);
        }

        // when another sensor reports different threshold, we expect to see smaller one to be used
        watcher.updateHeadroomThreshold(ThrottlingSeverity.LIGHT, 37, 52);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.MODERATE, 46, 52);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.SEVERE, 52, 52);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.CRITICAL, 64, 52);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.EMERGENCY, 100, 52);
        watcher.updateHeadroomThreshold(ThrottlingSeverity.SHUTDOWN, 200, 52);
        synchronized (watcher.mSamples) {
            assertArrayEquals(new float[]{Float.NaN, 0.5f, 0.8f, 1.0f, 1.4f, 1.7f, 2.0f},
                    watcher.mHeadroomThresholds, 0.01f);
        }
    }

    @Test
    public void testGetThermalHeadroomThresholdsOnlyReadOnce() throws Exception {
        float[] expected = new float[]{Float.NaN, 0.1f, 0.2f, 0.3f, 0.4f, Float.NaN, 0.6f};
        when(mIThermalServiceMock.getThermalHeadroomThresholds()).thenReturn(expected);
        Map<Integer, Float> thresholds1 = mPowerManager.getThermalHeadroomThresholds();
        verify(mIThermalServiceMock, times(1)).getThermalHeadroomThresholds();
        for (int status = PowerManager.THERMAL_STATUS_LIGHT;
                status <= PowerManager.THERMAL_STATUS_SHUTDOWN; status++) {
            if (Float.isNaN(expected[status])) {
                assertFalse(thresholds1.containsKey(status));
            } else {
                assertEquals(expected[status], thresholds1.get(status), 0.01f);
            }
        }
        reset(mIThermalServiceMock);
        Map<Integer, Float> thresholds2 = mPowerManager.getThermalHeadroomThresholds();
        verify(mIThermalServiceMock, times(0)).getThermalHeadroomThresholds();
        assertNotSame(thresholds1, thresholds2);
        assertEquals(thresholds1, thresholds2);
    }

    @Test
    public void testTemperatureWatcherGetSlopeOf() throws RemoteException {
        TemperatureWatcher watcher = mService.mTemperatureWatcher;
        List<TemperatureWatcher.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 30; ++i) {
            samples.add(watcher.createSampleForTesting(i, (float) (i / 2 * 2)));
        }
        assertEquals(1.0f, watcher.getSlopeOf(samples), 0.01f);
    }

    @Test
    public void testTemperatureWatcherNormalizeTemperature() throws RemoteException {
        assertEquals(0.5f,
                TemperatureWatcher.normalizeTemperature(25.0f, 40.0f), 0.0f);

        // Temperatures more than 30 degrees below the SEVERE threshold should be clamped to 0.0f
        assertEquals(0.0f,
                TemperatureWatcher.normalizeTemperature(0.0f, 40.0f), 0.0f);

        // Temperatures above the SEVERE threshold should not be clamped
        assertEquals(2.0f,
                TemperatureWatcher.normalizeTemperature(70.0f, 40.0f), 0.0f);
    }

    @Test
    public void testTemperatureWatcherGetForecast() throws RemoteException {
        TemperatureWatcher watcher = mService.mTemperatureWatcher;

        ArrayList<TemperatureWatcher.Sample> samples = new ArrayList<>();

        // Add a single sample
        samples.add(watcher.createSampleForTesting(0, 25.0f));
        watcher.mSamples.put("skin1", samples);

        // Because there are not enough samples to compute the linear regression,
        // no matter how far ahead we forecast, we should receive the same value
        assertEquals(0.5f, watcher.getForecast(0), 0.0f);
        assertEquals(0.5f, watcher.getForecast(5), 0.0f);

        // Add some time-series data
        for (int i = 1; i < 20; ++i) {
            samples.add(0, watcher.createSampleForTesting(1000 * i, 25.0f + 0.5f * i));
        }

        // Now the forecast should vary depending on how far ahead we are trying to predict
        assertEquals(0.9f, watcher.getForecast(4), 0.02f);
        assertEquals(1.0f, watcher.getForecast(10), 0.02f);

        // If there are no thresholds, then we shouldn't receive a headroom value
        watcher.mSevereThresholds.erase();
        assertTrue(Float.isNaN(watcher.getForecast(0)));
    }

    @Test
    public void testTemperatureWatcherGetForecastUpdate() throws Exception {
        TemperatureWatcher watcher = mService.mTemperatureWatcher;

        // Reduce the inactivity threshold to speed up testing
        watcher.mInactivityThresholdMillis = 2000;

        // Make sure mSamples is empty before updateTemperature
        assertTrue(isWatcherSamplesEmpty(watcher));

        // Call getForecast once to trigger updateTemperature
        watcher.getForecast(0);

        // After 1 second, the samples should be updated
        Thread.sleep(1000);
        assertFalse(isWatcherSamplesEmpty(watcher));

        // After mInactivityThresholdMillis, the samples should be cleared
        Thread.sleep(watcher.mInactivityThresholdMillis);
        assertTrue(isWatcherSamplesEmpty(watcher));
    }

    // Helper function to hold mSamples lock, avoid GuardedBy lint errors
    private boolean isWatcherSamplesEmpty(TemperatureWatcher watcher) {
        synchronized (watcher.mSamples) {
            return watcher.mSamples.isEmpty();
        }
    }

    @Test
    public void testDump() {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        final StringWriter out = new StringWriter();
        PrintWriter pw = new PrintWriter(out);
        mService.dumpInternal(new FileDescriptor(), pw, null);
        final String dumpStr = out.toString();
        assertThat(dumpStr).contains("IsStatusOverride: false");
        assertThat(dumpStr).contains(
                "ThermalEventListeners:\n"
                        + "\tcallbacks: 2\n"
                        + "\tkilled: false\n"
                        + "\tbroadcasts count: -1");
        assertThat(dumpStr).contains(
                "ThermalStatusListeners:\n"
                        + "\tcallbacks: 2\n"
                        + "\tkilled: false\n"
                        + "\tbroadcasts count: -1");
        assertThat(dumpStr).contains("Thermal Status: 0");
        assertThat(dumpStr).contains(
                "Cached temperatures:\n"
                + "\tTemperature{mValue=0.0, mType=4, mName=usbport, mStatus=0}\n"
                + "\tTemperature{mValue=0.0, mType=2, mName=batt, mStatus=0}\n"
                + "\tTemperature{mValue=0.0, mType=3, mName=skin1, mStatus=0}\n"
                + "\tTemperature{mValue=0.0, mType=3, mName=skin2, mStatus=0}"
        );
        assertThat(dumpStr).contains("HAL Ready: true\n"
                + "HAL connection:\n"
                + "\tThermalHAL AIDL 1  connected: yes");
        assertThat(dumpStr).contains("Current temperatures from HAL:\n"
                + "\tTemperature{mValue=0.0, mType=3, mName=skin1, mStatus=0}\n"
                + "\tTemperature{mValue=0.0, mType=3, mName=skin2, mStatus=0}\n"
                + "\tTemperature{mValue=0.0, mType=2, mName=batt, mStatus=0}\n"
                + "\tTemperature{mValue=0.0, mType=4, mName=usbport, mStatus=0}\n");
        assertThat(dumpStr).contains("Current cooling devices from HAL:\n"
                + "\tCoolingDevice{mValue=0, mType=1, mName=cpu}\n"
                + "\tCoolingDevice{mValue=0, mType=1, mName=gpu}\n");
        assertThat(dumpStr).contains("Temperature static thresholds from HAL:\n"
                + "\tTemperatureThreshold{mType=3, mName=skin1, mHotThrottlingThresholds=[25.0, "
                + "30.0, 35.0, 40.0, 45.0, 50.0, 55.0], mColdThrottlingThresholds=[0.0, 0.0, 0.0,"
                + " 0.0, 0.0, 0.0, 0.0]}\n"
                + "\tTemperatureThreshold{mType=0, mName=cpu, mHotThrottlingThresholds=[NaN, NaN,"
                + " NaN, 95.0, NaN, NaN, NaN], mColdThrottlingThresholds=[0.0, 0.0, 0.0, 0.0, 0"
                + ".0, 0.0, 0.0]}");
    }
}
