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

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for a PNO (preferred network offload). A mechanism by which scans are offloaded
 * from the host device to the Wi-Fi chip.
 *
 * @hide
 */
@SystemApi
public final class PnoSettings implements Parcelable {
    private long mIntervalMs;
    private int mMin2gRssi;
    private int mMin5gRssi;
    private int mMin6gRssi;
    private List<PnoNetwork> mPnoNetworks;

    /** Construct an uninitialized PnoSettings object */
    public PnoSettings() { }

    /**
     * Get the requested PNO scan interval in milliseconds.
     *
     * @return An interval in milliseconds.
     */
    public @DurationMillisLong long getIntervalMillis() {
        return mIntervalMs;
    }

    /**
     * Set the requested PNO scan interval in milliseconds.
     *
     * @param intervalMillis An interval in milliseconds.
     */
    public void setIntervalMillis(@DurationMillisLong long intervalMillis) {
        this.mIntervalMs = intervalMillis;
    }

    /**
     * Get the requested minimum RSSI threshold (in dBm) for APs to report in scan results in the
     * 2.4GHz band.
     *
     * @return An RSSI value in dBm.
     */
    public int getMin2gRssiDbm() {
        return mMin2gRssi;
    }

    /**
     * Set the requested minimum RSSI threshold (in dBm) for APs to report in scan scan results in
     * the 2.4GHz band.
     *
     * @param min2gRssiDbm An RSSI value in dBm.
     */
    public void setMin2gRssiDbm(int min2gRssiDbm) {
        this.mMin2gRssi = min2gRssiDbm;
    }

    /**
     * Get the requested minimum RSSI threshold (in dBm) for APs to report in scan results in the
     * 5GHz band.
     *
     * @return An RSSI value in dBm.
     */
    public int getMin5gRssiDbm() {
        return mMin5gRssi;
    }

    /**
     * Set the requested minimum RSSI threshold (in dBm) for APs to report in scan scan results in
     * the 5GHz band.
     *
     * @param min5gRssiDbm An RSSI value in dBm.
     */
    public void setMin5gRssiDbm(int min5gRssiDbm) {
        this.mMin5gRssi = min5gRssiDbm;
    }

    /**
     * Get the requested minimum RSSI threshold (in dBm) for APs to report in scan results in the
     * 6GHz band.
     *
     * @return An RSSI value in dBm.
     */
    public int getMin6gRssiDbm() {
        return mMin6gRssi;
    }

    /**
     * Set the requested minimum RSSI threshold (in dBm) for APs to report in scan scan results in
     * the 6GHz band.
     *
     * @param min6gRssiDbm An RSSI value in dBm.
     */
    public void setMin6gRssiDbm(int min6gRssiDbm) {
        this.mMin6gRssi = min6gRssiDbm;
    }

    /**
     * Return the configured list of specific networks to search for in a PNO scan.
     *
     * @return A list of {@link PnoNetwork} objects, possibly empty if non configured.
     */
    @NonNull public List<PnoNetwork> getPnoNetworks() {
        return mPnoNetworks;
    }

    /**
     * Set the list of specified networks to scan for in a PNO scan. The networks (APs) are
     * specified using {@link PnoNetwork}s. An empty list indicates that all networks are scanned
     * for.
     *
     * @param pnoNetworks A (possibly empty) list of {@link PnoNetwork} objects.
     */
    public void setPnoNetworks(@NonNull List<PnoNetwork> pnoNetworks) {
        this.mPnoNetworks = pnoNetworks;
    }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof PnoSettings)) {
            return false;
        }
        PnoSettings settings = (PnoSettings) rhs;
        if (settings == null) {
            return false;
        }
        return mIntervalMs == settings.mIntervalMs
                && mMin2gRssi == settings.mMin2gRssi
                && mMin5gRssi == settings.mMin5gRssi
                && mMin6gRssi == settings.mMin6gRssi
                && mPnoNetworks.equals(settings.mPnoNetworks);
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Objects.hash(mIntervalMs, mMin2gRssi, mMin5gRssi, mMin6gRssi, mPnoNetworks);
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * implement Parcelable interface
     * |flag| is ignored.
     **/
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeLong(mIntervalMs);
        out.writeInt(mMin2gRssi);
        out.writeInt(mMin5gRssi);
        out.writeInt(mMin6gRssi);
        out.writeTypedList(mPnoNetworks);
    }

    /** implement Parcelable interface */
    @NonNull public static final Parcelable.Creator<PnoSettings> CREATOR =
            new Parcelable.Creator<PnoSettings>() {
        @Override
        public PnoSettings createFromParcel(Parcel in) {
            PnoSettings result = new PnoSettings();
            result.mIntervalMs = in.readLong();
            result.mMin2gRssi = in.readInt();
            result.mMin5gRssi = in.readInt();
            result.mMin6gRssi = in.readInt();

            result.mPnoNetworks = new ArrayList<>();
            in.readTypedList(result.mPnoNetworks, PnoNetwork.CREATOR);

            return result;
        }

        @Override
        public PnoSettings[] newArray(int size) {
            return new PnoSettings[size];
        }
    };
}
