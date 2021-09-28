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

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeLog.REASON_SENSOR_TAP;
import static com.android.systemui.plugins.SensorManagerPlugin.Sensor.TYPE_WAKE_LOCK_SCREEN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.display.AmbientDisplayConfiguration;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.doze.DozeSensors.TriggerSensor;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.wakelock.WakeLock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class DozeSensorsTest extends SysuiTestCase {

    @Mock
    private AsyncSensorManager mSensorManager;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock
    private WakeLock mWakeLock;
    @Mock
    private DozeSensors.Callback mCallback;
    @Mock
    private Consumer<Boolean> mProxCallback;
    @Mock
    private TriggerSensor mTriggerSensor;
    @Mock
    private DozeLog mDozeLog;
    @Mock
    private AuthController mAuthController;
    @Mock
    private ProximitySensor mProximitySensor;
    private @DevicePostureController.DevicePostureInt int mDevicePosture =
            DevicePostureController.DEVICE_POSTURE_UNKNOWN;
    private FakeSettings mFakeSettings = new FakeSettings();
    private SensorManagerPlugin.SensorEventListener mWakeLockScreenListener;
    private TestableLooper mTestableLooper;
    private TestableDozeSensors mDozeSensors;
    private TriggerSensor mSensorTap;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        when(mAmbientDisplayConfiguration.getWakeLockScreenDebounce()).thenReturn(5000L);
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mWakeLock).wrap(any(Runnable.class));
        mDevicePosture = DevicePostureController.DEVICE_POSTURE_UNKNOWN;
        mDozeSensors = new TestableDozeSensors();
    }

    @Test
    public void testRegisterProx() {
        assertFalse(mProximitySensor.isRegistered());
        mDozeSensors.setProxListening(true);
        verify(mProximitySensor).resume();
    }

    @Test
    public void testSensorDebounce() {
        mDozeSensors.setListening(true, true);

        mWakeLockScreenListener.onSensorChanged(mock(SensorManagerPlugin.SensorEvent.class));
        mTestableLooper.processAllMessages();
        verify(mCallback).onSensorPulse(eq(DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN),
                anyFloat(), anyFloat(), eq(null));

        mDozeSensors.requestTemporaryDisable();
        reset(mCallback);
        mWakeLockScreenListener.onSensorChanged(mock(SensorManagerPlugin.SensorEvent.class));
        mTestableLooper.processAllMessages();
        verify(mCallback, never()).onSensorPulse(eq(DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN),
                anyFloat(), anyFloat(), eq(null));
    }

    @Test
    public void testSetListening_firstTrue_registerSettingsObserver() {
        verify(mSensorManager, never()).registerListener(any(), any(Sensor.class), anyInt());
        mDozeSensors.setListening(true, true);

        verify(mTriggerSensor).registerSettingsObserver(any(ContentObserver.class));
    }

    @Test
    public void testSetListening_twiceTrue_onlyRegisterSettingsObserverOnce() {
        verify(mSensorManager, never()).registerListener(any(), any(Sensor.class), anyInt());
        mDozeSensors.setListening(true, true);
        mDozeSensors.setListening(true, true);

        verify(mTriggerSensor, times(1)).registerSettingsObserver(any(ContentObserver.class));
    }

    @Test
    public void testDestroy() {
        mDozeSensors.destroy();

        verify(mTriggerSensor).setListening(false);
    }

    @Test
    public void testRegisterSensorsUsingProx() {
        // GIVEN we only should register sensors using prox when not in low-powered mode / off
        // and the single tap sensor uses the proximity sensor
        when(mDozeParameters.getSelectivelyRegisterSensorsUsingProx()).thenReturn(true);
        when(mDozeParameters.singleTapUsesProx(anyInt())).thenReturn(true);
        TestableDozeSensors dozeSensors = new TestableDozeSensors();

        // THEN on initialization, the tap sensor isn't requested
        assertFalse(mSensorTap.mRequested);

        // WHEN we're now in a low powered state
        dozeSensors.setListening(true, true, true);

        // THEN the tap sensor is registered
        assertTrue(mSensorTap.mRequested);
    }

    @Test
    public void testDozeSensorSetListening() {
        // GIVEN doze sensors enabled
        when(mAmbientDisplayConfiguration.enabled(anyInt())).thenReturn(true);

        // GIVEN a trigger sensor
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensor(
                mockSensor,
                /* settingEnabled */ true,
                /* requiresTouchScreen */ true);
        when(mSensorManager.requestTriggerSensor(eq(triggerSensor), eq(mockSensor)))
                .thenReturn(true);

        // WHEN we want to listen for the trigger sensor
        triggerSensor.setListening(true);

        // THEN the sensor is registered
        assertTrue(triggerSensor.mRegistered);
    }

    @Test
    public void testDozeSensorSettingDisabled() {
        // GIVEN doze sensors enabled
        when(mAmbientDisplayConfiguration.enabled(anyInt())).thenReturn(true);

        // GIVEN a trigger sensor
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensor(
                mockSensor,
                /* settingEnabled*/ false,
                /* requiresTouchScreen */ true);
        when(mSensorManager.requestTriggerSensor(eq(triggerSensor), eq(mockSensor)))
                .thenReturn(true);

        // WHEN setListening is called
        triggerSensor.setListening(true);

        // THEN the sensor is not registered
        assertFalse(triggerSensor.mRegistered);
    }

    @Test
    public void testDozeSensorIgnoreSetting() {
        // GIVEN doze sensors enabled
        when(mAmbientDisplayConfiguration.enabled(anyInt())).thenReturn(true);

        // GIVEN a trigger sensor that's
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensor(
                mockSensor,
                /* settingEnabled*/ false,
                /* requiresTouchScreen */ true);
        when(mSensorManager.requestTriggerSensor(eq(triggerSensor), eq(mockSensor)))
                .thenReturn(true);

        // GIVEN sensor is listening
        triggerSensor.setListening(true);

        // WHEN ignoreSetting is called
        triggerSensor.ignoreSetting(true);

        // THEN the sensor is registered
        assertTrue(triggerSensor.mRegistered);
    }

    @Test
    public void testUpdateListeningAfterAlreadyRegistered() {
        // GIVEN doze sensors enabled
        when(mAmbientDisplayConfiguration.enabled(anyInt())).thenReturn(true);

        // GIVEN a trigger sensor
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensor(
                mockSensor,
                /* settingEnabled*/ true,
                /* requiresTouchScreen */ true);
        when(mSensorManager.requestTriggerSensor(eq(triggerSensor), eq(mockSensor)))
                .thenReturn(true);

        // WHEN setListening is called AND updateListening is called
        triggerSensor.setListening(true);
        triggerSensor.updateListening();

        // THEN the sensor is still registered
        assertTrue(triggerSensor.mRegistered);
    }

    @Test
    public void testPostureOpen_registersCorrectTapGesture() {
        // GIVEN device posture open
        mDevicePosture = DevicePostureController.DEVICE_POSTURE_OPENED;

        // WHEN DozeSensors are initialized
        new TestableDozeSensors();

        // THEN we use the posture to determine which tap sensor to use
        verify(mAmbientDisplayConfiguration).tapSensorType(eq(mDevicePosture));
    }

    @Test
    public void testFindSensor() throws Exception {
        // GIVEN a prox sensor
        List<Sensor> sensors = new ArrayList<>();
        Sensor proxSensor =
                createSensor(Sensor.TYPE_PROXIMITY, Sensor.STRING_TYPE_PROXIMITY);
        sensors.add(proxSensor);

        when(mSensorManager.getSensorList(anyInt())).thenReturn(sensors);

        // WHEN we try to find the prox sensor with the same type and name
        // THEN we find the added sensor
        assertEquals(
                proxSensor,
                DozeSensors.findSensor(
                        mSensorManager,
                        Sensor.STRING_TYPE_PROXIMITY,
                        proxSensor.getName()));

        // WHEN we try to find a prox sensor with a different name
        // THEN no sensor is found
        assertEquals(
                null,
                DozeSensors.findSensor(
                        mSensorManager,
                        Sensor.STRING_TYPE_PROXIMITY,
                        "some other name"));
    }


    private class TestableDozeSensors extends DozeSensors {

        TestableDozeSensors() {
            super(getContext(), mSensorManager, mDozeParameters,
                    mAmbientDisplayConfiguration, mWakeLock, mCallback, mProxCallback, mDozeLog,
                    mProximitySensor, mFakeSettings, mAuthController,
                    mDevicePosture);
            for (TriggerSensor sensor : mSensors) {
                if (sensor instanceof PluginSensor
                        && ((PluginSensor) sensor).mPluginSensor.getType()
                        == TYPE_WAKE_LOCK_SCREEN) {
                    mWakeLockScreenListener = (PluginSensor) sensor;
                } else if (sensor.mPulseReason == REASON_SENSOR_TAP) {
                    mSensorTap = sensor;
                }
            }
            mSensors = new TriggerSensor[] {mTriggerSensor, mSensorTap};
        }

        public TriggerSensor createDozeSensor(Sensor sensor, boolean settingEnabled,
                boolean requiresTouchScreen) {
            return new TriggerSensor(/* sensor */ sensor,
                    /* setting name */ "test_setting",
                    /* settingDefault */ settingEnabled,
                    /* configured */ true,
                    /* pulseReason*/ 0,
                    /* reportsTouchCoordinate*/ false,
                    requiresTouchScreen,
                    mDozeLog);
        }
    }

    public static void setSensorType(Sensor sensor, int type, String strType) throws Exception {
        Method setter = Sensor.class.getDeclaredMethod("setType", Integer.TYPE);
        setter.setAccessible(true);
        setter.invoke(sensor, type);
        if (strType != null) {
            Field f = sensor.getClass().getDeclaredField("mStringType");
            f.setAccessible(true);
            f.set(sensor, strType);
        }
    }

    public static Sensor createSensor(int type, String strType) throws Exception {
        Constructor<Sensor> constr = Sensor.class.getDeclaredConstructor();
        constr.setAccessible(true);
        Sensor sensor = constr.newInstance();
        setSensorType(sensor, type, strType);
        return sensor;
    }
}
