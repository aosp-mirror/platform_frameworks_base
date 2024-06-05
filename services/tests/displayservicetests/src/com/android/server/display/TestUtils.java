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

package com.android.server.display;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.input.InputSensorInfo;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.DisplayAddress;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class TestUtils {

    public static SensorEvent createSensorEvent(Sensor sensor, int value) throws Exception {
        return createSensorEvent(sensor, value, SystemClock.elapsedRealtimeNanos());
    }

    /**
     * Creates a light sensor event
     */
    public static SensorEvent createSensorEvent(Sensor sensor, int value, long timestamp)
            throws Exception {
        final Constructor<SensorEvent> constructor =
                SensorEvent.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        final SensorEvent event = constructor.newInstance(1);
        event.sensor = sensor;
        event.values[0] = value;
        event.timestamp = timestamp;
        return event;
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

    public static void setMaximumRange(Sensor sensor, float maximumRange) throws Exception {
        Method setter = Sensor.class.getDeclaredMethod("setRange", Float.TYPE, Float.TYPE);
        setter.setAccessible(true);
        setter.invoke(sensor, maximumRange, 1);
    }

    public static Sensor createSensor(int type, String strType) throws Exception {
        Constructor<Sensor> constr = Sensor.class.getDeclaredConstructor();
        constr.setAccessible(true);
        Sensor sensor = constr.newInstance();
        setSensorType(sensor, type, strType);
        return sensor;
    }

    public static Sensor createSensor(int type, String strType, float maximumRange)
            throws Exception {
        Constructor<Sensor> constr = Sensor.class.getDeclaredConstructor();
        constr.setAccessible(true);
        Sensor sensor = constr.newInstance();
        setSensorType(sensor, type, strType);
        setMaximumRange(sensor, maximumRange);
        return sensor;
    }

    public static Sensor createSensor(String type, String name) {
        return new Sensor(new InputSensorInfo(
                name, "vendor", 0, 0, 0, 1f, 1f, 1, 1, 1, 1,
                type, "", 0, 0, 0));
    }

    /**
     * Create a custom {@link DisplayAddress} to ensure we're not relying on any specific
     * display-address implementation in our code. Intentionally uses default object (reference)
     * equality rules.
     */
    public static class TestDisplayAddress extends DisplayAddress {
        @Override
        public void writeToParcel(Parcel out, int flags) { }
    }
}
