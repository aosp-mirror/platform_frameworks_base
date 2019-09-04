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

package com.android.server.display.whitebalance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContextWrapper;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.test.InstrumentationRegistry;

import com.android.server.display.whitebalance.AmbientSensor.AmbientBrightnessSensor;
import com.android.server.display.whitebalance.AmbientSensor.AmbientColorTemperatureSensor;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public final class AmbientSensorTest {
    private static final int AMBIENT_COLOR_TYPE = 20705;
    private static final String AMBIENT_COLOR_TYPE_STR = "colorSensoryDensoryDoc";

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Sensor mLightSensor;
    private Sensor mAmbientColorSensor;
    private ContextWrapper mContextSpy;
    private Resources mResourcesSpy;

    @Mock private SensorManager mSensorManagerMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLightSensor = createSensor(Sensor.TYPE_LIGHT, null);
        mAmbientColorSensor = createSensor(AMBIENT_COLOR_TYPE, AMBIENT_COLOR_TYPE_STR);
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
    }

    @Test
    public void testAmbientBrightnessSensorCallback_NoCallbacks() throws Exception {
        when(mSensorManagerMock.getDefaultSensor(Sensor.TYPE_LIGHT)).thenReturn(mLightSensor);
        AmbientBrightnessSensor abs = DisplayWhiteBalanceFactory.createBrightnessSensor(
                mHandler, mSensorManagerMock, InstrumentationRegistry.getContext().getResources());

        abs.setCallbacks(null);
        abs.setEnabled(true);
        ArgumentCaptor<SensorEventListener> captor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManagerMock).registerListener(captor.capture(), isA(Sensor.class), anyInt(),
                isA(Handler.class));

        // There should be no issues when we callback the listener, even if there is no callback
        // set.
        SensorEventListener listener = captor.getValue();
        listener.onSensorChanged(createSensorEvent(mLightSensor, 100));
    }

    @Test
    public void testAmbientBrightnessSensorCallback_CallbacksCalled() throws Exception {
        final int luxValue = 83;
        when(mSensorManagerMock.getDefaultSensor(Sensor.TYPE_LIGHT)).thenReturn(mLightSensor);
        AmbientBrightnessSensor abs = DisplayWhiteBalanceFactory.createBrightnessSensor(
                mHandler, mSensorManagerMock, InstrumentationRegistry.getContext().getResources());

        final int[] luxReturned = new int[] { -1 };
        final CountDownLatch  changeSignal = new CountDownLatch(1);
        abs.setCallbacks(new AmbientBrightnessSensor.Callbacks() {
            @Override
            public void onAmbientBrightnessChanged(float value) {
                luxReturned[0] = (int) value;
                changeSignal.countDown();
            }
        });

        abs.setEnabled(true);
        ArgumentCaptor<SensorEventListener> captor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManagerMock).registerListener(captor.capture(), eq(mLightSensor),
                anyInt(), eq(mHandler));
        SensorEventListener listener = captor.getValue();
        listener.onSensorChanged(createSensorEvent(mLightSensor, luxValue));
        assertTrue(changeSignal.await(5, TimeUnit.SECONDS));
        assertEquals(luxValue, luxReturned[0]);
    }

    @Test
    public void testAmbientColorTemperatureSensorCallback_CallbacksCalled() throws Exception {
        final int colorTempValue = 79;
        final List<Sensor> sensorList = ImmutableList.of(mLightSensor, mAmbientColorSensor);
        when(mSensorManagerMock.getSensorList(Sensor.TYPE_ALL)).thenReturn(sensorList);
        when(mResourcesSpy.getString(
                com.android.internal.R.string.config_displayWhiteBalanceColorTemperatureSensorName))
                .thenReturn(AMBIENT_COLOR_TYPE_STR);

        AmbientColorTemperatureSensor abs = DisplayWhiteBalanceFactory.createColorTemperatureSensor(
                mHandler, mSensorManagerMock, mResourcesSpy);

        final int[] colorTempReturned = new int[] { -1 };
        final CountDownLatch  changeSignal = new CountDownLatch(1);
        abs.setCallbacks(new AmbientColorTemperatureSensor.Callbacks() {
            @Override
            public void onAmbientColorTemperatureChanged(float value) {
                colorTempReturned[0] = (int) value;
                changeSignal.countDown();
            }
        });

        abs.setEnabled(true);
        ArgumentCaptor<SensorEventListener> captor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManagerMock).registerListener(captor.capture(), eq(mAmbientColorSensor),
                anyInt(), eq(mHandler));
        SensorEventListener listener = captor.getValue();
        listener.onSensorChanged(createSensorEvent(mAmbientColorSensor, colorTempValue));
        assertTrue(changeSignal.await(5, TimeUnit.SECONDS));
        assertEquals(colorTempValue, colorTempReturned[0]);
    }

    private SensorEvent createSensorEvent(Sensor sensor, int lux) throws Exception {
        final Constructor<SensorEvent> constructor =
                SensorEvent.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        final SensorEvent event = constructor.newInstance(1);
        event.sensor = sensor;
        event.values[0] = lux;
        event.timestamp = SystemClock.elapsedRealtimeNanos();
        return event;
    }


    private void setSensorType(Sensor sensor, int type, String strType) throws Exception {
        Method setter = Sensor.class.getDeclaredMethod("setType", Integer.TYPE);
        setter.setAccessible(true);
        setter.invoke(sensor, type);
        if (strType != null) {
            Field f = sensor.getClass().getDeclaredField("mStringType");
            f.setAccessible(true);
            f.set(sensor, strType);
        }
    }

    private Sensor createSensor(int type, String strType) throws Exception {
        Constructor<Sensor> constr = Sensor.class.getDeclaredConstructor();
        constr.setAccessible(true);
        Sensor sensor = constr.newInstance();
        setSensorType(sensor, type, strType);
        return sensor;
    }
}
