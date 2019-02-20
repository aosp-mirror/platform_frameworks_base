/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.Manifest.permission;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.content.Intent;
import android.hardware.health.V1_0.Constants;

import com.android.internal.app.IBatteryStats;

/**
 * The BatteryManager class contains strings and constants used for values
 * in the {@link android.content.Intent#ACTION_BATTERY_CHANGED} Intent, and
 * provides a method for querying battery and charging properties.
 */
@SystemService(Context.BATTERY_SERVICE)
public class BatteryManager {
    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * integer containing the current status constant.
     */
    public static final String EXTRA_STATUS = "status";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * integer containing the current health constant.
     */
    public static final String EXTRA_HEALTH = "health";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * boolean indicating whether a battery is present.
     */
    public static final String EXTRA_PRESENT = "present";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * integer field containing the current battery level, from 0 to
     * {@link #EXTRA_SCALE}.
     */
    public static final String EXTRA_LEVEL = "level";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * Boolean field indicating whether the battery is currently considered to be
     * low, that is whether a {@link Intent#ACTION_BATTERY_LOW} broadcast
     * has been sent.
     */
    public static final String EXTRA_BATTERY_LOW = "battery_low";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * integer containing the maximum battery level.
     */
    public static final String EXTRA_SCALE = "scale";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * integer containing the resource ID of a small status bar icon
     * indicating the current battery state.
     */
    public static final String EXTRA_ICON_SMALL = "icon-small";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * integer indicating whether the device is plugged in to a power
     * source; 0 means it is on battery, other constants are different
     * types of power sources.
     */
    public static final String EXTRA_PLUGGED = "plugged";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * integer containing the current battery voltage level.
     */
    public static final String EXTRA_VOLTAGE = "voltage";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * integer containing the current battery temperature.
     */
    public static final String EXTRA_TEMPERATURE = "temperature";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * String describing the technology of the current battery.
     */
    public static final String EXTRA_TECHNOLOGY = "technology";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * Int value set to nonzero if an unsupported charger is attached
     * to the device.
     * {@hide}
     */
    public static final String EXTRA_INVALID_CHARGER = "invalid_charger";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * Int value set to the maximum charging current supported by the charger in micro amperes.
     * {@hide}
     */
    public static final String EXTRA_MAX_CHARGING_CURRENT = "max_charging_current";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * Int value set to the maximum charging voltage supported by the charger in micro volts.
     * {@hide}
     */
    public static final String EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * integer containing the charge counter present in the battery.
     * {@hide}
     */
     public static final String EXTRA_CHARGE_COUNTER = "charge_counter";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_CHANGED}:
     * Current int sequence number of the update.
     * {@hide}
     */
    public static final String EXTRA_SEQUENCE = "seq";

    /**
     * Extra for {@link android.content.Intent#ACTION_BATTERY_LEVEL_CHANGED}:
     * Contains list of Bundles representing battery events
     * @hide
     */
    @SystemApi
    public static final String EXTRA_EVENTS = "android.os.extra.EVENTS";

    /**
     * Extra for event in {@link android.content.Intent#ACTION_BATTERY_LEVEL_CHANGED}:
     * Long value representing time when event occurred as returned by
     * {@link android.os.SystemClock#elapsedRealtime()}
     * @hide
     */
    @SystemApi
    public static final String EXTRA_EVENT_TIMESTAMP = "android.os.extra.EVENT_TIMESTAMP";

    // values for "status" field in the ACTION_BATTERY_CHANGED Intent
    public static final int BATTERY_STATUS_UNKNOWN = Constants.BATTERY_STATUS_UNKNOWN;
    public static final int BATTERY_STATUS_CHARGING = Constants.BATTERY_STATUS_CHARGING;
    public static final int BATTERY_STATUS_DISCHARGING = Constants.BATTERY_STATUS_DISCHARGING;
    public static final int BATTERY_STATUS_NOT_CHARGING = Constants.BATTERY_STATUS_NOT_CHARGING;
    public static final int BATTERY_STATUS_FULL = Constants.BATTERY_STATUS_FULL;

    // values for "health" field in the ACTION_BATTERY_CHANGED Intent
    public static final int BATTERY_HEALTH_UNKNOWN = Constants.BATTERY_HEALTH_UNKNOWN;
    public static final int BATTERY_HEALTH_GOOD = Constants.BATTERY_HEALTH_GOOD;
    public static final int BATTERY_HEALTH_OVERHEAT = Constants.BATTERY_HEALTH_OVERHEAT;
    public static final int BATTERY_HEALTH_DEAD = Constants.BATTERY_HEALTH_DEAD;
    public static final int BATTERY_HEALTH_OVER_VOLTAGE = Constants.BATTERY_HEALTH_OVER_VOLTAGE;
    public static final int BATTERY_HEALTH_UNSPECIFIED_FAILURE = Constants.BATTERY_HEALTH_UNSPECIFIED_FAILURE;
    public static final int BATTERY_HEALTH_COLD = Constants.BATTERY_HEALTH_COLD;

    // values of the "plugged" field in the ACTION_BATTERY_CHANGED intent.
    // These must be powers of 2.
    /** Power source is an AC charger. */
    public static final int BATTERY_PLUGGED_AC = OsProtoEnums.BATTERY_PLUGGED_AC; // = 1
    /** Power source is a USB port. */
    public static final int BATTERY_PLUGGED_USB = OsProtoEnums.BATTERY_PLUGGED_USB; // = 2
    /** Power source is wireless. */
    public static final int BATTERY_PLUGGED_WIRELESS = OsProtoEnums.BATTERY_PLUGGED_WIRELESS; // = 4

    /** @hide */
    public static final int BATTERY_PLUGGED_ANY =
            BATTERY_PLUGGED_AC | BATTERY_PLUGGED_USB | BATTERY_PLUGGED_WIRELESS;

    /**
     * Sent when the device's battery has started charging (or has reached full charge
     * and the device is on power).  This is a good time to do work that you would like to
     * avoid doing while on battery (that is to avoid draining the user's battery due to
     * things they don't care enough about).
     *
     * This is paired with {@link #ACTION_DISCHARGING}.  The current state can always
     * be retrieved with {@link #isCharging()}.
     */
    public static final String ACTION_CHARGING = "android.os.action.CHARGING";

    /**
     * Sent when the device's battery may be discharging, so apps should avoid doing
     * extraneous work that would cause it to discharge faster.
     *
     * This is paired with {@link #ACTION_CHARGING}.  The current state can always
     * be retrieved with {@link #isCharging()}.
     */
    public static final String ACTION_DISCHARGING = "android.os.action.DISCHARGING";

    /*
     * Battery property identifiers.  These must match the values in
     * frameworks/native/include/batteryservice/BatteryService.h
     */
    /** Battery capacity in microampere-hours, as an integer. */
    public static final int BATTERY_PROPERTY_CHARGE_COUNTER = 1;

    /**
     * Instantaneous battery current in microamperes, as an integer.  Positive
     * values indicate net current entering the battery from a charge source,
     * negative values indicate net current discharging from the battery.
     */
    public static final int BATTERY_PROPERTY_CURRENT_NOW = 2;

    /**
     * Average battery current in microamperes, as an integer.  Positive
     * values indicate net current entering the battery from a charge source,
     * negative values indicate net current discharging from the battery.
     * The time period over which the average is computed may depend on the
     * fuel gauge hardware and its configuration.
     */
    public static final int BATTERY_PROPERTY_CURRENT_AVERAGE = 3;

    /**
     * Remaining battery capacity as an integer percentage of total capacity
     * (with no fractional part).
     */
    public static final int BATTERY_PROPERTY_CAPACITY = 4;

    /**
     * Battery remaining energy in nanowatt-hours, as a long integer.
     */
    public static final int BATTERY_PROPERTY_ENERGY_COUNTER = 5;

    /**
     * Battery charge status, from a BATTERY_STATUS_* value.
     */
    public static final int BATTERY_PROPERTY_STATUS = 6;

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    private final IBatteryPropertiesRegistrar mBatteryPropertiesRegistrar;

    /**
     * @removed Was previously made visible by accident.
     */
    public BatteryManager() {
        mContext = null;
        mBatteryStats = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));
        mBatteryPropertiesRegistrar = IBatteryPropertiesRegistrar.Stub.asInterface(
                ServiceManager.getService("batteryproperties"));
    }

    /** {@hide} */
    public BatteryManager(Context context,
            IBatteryStats batteryStats,
            IBatteryPropertiesRegistrar batteryPropertiesRegistrar) {
        mContext = context;
        mBatteryStats = batteryStats;
        mBatteryPropertiesRegistrar = batteryPropertiesRegistrar;
    }

    /**
     * Return true if the battery is currently considered to be charging.  This means that
     * the device is plugged in and is supplying sufficient power that the battery level is
     * going up (or the battery is fully charged).  Changes in this state are matched by
     * broadcasts of {@link #ACTION_CHARGING} and {@link #ACTION_DISCHARGING}.
     */
    public boolean isCharging() {
        try {
            return mBatteryStats.isCharging();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Query a battery property from the batteryproperties service.
     *
     * Returns the requested value, or Long.MIN_VALUE if property not
     * supported on this system or on other error.
     */
    private long queryProperty(int id) {
        long ret;

        if (mBatteryPropertiesRegistrar == null) {
            return Long.MIN_VALUE;
        }

        try {
            BatteryProperty prop = new BatteryProperty();

            if (mBatteryPropertiesRegistrar.getProperty(id, prop) == 0)
                ret = prop.getLong();
            else
                ret = Long.MIN_VALUE;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return ret;
    }

    /**
     * Return the value of a battery property of integer type.
     *
     * @param id identifier of the requested property
     *
     * @return the property value. If the property is not supported or there is any other error,
     *    return (a) 0 if {@code targetSdkVersion < VERSION_CODES.P} or (b) Integer.MIN_VALUE
     *    if {@code targetSdkVersion >= VERSION_CODES.P}.
     */
    public int getIntProperty(int id) {
        long value = queryProperty(id);
        if (value == Long.MIN_VALUE && mContext != null
                && mContext.getApplicationInfo().targetSdkVersion
                    >= android.os.Build.VERSION_CODES.P) {
            return Integer.MIN_VALUE;
        }

        return (int) value;
    }

    /**
     * Return the value of a battery property of long type If the
     * platform does not provide the property queried, this value will
     * be Long.MIN_VALUE.
     *
     * @param id identifier of the requested property
     *
     * @return the property value, or Long.MIN_VALUE if not supported.
     */
    public long getLongProperty(int id) {
        return queryProperty(id);
    }

    /**
     * Return true if the plugType given is wired
     * @param plugType {@link #BATTERY_PLUGGED_AC}, {@link #BATTERY_PLUGGED_USB},
     *        or {@link #BATTERY_PLUGGED_WIRELESS}
     *
     * @return true if plugType is wired
     * @hide
     */
    public static boolean isPlugWired(int plugType) {
        return plugType == BATTERY_PLUGGED_USB || plugType == BATTERY_PLUGGED_AC;
    }

    /**
     * Compute an approximation for how much time (in milliseconds) remains until the battery is
     * fully charged. Returns -1 if no time can be computed: either there is not enough current
     * data to make a decision or the battery is currently discharging.
     *
     * @return how much time is left, in milliseconds, until the battery is fully charged or -1 if
     *         the computation fails
     */
    public long computeChargeTimeRemaining() {
        try {
            return mBatteryStats.computeChargeTimeRemaining();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the delay for reporting battery state as charging after device is plugged in.
     * This allows machine-learning or heuristics to delay the reporting and the corresponding
     * broadcast, based on battery level, charging rate, and/or other parameters.
     *
     * @param delayMillis the delay in milliseconds, negative value to reset.
     *
     * @return True if the delay was set successfully.
     *
     * @see ACTION_CHARGING
     * @hide
     */
    @RequiresPermission(permission.POWER_SAVER)
    @SystemApi
    @TestApi
    public boolean setChargingStateUpdateDelayMillis(int delayMillis) {
        try {
            return mBatteryStats.setChargingStateUpdateDelayMillis(delayMillis);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
