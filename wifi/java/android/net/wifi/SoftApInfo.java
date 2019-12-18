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

package android.net.wifi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A class representing information about SoftAp.
 * {@see WifiManager}
 *
 * @hide
 */
@SystemApi
public final class SoftApInfo implements Parcelable {

    /**
     * AP Channel bandwidth is invalid.
     *
     * @see #getBandwidth()
     */
    public static final int CHANNEL_WIDTH_INVALID = 0;

    /**
     * AP Channel bandwidth is 20 MHZ but no HT.
     *
     * @see #getBandwidth()
     */
    public static final int CHANNEL_WIDTH_20MHZ_NOHT = 1;

    /**
     * AP Channel bandwidth is 20 MHZ.
     *
     * @see #getBandwidth()
     */
    public static final int CHANNEL_WIDTH_20MHZ = 2;

    /**
     * AP Channel bandwidth is 40 MHZ.
     *
     * @see #getBandwidth()
     */
    public static final int CHANNEL_WIDTH_40MHZ = 3;

    /**
     * AP Channel bandwidth is 80 MHZ.
     *
     * @see #getBandwidth()
     */
    public static final int CHANNEL_WIDTH_80MHZ = 4;

    /**
     * AP Channel bandwidth is 160 MHZ, but 80MHZ + 80MHZ.
     *
     * @see #getBandwidth()
     */
    public static final int CHANNEL_WIDTH_80MHZ_PLUS_MHZ = 5;

    /**
     * AP Channel bandwidth is 160 MHZ.
     *
     * @see #getBandwidth()
     */
    public static final int CHANNEL_WIDTH_160MHZ = 6;



    /** The frequency which AP resides on.  */
    private int mFrequency = 0;

    @WifiAnnotations.Bandwidth
    private int mBandwidth = CHANNEL_WIDTH_INVALID;

    /**
     * Get the frequency which AP resides on.
     */
    public int getFrequency() {
        return mFrequency;
    }

    /**
     * Set the frequency which AP resides on.
     * @hide
     */
    public void setFrequency(int freq) {
        mFrequency = freq;
    }

    /**
     * Get AP Channel bandwidth.
     *
     * @return One of {@link #CHANNEL_WIDTH_20MHZ}, {@link #CHANNEL_WIDTH_40MHZ},
     * {@link #CHANNEL_WIDTH_80MHZ}, {@link #CHANNEL_WIDTH_160MHZ},
     * {@link #CHANNEL_WIDTH_80MHZ_PLUS_MHZ} or {@link #CHANNEL_WIDTH_INVALID}.
     */
    @WifiAnnotations.Bandwidth
    public int getBandwidth() {
        return mBandwidth;
    }

    /**
     * Set AP Channel bandwidth.
     * @hide
     */
    public void setBandwidth(@WifiAnnotations.Bandwidth int bandwidth) {
        mBandwidth = bandwidth;
    }

    /**
     * @hide
     */
    public SoftApInfo(@Nullable SoftApInfo source) {
        if (source != null) {
            mFrequency = source.mFrequency;
            mBandwidth = source.mBandwidth;
        }
    }

    /**
     * @hide
     */
    public SoftApInfo() {
    }

    @Override
    /** Implement the Parcelable interface. */
    public int describeContents() {
        return 0;
    }

    @Override
    /** Implement the Parcelable interface */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mFrequency);
        dest.writeInt(mBandwidth);
    }

    @NonNull
    /** Implement the Parcelable interface */
    public static final Creator<SoftApInfo> CREATOR = new Creator<SoftApInfo>() {
        public SoftApInfo createFromParcel(Parcel in) {
            SoftApInfo info = new SoftApInfo();
            info.mFrequency = in.readInt();
            info.mBandwidth = in.readInt();
            return info;
        }

        public SoftApInfo[] newArray(int size) {
            return new SoftApInfo[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        return "SoftApInfo{"
                + "bandwidth= " + mBandwidth
                + ",frequency= " + mFrequency
                + '}';
    }

    @Override
    public boolean equals(@NonNull Object o) {
        if (this == o) return true;
        if (!(o instanceof SoftApInfo)) return false;
        SoftApInfo softApInfo = (SoftApInfo) o;
        return mFrequency == softApInfo.mFrequency
                && mBandwidth == softApInfo.mBandwidth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFrequency, mBandwidth);
    }
}
