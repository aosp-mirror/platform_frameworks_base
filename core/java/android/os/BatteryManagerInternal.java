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

import android.annotation.NonNull;

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
     * Returns battery health status as an integer representing the current battery health constant.
     *
     * This is a simple accessor that's safe to be called from any locks, but internally it may
     * wait on the battery service lock.
     */
    public abstract int getBatteryHealth();

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

    public interface ChargingPolicyChangeListener {
        void onChargingPolicyChanged(int newPolicy);
    }

    /**
     * Register a listener for changes to {@link BatteryManager#BATTERY_PROPERTY_CHARGING_POLICY}.
     * The charging policy can't be added to the BATTERY_CHANGED intent because it requires
     * the BATTERY_STATS permission.
     */
    public abstract void registerChargingPolicyChangeListener(
            @NonNull ChargingPolicyChangeListener chargingPolicyChangeListener);

    /**
     * Returns the value of {@link BatteryManager#BATTERY_PROPERTY_CHARGING_POLICY}.
     * This will return {@link Integer#MIN_VALUE} if the device does not support the property.
     *
     * @see BatteryManager#getIntProperty(int)
     */
    public abstract int getChargingPolicy();

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
