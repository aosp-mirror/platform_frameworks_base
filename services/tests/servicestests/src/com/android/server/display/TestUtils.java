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
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class TestUtils {

    public static SensorEvent createSensorEvent(Sensor sensor, int lux) throws Exception {
        final Constructor<SensorEvent> constructor =
                SensorEvent.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        final SensorEvent event = constructor.newInstance(1);
        event.sensor = sensor;
        event.values[0] = lux;
        event.timestamp = SystemClock.elapsedRealtimeNanos();
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

    public static Sensor createSensor(int type, String strType) throws Exception {
        Constructor<Sensor> constr = Sensor.class.getDeclaredConstructor();
        constr.setAccessible(true);
        Sensor sensor = constr.newInstance();
        setSensorType(sensor, type, strType);
        return sensor;
    }

}
