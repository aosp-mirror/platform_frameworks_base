/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.hardware.Sensor;
import android.hardware.SensorAdditionalInfo;
import android.hardware.SensorDirectChannel;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEventListener;
import android.os.Handler;
import android.os.MemoryFile;
import android.os.SystemClock;
import android.util.ArraySet;

import com.google.android.collect.Lists;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Rudimentary fake for SensorManager
 *
 * Currently only supports the proximity sensor.
 *
 * Note that this class ignores the "Handler" argument, so the test is responsible for calling the
 * listener on the right thread.
 */
public class SensorManagerFake extends SensorManager {

    public MockSensor PROXIMITY;

    public SensorManagerFake(Context context) {
        PROXIMITY = new MockSensor(context.getSystemService(SensorManager.class)
                .getDefaultSensor(Sensor.TYPE_PROXIMITY));
    }

    @Override
    protected List<Sensor> getFullSensorList() {
        return Lists.newArrayList(PROXIMITY.sensor);
    }

    @Override
    protected List<Sensor> getFullDynamicSensorList() {
        return new ArrayList<>();
    }

    @Override
    protected void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
        if (sensor == PROXIMITY.sensor || sensor == null) {
            PROXIMITY.listeners.remove(listener);
        }
    }

    @Override
    protected boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
            int delayUs,
            Handler handler, int maxReportLatencyUs, int reservedFlags) {
        if (sensor == PROXIMITY.sensor) {
            PROXIMITY.listeners.add(listener);
            return true;
        }
        return false;
    }

    @Override
    protected boolean flushImpl(SensorEventListener listener) {
        return false;
    }

    @Override
    protected SensorDirectChannel createDirectChannelImpl(MemoryFile memoryFile,
            HardwareBuffer hardwareBuffer) {
        return null;
    }

    @Override
    protected void destroyDirectChannelImpl(SensorDirectChannel channel) {

    }

    @Override
    protected int configureDirectChannelImpl(SensorDirectChannel channel, Sensor s, int rate) {
        return 0;
    }

    @Override
    protected void registerDynamicSensorCallbackImpl(DynamicSensorCallback callback,
            Handler handler) {

    }

    @Override
    protected void unregisterDynamicSensorCallbackImpl(
            DynamicSensorCallback callback) {

    }

    @Override
    protected boolean requestTriggerSensorImpl(TriggerEventListener listener, Sensor sensor) {
        return false;
    }

    @Override
    protected boolean cancelTriggerSensorImpl(TriggerEventListener listener, Sensor sensor,
            boolean disable) {
        return false;
    }

    @Override
    protected boolean initDataInjectionImpl(boolean enable) {
        return false;
    }

    @Override
    protected boolean injectSensorDataImpl(Sensor sensor, float[] values, int accuracy,
            long timestamp) {
        return false;
    }

    @Override
    protected boolean setOperationParameterImpl(SensorAdditionalInfo parameter) {
        return false;
    }

    public class MockSensor {
        final Sensor sensor;
        final ArraySet<SensorEventListener> listeners = new ArraySet<>();

        private MockSensor(Sensor sensor) {
            this.sensor = sensor;
        }

        public void sendProximityResult(boolean far) {
            SensorEvent event = createSensorEvent(1);
            event.values[0] = far ? sensor.getMaximumRange() : 0;
            for (SensorEventListener listener : listeners) {
                listener.onSensorChanged(event);
            }
        }

        private SensorEvent createSensorEvent(int valuesSize) {
            SensorEvent event;
            try {
                Constructor<SensorEvent> constr =
                        SensorEvent.class.getDeclaredConstructor(Integer.TYPE);
                constr.setAccessible(true);
                event = constr.newInstance(valuesSize);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            event.sensor = sensor;
            event.timestamp = SystemClock.elapsedRealtimeNanos();

            return event;
        }
    }
}
