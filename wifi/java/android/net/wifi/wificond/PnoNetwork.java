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

package android.net.wifi.wificond;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * PnoNetwork for wificond
 *
 * @hide
 */
public class PnoNetwork implements Parcelable {

    public boolean isHidden;
    public byte[] ssid;
    public int[] frequencies;

    /** public constructor */
    public PnoNetwork() { }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof PnoNetwork)) {
            return false;
        }
        PnoNetwork network = (PnoNetwork) rhs;
        return Arrays.equals(ssid, network.ssid)
                && Arrays.equals(frequencies, network.frequencies)
                && isHidden == network.isHidden;
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Objects.hash(
                isHidden,
                Arrays.hashCode(ssid),
                Arrays.hashCode(frequencies));
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * implement Parcelable interface
     * |flag| is ignored.
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(isHidden ? 1 : 0);
        out.writeByteArray(ssid);
        out.writeIntArray(frequencies);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<PnoNetwork> CREATOR =
            new Parcelable.Creator<PnoNetwork>() {
        @Override
        public PnoNetwork createFromParcel(Parcel in) {
            PnoNetwork result = new PnoNetwork();
            result.isHidden = in.readInt() != 0 ? true : false;
            result.ssid = in.createByteArray();
            result.frequencies = in.createIntArray();
            return result;
        }

        @Override
        public PnoNetwork[] newArray(int size) {
            return new PnoNetwork[size];
        }
    };
}
