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
 * {@hide}
 */
public class BatteryProperty implements Parcelable {
    /*
     * Battery property identifiers.  These must match the values in
     * frameworks/native/include/batteryservice/BatteryService.h
     */
    public static final int BATTERY_PROP_CHARGE_COUNTER = 1;
    public static final int BATTERY_PROP_CURRENT_NOW = 2;
    public static final int BATTERY_PROP_CURRENT_AVG = 3;
    public static final int BATTERY_PROP_CAPACITY = 4;

    public int valueInt;

    public BatteryProperty() {
        valueInt = Integer.MIN_VALUE;
    }

    /*
     * Parcel read/write code must be kept in sync with
     * frameworks/native/services/batteryservice/BatteryProperty.cpp
     */

    private BatteryProperty(Parcel p) {
        readFromParcel(p);
    }

    public void readFromParcel(Parcel p) {
        valueInt = p.readInt();
    }

    public void writeToParcel(Parcel p, int flags) {
        p.writeInt(valueInt);
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
