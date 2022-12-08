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

package android.companion.virtual.sensor;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.companion.virtual.IVirtualDevice;
import android.hardware.Sensor;
import android.os.IBinder;
import android.os.RemoteException;

import java.time.Duration;

/**
 * Representation of a sensor on a remote device, capable of sending events, such as an
 * accelerometer or a gyroscope.
 *
 * This registers the sensor device with the sensor framework as a runtime sensor.
 *
 * @hide
 */
@SystemApi
public class VirtualSensor {

    /**
     * Interface for notification of listener registration changes for a virtual sensor.
     */
    public interface SensorStateChangeCallback {
        /**
         * Called when the registered listeners to a virtual sensor have changed.
         *
         * @param enabled Whether the sensor is enabled.
         * @param samplingPeriod The requested sampling period of the sensor.
         * @param batchReportLatency The requested maximum time interval between the delivery of two
         * batches of sensor events.
         */
        void onStateChanged(boolean enabled, @NonNull Duration samplingPeriod,
                @NonNull Duration batchReportLatency);
    }

    private final int mType;
    private final String mName;
    private final IVirtualDevice mVirtualDevice;
    private final IBinder mToken;

    /**
     * @hide
     */
    public VirtualSensor(int type, String name, IVirtualDevice virtualDevice, IBinder token) {
        mType = type;
        mName = name;
        mVirtualDevice = virtualDevice;
        mToken = token;
    }

    /**
     * Returns the type of the sensor.
     *
     * @see Sensor#getType()
     * @see <a href="https://source.android.com/devices/sensors/sensor-types">Sensor types</a>
     */
    public int getType() {
        return mType;
    }

    /**
     * Returns the name of the sensor.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Send a sensor event to the system.
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    public void sendEvent(@NonNull VirtualSensorEvent event) {
        try {
            mVirtualDevice.sendSensorEvent(mToken, event);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
