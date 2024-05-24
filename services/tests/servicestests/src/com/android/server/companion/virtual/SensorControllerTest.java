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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.sensor.IVirtualSensorCallback;
import android.companion.virtual.sensor.VirtualSensor;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.companion.virtual.sensor.VirtualSensorEvent;
import android.content.AttributionSource;
import android.hardware.Sensor;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.server.LocalServices;
import com.android.server.sensors.SensorManagerInternal;

import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class SensorControllerTest {

    private static final int VIRTUAL_DEVICE_ID = 42;
    private static final String VIRTUAL_SENSOR_NAME = "VirtualAccelerometer";
    private static final int SENSOR_HANDLE = 7;

    private static final int VIRTUAL_SENSOR_TYPE = Sensor.TYPE_ACCELEROMETER;

    @Mock
    private SensorManagerInternal mSensorManagerInternalMock;
    @Mock
    private IVirtualSensorCallback mVirtualSensorCallback;
    @Mock
    private IVirtualDevice mVirtualDevice;

    private VirtualSensorEvent mSensorEvent;
    private VirtualSensorConfig mVirtualSensorConfig;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(SensorManagerInternal.class);
        LocalServices.addService(SensorManagerInternal.class, mSensorManagerInternalMock);

        mSensorEvent = new VirtualSensorEvent.Builder(new float[] { 1f, 2f, 3f}).build();
        mVirtualSensorConfig =
                new VirtualSensorConfig.Builder(VIRTUAL_SENSOR_TYPE, VIRTUAL_SENSOR_NAME)
                        .build();
    }

    @Test
    public void createSensor_invalidHandle_throwsException() {
        doReturn(/* handle= */0).when(mSensorManagerInternalMock).createRuntimeSensor(
                anyInt(), anyInt(), anyString(), anyString(), anyFloat(), anyFloat(), anyFloat(),
                anyInt(), anyInt(), anyInt(), any());

        Throwable thrown = assertThrows(
                RuntimeException.class,
                () -> new SensorController(mVirtualDevice, VIRTUAL_DEVICE_ID,
                        AttributionSource.myAttributionSource(),
                        mVirtualSensorCallback, List.of(mVirtualSensorConfig)));

        assertThat(thrown.getCause().getMessage())
                .contains("Received an invalid virtual sensor handle");
    }

    @Test
    public void createSensor_success() throws Exception {
        SensorController sensorController = doCreateSensorSuccessfully();

        assertThat(sensorController.getSensorDescriptors()).isNotEmpty();
    }

    @Test
    public void getSensorByHandle_success() throws Exception {
        SensorController sensorController = doCreateSensorSuccessfully();

        VirtualSensor sensor = sensorController.getSensorByHandle(SENSOR_HANDLE);

        assertThat(sensor).isNotNull();
        assertThat(sensor.getHandle()).isEqualTo(SENSOR_HANDLE);
        assertThat(sensor.getDeviceId()).isEqualTo(VIRTUAL_DEVICE_ID);
        assertThat(sensor.getType()).isEqualTo(VIRTUAL_SENSOR_TYPE);
    }

    @Test
    public void getSensorByHandle_invalidHandle_returnsNull() throws Exception {
        SensorController sensorController = doCreateSensorSuccessfully();
        final int invalidSensorHandle = 123456;

        assertThat(sensorController.getSensorByHandle(invalidSensorHandle)).isNull();
    }

    @Test
    public void sendSensorEvent_invalidToken_throwsException() throws Exception {
        SensorController sensorController = doCreateSensorSuccessfully();

        assertThrows(
                IllegalArgumentException.class,
                () -> sensorController.sendSensorEvent(
                        new Binder("invalidSensorToken"), mSensorEvent));
    }

    @Test
    public void sendSensorEvent_success() throws Exception {
        SensorController sensorController = doCreateSensorSuccessfully();

        clearInvocations(mSensorManagerInternalMock);
        IBinder token = Iterables.getOnlyElement(sensorController.getSensorDescriptors().keySet());

        sensorController.sendSensorEvent(token, mSensorEvent);
        verify(mSensorManagerInternalMock).sendSensorEvent(
                SENSOR_HANDLE, Sensor.TYPE_ACCELEROMETER, mSensorEvent.getTimestampNanos(),
                mSensorEvent.getValues());
    }

    @Test
    public void close_unregistersSensors() throws Exception {
        SensorController sensorController = doCreateSensorSuccessfully();

        sensorController.close();
        verify(mSensorManagerInternalMock).removeRuntimeSensor(SENSOR_HANDLE);
        assertThat(sensorController.getSensorDescriptors()).isEmpty();
    }

    private SensorController doCreateSensorSuccessfully() throws RemoteException {
        doReturn(SENSOR_HANDLE).when(mSensorManagerInternalMock).createRuntimeSensor(
                anyInt(), anyInt(), anyString(), anyString(), anyFloat(), anyFloat(), anyFloat(),
                anyInt(), anyInt(), anyInt(), any());
        doReturn(VIRTUAL_DEVICE_ID).when(mVirtualDevice).getDeviceId();

        SensorController sensorController = new SensorController(mVirtualDevice, VIRTUAL_DEVICE_ID,
                AttributionSource.myAttributionSource(),
                mVirtualSensorCallback, List.of(mVirtualSensorConfig));

        List<VirtualSensor> sensors = sensorController.getSensorList();
        assertThat(sensors).hasSize(1);
        assertThat(sensors.get(0).getHandle()).isEqualTo(SENSOR_HANDLE);
        return sensorController;
    }
}
