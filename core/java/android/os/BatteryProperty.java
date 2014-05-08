/* Copyright 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package android.os;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Battery properties that may be queried using
 * {@link BatteryManager#getProperty
 * BatteryManager.getProperty()}
 */
public class BatteryProperty implements Parcelable {
    /*
     * Battery property identifiers.  These must match the values in
     * frameworks/native/include/batteryservice/BatteryService.h
     */
    /** Battery capacity in microampere-hours, as an integer. */
    public static final int CHARGE_COUNTER = 1;

    /**
     * Instantaneous battery current in microamperes, as an integer.  Positive
     * values indicate net current entering the battery from a charge source,
     * negative values indicate net current discharging from the battery.
     */
    public static final int CURRENT_NOW = 2;

    /**
     * Average battery current in microamperes, as an integer.  Positive
     * values indicate net current entering the battery from a charge source,
     * negative values indicate net current discharging from the battery.
     * The time period over which the average is computed may depend on the
     * fuel gauge hardware and its configuration.
     */
    public static final int CURRENT_AVERAGE = 3;

    /**
     * Remaining battery capacity as an integer percentage of total capacity
     * (with no fractional part).
     */
    public static final int CAPACITY = 4;

    /**
     * Battery remaining energy in nanowatt-hours, as a long integer.
     */
    public static final int ENERGY_COUNTER = 4;

    private long mValueLong;

    /**
     * @hide
     */
    public BatteryProperty() {
        mValueLong = Long.MIN_VALUE;
    }

    /**
     * Return the value of a property of integer type previously queried
     * via {@link BatteryManager#getProperty
     * BatteryManager.getProperty()}.  If the platform does
     * not provide the property queried, this value will be
     * Integer.MIN_VALUE.
     *
     * @return The queried property value, or Integer.MIN_VALUE if not supported.
     */
    public int getInt() {
        return (int)mValueLong;
    }

    /**
     * Return the value of a property of long type previously queried
     * via {@link BatteryManager#getProperty
     * BatteryManager.getProperty()}.  If the platform does
     * not provide the property queried, this value will be
     * Long.MIN_VALUE.
     *
     * @return The queried property value, or Long.MIN_VALUE if not supported.
     */
    public long getLong() {
        return mValueLong;
    }
    /*
     * Parcel read/write code must be kept in sync with
     * frameworks/native/services/batteryservice/BatteryProperty.cpp
     */

    private BatteryProperty(Parcel p) {
        readFromParcel(p);
    }

    public void readFromParcel(Parcel p) {
        mValueLong = p.readLong();
    }

    public void writeToParcel(Parcel p, int flags) {
        p.writeLong(mValueLong);
    }

    public static final Parcelable.Creator<BatteryProperty> CREATOR
        = new Parcelable.Creator<BatteryProperty>() {
        public BatteryProperty createFromParcel(Parcel p) {
            return new BatteryProperty(p);
        }

        public BatteryProperty[] newArray(int size) {
            return new BatteryProperty[size];
        }
    };

    public int describeContents() {
        return 0;
    }
}
