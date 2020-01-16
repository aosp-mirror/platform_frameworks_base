/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.wifi.ScanResult;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.Objects;

/**
 * DeviceWiphyCapabilities for wificond
 *
 * @hide
 */
@SystemApi
public final class DeviceWiphyCapabilities implements Parcelable {
    private static final String TAG = "DeviceWiphyCapabilities";

    private boolean m80211nSupported;
    private boolean m80211acSupported;
    private boolean m80211axSupported;

    /** public constructor */
    public DeviceWiphyCapabilities() {
        m80211nSupported = false;
        m80211acSupported = false;
        m80211axSupported = false;
    }

    /**
     * Get the IEEE 802.11 standard support
     *
     * @param standard the IEEE 802.11 standard to check on its support.
     *        valid values from {@link ScanResult}'s {@code WIFI_STANDARD_}
     * @return {@code true} if supported, {@code false} otherwise.
     */
    public boolean isWifiStandardSupported(int standard) {
        switch (standard) {
            case ScanResult.WIFI_STANDARD_LEGACY:
                return true;
            case ScanResult.WIFI_STANDARD_11N:
                return m80211nSupported;
            case ScanResult.WIFI_STANDARD_11AC:
                return m80211acSupported;
            case ScanResult.WIFI_STANDARD_11AX:
                return m80211axSupported;
            default:
                Log.e(TAG, "isWifiStandardSupported called with invalid standard: " + standard);
                return false;
        }
    }

    /**
     * Set the IEEE 802.11 standard support
     *
     * @param standard the IEEE 802.11 standard to set its support.
     *        valid values from {@link ScanResult}'s {@code WIFI_STANDARD_}
     * @param support {@code true} if supported, {@code false} otherwise.
     */
    public void setWifiStandardSupport(int standard, boolean support) {
        switch (standard) {
            case ScanResult.WIFI_STANDARD_11N:
                m80211nSupported = support;
                break;
            case ScanResult.WIFI_STANDARD_11AC:
                m80211acSupported = support;
                break;
            case ScanResult.WIFI_STANDARD_11AX:
                m80211axSupported = support;
                break;
            default:
                Log.e(TAG, "setWifiStandardSupport called with invalid standard: " + standard);
        }
    }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof DeviceWiphyCapabilities)) {
            return false;
        }
        DeviceWiphyCapabilities capa = (DeviceWiphyCapabilities) rhs;

        return m80211nSupported == capa.m80211nSupported
                && m80211acSupported == capa.m80211acSupported
                && m80211axSupported == capa.m80211axSupported;
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Objects.hash(m80211nSupported, m80211acSupported, m80211axSupported);
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
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeBoolean(m80211nSupported);
        out.writeBoolean(m80211acSupported);
        out.writeBoolean(m80211axSupported);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("m80211nSupported:").append(m80211nSupported ? "Yes" : "No");
        sb.append("m80211acSupported:").append(m80211acSupported ? "Yes" : "No");
        sb.append("m80211axSupported:").append(m80211axSupported ? "Yes" : "No");
        return sb.toString();
    }

    /** implement Parcelable interface */
    public static final @NonNull Parcelable.Creator<DeviceWiphyCapabilities> CREATOR =
            new Parcelable.Creator<DeviceWiphyCapabilities>() {
        /**
         * Caller is responsible for providing a valid parcel.
         */
        @Override
        public DeviceWiphyCapabilities createFromParcel(Parcel in) {
            DeviceWiphyCapabilities capabilities = new DeviceWiphyCapabilities();
            capabilities.m80211nSupported = in.readBoolean();
            capabilities.m80211acSupported = in.readBoolean();
            capabilities.m80211axSupported = in.readBoolean();
            return capabilities;
        }

        @Override
        public DeviceWiphyCapabilities[] newArray(int size) {
            return new DeviceWiphyCapabilities[size];
        }
    };
}
