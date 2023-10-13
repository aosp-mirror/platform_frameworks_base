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

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;

import static com.android.systemui.doze.DozeLog.REASON_SENSOR_TAP;
import static com.android.systemui.doze.DozeLog.REASON_SENSOR_UDFPS_LONG_PRESS;
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

import android.app.ActivityManager;
import android.content.res.Resources;
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
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.wakelock.WakeLock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
    private Resources mResources;
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
    private DevicePostureController mDevicePostureController;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    @Mock
    private ProximitySensor mProximitySensor;

    // Capture listeners so that they can be used to send events
    @Captor
    private ArgumentCaptor<AuthController.Callback> mAuthControllerCallbackCaptor =
            ArgumentCaptor.forClass(AuthController.Callback.class);
    private AuthController.Callback mAuthControllerCallback;

    private FakeSettings mFakeSettings = new FakeSettings();
    private SensorManagerPlugin.SensorEventListener mWakeLockScreenListener;
    private TestableLooper mTestableLooper;
    private TestableDozeSensors mDozeSensors;
    private TriggerSensor mSensorTap;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        when(mSelectedUserInteractor.getSelectedUserId())
                .thenReturn(ActivityManager.getCurrentUser());
        when(mAmbientDisplayConfiguration.tapSensorTypeMapping())
                .thenReturn(new String[]{"tapSensor"});
        when(mAmbientDisplayConfiguration.getWakeLockScreenDebounce()).thenReturn(5000L);
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        when(mAmbientDisplayConfiguration.enabled(ActivityManager.getCurrentUser())).thenReturn(
                true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mWakeLock).wrap(any(Runnable.class));
        mDozeSensors = new TestableDozeSensors();

        verify(mAuthController).addCallback(mAuthControllerCallbackCaptor.capture());
        mAuthControllerCallback = mAuthControllerCallbackCaptor.getValue();
    }

    @Test
    public void testRegisterProx() {
        assertFalse(mProximitySensor.isRegistered());
        mDozeSensors.setProxListening(true);
        verify(mProximitySensor).resume();
    }

    @Test
    public void testSensorDebounce() {
        mDozeSensors.setListening(true, true, true);

        mWakeLockScreenListener.onSensorChanged(mock(SensorManagerPlugin.SensorEvent.class));
        mTestableLooper.processAllMessages();
        verify(mCallback).onSensorPulse(eq(DozeLog.PULSE_REASON_SENSOR_WAKE_REACH),
                anyFloat(), anyFloat(), eq(null));

        mDozeSensors.requestTemporaryDisable();
        reset(mCallback);
        mWakeLockScreenListener.onSensorChanged(mock(SensorManagerPlugin.SensorEvent.class));
        mTestableLooper.processAllMessages();
        verify(mCallback, never()).onSensorPulse(eq(DozeLog.PULSE_REASON_SENSOR_WAKE_REACH),
                anyFloat(), anyFloat(), eq(null));
    }

    @Test
    public void testSetListening_firstTrue_registerSettingsObserver() {
        verify(mSensorManager, never()).registerListener(any(), any(Sensor.class), anyInt());
        mDozeSensors.setListening(true, true, true);

        verify(mTriggerSensor).registerSettingsObserver(any(ContentObserver.class));
    }

    @Test
    public void testSetListening_twiceTrue_onlyRegisterSettingsObserverOnce() {
        verify(mSensorManager, never()).registerListener(any(), any(Sensor.class), anyInt());
        mDozeSensors.setListening(true, true, true);
        mDozeSensors.setListening(true, true, true);

        verify(mTriggerSensor, times(1)).registerSettingsObserver(any(ContentObserver.class));
    }

    @Test
    public void testDestroy() {
        mDozeSensors.destroy();

        verify(mProximitySensor).destroy();
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
        dozeSensors.setListeningWithPowerState(true, true, true, true);

        // THEN the tap sensor is registered
        assertTrue(mSensorTap.mRequested);
    }

    @Test
    public void testDozeSensorSetListening() {
        // GIVEN doze sensors enabled
        when(mAmbientDisplayConfiguration.enabled(anyInt())).thenReturn(true);

        // GIVEN a trigger sensor that's enabled by settings
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensorWithSettingEnabled(
                mockSensor,
                /* settingEnabled */ true
        );
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

        // GIVEN a trigger sensor that's not enabled by settings
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensorWithSettingEnabled(
                mockSensor,
                /* settingEnabled*/ false
        );
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

        // GIVEN a trigger sensor that's not enabled by settings
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensorWithSettingEnabled(
                mockSensor,
                /* settingEnabled*/ false
        );
        when(mSensorManager.requestTriggerSensor(eq(triggerSensor), eq(mockSensor)))
                .thenReturn(true);

        // GIVEN sensor is listening
        triggerSensor.setListening(true);

        // WHEN ignoreSetting is called
        triggerSensor.ignoreSetting(true);

        // THEN the sensor is still registered since the setting is ignore
        assertTrue(triggerSensor.mRegistered);
    }

    @Test
    public void testUpdateListeningAfterAlreadyRegistered() {
        // GIVEN doze sensors enabled
        when(mAmbientDisplayConfiguration.enabled(anyInt())).thenReturn(true);

        // GIVEN a trigger sensor
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensorWithSettingEnabled(
                mockSensor,
                /* settingEnabled*/ true
        );
        when(mSensorManager.requestTriggerSensor(eq(triggerSensor), eq(mockSensor)))
                .thenReturn(true);

        // WHEN setListening is called AND updateListening is called
        triggerSensor.setListening(true);
        triggerSensor.updateListening();

        // THEN the sensor is still registered
        assertTrue(triggerSensor.mRegistered);
    }

    @Test
    public void testPostureStartStateClosed_registersCorrectSensor() throws Exception {
        // GIVEN doze sensor that supports postures
        Sensor closedSensor = createSensor(Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT);
        Sensor openedSensor = createSensor(Sensor.TYPE_PROXIMITY, Sensor.STRING_TYPE_LIGHT);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensorForPosture(
                new Sensor[] {
                        null /* unknown */,
                        closedSensor,
                        null /* half-opened */,
                        openedSensor},
                DevicePostureController.DEVICE_POSTURE_CLOSED);

        // WHEN trigger sensor requests listening
        triggerSensor.setListening(true);

        // THEN the correct sensor is registered
        verify(mSensorManager).requestTriggerSensor(eq(triggerSensor), eq(closedSensor));
        verify(mSensorManager, never()).requestTriggerSensor(eq(triggerSensor), eq(openedSensor));
    }

    @Test
    public void testPostureChange_registersCorrectSensor() throws Exception {
        // GIVEN doze sensor that supports postures
        Sensor closedSensor = createSensor(Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT);
        Sensor openedSensor = createSensor(Sensor.TYPE_PROXIMITY, Sensor.STRING_TYPE_LIGHT);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensorForPosture(
                new Sensor[] {
                        null /* unknown */,
                        closedSensor,
                        null /* half-opened */,
                        openedSensor},
                DevicePostureController.DEVICE_POSTURE_CLOSED);

        // GIVEN sensor is listening
        when(mSensorManager.requestTriggerSensor(any(), any())).thenReturn(true);
        triggerSensor.setListening(true);
        reset(mSensorManager);
        assertTrue(triggerSensor.mRegistered);

        // WHEN posture changes
        boolean sensorChanged =
                triggerSensor.setPosture(DevicePostureController.DEVICE_POSTURE_OPENED);

        // THEN the correct sensor is registered
        assertTrue(sensorChanged);
        verify(mSensorManager).requestTriggerSensor(eq(triggerSensor), eq(openedSensor));
        verify(mSensorManager, never()).requestTriggerSensor(eq(triggerSensor), eq(closedSensor));
    }

    @Test
    public void testPostureChange_noSensorChange() throws Exception {
        // GIVEN doze sensor that supports postures
        Sensor closedSensor = createSensor(Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT);
        Sensor openedSensor = createSensor(Sensor.TYPE_PROXIMITY, Sensor.STRING_TYPE_LIGHT);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensorForPosture(
                new Sensor[] {
                        null /* unknown */,
                        closedSensor,
                        openedSensor /* half-opened uses the same sensor as opened*/,
                        openedSensor},
                DevicePostureController.DEVICE_POSTURE_HALF_OPENED);

        // GIVEN sensor is listening
        when(mSensorManager.requestTriggerSensor(any(), any())).thenReturn(true);
        triggerSensor.setListening(true);
        reset(mSensorManager);

        // WHEN posture changes
        boolean sensorChanged =
                triggerSensor.setPosture(DevicePostureController.DEVICE_POSTURE_OPENED);

        // THEN no change in sensor
        assertFalse(sensorChanged);
        verify(mSensorManager, never()).requestTriggerSensor(eq(triggerSensor), any());
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

    @Test
    public void testUdfpsEnrollmentChanged() throws Exception {
        // GIVEN a UDFPS_LONG_PRESS trigger sensor that's not configured
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor triggerSensor = mDozeSensors.createDozeSensorForPosture(
                mockSensor,
                REASON_SENSOR_UDFPS_LONG_PRESS,
                /* configured */ false);
        mDozeSensors.addSensor(triggerSensor);
        when(mSensorManager.requestTriggerSensor(eq(triggerSensor), eq(mockSensor)))
                .thenReturn(true);

        // WHEN listening state is set to TRUE
        mDozeSensors.setListening(true, true, true);

        // THEN mRegistered is still false b/c !mConfigured
        assertFalse(triggerSensor.mConfigured);
        assertFalse(triggerSensor.mRegistered);

        // WHEN enrollment changes to TRUE
        when(mAuthController.isUdfpsEnrolled(anyInt())).thenReturn(true);
        mAuthControllerCallback.onEnrollmentsChanged(TYPE_FINGERPRINT);

        // THEN mConfigured = TRUE
        assertTrue(triggerSensor.mConfigured);

        // THEN mRegistered = TRUE
        assertTrue(triggerSensor.mRegistered);
    }

    @Test
    public void testGesturesAllInitiallyRespectSettings() {
        DozeSensors dozeSensors = new DozeSensors(mResources, mSensorManager, mDozeParameters,
                mAmbientDisplayConfiguration, mWakeLock, mCallback, mProxCallback, mDozeLog,
                mProximitySensor, mFakeSettings, mAuthController,
                mDevicePostureController, mSelectedUserInteractor);

        for (TriggerSensor sensor : dozeSensors.mTriggerSensors) {
            assertFalse(sensor.mIgnoresSetting);
        }
    }

    @Test
    public void aodOnlySensor_onlyRegisteredWhenAodSensorsIncluded() {
        // GIVEN doze sensors enabled
        when(mAmbientDisplayConfiguration.enabled(anyInt())).thenReturn(true);

        // GIVEN a trigger sensor that requires aod
        Sensor mockSensor = mock(Sensor.class);
        TriggerSensor aodOnlyTriggerSensor = mDozeSensors.createDozeSensorRequiringAod(mockSensor);
        when(mSensorManager.requestTriggerSensor(eq(aodOnlyTriggerSensor), eq(mockSensor)))
                .thenReturn(true);
        mDozeSensors.addSensor(aodOnlyTriggerSensor);

        // WHEN aod only sensors aren't included
        mDozeSensors.setListening(/* listen */ true, /* includeTouchScreenSensors */true,
                /* includeAodOnlySensors */false);

        // THEN the sensor is not registered or requested
        assertFalse(aodOnlyTriggerSensor.mRequested);
        assertFalse(aodOnlyTriggerSensor.mRegistered);

        // WHEN aod only sensors ARE included
        mDozeSensors.setListening(/* listen */ true, /* includeTouchScreenSensors */true,
                /* includeAodOnlySensors */true);

        // THEN the sensor is registered and requested
        assertTrue(aodOnlyTriggerSensor.mRequested);
        assertTrue(aodOnlyTriggerSensor.mRegistered);
    }

    @Test
    public void liftToWake_defaultSetting_configDefaultFalse() {
        // WHEN the default lift to wake gesture setting is false
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_dozePickupGestureEnabled)).thenReturn(false);

        DozeSensors dozeSensors = new DozeSensors(mResources, mSensorManager, mDozeParameters,
                mAmbientDisplayConfiguration, mWakeLock, mCallback, mProxCallback, mDozeLog,
                mProximitySensor, mFakeSettings, mAuthController,
                mDevicePostureController, mSelectedUserInteractor);

        for (TriggerSensor sensor : dozeSensors.mTriggerSensors) {
            // THEN lift to wake's TriggerSensor enabledBySettings is false
            if (sensor.mPulseReason == DozeLog.REASON_SENSOR_PICKUP) {
                assertFalse(sensor.enabledBySetting());
            }
        }
    }

    @Test
    public void liftToWake_defaultSetting_configDefaultTrue() {
        // WHEN the default lift to wake gesture setting is true
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_dozePickupGestureEnabled)).thenReturn(true);

        DozeSensors dozeSensors = new DozeSensors(mResources, mSensorManager, mDozeParameters,
                mAmbientDisplayConfiguration, mWakeLock, mCallback, mProxCallback, mDozeLog,
                mProximitySensor, mFakeSettings, mAuthController,
                mDevicePostureController, mSelectedUserInteractor);

        for (TriggerSensor sensor : dozeSensors.mTriggerSensors) {
            // THEN lift to wake's TriggerSensor enabledBySettings is true
            if (sensor.mPulseReason == DozeLog.REASON_SENSOR_PICKUP) {
                assertTrue(sensor.enabledBySetting());
            }
        }
    }

    private class TestableDozeSensors extends DozeSensors {
        TestableDozeSensors() {
            super(mResources, mSensorManager, mDozeParameters,
                    mAmbientDisplayConfiguration, mWakeLock, mCallback, mProxCallback, mDozeLog,
                    mProximitySensor, mFakeSettings, mAuthController,
                    mDevicePostureController, mSelectedUserInteractor);
            for (TriggerSensor sensor : mTriggerSensors) {
                if (sensor instanceof PluginSensor
                        && ((PluginSensor) sensor).mPluginSensor.getType()
                        == TYPE_WAKE_LOCK_SCREEN) {
                    mWakeLockScreenListener = (PluginSensor) sensor;
                } else if (sensor.mPulseReason == REASON_SENSOR_TAP) {
                    mSensorTap = sensor;
                }
            }
            mTriggerSensors = new TriggerSensor[] {mTriggerSensor, mSensorTap};
        }

        public TriggerSensor createDozeSensorWithSettingEnabled(Sensor sensor,
                boolean settingEnabled) {
            return new TriggerSensor(/* sensor */ sensor,
                    /* setting name */ "test_setting",
                    /* settingDefault */ settingEnabled,
                    /* configured */ true,
                    /* pulseReason*/ 0,
                    /* reportsTouchCoordinate*/ false,
                    /* requiresTouchscreen */ false,
                    /* ignoresSetting */ false,
                    /* requiresProx */ false,
                    /* immediatelyReRegister */ true,
                    /* requiresAod */false
            );
        }

        public TriggerSensor createDozeSensorForPosture(
                Sensor sensor,
                int pulseReason,
                boolean configured
        ) {
            return new TriggerSensor(/* sensor */ sensor,
                    /* setting name */ "test_setting",
                    /* settingDefault */ true,
                    /* configured */ configured,
                    /* pulseReason*/ pulseReason,
                    /* reportsTouchCoordinate*/ false,
                    /* requiresTouchscreen */ false,
                    /* ignoresSetting */ false,
                    /* requiresTouchScreen */ false,
                    /* immediatelyReRegister*/ true,
                    false
            );
        }

        /**
         * Create a doze sensor that requires Aod
         */
        public TriggerSensor createDozeSensorRequiringAod(Sensor sensor) {
            return new TriggerSensor(/* sensor */ sensor,
                    /* setting name */ "aod_requiring_sensor",
                    /* settingDefault */ true,
                    /* configured */ true,
                    /* pulseReason*/ 0,
                    /* reportsTouchCoordinate*/ false,
                    /* requiresTouchscreen */ false,
                    /* ignoresSetting */ false,
                    /* requiresProx */ false,
                    /* immediatelyReRegister */ true,
                    /* requiresAoD */ true
            );
        }

        /**
         * Create a doze sensor that supports postures and is enabled
         */
        public TriggerSensor createDozeSensorForPosture(Sensor[] sensors, int posture) {
            return new TriggerSensor(/* sensor */ sensors,
                    /* setting name */ "posture_test_setting",
                    /* settingDefault */ true,
                    /* configured */ true,
                    /* pulseReason*/ 0,
                    /* reportsTouchCoordinate*/ false,
                    /* requiresTouchscreen */ false,
                    /* ignoresSetting */ true,
                    /* requiresProx */ false,
                    /* immediatelyReRegister */ true,
                    posture,
                    /* requiresUi */ false
            );
        }

        public void addSensor(TriggerSensor sensor) {
            TriggerSensor[] newArray = new TriggerSensor[mTriggerSensors.length + 1];
            for (int i = 0; i < mTriggerSensors.length; i++) {
                newArray[i] = mTriggerSensors[i];
            }
            newArray[mTriggerSensors.length] = sensor;
            mTriggerSensors = newArray;
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
