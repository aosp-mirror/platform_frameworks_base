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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Configuration for a soft access point (a.k.a. Soft AP, SAP, Hotspot).
 *
 * This is input for the framework provided by a client app, i.e. it exposes knobs to instruct the
 * framework how it should configure a hotspot.
 *
 * System apps can use this to configure a tethered hotspot using
 * {@link WifiManager#startTetheredHotspot(SoftApConfiguration)} and
 * {@link WifiManager#setSoftApConfiguration(SoftApConfiguration)}
 * or local-only hotspot using
 * {@link WifiManager#startLocalOnlyHotspot(SoftApConfiguration, Executor,
 * WifiManager.LocalOnlyHotspotCallback)}.
 *
 * Instances of this class are immutable; use {@link SoftApConfiguration.Builder} and its methods to
 * create a new instance.
 *
 * @hide
 */
@SystemApi
public final class SoftApConfiguration implements Parcelable {

    /**
     * 2GHz band.
     * @hide
     */
    @SystemApi
    public static final int BAND_2GHZ = 1 << 0;

    /**
     * 5GHz band.
     * @hide
     */
    @SystemApi
    public static final int BAND_5GHZ = 1 << 1;

    /**
     * 6GHz band.
     * @hide
     */
    @SystemApi
    public static final int BAND_6GHZ = 1 << 2;

    /**
     * Device is allowed to choose the optimal band (2Ghz, 5Ghz, 6Ghz) based on device capability,
     * operating country code and current radio conditions.
     * @hide
     */
    @SystemApi
    public static final int BAND_ANY = BAND_2GHZ | BAND_5GHZ | BAND_6GHZ;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "BAND_TYPE_" }, value = {
            BAND_2GHZ,
            BAND_5GHZ,
            BAND_6GHZ,
    })
    public @interface BandType {}

    private static boolean isBandValid(@BandType int band) {
        return ((band != 0) && ((band & ~BAND_ANY) == 0));
    }

    private static final int MIN_CH_2G_BAND = 1;
    private static final int MAX_CH_2G_BAND = 14;
    private static final int MIN_CH_5G_BAND = 34;
    private static final int MAX_CH_5G_BAND = 196;
    private static final int MIN_CH_6G_BAND = 1;
    private static final int MAX_CH_6G_BAND = 253;



    private static boolean isChannelBandPairValid(int channel, @BandType int band) {
        switch (band) {
            case BAND_2GHZ:
                if (channel < MIN_CH_2G_BAND || channel >  MAX_CH_2G_BAND) {
                    return false;
                }
                break;

            case BAND_5GHZ:
                if (channel < MIN_CH_5G_BAND || channel >  MAX_CH_5G_BAND) {
                    return false;
                }
                break;

            case BAND_6GHZ:
                if (channel < MIN_CH_6G_BAND || channel >  MAX_CH_6G_BAND) {
                    return false;
                }
                break;
            default:
                return false;
        }
        return true;
    }

    /**
     * SSID for the AP, or null for a framework-determined SSID.
     */
    private final @Nullable String mSsid;

    /**
     * BSSID for the AP, or null to use a framework-determined BSSID.
     */
    private final @Nullable MacAddress mBssid;

    /**
     * Pre-shared key for WPA2-PSK encryption (non-null enables WPA2-PSK).
     */
    private final @Nullable String mWpa2Passphrase;

    /**
     * This is a network that does not broadcast its SSID, so an
     * SSID-specific probe request must be used for scans.
     */
    private final boolean mHiddenSsid;

    /**
     * The operating band of the AP.
     * One of the band types from {@link @BandType}.
     */
    private final @BandType int mBand;

    /**
     * The operating channel of the AP.
     */
    private final int mChannel;

    /**
     * The maximim allowed number of clients that can associate to the AP.
     */
    private final int mMaxNumberOfClients;

    /**
     * The operating security type of the AP.
     * One of the security types from {@link @SecurityType}
     */
    private final @SecurityType int mSecurityType;

    /**
     * Security types we support.
     */
    /** @hide */
    @SystemApi
    public static final int SECURITY_TYPE_OPEN = 0;

    /** @hide */
    @SystemApi
    public static final int SECURITY_TYPE_WPA2_PSK = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SECURITY_TYPE" }, value = {
        SECURITY_TYPE_OPEN,
        SECURITY_TYPE_WPA2_PSK,
    })
    public @interface SecurityType {}

    /** Private constructor for Builder and Parcelable implementation. */
    private SoftApConfiguration(@Nullable String ssid, @Nullable MacAddress bssid,
            @Nullable String wpa2Passphrase, boolean hiddenSsid, @BandType int band, int channel,
            @SecurityType int securityType, int maxNumberOfClients) {
        mSsid = ssid;
        mBssid = bssid;
        mWpa2Passphrase = wpa2Passphrase;
        mHiddenSsid = hiddenSsid;
        mBand = band;
        mChannel = channel;
        mSecurityType = securityType;
        mMaxNumberOfClients = maxNumberOfClients;
    }

    @Override
    public boolean equals(Object otherObj) {
        if (this == otherObj) {
            return true;
        }
        if (!(otherObj instanceof SoftApConfiguration)) {
            return false;
        }
        SoftApConfiguration other = (SoftApConfiguration) otherObj;
        return Objects.equals(mSsid, other.mSsid)
                && Objects.equals(mBssid, other.mBssid)
                && Objects.equals(mWpa2Passphrase, other.mWpa2Passphrase)
                && mHiddenSsid == other.mHiddenSsid
                && mBand == other.mBand
                && mChannel == other.mChannel
                && mSecurityType == other.mSecurityType
                && mMaxNumberOfClients == other.mMaxNumberOfClients;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSsid, mBssid, mWpa2Passphrase, mHiddenSsid,
                mBand, mChannel, mSecurityType, mMaxNumberOfClients);
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("ssid=").append(mSsid);
        if (mBssid != null) sbuf.append(" \n bssid=").append(mBssid.toString());
        sbuf.append(" \n Wpa2Passphrase =").append(
                TextUtils.isEmpty(mWpa2Passphrase) ? "<empty>" : "<non-empty>");
        sbuf.append(" \n HiddenSsid =").append(mHiddenSsid);
        sbuf.append(" \n Band =").append(mBand);
        sbuf.append(" \n Channel =").append(mChannel);
        sbuf.append(" \n SecurityType=").append(getSecurityType());
        sbuf.append(" \n MaxClient=").append(mMaxNumberOfClients);
        return sbuf.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mSsid);
        dest.writeParcelable(mBssid, flags);
        dest.writeString(mWpa2Passphrase);
        dest.writeBoolean(mHiddenSsid);
        dest.writeInt(mBand);
        dest.writeInt(mChannel);
        dest.writeInt(mSecurityType);
        dest.writeInt(mMaxNumberOfClients);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<SoftApConfiguration> CREATOR = new Creator<SoftApConfiguration>() {
        @Override
        public SoftApConfiguration createFromParcel(Parcel in) {
            return new SoftApConfiguration(
                    in.readString(),
                    in.readParcelable(MacAddress.class.getClassLoader()),
                    in.readString(), in.readBoolean(), in.readInt(), in.readInt(), in.readInt(),
                    in.readInt());
        }

        @Override
        public SoftApConfiguration[] newArray(int size) {
            return new SoftApConfiguration[size];
        }
    };

    /**
     * Return String set to be the SSID for the AP.
     * {@link #setSsid(String)}.
     */
    @Nullable
    public String getSsid() {
        return mSsid;
    }

    /**
     * Returns MAC address set to be BSSID for the AP.
     * {@link Builder#setBssid(MacAddress)}.
     */
    @Nullable
    public MacAddress getBssid() {
        return mBssid;
    }

    /**
     * Returns String set to be passphrase for the WPA2-PSK AP.
     * {@link Builder#setWpa2Passphrase(String)}.
     */
    @Nullable
    public String getWpa2Passphrase() {
        return mWpa2Passphrase;
    }

    /**
     * Returns Boolean set to be indicate hidden (true: doesn't broadcast its SSID) or
     * not (false: broadcasts its SSID) for the AP.
     * {@link Builder#setHiddenSsid(boolean)}.
     */
    public boolean isHiddenSsid() {
        return mHiddenSsid;
    }

    /**
     * Returns {@link BandType} set to be the band for the AP.
     * {@link Builder#setBand(@BandType int)}.
     */
    public @BandType int getBand() {
        return mBand;
    }

    /**
     * Returns Integer set to be the channel for the AP.
     * {@link Builder#setChannel(int)}.
     */
    public int getChannel() {
        return mChannel;
    }

    /**
     * Get security type params which depends on which security passphrase to set.
     *
     * @return One of the security types from {@link SecurityType}.
     */
    public @SecurityType int getSecurityType() {
        return mSecurityType;
    }

    /**
     * Returns the maximum number of clients that can associate to the AP.
     * {@link Builder#setMaxNumberOfClients(int)}.
     */
    public int getMaxNumberOfClients() {
        return mMaxNumberOfClients;
    }

    /**
     * Builds a {@link SoftApConfiguration}, which allows an app to configure various aspects of a
     * Soft AP.
     *
     * All fields are optional. By default, SSID and BSSID are automatically chosen by the
     * framework, and an open network is created.
     */
    public static final class Builder {
        private String mSsid;
        private MacAddress mBssid;
        private String mWpa2Passphrase;
        private boolean mHiddenSsid;
        private int mBand;
        private int mChannel;
        private int mMaxNumberOfClients;

        private int setSecurityType() {
            int securityType = SECURITY_TYPE_OPEN;
            if (!TextUtils.isEmpty(mWpa2Passphrase)) { // WPA2-PSK network.
                securityType = SECURITY_TYPE_WPA2_PSK;
            }
            return securityType;
        }

        private void clearAllPassphrase() {
            mWpa2Passphrase = null;
        }

        /**
         * Constructs a Builder with default values (see {@link Builder}).
         */
        public Builder() {
            mSsid = null;
            mBssid = null;
            mWpa2Passphrase = null;
            mHiddenSsid = false;
            mBand = BAND_2GHZ;
            mChannel = 0;
            mMaxNumberOfClients = 0;
        }

        /**
         * Constructs a Builder initialized from an existing {@link SoftApConfiguration} instance.
         */
        public Builder(@NonNull SoftApConfiguration other) {
            Objects.requireNonNull(other);

            mSsid = other.mSsid;
            mBssid = other.mBssid;
            mWpa2Passphrase = other.mWpa2Passphrase;
            mHiddenSsid = other.mHiddenSsid;
            mBand = other.mBand;
            mChannel = other.mChannel;
            mMaxNumberOfClients = other.mMaxNumberOfClients;
        }

        /**
         * Builds the {@link SoftApConfiguration}.
         *
         * @return A new {@link SoftApConfiguration}, as configured by previous method calls.
         */
        @NonNull
        public SoftApConfiguration build() {
            return new SoftApConfiguration(mSsid, mBssid, mWpa2Passphrase,
                mHiddenSsid, mBand, mChannel, setSecurityType(), mMaxNumberOfClients);
        }

        /**
         * Specifies an SSID for the AP.
         * <p>
         * Null SSID only support when configure a local-only hotspot.
         * <p>
         * <li>If not set, defaults to null.</li>
         *
         * @param ssid SSID of valid Unicode characters, or null to have the SSID automatically
         *             chosen by the framework.
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the SSID is empty or not valid Unicode.
         */
        @NonNull
        public Builder setSsid(@Nullable String ssid) {
            if (ssid != null) {
                Preconditions.checkStringNotEmpty(ssid);
                Preconditions.checkArgument(StandardCharsets.UTF_8.newEncoder().canEncode(ssid));
            }
            mSsid = ssid;
            return this;
        }

        /**
         * Specifies a BSSID for the AP.
         * <p>
         * Only supported when configuring a local-only hotspot.
         * <p>
         * <li>If not set, defaults to null.</li>
         * @param bssid BSSID, or null to have the BSSID chosen by the framework. The caller is
         *              responsible for avoiding collisions.
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the given BSSID is the all-zero or broadcast MAC
         *                                  address.
         */
        @NonNull
        public Builder setBssid(@Nullable MacAddress bssid) {
            if (bssid != null) {
                Preconditions.checkArgument(!bssid.equals(WifiManager.ALL_ZEROS_MAC_ADDRESS));
                Preconditions.checkArgument(!bssid.equals(MacAddress.BROADCAST_ADDRESS));
            }
            mBssid = bssid;
            return this;
        }

        /**
         * Specifies that this AP should use WPA2-PSK with the given ASCII WPA2 passphrase.
         * When set to null, an open network is created.
         * <p>
         *
         * @param passphrase The passphrase to use, or null to unset a previously-set WPA2-PSK
         *                   configuration.
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the passphrase is the empty string
         */
        @NonNull
        public Builder setWpa2Passphrase(@Nullable String passphrase) {
            if (passphrase != null) {
                final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();
                if (!asciiEncoder.canEncode(passphrase)) {
                    throw new IllegalArgumentException("passphrase not ASCII encodable");
                }
                Preconditions.checkStringNotEmpty(passphrase);
            }
            clearAllPassphrase();
            mWpa2Passphrase = passphrase;
            return this;
        }

        /**
         * Specifies whether the AP is hidden (doesn't broadcast its SSID) or
         * not (broadcasts its SSID).
         * <p>
         * <li>If not set, defaults to false (i.e not a hidden network).</li>
         *
         * @param hiddenSsid true for a hidden SSID, false otherwise.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setHiddenSsid(boolean hiddenSsid) {
            mHiddenSsid = hiddenSsid;
            return this;
        }

        /**
         * Specifies the band for the AP.
         * <p>
         * <li>If not set, defaults to BAND_2GHZ {@link @BandType}.</li>
         *
         * @param band One or combination of the band types from {@link @BandType}.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setBand(@BandType int band) {
            if (!isBandValid(band)) {
                throw new IllegalArgumentException("Invalid band type");
            }
            mBand = band;
            // Since band preference is specified, no specific channel is selected.
            mChannel = 0;
            return this;
        }

        /**
         * Specifies the channel and associated band for the AP.
         *
         * The channel which AP resides on. Valid channels are country dependent.
         * <p>
         * The default for the channel is a the special value 0 to have the framework
         * auto-select a valid channel from the band configured with
         * {@link #setBand(@BandType int)}.
         *
         * The channel auto selection will offload to driver when
         * {@link SoftApCapability#isFeatureSupported(SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD)}
         * return true. Driver will auto select best channel which based on environment
         * interference to get best performance. Check {@link SoftApCapability} to get more detail.
         *
         * Note, since 6GHz band use the same channel numbering of 2.4GHz and 5GHZ bands,
         * the caller needs to pass the band containing the selected channel.
         *
         * <p>
         * <li>If not set, defaults to 0.</li>
         * @param channel operating channel of the AP.
         * @param band containing this channel.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setChannel(int channel, @BandType int band) {
            if (!isChannelBandPairValid(channel, band)) {
                throw new IllegalArgumentException("Invalid band type");
            }
            mBand = band;
            mChannel = channel;
            return this;
        }

        /**
         * Specifies the maximum number of clients that can associate to the AP.
         *
         * The maximum number of clients (STAs) which can associate to the AP.
         * The AP will reject association from any clients above this number.
         * Specify a value of 0 to have the framework automatically use the maximum number
         * which the device can support (based on hardware and carrier constraints).
         * <p>
         * Use {@link WifiManager.SoftApCallback#onCapabilityChanged(SoftApCapability)} and
         * {@link SoftApCapability#getMaxSupportedClients} to get the maximum number of clients
         * which the device supports (based on hardware and carrier constraints).
         *
         * <p>
         * <li>If not set, defaults to 0.</li>
         *
         * This method requires hardware support. If the method is used to set a
         * non-zero {@code maxNumberOfClients} value then
         * {@link WifiManager#startTetheredHotspot} will report error code
         * {@link WifiManager#SAP_START_FAILURE_UNSUPPORTED_CONFIGURATION}.
         *
         * <p>
         * Use {@link WifiManager.SoftApCallback#onCapabilityChanged(SoftApCapability)} and
         * {@link SoftApCapability#isFeatureSupported(int)}
         * with {@link SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT} to determine whether
         * or not this feature is supported.
         *
         * @param maxNumberOfClients maximum client number of the AP.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setMaxNumberOfClients(int maxNumberOfClients) {
            if (maxNumberOfClients < 0) {
                throw new IllegalArgumentException("maxNumberOfClients should be not negative");
            }
            mMaxNumberOfClients = maxNumberOfClients;
            return this;
        }
    }
}
