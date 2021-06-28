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

package com.android.settingslib.fuelgauge;

import static android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT;
import static android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN;
import static android.os.BatteryManager.BATTERY_STATUS_FULL;
import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;
import static android.os.BatteryManager.EXTRA_HEALTH;
import static android.os.BatteryManager.EXTRA_LEVEL;
import static android.os.BatteryManager.EXTRA_MAX_CHARGING_CURRENT;
import static android.os.BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE;
import static android.os.BatteryManager.EXTRA_PLUGGED;
import static android.os.BatteryManager.EXTRA_PRESENT;
import static android.os.BatteryManager.EXTRA_STATUS;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import com.android.settingslib.R;

/**
 * Stores and computes some battery information.
 */
public class BatteryStatus {
    private static final int LOW_BATTERY_THRESHOLD = 20;
    private static final int DEFAULT_CHARGING_VOLTAGE_MICRO_VOLT = 5000000;

    public static final int CHARGING_UNKNOWN = -1;
    public static final int CHARGING_SLOWLY = 0;
    public static final int CHARGING_REGULAR = 1;
    public static final int CHARGING_FAST = 2;

    public final int status;
    public final int level;
    public final int plugged;
    public final int health;
    public final int maxChargingWattage;
    public final boolean present;

    public BatteryStatus(int status, int level, int plugged, int health,
            int maxChargingWattage, boolean present) {
        this.status = status;
        this.level = level;
        this.plugged = plugged;
        this.health = health;
        this.maxChargingWattage = maxChargingWattage;
        this.present = present;
    }

    public BatteryStatus(Intent batteryChangedIntent) {
        status = batteryChangedIntent.getIntExtra(EXTRA_STATUS, BATTERY_STATUS_UNKNOWN);
        plugged = batteryChangedIntent.getIntExtra(EXTRA_PLUGGED, 0);
        level = batteryChangedIntent.getIntExtra(EXTRA_LEVEL, 0);
        health = batteryChangedIntent.getIntExtra(EXTRA_HEALTH, BATTERY_HEALTH_UNKNOWN);
        present = batteryChangedIntent.getBooleanExtra(EXTRA_PRESENT, true);

        final int maxChargingMicroAmp = batteryChangedIntent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT,
                -1);
        int maxChargingMicroVolt = batteryChangedIntent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, -1);

        if (maxChargingMicroVolt <= 0) {
            maxChargingMicroVolt = DEFAULT_CHARGING_VOLTAGE_MICRO_VOLT;
        }
        if (maxChargingMicroAmp > 0) {
            // Calculating muW = muA * muV / (10^6 mu^2 / mu); splitting up the divisor
            // to maintain precision equally on both factors.
            maxChargingWattage = (maxChargingMicroAmp / 1000)
                    * (maxChargingMicroVolt / 1000);
        } else {
            maxChargingWattage = -1;
        }
    }

    /**
     * Determine whether the device is plugged in (USB, power, or wireless).
     *
     * @return true if the device is plugged in.
     */
    public boolean isPluggedIn() {
        return plugged == BatteryManager.BATTERY_PLUGGED_AC
                || plugged == BatteryManager.BATTERY_PLUGGED_USB
                || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
    }

    /**
     * Determine whether the device is plugged in (USB, power).
     *
     * @return true if the device is plugged in wired (as opposed to wireless)
     */
    public boolean isPluggedInWired() {
        return plugged == BatteryManager.BATTERY_PLUGGED_AC
                || plugged == BatteryManager.BATTERY_PLUGGED_USB;
    }

    /**
     * Whether or not the device is charged. Note that some devices never return 100% for
     * battery level, so this allows either battery level or status to determine if the
     * battery is charged.
     *
     * @return true if the device is charged
     */
    public boolean isCharged() {
        return status == BATTERY_STATUS_FULL || level >= 100;
    }

    /**
     * Whether battery is low and needs to be charged.
     *
     * @return true if battery is low
     */
    public boolean isBatteryLow() {
        return level < LOW_BATTERY_THRESHOLD;
    }

    /**
     * Whether battery is overheated.
     *
     * @return true if battery is overheated
     */
    public boolean isOverheated() {
        return health == BATTERY_HEALTH_OVERHEAT;
    }

    /**
     * Return current chargin speed is fast, slow or normal.
     *
     * @return the charing speed
     */
    public final int getChargingSpeed(Context context) {
        final int slowThreshold = context.getResources().getInteger(
                R.integer.config_chargingSlowlyThreshold);
        final int fastThreshold = context.getResources().getInteger(
                R.integer.config_chargingFastThreshold);
        return maxChargingWattage <= 0 ? CHARGING_UNKNOWN :
                maxChargingWattage < slowThreshold ? CHARGING_SLOWLY :
                        maxChargingWattage > fastThreshold ? CHARGING_FAST :
                                CHARGING_REGULAR;
    }

    @Override
    public String toString() {
        return "BatteryStatus{status=" + status + ",level=" + level + ",plugged=" + plugged
                + ",health=" + health + ",maxChargingWattage=" + maxChargingWattage + "}";
    }
}
