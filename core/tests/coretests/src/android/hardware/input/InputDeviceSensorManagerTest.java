/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.hardware.input;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.view.InputDevice;

import androidx.test.InstrumentationRegistry;

import com.android.internal.annotations.GuardedBy;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link InputDeviceSensorManager}.
 *
 * Build/Install/Run:
 * atest FrameworksCoreTests:InputDeviceSensorManagerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class InputDeviceSensorManagerTest {
    private static final String TAG = "InputDeviceSensorManagerTest";

    private static final int DEVICE_ID = 1000;

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;
    private InputManager mInputManager;
    private InputDeviceSensorManager mSensorManager;
    private IInputSensorEventListener mIInputSensorEventListener;
    private final Object mLock = new Object();

    @Mock private IInputManager mIInputManagerMock;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        InputManager inputManager = InputManager.resetInstance(mIInputManagerMock);

        when(mContextSpy.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager);

        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{DEVICE_ID});

        when(mIInputManagerMock.getInputDevice(eq(DEVICE_ID))).thenReturn(
                createInputDeviceWithSensor(DEVICE_ID));

        when(mIInputManagerMock.getSensorList(eq(DEVICE_ID))).thenReturn(new InputSensorInfo[] {
                createInputSensorInfo(DEVICE_ID, Sensor.TYPE_ACCELEROMETER),
                createInputSensorInfo(DEVICE_ID, Sensor.TYPE_GYROSCOPE)});

        when(mIInputManagerMock.enableSensor(eq(DEVICE_ID), anyInt(), anyInt(), anyInt()))
                .thenReturn(true);

        when(mIInputManagerMock.registerSensorListener(any())).thenReturn(true);

        mInputManager = mContextSpy.getSystemService(InputManager.class);
    }

    @After
    public void tearDown() {
        InputManager.clearInstance();
    }

    private class InputTestSensorEventListener implements SensorEventListener {
        @GuardedBy("mLock")
        private final BlockingQueue<SensorEvent> mEvents = new LinkedBlockingQueue<>();
        InputTestSensorEventListener() {
            super();
        }

        public SensorEvent waitForSensorEvent() {
            try {
                return mEvents.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail("unexpectedly interrupted while waiting for SensorEvent");
                return null;
            }
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            synchronized (mLock) {
                try {
                    mEvents.put(event);
                } catch (InterruptedException ex) {
                    fail("interrupted while adding a SensorEvent to the queue");
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }

    private InputDevice createInputDeviceWithSensor(int id) {
        InputDevice d = new InputDevice(id, 0 /* generation */, 0 /* controllerNumber */, "name",
                0 /* vendorId */, 0 /* productId */, "descriptor", true /* isExternal */,
                0 /* sources */, 0 /* keyboardType */, null /* keyCharacterMap */,
                false /* hasVibrator */, false /* hasMicrophone */, false /* hasButtonUnderpad */,
                true /* hasSensor */, false /* hasBattery */);
        assertTrue(d.hasSensor());
        return d;
    }

    private InputSensorInfo createInputSensorInfo(int id, int type) {
        InputSensorInfo info = new InputSensorInfo("name", "vendor", 0 /* version */,
                0 /* handle */, type, 100.0f /*maxRange */, 0.02f /* resolution */,
                0.8f /* power */, 1000 /* minDelay */, 0 /* fifoReservedEventCount */,
                0 /* fifoMaxEventCount */, "" /* stringType */, "" /* requiredPermission */,
                0 /* maxDelay */, 0 /* flags */, id);
        return info;
    }

    private InputDevice getSensorDevice(int[] deviceIds) {
        for (int i = 0; i < deviceIds.length; i++) {
            InputDevice device = mInputManager.getInputDevice(deviceIds[i]);
            if (device.hasSensor()) {
                return device;
            }
        }
        return null;
    }

    @Test
    public void getInputDeviceSensors_withExpectedType() throws Exception {
        InputDevice device = getSensorDevice(mInputManager.getInputDeviceIds());
        assertTrue(device != null);

        SensorManager sensorManager = device.getSensorManager();
        List<Sensor> accelList = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
        verify(mIInputManagerMock).getSensorList(eq(DEVICE_ID));
        assertEquals(1, accelList.size());
        assertEquals(DEVICE_ID, accelList.get(0).getId());
        assertEquals(Sensor.TYPE_ACCELEROMETER, accelList.get(0).getType());

        List<Sensor> gyroList = sensorManager.getSensorList(Sensor.TYPE_GYROSCOPE);
        verify(mIInputManagerMock).getSensorList(eq(DEVICE_ID));
        assertEquals(1, gyroList.size());
        assertEquals(DEVICE_ID, gyroList.get(0).getId());
        assertEquals(Sensor.TYPE_GYROSCOPE, gyroList.get(0).getType());

    }

    @Test
    public void getInputDeviceSensors_withUnexpectedType() throws Exception {
        InputDevice device = getSensorDevice(mInputManager.getInputDeviceIds());

        assertTrue(device != null);
        SensorManager sensorManager = device.getSensorManager();

        List<Sensor> gameRotationList = sensorManager.getSensorList(
                Sensor.TYPE_GAME_ROTATION_VECTOR);
        verify(mIInputManagerMock).getSensorList(eq(DEVICE_ID));
        assertEquals(0, gameRotationList.size());

        List<Sensor> gravityList = sensorManager.getSensorList(Sensor.TYPE_GRAVITY);
        verify(mIInputManagerMock).getSensorList(eq(DEVICE_ID));
        assertEquals(0, gravityList.size());
    }

    @Test
    public void testInputDeviceSensorListener() throws Exception {
        InputDevice device = getSensorDevice(mInputManager.getInputDeviceIds());
        assertTrue(device != null);

        SensorManager sensorManager = device.getSensorManager();
        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        assertEquals(Sensor.TYPE_ACCELEROMETER, sensor.getType());

        doAnswer(invocation -> {
            mIInputSensorEventListener = invocation.getArgument(0);
            assertNotNull(mIInputSensorEventListener);
            return true;
        }).when(mIInputManagerMock).registerSensorListener(any());

        InputTestSensorEventListener listener = new InputTestSensorEventListener();
        assertTrue(sensorManager.registerListener(listener, sensor,
                SensorManager.SENSOR_DELAY_NORMAL));
        verify(mIInputManagerMock).registerSensorListener(any());
        verify(mIInputManagerMock).enableSensor(eq(DEVICE_ID), eq(sensor.getType()),
                anyInt(), anyInt());

        float[] values = new float[] {0.12f, 9.8f, 0.2f};
        mIInputSensorEventListener.onInputSensorChanged(DEVICE_ID, Sensor.TYPE_ACCELEROMETER,
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH,  /* timestamp */ 0x1234abcd, values);

        SensorEvent event = listener.waitForSensorEvent();
        assertNotNull(event);
        assertEquals(0x1234abcd, event.timestamp);
        assertEquals(values.length, event.values.length);
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], event.values[i], 0.001f);
        }

        sensorManager.unregisterListener(listener);
        verify(mIInputManagerMock).disableSensor(eq(DEVICE_ID), eq(sensor.getType()));
    }

}
