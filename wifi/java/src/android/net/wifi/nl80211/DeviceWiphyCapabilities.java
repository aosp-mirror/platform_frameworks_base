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

package android.net.wifi.nl80211;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiAnnotations.ChannelWidth;
import android.net.wifi.WifiAnnotations.WifiStandard;
import android.net.wifi.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.Objects;

/**
 * DeviceWiphyCapabilities for wificond
 *
 * Contains the WiFi physical layer attributes and capabilities of the device.
 * It is used to collect these attributes from the device driver via wificond.
 *
 * @hide
 */
@SystemApi
public final class DeviceWiphyCapabilities implements Parcelable {
    private static final String TAG = "DeviceWiphyCapabilities";

    private boolean m80211nSupported;
    private boolean m80211acSupported;
    private boolean m80211axSupported;
    private boolean m80211beSupported;
    private boolean mChannelWidth160MhzSupported;
    private boolean mChannelWidth80p80MhzSupported;
    private boolean mChannelWidth320MhzSupported;
    private int mMaxNumberTxSpatialStreams;
    private int mMaxNumberRxSpatialStreams;
    private int mMaxNumberAkms;


    /** public constructor */
    public DeviceWiphyCapabilities() {
        m80211nSupported = false;
        m80211acSupported = false;
        m80211axSupported = false;
        m80211beSupported = false;
        mChannelWidth160MhzSupported = false;
        mChannelWidth80p80MhzSupported = false;
        mChannelWidth320MhzSupported = false;
        mMaxNumberTxSpatialStreams = 1;
        mMaxNumberRxSpatialStreams = 1;
        mMaxNumberAkms = 1;
    }

    /**
     * Get the IEEE 802.11 standard support
     *
     * @param standard the IEEE 802.11 standard to check on its support.
     *        valid values from {@link ScanResult}'s {@code WIFI_STANDARD_}
     * @return {@code true} if supported, {@code false} otherwise.
     */
    public boolean isWifiStandardSupported(@WifiStandard int standard) {
        switch (standard) {
            case ScanResult.WIFI_STANDARD_LEGACY:
                return true;
            case ScanResult.WIFI_STANDARD_11N:
                return m80211nSupported;
            case ScanResult.WIFI_STANDARD_11AC:
                return m80211acSupported;
            case ScanResult.WIFI_STANDARD_11AX:
                return m80211axSupported;
            case ScanResult.WIFI_STANDARD_11BE:
                return m80211beSupported;
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
    public void setWifiStandardSupport(@WifiStandard int standard, boolean support) {
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
            case ScanResult.WIFI_STANDARD_11BE:
                m80211beSupported = support;
                break;
            default:
                Log.e(TAG, "setWifiStandardSupport called with invalid standard: " + standard);
        }
    }

    /**
     * Get the support for channel bandwidth
     *
     * @param chWidth valid values from {@link ScanResult}'s {@code CHANNEL_WIDTH_}
     *
     * @return {@code true} if supported, {@code false} otherwise.
     */
    public boolean isChannelWidthSupported(@ChannelWidth int chWidth) {
        switch (chWidth) {
            case ScanResult.CHANNEL_WIDTH_20MHZ:
                return true;
            case ScanResult.CHANNEL_WIDTH_40MHZ:
                return (m80211nSupported || m80211acSupported || m80211axSupported
                    || m80211beSupported);
            case ScanResult.CHANNEL_WIDTH_80MHZ:
                return (m80211acSupported || m80211axSupported || m80211beSupported);
            case ScanResult.CHANNEL_WIDTH_160MHZ:
                return mChannelWidth160MhzSupported;
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return mChannelWidth80p80MhzSupported;
            case ScanResult.CHANNEL_WIDTH_320MHZ:
                return mChannelWidth320MhzSupported;
            default:
                Log.e(TAG, "isChannelWidthSupported called with invalid channel width: " + chWidth);
        }
        return false;
    }

    /**
     * Set support for channel bandwidth
     *
     * @param chWidth valid values are {@link ScanResult#CHANNEL_WIDTH_160MHZ},
     *        {@link ScanResult#CHANNEL_WIDTH_80MHZ_PLUS_MHZ} and
     *        {@link ScanResult#CHANNEL_WIDTH_320MHZ}
     * @param support {@code true} if supported, {@code false} otherwise.
     *
     * @hide
     */
    public void setChannelWidthSupported(@ChannelWidth int chWidth, boolean support) {
        switch (chWidth) {
            case ScanResult.CHANNEL_WIDTH_160MHZ:
                mChannelWidth160MhzSupported = support;
                break;
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                mChannelWidth80p80MhzSupported = support;
                break;
            case ScanResult.CHANNEL_WIDTH_320MHZ:
                mChannelWidth320MhzSupported = support;
                break;
            default:
                Log.e(TAG, "setChannelWidthSupported called with Invalid channel width: "
                        + chWidth);
        }
    }

    /**
     * Get maximum number of transmit spatial streams
     *
     * @return number of spatial streams
     */
    public int getMaxNumberTxSpatialStreams() {
        return mMaxNumberTxSpatialStreams;
    }

    /**
     * Set maximum number of transmit spatial streams
     *
     * @param streams number of spatial streams
     *
     * @hide
     */
    public void setMaxNumberTxSpatialStreams(int streams) {
        mMaxNumberTxSpatialStreams = streams;
    }

    /**
     * Get maximum number of receive spatial streams
     *
     * @return number of streams
     */
    public int getMaxNumberRxSpatialStreams() {
        return mMaxNumberRxSpatialStreams;
    }

    /**
     * Get the maximum number of AKM suites supported in the connection request to the driver.
     *
     * @return maximum number of AKMs
     */
    @FlaggedApi(Flags.FLAG_GET_DEVICE_CROSS_AKM_ROAMING_SUPPORT)
    public int getMaxNumberAkms() {
        return mMaxNumberAkms;
    }

    /**
     * Set the maximum number of AKM suites supported in the connection request to the driver.
     *
     * @hide
     */
    public void setMaxNumberAkms(int akms) {
        mMaxNumberAkms = akms;
    }

    /**
     * Set maximum number of receive spatial streams
     *
     * @param streams number of streams
     *
     * @hide
     */
    public void setMaxNumberRxSpatialStreams(int streams) {
        mMaxNumberRxSpatialStreams = streams;
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
                && m80211axSupported == capa.m80211axSupported
                && m80211beSupported == capa.m80211beSupported
                && mChannelWidth160MhzSupported == capa.mChannelWidth160MhzSupported
                && mChannelWidth80p80MhzSupported == capa.mChannelWidth80p80MhzSupported
                && mChannelWidth320MhzSupported == capa.mChannelWidth320MhzSupported
                && mMaxNumberTxSpatialStreams == capa.mMaxNumberTxSpatialStreams
                && mMaxNumberRxSpatialStreams == capa.mMaxNumberRxSpatialStreams
                && mMaxNumberAkms == capa.mMaxNumberAkms;
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Objects.hash(m80211nSupported, m80211acSupported, m80211axSupported,
                m80211beSupported, mChannelWidth160MhzSupported, mChannelWidth80p80MhzSupported,
                mChannelWidth320MhzSupported, mMaxNumberTxSpatialStreams,
                mMaxNumberRxSpatialStreams, mMaxNumberAkms);
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
        out.writeBoolean(m80211beSupported);
        out.writeBoolean(mChannelWidth160MhzSupported);
        out.writeBoolean(mChannelWidth80p80MhzSupported);
        out.writeBoolean(mChannelWidth320MhzSupported);
        out.writeInt(mMaxNumberTxSpatialStreams);
        out.writeInt(mMaxNumberRxSpatialStreams);
        out.writeInt(mMaxNumberAkms);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("m80211nSupported:").append(m80211nSupported ? "Yes" : "No");
        sb.append("m80211acSupported:").append(m80211acSupported ? "Yes" : "No");
        sb.append("m80211axSupported:").append(m80211axSupported ? "Yes" : "No");
        sb.append("m80211beSupported:").append(m80211beSupported ? "Yes" : "No");
        sb.append("mChannelWidth160MhzSupported: ")
                .append(mChannelWidth160MhzSupported ? "Yes" : "No");
        sb.append("mChannelWidth80p80MhzSupported: ")
                .append(mChannelWidth80p80MhzSupported ? "Yes" : "No");
        sb.append("mChannelWidth320MhzSupported: ")
                .append(mChannelWidth320MhzSupported ? "Yes" : "No");
        sb.append("mMaxNumberTxSpatialStreams: ").append(mMaxNumberTxSpatialStreams);
        sb.append("mMaxNumberRxSpatialStreams: ").append(mMaxNumberRxSpatialStreams);
        sb.append("mMaxNumberAkms: ").append(mMaxNumberAkms);

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
            capabilities.m80211beSupported = in.readBoolean();
            capabilities.mChannelWidth160MhzSupported = in.readBoolean();
            capabilities.mChannelWidth80p80MhzSupported = in.readBoolean();
            capabilities.mChannelWidth320MhzSupported = in.readBoolean();
            capabilities.mMaxNumberTxSpatialStreams = in.readInt();
            capabilities.mMaxNumberRxSpatialStreams = in.readInt();
            capabilities.mMaxNumberAkms = in.readInt();
            return capabilities;
        }

        @Override
        public DeviceWiphyCapabilities[] newArray(int size) {
            return new DeviceWiphyCapabilities[size];
        }
    };
}
