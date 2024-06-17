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
 * BatteryManager.getProperty()}
 */

/**
 * @hide
 */
public class BatteryProperty implements Parcelable {
    private long mValueLong;
    private String mValueString;

    /**
     * @hide
     */
    public BatteryProperty() {
        mValueLong = Long.MIN_VALUE;
        mValueString = null;
    }

    /**
     * @hide
     */
    public long getLong() {
        return mValueLong;
    }

    /**
     * @hide
     */
    public String getString() {
        return mValueString;
    }

    /**
     * @hide
     */
    public void setLong(long val) {
        mValueLong = val;
    }

    /**
     * @hide
     */
    public void setString(String val) {
        mValueString = val;
    }

    private BatteryProperty(Parcel p) {
        readFromParcel(p);
    }

    public void readFromParcel(Parcel p) {
        mValueLong = p.readLong();
        mValueString = p.readString8();
    }

    public void writeToParcel(Parcel p, int flags) {
        p.writeLong(mValueLong);
        p.writeString8(mValueString);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BatteryProperty> CREATOR
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
