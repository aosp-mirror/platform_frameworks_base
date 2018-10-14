/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.plugins;

import android.hardware.Sensor;
import android.hardware.TriggerEventListener;

import com.android.systemui.plugins.annotations.ProvidesInterface;

/**
 * Allows for additional sensors to be retrieved from
 * {@link com.android.systemui.util.AsyncSensorManager}.
 */
@ProvidesInterface(action = SensorManagerPlugin.ACTION, version = SensorManagerPlugin.VERSION)
public interface SensorManagerPlugin extends Plugin {
    String ACTION = "com.android.systemui.action.PLUGIN_SENSOR_MANAGER";
    int VERSION = 1;

    /**
     * Registers for trigger events from the sensor. Trigger events are one-shot and need to
     * re-registered in order for them to be fired again.
     * @param sensor
     * @param listener
     * @see android.hardware.SensorManager#requestTriggerSensor(
     *     android.hardware.TriggerEventListener, android.hardware.Sensor)
     */
    void registerTriggerEvent(Sensor sensor, TriggerEventListener listener);

    /**
     * Unregisters trigger events from the sensor.
     * @param sensor
     * @param listener
     */
    void unregisterTriggerEvent(Sensor sensor, TriggerEventListener listener);

    interface TriggerEventListener {
        void onTrigger(TriggerEvent event);
    }

    class Sensor {
        public static int TYPE_WAKE_LOCK_SCREEN = 1;

        int mType;

        public int getType() {
            return mType;
        }

        public Sensor(int type) {
            mType = type;
        }
    }

    class TriggerEvent {
        Sensor mSensor;
        int mVendorType;

        /**
         * Creates a trigger event
         * @param sensor The type of sensor, e.g. TYPE_WAKE_LOCK_SCREEN
         * @param vendorType The vendor type, which should be unique for each type of sensor,
         *                   e.g. SINGLE_TAP = 1, DOUBLE_TAP = 2, etc.
         */
        public TriggerEvent(Sensor sensor, int vendorType) {
            mSensor = sensor;
            mVendorType = vendorType;
        }

        public Sensor getSensor() {
            return mSensor;
        }

        public int getVendorType() {
            return mVendorType;
        }
    }
}
