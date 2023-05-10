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

package com.android.server.companion.virtual;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.companion.virtual.sensor.IVirtualSensorCallback;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.companion.virtual.sensor.VirtualSensorEvent;
import android.hardware.Sensor;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.server.LocalServices;
import com.android.server.sensors.SensorManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class SensorControllerTest {

    private static final int VIRTUAL_DEVICE_ID = 42;
    private static final String VIRTUAL_SENSOR_NAME = "VirtualAccelerometer";
    private static final int SENSOR_HANDLE = 7;

    @Mock
    private SensorManagerInternal mSensorManagerInternalMock;
    @Mock
    private IVirtualSensorCallback mVirtualSensorCallback;
    private SensorController mSensorController;
    private VirtualSensorEvent mSensorEvent;
    private VirtualSensorConfig mVirtualSensorConfig;
    private IBinder mSensorToken;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(SensorManagerInternal.class);
        LocalServices.addService(SensorManagerInternal.class, mSensorManagerInternalMock);

        mSensorController = new SensorController(VIRTUAL_DEVICE_ID, mVirtualSensorCallback);
        mSensorEvent = new VirtualSensorEvent.Builder(new float[] { 1f, 2f, 3f}).build();
        mVirtualSensorConfig =
                new VirtualSensorConfig.Builder(Sensor.TYPE_ACCELEROMETER, VIRTUAL_SENSOR_NAME)
                        .build();
        mSensorToken = new Binder("sensorToken");
    }

    @Test
    public void createSensor_invalidHandle_throwsException() {
        doReturn(/* handle= */0).when(mSensorManagerInternalMock).createRuntimeSensor(
                anyInt(), anyInt(), anyString(), anyString(), anyFloat(), anyFloat(), anyFloat(),
                anyInt(), anyInt(), anyInt(), any());

        Throwable thrown = assertThrows(
                RuntimeException.class,
                () -> mSensorController.createSensor(mSensorToken, mVirtualSensorConfig));

        assertThat(thrown.getCause().getMessage())
                .contains("Received an invalid virtual sensor handle");
    }

    @Test
    public void createSensor_success() {
        doCreateSensorSuccessfully();

        assertThat(mSensorController.getSensorDescriptors()).isNotEmpty();
    }

    @Test
    public void sendSensorEvent_invalidToken_throwsException() {
        doCreateSensorSuccessfully();

        assertThrows(
                IllegalArgumentException.class,
                () -> mSensorController.sendSensorEvent(
                        new Binder("invalidSensorToken"), mSensorEvent));
    }

    @Test
    public void sendSensorEvent_success() {
        doCreateSensorSuccessfully();

        mSensorController.sendSensorEvent(mSensorToken, mSensorEvent);
        verify(mSensorManagerInternalMock).sendSensorEvent(
                SENSOR_HANDLE, Sensor.TYPE_ACCELEROMETER, mSensorEvent.getTimestampNanos(),
                mSensorEvent.getValues());
    }

    @Test
    public void unregisterSensor_invalidToken_throwsException() {
        doCreateSensorSuccessfully();

        assertThrows(
                IllegalArgumentException.class,
                () -> mSensorController.unregisterSensor(new Binder("invalidSensorToken")));
    }

    @Test
    public void unregisterSensor_success() {
        doCreateSensorSuccessfully();

        mSensorController.unregisterSensor(mSensorToken);
        verify(mSensorManagerInternalMock).removeRuntimeSensor(SENSOR_HANDLE);
        assertThat(mSensorController.getSensorDescriptors()).isEmpty();
    }

    private void doCreateSensorSuccessfully() {
        doReturn(SENSOR_HANDLE).when(mSensorManagerInternalMock).createRuntimeSensor(
                anyInt(), anyInt(), anyString(), anyString(), anyFloat(), anyFloat(), anyFloat(),
                anyInt(), anyInt(), anyInt(), any());
        assertThat(mSensorController.createSensor(mSensorToken, mVirtualSensorConfig))
                .isEqualTo(SENSOR_HANDLE);
    }
}
