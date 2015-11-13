/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * The HardwarePropertiesManager class provides a mechanism of accessing hardware state of a
 * device: CPU, GPU and battery temperatures, CPU usage per core, fan speed, etc.
 */
public class HardwarePropertiesManager {

    private static final String TAG = HardwarePropertiesManager.class.getSimpleName();

    private static native void nativeInit();

    private static native float[] nativeGetFanSpeeds();
    private static native float[] nativeGetDeviceTemperatures(int type);
    private static native CpuUsageInfo[] nativeGetCpuUsages();

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        DEVICE_TEMPERATURE_CPU, DEVICE_TEMPERATURE_GPU, DEVICE_TEMPERATURE_BATTERY
    })
    public @interface DeviceTemperatureType {}

    /**
     * Device temperature types. These must match the values in
     * frameworks/native/include/hardwareproperties/HardwarePropertiesManager.h
     */
    /** Temperature of CPUs in Celsius. */
    public static final int DEVICE_TEMPERATURE_CPU = 0;

    /** Temperature of GPUs in Celsius. */
    public static final int DEVICE_TEMPERATURE_GPU = 1;

    /** Temperature of battery in Celsius. */
    public static final int DEVICE_TEMPERATURE_BATTERY = 2;

    /** @hide */
    public HardwarePropertiesManager() {
        nativeInit();
    }

    /**
     * Return an array of device temperatures in Celsius.
     *
     * @param type type of requested device temperature, one of {@link #DEVICE_TEMPERATURE_CPU},
     * {@link #DEVICE_TEMPERATURE_GPU} or {@link #DEVICE_TEMPERATURE_BATTERY}.
     * @return an array of requested float device temperatures.
     *         Empty if platform doesn't provide the queried temperature.
     *
     * @throws IllegalArgumentException if an incorrect temperature type is queried.
    */
    public @NonNull float[] getDeviceTemperatures(@DeviceTemperatureType int type) {
        switch (type) {
        case DEVICE_TEMPERATURE_CPU:
        case DEVICE_TEMPERATURE_GPU:
        case DEVICE_TEMPERATURE_BATTERY:
            return nativeGetDeviceTemperatures(type);
        default:
            throw new IllegalArgumentException();
        }
    }

    /**
     * Return an array of CPU usage info for each core.
     *
     * @return an array of {@link android.os.CpuUsageInfo} for each core.
     *         Empty if CPU usage is not supported on this system.
     */
    public @NonNull CpuUsageInfo[] getCpuUsages() {
        return nativeGetCpuUsages();
    }

    /**
     * Return an array of fan speeds in RPM.
     *
     * @return an arrat of float fan speeds. Empty if there is no fans or fan speed
     *         not supported on this system.
     */
    public @NonNull float[] getFanSpeeds() {
        return nativeGetFanSpeeds();
    }
}
