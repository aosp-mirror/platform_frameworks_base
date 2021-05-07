/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.os.BatteryManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The BatteryState class is a representation of a single battery on a device.
 */
public abstract class BatteryState {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_UNKNOWN,
            STATUS_CHARGING,
            STATUS_DISCHARGING,
            STATUS_NOT_CHARGING,
            STATUS_FULL
    })
    public @interface BatteryStatus {
    }

    /** Battery status is unknown. */
    public static final int STATUS_UNKNOWN = BatteryManager.BATTERY_STATUS_UNKNOWN;
    /** Battery is charging. */
    public static final int STATUS_CHARGING = BatteryManager.BATTERY_STATUS_CHARGING;
    /** Battery is discharging. */
    public static final int STATUS_DISCHARGING = BatteryManager.BATTERY_STATUS_DISCHARGING;
    /** Battery is connected to power but not charging. */
    public static final int STATUS_NOT_CHARGING = BatteryManager.BATTERY_STATUS_NOT_CHARGING;
    /** Battery is full. */
    public static final int STATUS_FULL = BatteryManager.BATTERY_STATUS_FULL;

    /**
     * Check whether the hardware has a battery.
     *
     * @return True if the hardware has a battery, else false.
     */
    public abstract boolean isPresent();

    /**
     * Get the battery status.
     *
     * @return the battery status.
     */
    public abstract @BatteryStatus int getStatus();

    /**
     * Get remaining battery capacity as float percentage [0.0f, 1.0f] of total capacity
     * Returns NaN when battery capacity can't be read.
     *
     * @return the battery capacity.
     */
    public abstract @FloatRange(from = -1.0f, to = 1.0f) float getCapacity();
}
