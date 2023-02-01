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

package com.android.server.sensorprivacy;

import static android.app.AppOpsManager.OPSTR_CAMERA;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.os.Handler;
import android.os.Looper;
import android.permission.PermissionManager;
import android.util.ArraySet;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public class CameraPrivacyLightControllerTest {

    private int mDayColor = 1;
    private int mNightColor = 0;
    private int mCameraPrivacyLightAlsAveragingIntervalMillis = 5000;
    private int mCameraPrivacyLightAlsNightThreshold = (int) getLightSensorValue(15);

    private MockitoSession mMockitoSession;

    @Mock
    private Context mContext;

    @Mock
    private Resources mResources;

    @Mock
    private LightsManager mLightsManager;

    @Mock
    private AppOpsManager mAppOpsManager;

    @Mock
    private SensorManager mSensorManager;

    @Mock
    private LightsManager.LightsSession mLightsSession;

    @Mock
    private Sensor mLightSensor;

    private ArgumentCaptor<AppOpsManager.OnOpActiveChangedListener> mAppOpsListenerCaptor =
            ArgumentCaptor.forClass(AppOpsManager.OnOpActiveChangedListener.class);

    private ArgumentCaptor<LightsRequest> mLightsRequestCaptor =
            ArgumentCaptor.forClass(LightsRequest.class);

    private ArgumentCaptor<SensorEventListener> mLightSensorListenerCaptor =
            ArgumentCaptor.forClass(SensorEventListener.class);

    private Set<String> mExemptedPackages = new ArraySet<>();
    private List<Light> mLights = new ArrayList<>();

    private int mNextLightId = 1;

    @Before
    public void setUp() {
        mMockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(PermissionManager.class)
                .startMocking();

        doReturn(mDayColor).when(mContext).getColor(R.color.camera_privacy_light_day);
        doReturn(mNightColor).when(mContext).getColor(R.color.camera_privacy_light_night);

        doReturn(mResources).when(mContext).getResources();
        doReturn(mCameraPrivacyLightAlsAveragingIntervalMillis).when(mResources)
                .getInteger(R.integer.config_cameraPrivacyLightAlsAveragingIntervalMillis);
        doReturn(mCameraPrivacyLightAlsNightThreshold).when(mResources)
                .getInteger(R.integer.config_cameraPrivacyLightAlsNightThreshold);

        doReturn(mLightsManager).when(mContext).getSystemService(LightsManager.class);
        doReturn(mAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);
        doReturn(mSensorManager).when(mContext).getSystemService(SensorManager.class);

        doReturn(mLights).when(mLightsManager).getLights();
        doReturn(mLightsSession).when(mLightsManager).openSession(anyInt());
        doReturn(mLightSensor).when(mSensorManager).getDefaultSensor(Sensor.TYPE_LIGHT);

        doReturn(mExemptedPackages)
                .when(() -> PermissionManager.getIndicatorExemptedPackages(any()));
    }

    @After
    public void tearDown() {
        mExemptedPackages.clear();
        mLights.clear();

        mMockitoSession.finishMocking();
    }

    @Test
    public void testAppsOpsListenerNotRegisteredWithoutCameraLights() {
        mLights.add(getNextLight(false));
        createCameraPrivacyLightController();

        verify(mAppOpsManager, times(0)).startWatchingActive(any(), any(), any());
    }

    @Test
    public void testAppsOpsListenerRegisteredWithCameraLight() {
        mLights.add(getNextLight(true));

        createCameraPrivacyLightController();

        verify(mAppOpsManager, times(1)).startWatchingActive(any(), any(), any());
    }

    @Test
    public void testAllCameraLightsAreRequestedOnOpActive() {
        Random r = new Random(0);
        for (int i = 0; i < 30; i++) {
            mLights.add(getNextLight(r.nextBoolean()));
        }

        createCameraPrivacyLightController();

        // Verify no session has been opened at this point.
        verify(mLightsManager, times(0)).openSession(anyInt());

        // Set camera op as active.
        openCamera();

        // Verify session has been opened exactly once
        verify(mLightsManager, times(1)).openSession(anyInt());

        verify(mLightsSession).requestLights(mLightsRequestCaptor.capture());
        assertEquals("requestLights() not invoked exactly once",
                1, mLightsRequestCaptor.getAllValues().size());

        List<Integer> expectedCameraLightIds = mLights.stream()
                .filter(l -> l.getType() == Light.LIGHT_TYPE_CAMERA)
                .map(l -> l.getId())
                .collect(Collectors.toList());
        List<Integer> lightsRequestLightIds = mLightsRequestCaptor.getValue().getLights();

        // We don't own lights framework, don't assume it will retain order
        lightsRequestLightIds.sort(Integer::compare);
        expectedCameraLightIds.sort(Integer::compare);

        assertEquals(expectedCameraLightIds, lightsRequestLightIds);
    }

    @Test
    public void testWillOnlyOpenOnceWhenTwoPackagesStartOp() {
        mLights.add(getNextLight(true));

        createCameraPrivacyLightController();

        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());

        AppOpsManager.OnOpActiveChangedListener listener = mAppOpsListenerCaptor.getValue();
        listener.onOpActiveChanged(OPSTR_CAMERA, 10101, "pkg1", true);
        verify(mLightsManager, times(1)).openSession(anyInt());
        listener.onOpActiveChanged(OPSTR_CAMERA, 10102, "pkg2", true);
        verify(mLightsManager, times(1)).openSession(anyInt());
    }

    @Test
    public void testWillCloseOnFinishOp() {
        mLights.add(getNextLight(true));

        createCameraPrivacyLightController();

        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());

        AppOpsManager.OnOpActiveChangedListener listener = mAppOpsListenerCaptor.getValue();
        listener.onOpActiveChanged(OPSTR_CAMERA, 10101, "pkg1", true);

        verify(mLightsSession, times(0)).close();
        listener.onOpActiveChanged(OPSTR_CAMERA, 10101, "pkg1", false);
        verify(mLightsSession, times(1)).close();
    }

    @Test
    public void testWillCloseOnFinishOpForAllPackages() {
        mLights.add(getNextLight(true));

        createCameraPrivacyLightController();

        int numUids = 100;
        List<Integer> uids = new ArrayList<>(numUids);
        for (int i = 0; i < numUids; i++) {
            uids.add(10001 + i);
        }

        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());

        AppOpsManager.OnOpActiveChangedListener listener = mAppOpsListenerCaptor.getValue();

        for (int i = 0; i < numUids; i++) {
            listener.onOpActiveChanged(OPSTR_CAMERA, uids.get(i), "pkg" + (int) uids.get(i), true);
        }

        // Change the order which their ops are finished
        Collections.shuffle(uids, new Random(0));

        for (int i = 0; i < numUids - 1; i++) {
            listener.onOpActiveChanged(OPSTR_CAMERA, uids.get(i), "pkg" + (int) uids.get(i), false);
        }

        verify(mLightsSession, times(0)).close();
        int lastUid = uids.get(numUids - 1);
        listener.onOpActiveChanged(OPSTR_CAMERA, lastUid, "pkg" + lastUid, false);
        verify(mLightsSession, times(1)).close();
    }

    @Test
    public void testWontOpenForExemptedPackage() {
        mLights.add(getNextLight(true));
        mExemptedPackages.add("pkg1");

        createCameraPrivacyLightController();

        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());

        AppOpsManager.OnOpActiveChangedListener listener = mAppOpsListenerCaptor.getValue();
        listener.onOpActiveChanged(OPSTR_CAMERA, 10101, "pkg1", true);
        verify(mLightsManager, times(0)).openSession(anyInt());
    }

    @Test
    public void testNoLightSensor() {
        mLights.add(getNextLight(true));
        doReturn(null).when(mSensorManager).getDefaultSensor(Sensor.TYPE_LIGHT);

        createCameraPrivacyLightController();

        openCamera();

        verify(mLightsSession).requestLights(mLightsRequestCaptor.capture());
        LightsRequest lightsRequest = mLightsRequestCaptor.getValue();
        for (LightState lightState : lightsRequest.getLightStates()) {
            assertEquals(mDayColor, lightState.getColor());
        }
    }

    @Test
    public void testALSListenerNotRegisteredUntilCameraIsOpened() {
        mLights.add(getNextLight(true));
        Sensor sensor = mock(Sensor.class);
        doReturn(sensor).when(mSensorManager).getDefaultSensor(Sensor.TYPE_LIGHT);

        CameraPrivacyLightController cplc = createCameraPrivacyLightController();

        verify(mSensorManager, never()).registerListener(any(SensorEventListener.class),
                any(Sensor.class), anyInt(), any(Handler.class));

        openCamera();

        verify(mSensorManager, times(1)).registerListener(mLightSensorListenerCaptor.capture(),
                any(Sensor.class), anyInt(), any(Handler.class));

        mAppOpsListenerCaptor.getValue().onOpActiveChanged(OPSTR_CAMERA, 10001, "pkg", false);
        verify(mSensorManager, times(1)).unregisterListener(mLightSensorListenerCaptor.getValue());
    }

    @Ignore
    @Test
    public void testDayColor() {
        testBrightnessToColor(20, mDayColor);
    }

    @Ignore
    @Test
    public void testNightColor() {
        testBrightnessToColor(10, mNightColor);
    }

    private void testBrightnessToColor(int brightnessValue, int color) {
        mLights.add(getNextLight(true));
        Sensor sensor = mock(Sensor.class);
        doReturn(sensor).when(mSensorManager).getDefaultSensor(Sensor.TYPE_LIGHT);

        CameraPrivacyLightController cplc = createCameraPrivacyLightController();
        cplc.setElapsedRealTime(0);

        openCamera();

        verify(mSensorManager).registerListener(mLightSensorListenerCaptor.capture(),
                any(Sensor.class), anyInt(), any(Handler.class));
        SensorEventListener sensorListener = mLightSensorListenerCaptor.getValue();
        float[] sensorEventValues = new float[1];
        SensorEvent sensorEvent = new SensorEvent(sensor, 0, 0, sensorEventValues);

        sensorEventValues[0] = getLightSensorValue(brightnessValue);
        sensorListener.onSensorChanged(sensorEvent);

        verify(mLightsSession, atLeastOnce()).requestLights(mLightsRequestCaptor.capture());
        for (LightState lightState : mLightsRequestCaptor.getValue().getLightStates()) {
            assertEquals(color, lightState.getColor());
        }
    }

    @Ignore
    @Test
    public void testDayToNightTransistion() {
        mLights.add(getNextLight(true));
        Sensor sensor = mock(Sensor.class);
        doReturn(sensor).when(mSensorManager).getDefaultSensor(Sensor.TYPE_LIGHT);

        CameraPrivacyLightController cplc = createCameraPrivacyLightController();
        cplc.setElapsedRealTime(0);

        openCamera();
        // There will be an initial call at brightness 0
        verify(mLightsSession, times(1)).requestLights(any(LightsRequest.class));

        verify(mSensorManager).registerListener(mLightSensorListenerCaptor.capture(),
                any(Sensor.class), anyInt(), any(Handler.class));
        SensorEventListener sensorListener = mLightSensorListenerCaptor.getValue();

        onSensorEvent(cplc, sensorListener, sensor, 0, 20);

        // 5 sec avg = 20
        onSensorEvent(cplc, sensorListener, sensor, 5000, 30);

        verify(mLightsSession, times(2)).requestLights(mLightsRequestCaptor.capture());
        for (LightState lightState : mLightsRequestCaptor.getValue().getLightStates()) {
            assertEquals(mDayColor, lightState.getColor());
        }

        // 5 sec avg = 22

        onSensorEvent(cplc, sensorListener, sensor, 6000, 10);

        // 5 sec avg = 18

        onSensorEvent(cplc, sensorListener, sensor, 8000, 5);

        // Should have always been day
        verify(mLightsSession, times(2)).requestLights(mLightsRequestCaptor.capture());
        for (LightState lightState : mLightsRequestCaptor.getValue().getLightStates()) {
            assertEquals(mDayColor, lightState.getColor());
        }

        // 5 sec avg = 12

        onSensorEvent(cplc, sensorListener, sensor, 10000, 5);

        // Should now be night
        verify(mLightsSession, times(3)).requestLights(mLightsRequestCaptor.capture());
        for (LightState lightState : mLightsRequestCaptor.getValue().getLightStates()) {
            assertEquals(mNightColor, lightState.getColor());
        }
    }

    private void onSensorEvent(CameraPrivacyLightController cplc,
            SensorEventListener sensorListener, Sensor sensor, long timestamp, int value) {
        cplc.setElapsedRealTime(timestamp);
        sensorListener.onSensorChanged(new SensorEvent(sensor, 0, timestamp,
                new float[] {getLightSensorValue(value)}));
    }

    // Use the test thread so that the test is deterministic
    private CameraPrivacyLightController createCameraPrivacyLightController() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        return new CameraPrivacyLightController(mContext, Looper.myLooper());
    }

    private Light getNextLight(boolean cameraType) {
        Light light = ExtendedMockito.mock(Light.class);
        if (cameraType) {
            doReturn(Light.LIGHT_TYPE_CAMERA).when(light).getType();
        } else {
            doReturn(Light.LIGHT_TYPE_MICROPHONE).when(light).getType();
        }
        doReturn(mNextLightId++).when(light).getId();
        return light;
    }

    private float getLightSensorValue(int i) {
        return (float) Math.exp(i / CameraPrivacyLightController.LIGHT_VALUE_MULTIPLIER);
    }

    private void openCamera() {
        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());
        mAppOpsListenerCaptor.getValue().onOpActiveChanged(OPSTR_CAMERA, 10001, "pkg", true);
    }
}
