/*
 * Copyright (C) 2014 The Android Open Source Project
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

/**
 * Battery manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class BatteryManagerInternal {
    /**
     * Returns true if the device is plugged into any of the specified plug types.
     *
     * This is a simple accessor that's safe to be called from any locks, but internally it may
     * wait on the battery service lock.
     */
    public abstract boolean isPowered(int plugTypeSet);

    /**
     * Returns the current plug type.
     *
     * This is a simple accessor that's safe to be called from any locks, but internally it may
     * wait on the battery service lock.
     */
    public abstract int getPlugType();

    /**
     * Returns battery level as a percentage.
     *
     * This is a simple accessor that's safe to be called from any locks, but internally it may
     * wait on the battery service lock.
     */
    public abstract int getBatteryLevel();

    /**
     * Instantaneous battery capacity in uA-h, as defined in the HealthInfo HAL struct.
     * Please note apparently it could be bigger than {@link #getBatteryFullCharge}.
     *
     * This is a simple accessor that's safe to be called from any locks, but internally it may
     * wait on the battery service lock.
     *
     * @see android.hardware.health.V1_0.HealthInfo#batteryChargeCounter
     */
    public abstract int getBatteryChargeCounter();

    /**
     * Battery charge value when it is considered to be "full" in uA-h , as defined in the
     * HealthInfo HAL struct.
     *
     * This is a simple accessor that's safe to be called from any locks, but internally it may
     * wait on the battery service lock.
     *
     * @see android.hardware.health.V1_0.HealthInfo#batteryFullCharge
     */
    public abstract int getBatteryFullCharge();

    /**
     * Returns whether we currently consider the battery level to be low.
     *
     * This is a simple accessor that's safe to be called from any locks, but internally it may
     * wait on the battery service lock.
     */
    public abstract boolean getBatteryLevelLow();

    /**
     * Returns a non-zero value if an unsupported charger is attached.
     *
     * This is a simple accessor that's safe to be called from any locks, but internally it may
     * wait on the battery service lock.
     */
    public abstract int getInvalidCharger();

    /**
     * Sets battery AC charger to enabled/disabled, and freezes the battery state.
     */
    public abstract void setChargerAcOnline(boolean online, boolean forceUpdate);

    /**
     * Sets battery level, and freezes the battery state.
     */
    public abstract void setBatteryLevel(int level, boolean forceUpdate);

    /**
     * Unplugs battery, and freezes the battery state.
     */
    public abstract void unplugBattery(boolean forceUpdate);

    /**
     * Unfreezes battery state, returning to current hardware values.
     */
    public abstract void resetBattery(boolean forceUpdate);

    /**
     * Suspend charging even if plugged in.
     */
    public abstract void suspendBatteryInput();
}
