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
 * limitations under the License
 */

package com.android.systemui.utils.hardware;

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

import com.android.internal.util.Preconditions;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Rudimentary fake for SensorManager
 *
 * Currently only supports the proximity sensor.
 *
 * Note that this class ignores the "Handler" argument, so the test is responsible for calling the
 * listener on the right thread.
 */
public class FakeSensorManager extends SensorManager {

    private final MockProximitySensor mMockProximitySensor;
    private final FakeGenericSensor mFakeLightSensor;
    private final FakeGenericSensor[] mSensors;

    public FakeSensorManager(Context context) throws Exception {
        Sensor proxSensor = context.getSystemService(SensorManager.class)
                .getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proxSensor == null) {
            // No prox? Let's create a fake one!
            proxSensor = createSensor(Sensor.TYPE_PROXIMITY);
        }

        mSensors = new FakeGenericSensor[]{
                mMockProximitySensor = new MockProximitySensor(proxSensor),
                mFakeLightSensor = new FakeGenericSensor(createSensor(Sensor.TYPE_LIGHT)),
        };
    }

    public MockProximitySensor getMockProximitySensor() {
        return mMockProximitySensor;
    }

    public FakeGenericSensor getFakeLightSensor() {
        return mFakeLightSensor;
    }

    @Override
    public Sensor getDefaultSensor(int type) {
        Sensor s = super.getDefaultSensor(type);
        if (s != null) {
            return s;
        }
        // Our mock sensors aren't wakeup, and it's a pain to create them that way. Instead, just
        // return non-wakeup sensors if we can't find a wakeup sensor.
        return getDefaultSensor(type, false /* wakeup */);
    }

    @Override
    protected List<Sensor> getFullSensorList() {
        return Arrays
                .stream(mSensors)
                .map(i -> i.mSensor)
                .collect(Collectors.toList());
    }

    @Override
    protected List<Sensor> getFullDynamicSensorList() {
        return new ArrayList<>();
    }

    @Override
    protected void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
        Preconditions.checkNotNull(listener);
        for (FakeGenericSensor s : mSensors) {
            if (sensor == null || s.mSensor == sensor) {
                s.mListeners.remove(listener);
            }
        }
    }

    @Override
    protected boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
            int delayUs,
            Handler handler, int maxReportLatencyUs, int reservedFlags) {
        Preconditions.checkNotNull(sensor);
        Preconditions.checkNotNull(listener);
        for (FakeGenericSensor s : mSensors) {
            if (s.mSensor == sensor) {
                s.mListeners.add(listener);
                return true;
            }
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

    private Sensor createSensor(int type) throws Exception {
        Constructor<Sensor> constr = Sensor.class.getDeclaredConstructor();
        constr.setAccessible(true);
        Sensor sensor = constr.newInstance();

        setSensorType(sensor, type);
        setSensorField(sensor, "mName", "Mock " + sensor.getStringType() + "/" + type);
        setSensorField(sensor, "mVendor", "Mock Vendor");
        setSensorField(sensor, "mVersion", 1);
        setSensorField(sensor, "mHandle", -1);
        setSensorField(sensor, "mMaxRange", 10);
        setSensorField(sensor, "mResolution", 1);
        setSensorField(sensor, "mPower", 1);
        setSensorField(sensor, "mMinDelay", 1000);
        setSensorField(sensor, "mMaxDelay", 1000000000);
        setSensorField(sensor, "mFlags", 0);
        setSensorField(sensor, "mId", -1);

        return sensor;
    }

    private void setSensorField(Sensor sensor, String fieldName, Object value) throws Exception {
        Field field = Sensor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(sensor, value);
    }

    private void setSensorType(Sensor sensor, int type) throws Exception {
        Method setter = Sensor.class.getDeclaredMethod("setType", Integer.TYPE);
        setter.setAccessible(true);
        setter.invoke(sensor, type);
    }

    public class MockProximitySensor extends FakeGenericSensor {

        private MockProximitySensor(Sensor sensor) {
            super(sensor);
        }

        public void sendProximityResult(boolean far) {
            sendSensorEvent(far ? getSensor().getMaximumRange() : 0);
        }
    }

    public class FakeGenericSensor {

        private final Sensor mSensor;
        private final ArraySet<SensorEventListener> mListeners = new ArraySet<>();

        public FakeGenericSensor(
                Sensor sensor) {
            this.mSensor = sensor;
        }

        public Sensor getSensor() {
            return mSensor;
        }

        public void sendSensorEvent(float... values) {
            SensorEvent event = createSensorEvent(values.length);
            System.arraycopy(values, 0, event.values, 0, values.length);
            for (SensorEventListener listener : mListeners) {
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
            event.sensor = mSensor;
            event.timestamp = SystemClock.elapsedRealtimeNanos();

            return event;
        }
    }
}
