/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi.rtt;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Location Civic Report (LCR).
 * <p>
 * The information matches the IEEE 802.11-2016 LCR report.
 * <p>
 * Note: depending on the mechanism by which this information is returned (i.e. the API which
 * returns an instance of this class) it is possibly Self Reported (by the peer). In such a case
 * the information is NOT validated - use with caution. Consider validating it with other sources
 * of information before using it.
 */
public final class LocationCivic implements Parcelable {
    private final byte[] mData;

    /**
     * Parse the raw LCR information element (byte array) and extract the LocationCivic structure.
     *
     * Note: any parsing errors or invalid/unexpected errors will result in a null being returned.
     *
     * @hide
     */
    @Nullable
    public static LocationCivic parseInformationElement(byte id, byte[] data) {
        // TODO
        return null;
    }

    /** @hide */
    public LocationCivic(byte[] data) {
        mData = data;
    }

    /**
     * Return the Location Civic data reported by the peer.
     *
     * @return An arbitrary location information.
     */
    public byte[] getData() {
        return mData;
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(mData);
    }

    public static final Parcelable.Creator<LocationCivic> CREATOR =
            new Parcelable.Creator<LocationCivic>() {
                @Override
                public LocationCivic[] newArray(int size) {
                    return new LocationCivic[size];
                }

                @Override
                public LocationCivic createFromParcel(Parcel in) {
                    byte[] data = in.createByteArray();

                    return new LocationCivic(data);
                }
            };

    /** @hide */
    @Override
    public String toString() {
        return new StringBuilder("LCR: data=").append(Arrays.toString(mData)).toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof LocationCivic)) {
            return false;
        }

        LocationCivic lhs = (LocationCivic) o;

        return Arrays.equals(mData, lhs.mData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mData);
    }
}
