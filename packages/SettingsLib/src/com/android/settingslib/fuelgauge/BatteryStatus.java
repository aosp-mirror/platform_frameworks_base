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

import static android.os.BatteryManager.BATTERY_STATUS_FULL;
import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;
import static android.os.BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE;
import static android.os.BatteryManager.CHARGING_POLICY_DEFAULT;
import static android.os.BatteryManager.EXTRA_CHARGING_STATUS;
import static android.os.BatteryManager.EXTRA_MAX_CHARGING_CURRENT;
import static android.os.BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE;
import static android.os.BatteryManager.EXTRA_PLUGGED;
import static android.os.BatteryManager.EXTRA_PRESENT;
import static android.os.BatteryManager.EXTRA_STATUS;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import com.android.settingslib.R;

import java.util.Optional;

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
    public final int chargingStatus;
    public final int maxChargingWattage;
    public final boolean present;
    public final Optional<Boolean> incompatibleCharger;

    public static BatteryStatus create(Context context, boolean incompatibleCharger) {
        final Intent batteryChangedIntent = BatteryUtils.getBatteryIntent(context);
        return batteryChangedIntent == null
                ? null : new BatteryStatus(batteryChangedIntent, incompatibleCharger);
    }

    public BatteryStatus(int status, int level, int plugged, int chargingStatus,
            int maxChargingWattage, boolean present) {
        this.status = status;
        this.level = level;
        this.plugged = plugged;
        this.chargingStatus = chargingStatus;
        this.maxChargingWattage = maxChargingWattage;
        this.present = present;
        this.incompatibleCharger = Optional.empty();
    }


    public BatteryStatus(Intent batteryChangedIntent) {
        this(batteryChangedIntent, Optional.empty());
    }

    public BatteryStatus(Intent batteryChangedIntent, boolean incompatibleCharger) {
        this(batteryChangedIntent, Optional.of(incompatibleCharger));
    }

    private BatteryStatus(Intent batteryChangedIntent, Optional<Boolean> incompatibleCharger) {
        status = batteryChangedIntent.getIntExtra(EXTRA_STATUS, BATTERY_STATUS_UNKNOWN);
        plugged = batteryChangedIntent.getIntExtra(EXTRA_PLUGGED, 0);
        level = getBatteryLevel(batteryChangedIntent);
        chargingStatus = batteryChangedIntent.getIntExtra(EXTRA_CHARGING_STATUS,
                CHARGING_POLICY_DEFAULT);
        present = batteryChangedIntent.getBooleanExtra(EXTRA_PRESENT, true);
        this.incompatibleCharger = incompatibleCharger;

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

    /** Determine whether the device is plugged. */
    public boolean isPluggedIn() {
        return isPluggedIn(plugged);
    }

    /** Determine whether the device is plugged in (USB, power). */
    public boolean isPluggedInWired() {
        return plugged == BatteryManager.BATTERY_PLUGGED_AC
                || plugged == BatteryManager.BATTERY_PLUGGED_USB;
    }

    /**
     * Determine whether the device is plugged in wireless. */
    public boolean isPluggedInWireless() {
        return plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
    }

    /** Determine whether the device is plugged in dock. */
    public boolean isPluggedInDock() {
        return plugged == BatteryManager.BATTERY_PLUGGED_DOCK;
    }

    /**
     * Whether or not the device is charged. Note that some devices never return 100% for
     * battery level, so this allows either battery level or status to determine if the
     * battery is charged.
     */
    public boolean isCharged() {
        return isCharged(status, level);
    }

    /** Whether battery is low and needs to be charged. */
    public boolean isBatteryLow() {
        return level < LOW_BATTERY_THRESHOLD;
    }

    /** Whether battery defender is enabled. */
    public boolean isBatteryDefender() {
        return chargingStatus == CHARGING_POLICY_ADAPTIVE_LONGLIFE;
    }

    /** Return current chargin speed is fast, slow or normal. */
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
                + ",chargingStatus=" + chargingStatus + ",maxChargingWattage=" + maxChargingWattage
                + "}";
    }

    /**
     * Whether or not the device is charged. Note that some devices never return 100% for
     * battery level, so this allows either battery level or status to determine if the
     * battery is charged.
     *
     * @param batteryChangedIntent ACTION_BATTERY_CHANGED intent
     * @return true if the device is charged
     */
    public static boolean isCharged(Intent batteryChangedIntent) {
        int status = batteryChangedIntent.getIntExtra(EXTRA_STATUS, BATTERY_STATUS_UNKNOWN);
        int level = getBatteryLevel(batteryChangedIntent);
        return isCharged(status, level);
    }

    /**
     * Whether or not the device is charged. Note that some devices never return 100% for
     * battery level, so this allows either battery level or status to determine if the
     * battery is charged.
     *
     * @param status values for "status" field in the ACTION_BATTERY_CHANGED Intent
     * @param level values from 0 to 100
     * @return true if the device is charged
     */
    public static boolean isCharged(int status, int level) {
        return status == BATTERY_STATUS_FULL || level >= 100;
    }

    /** Gets the battery level from the intent. */
    public static int getBatteryLevel(Intent batteryChangedIntent) {
        if (batteryChangedIntent == null) {
            return -1; /*invalid battery level*/
        }
        final int level = batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
        return scale == 0
                ? -1 /*invalid battery level*/
                : Math.round((level / (float) scale) * 100f);
    }

    /** Whether the device is plugged or not. */
    public static boolean isPluggedIn(Intent batteryChangedIntent) {
        return isPluggedIn(batteryChangedIntent.getIntExtra(EXTRA_PLUGGED, 0));
    }

    /** Whether the device is plugged or not. */
    public static boolean isPluggedIn(int plugged) {
        return plugged == BatteryManager.BATTERY_PLUGGED_AC
                || plugged == BatteryManager.BATTERY_PLUGGED_USB
                || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
                || plugged == BatteryManager.BATTERY_PLUGGED_DOCK;
    }
}
