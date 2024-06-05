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
import static android.os.OsProtoEnums.BATTERY_PLUGGED_NONE;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import com.android.settingslib.R;

import java.util.Optional;

/**
 * Stores and computes some battery information.
 */
public class BatteryStatus {

    private static final int DEFAULT_CHARGING_VOLTAGE_MICRO_VOLT = 5000000;

    public static final int BATTERY_LEVEL_UNKNOWN = -1;
    public static final int CHARGING_UNKNOWN = -1;
    public static final int CHARGING_SLOWLY = 0;
    public static final int CHARGING_REGULAR = 1;
    public static final int CHARGING_FAST = 2;
    public static final int LOW_BATTERY_THRESHOLD = 20;
    public static final int SEVERE_LOW_BATTERY_THRESHOLD = 10;
    public static final int EXTREME_LOW_BATTERY_THRESHOLD = 3;

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

        maxChargingWattage = calculateMaxChargingMicroWatt(batteryChangedIntent);
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
     * Determine whether the device is plugged in wireless.
     */
    public boolean isPluggedInWireless() {
        return plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
    }

    /** Determine whether the device is plugged in dock. */
    public boolean isPluggedInDock() {
        return isPluggedInDock(plugged);
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
        return isLowBattery(level);
    }

    /** Whether battery defender is enabled. */
    public boolean isBatteryDefender() {
        return isBatteryDefender(chargingStatus);
    }

    /** Return current charging speed is fast, slow or normal. */
    public final int getChargingSpeed(Context context) {
        final int slowThreshold = context.getResources().getInteger(
                R.integer.config_chargingSlowlyThreshold);
        final int fastThreshold = context.getResources().getInteger(
                getFastChargingThresholdResId());

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

    /**
     * Whether or not the device is charged. Note that some devices never return 100% for battery
     * level, so this allows either battery level or status to determine if the battery is charged.
     *
     * @param status the value from extra {@link BatteryManager.EXTRA_STATUS} of
     *     {@link Intent.ACTION_BATTERY_CHANGED} intent
     * @param level the value from extra {@link BatteryManager.EXTRA_LEVEL} of
     *     {@link Intent.ACTION_BATTERY_CHANGED} intent
     * @param scale the value from extra {@link BatteryManager.EXTRA_SCALE} of
     *     {@link Intent.ACTION_BATTERY_CHANGED} intent
     */
    public static boolean isCharged(int status, int level, int scale) {
        var batteryLevel = getBatteryLevel(level, scale);
        return isCharged(status, batteryLevel);
    }

    /** Gets the battery level from the intent. */
    public static int getBatteryLevel(Intent batteryChangedIntent) {
        if (batteryChangedIntent == null) {
            return BATTERY_LEVEL_UNKNOWN;
        }
        final int level =
                batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, BATTERY_LEVEL_UNKNOWN);
        final int scale = batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
        return getBatteryLevel(level, scale);
    }

    /**
     * Gets the battery level from the value of {@link Intent.BATTERY_CHANGED_INTENT}'s EXTRA_LEVEL
     * and EXTRA_SCALE.
     */
    public static int getBatteryLevel(int level, int scale) {
        return scale == 0
                ? BATTERY_LEVEL_UNKNOWN
                : Math.round((level / (float) scale) * 100f);
    }

    /** Returns the plugged type from {@code batteryChangedIntent}. */
    public static int getPluggedType(Intent batteryChangedIntent) {
        return batteryChangedIntent.getIntExtra(EXTRA_PLUGGED, 0);
    }

    /** Whether the device is plugged or not. */
    public static boolean isPluggedIn(Intent batteryChangedIntent) {
        return isPluggedIn(getPluggedType(batteryChangedIntent));
    }

    /** Whether the device is plugged or not. */
    public static boolean isPluggedIn(int plugged) {
        return plugged == BatteryManager.BATTERY_PLUGGED_AC
                || plugged == BatteryManager.BATTERY_PLUGGED_USB
                || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
                || plugged == BatteryManager.BATTERY_PLUGGED_DOCK;
    }

    /** Determine whether the device is plugged in dock. */
    public static boolean isPluggedInDock(Intent batteryChangedIntent) {
        return isPluggedInDock(
                batteryChangedIntent.getIntExtra(EXTRA_PLUGGED, BATTERY_PLUGGED_NONE));
    }

    /** Determine whether the device is plugged in dock. */
    public static boolean isPluggedInDock(int plugged) {
        return plugged == BatteryManager.BATTERY_PLUGGED_DOCK;
    }

    /**
     * Whether the battery is low or not.
     *
     * @param batteryChangedIntent the {@link ACTION_BATTERY_CHANGED} intent
     * @return {@code true} if the battery level is less or equal to {@link LOW_BATTERY_THRESHOLD}
     */
    public static boolean isLowBattery(Intent batteryChangedIntent) {
        int level = getBatteryLevel(batteryChangedIntent);
        return isLowBattery(level);
    }

    /**
     * Whether the battery is low or not.
     *
     * @param batteryLevel the battery level
     * @return {@code true} if the battery level is less or equal to {@link LOW_BATTERY_THRESHOLD}
     */
    public static boolean isLowBattery(int batteryLevel) {
        return batteryLevel <= LOW_BATTERY_THRESHOLD;
    }

    /**
     * Whether the battery is severe low or not.
     *
     * @param batteryChangedIntent the ACTION_BATTERY_CHANGED intent
     * @return {@code true} if the battery level is less or equal to {@link
     * SEVERE_LOW_BATTERY_THRESHOLD}
     */
    public static boolean isSevereLowBattery(Intent batteryChangedIntent) {
        int batteryLevel = getBatteryLevel(batteryChangedIntent);
        return isSevereLowBattery(batteryLevel);
    }

    /**
     * Whether the battery is severe low or not.
     *
     * @param batteryLevel the value of battery level
     * @return {@code true} if the battery level is less or equal to {@link
     * SEVERE_LOW_BATTERY_THRESHOLD}
     */
    public static boolean isSevereLowBattery(int batteryLevel) {
        return batteryLevel <= SEVERE_LOW_BATTERY_THRESHOLD;
    }

    /**
     * Whether the battery is extreme low or not.
     *
     * @param batteryChangedIntent the ACTION_BATTERY_CHANGED intent
     * @return {@code true} if the battery level is less or equal to {@link
     * EXTREME_LOW_BATTERY_THRESHOLD}
     */
    public static boolean isExtremeLowBattery(Intent batteryChangedIntent) {
        int level = getBatteryLevel(batteryChangedIntent);
        return isExtremeLowBattery(level);
    }

    /**
     * Whether the battery is extreme low or not.
     *
     * @return {@code true} if the {@code batteryLevel} is less or equal to
     * {@link EXTREME_LOW_BATTERY_THRESHOLD}
     */
    public static boolean isExtremeLowBattery(int batteryLevel) {
        return batteryLevel <= EXTREME_LOW_BATTERY_THRESHOLD;
    }

    /**
     * Whether the battery defender is enabled or not.
     *
     * @param batteryChangedIntent the ACTION_BATTERY_CHANGED intent
     * @return {@code true} if the battery defender is enabled. It could be dock defend, dwell
     * defend, or temp defend
     */
    public static boolean isBatteryDefender(Intent batteryChangedIntent) {
        int chargingStatus =
                batteryChangedIntent.getIntExtra(EXTRA_CHARGING_STATUS, CHARGING_POLICY_DEFAULT);
        return isBatteryDefender(chargingStatus);
    }

    /**
     * Whether the battery defender is enabled or not.
     *
     * @param chargingStatus for {@link EXTRA_CHARGING_STATUS} field in the ACTION_BATTERY_CHANGED
     *     intent
     * @return {@code true} if the battery defender is enabled. It could be dock defend, dwell
     *     defend, or temp defend
     */
    public static boolean isBatteryDefender(int chargingStatus) {
        return chargingStatus == CHARGING_POLICY_ADAPTIVE_LONGLIFE;
    }

    /**
     * Calculates the charging speed based on the {@link R.integer.config_chargingSlowlyThreshold}
     * and {@link R.integer.config_chargingFastThreshold}.
     *
     * @param context the application context
     * @param batteryChangedIntent the intent from {@link Intent.ACTION_BATTERY_CHANGED}
     * @return the charging speed. {@link CHARGING_REGULAR}, {@link CHARGING_FAST}, {@link
     *     CHARGING_SLOWLY} or {@link CHARGING_UNKNOWN}
     */
    public static int getChargingSpeed(Context context, Intent batteryChangedIntent) {
        final int maxChargingMicroCurrent =
                batteryChangedIntent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, -1);
        int maxChargingMicroVolt = batteryChangedIntent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, -1);

        return calculateChargingSpeed(context, maxChargingMicroCurrent, maxChargingMicroVolt);
    }

    /**
     * Calculates the charging speed based on the {@link R.integer.config_chargingSlowlyThreshold}
     * and {@link R.integer.config_chargingFastThreshold}.
     *
     * @param maxChargingMicroCurrent the max charging micro current that is retrieved form the
     *     extra of {@link Intent.Action_BATTERY_CHANGED}
     * @param maxChargingMicroVolt the max charging micro voltage that is retrieved form the extra
     *     of {@link Intent.Action_BATTERY_CHANGED}
     * @return the charging speed. {@link CHARGING_REGULAR}, {@link CHARGING_FAST}, {@link
     *     CHARGING_SLOWLY} or {@link CHARGING_UNKNOWN}
     */
    public static int calculateChargingSpeed(
            Context context, int maxChargingMicroCurrent, int maxChargingMicroVolt) {
        final int maxChargingMicroWatt =
                calculateMaxChargingMicroWatt(maxChargingMicroCurrent, maxChargingMicroVolt);

        if (maxChargingMicroWatt <= 0) {
            return CHARGING_UNKNOWN;
        } else if (maxChargingMicroWatt
                < context.getResources().getInteger(R.integer.config_chargingSlowlyThreshold)) {
            return CHARGING_SLOWLY;
        } else if (maxChargingMicroWatt
                > context.getResources().getInteger(getFastChargingThresholdResId())) {
            return CHARGING_FAST;
        } else {
            return CHARGING_REGULAR;
        }
    }

    private static int calculateMaxChargingMicroWatt(Intent batteryChangedIntent) {
        final int maxChargingMicroAmp =
                batteryChangedIntent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, -1);
        int maxChargingMicroVolt = batteryChangedIntent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, -1);

        return calculateMaxChargingMicroWatt(maxChargingMicroAmp, maxChargingMicroVolt);
    }

    private static int calculateMaxChargingMicroWatt(int maxChargingMicroAmp,
            int maxChargingMicroVolt) {
        if (maxChargingMicroVolt <= 0) {
            maxChargingMicroVolt = DEFAULT_CHARGING_VOLTAGE_MICRO_VOLT;
        }

        if (maxChargingMicroAmp > 0) {
            // Calculating µW = mA * mV
            return (int) Math.round(maxChargingMicroAmp * 0.001 * maxChargingMicroVolt * 0.001);
        } else {
            return -1;
        }
    }

    private static int getFastChargingThresholdResId() {
        return BatteryUtils.isChargingStringV2Enabled()
                        ? R.integer.config_chargingFastThreshold_v2
                        : R.integer.config_chargingFastThreshold;
    }
}
