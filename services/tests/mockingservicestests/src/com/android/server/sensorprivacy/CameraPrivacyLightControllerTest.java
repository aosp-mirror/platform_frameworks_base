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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyZeroInteractions;

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
import android.testing.TestableLooper;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class CameraPrivacyLightControllerTest {
    private int[] mDefaultColors = {0, 1, 2};
    private int[] mDefaultAlsThresholdsLux = {10, 50};
    private int mDefaultAlsAveragingIntervalMillis = 5000;

    private TestableLooper mTestableLooper;

    private MockitoSession mMockitoSession;

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

    private Set<String> mExemptedPackages;
    private List<Light> mLights;

    private int mNextLightId = 1;

    public CameraPrivacyLightController prepareDefaultCameraPrivacyLightController() {
        return prepareDefaultCameraPrivacyLightController(List.of(getNextLight(true)));
    }

    public CameraPrivacyLightController prepareDefaultCameraPrivacyLightController(
            List<Light> lights) {
        return prepareCameraPrivacyLightController(lights, Set.of(), true, mDefaultColors,
                mDefaultAlsThresholdsLux, mDefaultAlsAveragingIntervalMillis);
    }

    public CameraPrivacyLightController prepareCameraPrivacyLightController(List<Light> lights,
            Set<String> exemptedPackages, boolean hasLightSensor, int[] lightColors,
            int[] alsThresholds, int averagingInterval) {
        Looper looper = Looper.myLooper();
        if (looper == null) {
            Looper.prepare();
            looper = Looper.myLooper();
        }
        if (mTestableLooper == null) {
            try {
                mTestableLooper = new TestableLooper(looper);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        Context context = mock(Context.class);
        Resources resources = mock(Resources.class);
        doReturn(resources).when(context).getResources();
        doReturn(lightColors).when(resources).getIntArray(R.array.config_cameraPrivacyLightColors);
        doReturn(alsThresholds).when(resources)
                .getIntArray(R.array.config_cameraPrivacyLightAlsLuxThresholds);
        doReturn(averagingInterval).when(resources)
                .getInteger(R.integer.config_cameraPrivacyLightAlsAveragingIntervalMillis);

        doReturn(mLightsManager).when(context).getSystemService(LightsManager.class);
        doReturn(mAppOpsManager).when(context).getSystemService(AppOpsManager.class);
        doReturn(mSensorManager).when(context).getSystemService(SensorManager.class);

        mLights = lights;
        mExemptedPackages = exemptedPackages;
        doReturn(mLights).when(mLightsManager).getLights();
        doReturn(mLightsSession).when(mLightsManager).openSession(anyInt());
        if (!hasLightSensor) {
            mLightSensor = null;
        }
        doReturn(mLightSensor).when(mSensorManager).getDefaultSensor(Sensor.TYPE_LIGHT);
        doReturn(exemptedPackages)
                .when(() -> PermissionManager.getIndicatorExemptedPackages(any()));

        return new CameraPrivacyLightController(context, looper);
    }

    @Before
    public void setUp() {
        mMockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(PermissionManager.class)
                .startMocking();
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testNoInteractionsWithServicesIfNoColorsSpecified() {
        prepareCameraPrivacyLightController(List.of(getNextLight(true)), Collections.EMPTY_SET,
                true, new int[0], mDefaultAlsThresholdsLux, mDefaultAlsAveragingIntervalMillis);

        verifyZeroInteractions(mLightsManager);
        verifyZeroInteractions(mAppOpsManager);
        verifyZeroInteractions(mSensorManager);
    }

    @Test
    public void testAppsOpsListenerNotRegisteredWithoutCameraLights() {
        prepareDefaultCameraPrivacyLightController(List.of(getNextLight(false)));

        verify(mAppOpsManager, times(0)).startWatchingActive(any(), any(), any());
    }

    @Test
    public void testAppsOpsListenerRegisteredWithCameraLight() {
        prepareDefaultCameraPrivacyLightController();

        verify(mAppOpsManager, times(1)).startWatchingActive(any(), any(), any());
    }

    @Test
    public void testAllCameraLightsAreRequestedOnOpActive() {
        Random r = new Random(0);
        List<Light> lights = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lights.add(getNextLight(r.nextBoolean()));
        }

        prepareDefaultCameraPrivacyLightController(lights);

        // Verify no session has been opened at this point.
        verify(mLightsManager, times(0)).openSession(anyInt());

        // Set camera op as active.
        openCamera();

        // Verify session has been opened exactly once
        verify(mLightsManager, times(1)).openSession(anyInt());

        verify(mLightsSession).requestLights(mLightsRequestCaptor.capture());

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
        prepareDefaultCameraPrivacyLightController();
        notifyCamOpChanged(10101, "pkg1", true);
        verify(mLightsManager, times(1)).openSession(anyInt());
        notifyCamOpChanged(10102, "pkg2", true);
        verify(mLightsManager, times(1)).openSession(anyInt());
    }

    @Test
    public void testWillCloseOnFinishOp() {
        prepareDefaultCameraPrivacyLightController();
        notifyCamOpChanged(10101, "pkg1", true);
        verify(mLightsSession, times(0)).close();
        notifyCamOpChanged(10101, "pkg1", false);
        verify(mLightsSession, times(1)).close();
    }

    @Test
    public void testWillCloseOnFinishOpForAllPackages() {
        prepareDefaultCameraPrivacyLightController();

        int numUids = 100;
        List<Integer> uids = new ArrayList<>(numUids);
        for (int i = 0; i < numUids; i++) {
            uids.add(10001 + i);
        }

        for (int i = 0; i < numUids; i++) {
            notifyCamOpChanged(uids.get(i), "pkg" + (int) uids.get(i), true);
        }

        // Change the order which their ops are finished
        Collections.shuffle(uids, new Random(0));

        for (int i = 0; i < numUids - 1; i++) {
            notifyCamOpChanged(uids.get(i), "pkg" + (int) uids.get(i), false);
        }

        verify(mLightsSession, times(0)).close();
        int lastUid = uids.get(numUids - 1);
        notifyCamOpChanged(lastUid, "pkg" + lastUid, false);
        verify(mLightsSession, times(1)).close();
    }

    @Test
    public void testWontOpenForExemptedPackage() {
        String exemptPackage = "pkg1";
        prepareCameraPrivacyLightController(List.of(getNextLight(true)),
                Set.of(exemptPackage), true, mDefaultColors, mDefaultAlsThresholdsLux,
                mDefaultAlsAveragingIntervalMillis);

        notifyCamOpChanged(10101, exemptPackage, true);
        verify(mLightsManager, times(0)).openSession(anyInt());
    }

    @Test
    public void testNoLightSensor() {
        prepareCameraPrivacyLightController(List.of(getNextLight(true)),
                Set.of(), true, mDefaultColors, mDefaultAlsThresholdsLux,
                mDefaultAlsAveragingIntervalMillis);

        openCamera();

        verify(mLightsSession).requestLights(mLightsRequestCaptor.capture());
        LightsRequest lightsRequest = mLightsRequestCaptor.getValue();
        for (LightState lightState : lightsRequest.getLightStates()) {
            assertEquals(mDefaultColors[mDefaultColors.length - 1], lightState.getColor());
        }
    }

    @Test
    public void testALSListenerNotRegisteredUntilCameraIsOpened() {
        prepareDefaultCameraPrivacyLightController();

        verify(mSensorManager, never()).registerListener(any(SensorEventListener.class),
                any(Sensor.class), anyInt(), any(Handler.class));

        openCamera();

        verify(mSensorManager, times(1)).registerListener(mLightSensorListenerCaptor.capture(),
                any(Sensor.class), anyInt(), any(Handler.class));

        notifyCamOpChanged(10001, "pkg", false);
        verify(mSensorManager, times(1)).unregisterListener(mLightSensorListenerCaptor.getValue());
    }

    @Test
    public void testAlsThresholds() {
        CameraPrivacyLightController cplc = prepareDefaultCameraPrivacyLightController();
        long elapsedTime = 0;
        cplc.setElapsedRealTime(0);
        openCamera();
        for (int i = 0; i < mDefaultColors.length; i++) {
            int expectedColor = mDefaultColors[i];
            int alsLuxValue = i
                    == mDefaultAlsThresholdsLux.length
                    ? mDefaultAlsThresholdsLux[i - 1] : mDefaultAlsThresholdsLux[i] - 1;

            notifySensorEvent(cplc, elapsedTime, alsLuxValue);
            elapsedTime += mDefaultAlsAveragingIntervalMillis + 1;
            notifySensorEvent(cplc, elapsedTime, alsLuxValue);

            verify(mLightsSession, atLeastOnce()).requestLights(mLightsRequestCaptor.capture());
            for (LightState lightState : mLightsRequestCaptor.getValue().getLightStates()) {
                assertEquals(expectedColor, lightState.getColor());
            }
        }
    }

    private void notifyCamOpChanged(int uid, String pkg, boolean active) {
        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());
        mAppOpsListenerCaptor.getValue().onOpActiveChanged(OPSTR_CAMERA, uid, pkg, active);
    }

    private void notifySensorEvent(CameraPrivacyLightController cplc, long timestamp, int value) {
        cplc.setElapsedRealTime(timestamp);
        verify(mSensorManager, atLeastOnce()).registerListener(mLightSensorListenerCaptor.capture(),
                        eq(mLightSensor), anyInt(), any());
        mLightSensorListenerCaptor.getValue().onSensorChanged(new SensorEvent(mLightSensor, 0,
                TimeUnit.MILLISECONDS.toNanos(timestamp), new float[] {value}));
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

    private void openCamera() {
        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());
        mAppOpsListenerCaptor.getValue().onOpActiveChanged(OPSTR_CAMERA, 10001, "pkg", true);
    }
}
