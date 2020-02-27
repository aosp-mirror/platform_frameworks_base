/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.nl80211;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * HiddenNetwork for wificond
 *
 * @hide
 */
public class HiddenNetwork implements Parcelable {
    private static final String TAG = "HiddenNetwork";

    public byte[] ssid;

    /** public constructor */
    public HiddenNetwork() { }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof HiddenNetwork)) {
            return false;
        }
        HiddenNetwork network = (HiddenNetwork) rhs;
        return Arrays.equals(ssid, network.ssid);
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Arrays.hashCode(ssid);
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * implement Parcelable interface
     * |flags| is ignored.
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeByteArray(ssid);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<HiddenNetwork> CREATOR =
            new Parcelable.Creator<HiddenNetwork>() {
        /**
         * Caller is responsible for providing a valid parcel.
         */
        @Override
        public HiddenNetwork createFromParcel(Parcel in) {
            HiddenNetwork result = new HiddenNetwork();
            result.ssid = in.createByteArray();
            return result;
        }

        @Override
        public HiddenNetwork[] newArray(int size) {
            return new HiddenNetwork[size];
        }
    };
}
