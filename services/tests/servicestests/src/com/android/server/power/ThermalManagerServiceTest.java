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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalEventListener;
import android.os.IThermalStatusListener;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Temperature;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.SystemService;
import com.android.server.power.ThermalManagerService.ThermalHalWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

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
        private Temperature mSkin1 = new Temperature(0, Temperature.TYPE_SKIN, "skin1",
                INIT_STATUS);
        private Temperature mSkin2 = new Temperature(0, Temperature.TYPE_SKIN, "skin2",
                INIT_STATUS);
        private Temperature mBattery = new Temperature(0, Temperature.TYPE_BATTERY, "batt",
                INIT_STATUS);
        private Temperature mUsbPort = new Temperature(0, Temperature.TYPE_USB_PORT, "usbport",
                INIT_STATUS);

        ThermalHalFake() {
            mTemperatureList.add(mSkin1);
            mTemperatureList.add(mSkin2);
            mTemperatureList.add(mBattery);
            mTemperatureList.add(mUsbPort);
        }

        @Override
        protected List<Temperature> getCurrentTemperatures(boolean shouldFilter, int type) {
            return mTemperatureList;
        }

        @Override
        protected boolean connectToHal() {
            return true;
        }

        @Override
        protected void dump(PrintWriter pw, String prefix) {
            return;
        }
    }

    private void assertTemperatureEquals(List<Temperature> expected, List<Temperature> value) {
        assertEquals(new HashSet<>(expected), new HashSet<>(value));
    }

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mFakeHal = new ThermalHalFake();
        mPowerManager = new PowerManager(mContext, mIPowerManagerMock, null);
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
                .times(0)).onStatusChange(anyInt());
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).notifyThrottling(any(Temperature.class));
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onStatusChange(anyInt());
        mService.onBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);
        ArgumentCaptor<Temperature> captor = ArgumentCaptor.forClass(Temperature.class);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(4)).notifyThrottling(captor.capture());
        assertTemperatureEquals(mFakeHal.mTemperatureList, captor.getAllValues());
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(Temperature.THROTTLING_NONE);
        captor = ArgumentCaptor.forClass(Temperature.class);
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(2)).notifyThrottling(captor.capture());
        assertTemperatureEquals(new ArrayList<>(Arrays.asList(mFakeHal.mSkin1, mFakeHal.mSkin2)),
                captor.getAllValues());
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(Temperature.THROTTLING_NONE);
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
        // Unregister all
        assertTrue(mService.mService.unregisterThermalEventListener(mEventListener1));
        assertTrue(mService.mService.unregisterThermalStatusListener(mStatusListener1));
        assertTrue(mService.mService.unregisterThermalEventListener(mEventListener2));
        assertTrue(mService.mService.unregisterThermalStatusListener(mStatusListener2));
        resetListenerMock();
        // Register callbacks and verify they are called
        assertTrue(mService.mService.registerThermalEventListener(mEventListener1));
        assertTrue(mService.mService.registerThermalStatusListener(mStatusListener1));
        ArgumentCaptor<Temperature> captor = ArgumentCaptor.forClass(Temperature.class);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(4)).notifyThrottling(captor.capture());
        assertTemperatureEquals(mFakeHal.mTemperatureList, captor.getAllValues());
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
        assertTemperatureEquals(new ArrayList<>(Arrays.asList(mFakeHal.mSkin1, mFakeHal.mSkin2)),
                captor.getAllValues());
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(Temperature.THROTTLING_NONE);
    }

    @Test
    public void testNotify() throws RemoteException {
        int status = Temperature.THROTTLING_SEVERE;
        Temperature newBattery = new Temperature(50, Temperature.TYPE_BATTERY, "batt", status);
        mFakeHal.mCallback.onValues(newBattery);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).notifyThrottling(newBattery);
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(status);
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).notifyThrottling(newBattery);
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).onStatusChange(status);
        resetListenerMock();
        // Should only notify event not status
        Temperature newSkin = new Temperature(50, Temperature.TYPE_SKIN, "skin1", status);
        mFakeHal.mCallback.onValues(newSkin);
        verify(mEventListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).notifyThrottling(newSkin);
        verify(mStatusListener1, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onStatusChange(anyInt());
        verify(mEventListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(1)).notifyThrottling(newSkin);
        verify(mStatusListener2, timeout(CALLBACK_TIMEOUT_MILLI_SEC)
                .times(0)).onStatusChange(anyInt());
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
        assertTemperatureEquals(mFakeHal.getCurrentTemperatures(false, 0),
                mService.mService.getCurrentTemperatures());
        assertTemperatureEquals(mFakeHal.getCurrentTemperatures(true, Temperature.TYPE_SKIN),
                mService.mService.getCurrentTemperaturesWithType(Temperature.TYPE_SKIN));
    }

    @Test
    public void testGetCurrentStatus() throws RemoteException {
        int status = Temperature.THROTTLING_EMERGENCY;
        Temperature newSkin = new Temperature(100, Temperature.TYPE_SKIN, "skin1", status);
        mFakeHal.mCallback.onValues(newSkin);
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
        assertEquals(0, mService.mService.getCurrentTemperatures().size());
        assertEquals(0,
                mService.mService.getCurrentTemperaturesWithType(Temperature.TYPE_SKIN).size());
        assertEquals(Temperature.THROTTLING_NONE, mService.mService.getCurrentThermalStatus());
    }
}
