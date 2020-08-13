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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for a soft access point (a.k.a. Soft AP, SAP, Hotspot).
 *
 * This is input for the framework provided by a client app, i.e. it exposes knobs to instruct the
 * framework how it should configure a hotspot.
 *
 * System apps can use this to configure a tethered hotspot using
 * {@code WifiManager#startTetheredHotspot(SoftApConfiguration)} and
 * {@code WifiManager#setSoftApConfiguration(SoftApConfiguration)}
 * or local-only hotspot using
 * {@code WifiManager#startLocalOnlyHotspot(SoftApConfiguration, Executor,
 * WifiManager.LocalOnlyHotspotCallback)}.
 *
 * Instances of this class are immutable; use {@link SoftApConfiguration.Builder} and its methods to
 * create a new instance.
 *
 */
public final class SoftApConfiguration implements Parcelable {

    private static final String TAG = "SoftApConfiguration";

    @VisibleForTesting
    static final int PSK_MIN_LEN = 8;

    @VisibleForTesting
    static final int PSK_MAX_LEN = 63;

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
     * Pre-shared key for WPA2-PSK or WPA3-SAE-Transition or WPA3-SAE encryption which depends on
     * the security type.
     */
    private final @Nullable String mPassphrase;

    /**
     * This is a network that does not broadcast its SSID, so an
     * SSID-specific probe request must be used for scans.
     */
    private final boolean mHiddenSsid;

    /**
     * The operating band of the AP.
     * One or combination of the following band type:
     * {@link #BAND_2GHZ}, {@link #BAND_5GHZ}, {@link #BAND_6GHZ}.
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
     * One of the following security types:
     * {@link #SECURITY_TYPE_OPEN},
     * {@link #SECURITY_TYPE_WPA2_PSK},
     * {@link #SECURITY_TYPE_WPA3_SAE_TRANSITION},
     * {@link #SECURITY_TYPE_WPA3_SAE}
     */
    private final @SecurityType int mSecurityType;

    /**
     * The flag to indicate client need to authorize by user
     * when client is connecting to AP.
     */
    private final boolean mClientControlByUser;

    /**
     * The list of blocked client that can't associate to the AP.
     */
    private final List<MacAddress> mBlockedClientList;

    /**
     * The list of allowed client that can associate to the AP.
     */
    private final List<MacAddress> mAllowedClientList;

    /**
     * Whether auto shutdown of soft AP is enabled or not.
     */
    private final boolean mAutoShutdownEnabled;

    /**
     * Delay in milliseconds before shutting down soft AP when
     * there are no connected devices.
     */
    private final long mShutdownTimeoutMillis;

    /**
     * THe definition of security type OPEN.
     */
    public static final int SECURITY_TYPE_OPEN = 0;

    /**
     * The definition of security type WPA2-PSK.
     */
    public static final int SECURITY_TYPE_WPA2_PSK = 1;

    /**
     * The definition of security type WPA3-SAE Transition mode.
     */
    public static final int SECURITY_TYPE_WPA3_SAE_TRANSITION = 2;

    /**
     * The definition of security type WPA3-SAE.
     */
    public static final int SECURITY_TYPE_WPA3_SAE = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SECURITY_TYPE_" }, value = {
        SECURITY_TYPE_OPEN,
        SECURITY_TYPE_WPA2_PSK,
        SECURITY_TYPE_WPA3_SAE_TRANSITION,
        SECURITY_TYPE_WPA3_SAE,
    })
    public @interface SecurityType {}

    /** Private constructor for Builder and Parcelable implementation. */
    private SoftApConfiguration(@Nullable String ssid, @Nullable MacAddress bssid,
            @Nullable String passphrase, boolean hiddenSsid, @BandType int band, int channel,
            @SecurityType int securityType, int maxNumberOfClients, boolean shutdownTimeoutEnabled,
            long shutdownTimeoutMillis, boolean clientControlByUser,
            @NonNull List<MacAddress> blockedList, @NonNull List<MacAddress> allowedList) {
        mSsid = ssid;
        mBssid = bssid;
        mPassphrase = passphrase;
        mHiddenSsid = hiddenSsid;
        mBand = band;
        mChannel = channel;
        mSecurityType = securityType;
        mMaxNumberOfClients = maxNumberOfClients;
        mAutoShutdownEnabled = shutdownTimeoutEnabled;
        mShutdownTimeoutMillis = shutdownTimeoutMillis;
        mClientControlByUser = clientControlByUser;
        mBlockedClientList = new ArrayList<>(blockedList);
        mAllowedClientList = new ArrayList<>(allowedList);
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
                && Objects.equals(mPassphrase, other.mPassphrase)
                && mHiddenSsid == other.mHiddenSsid
                && mBand == other.mBand
                && mChannel == other.mChannel
                && mSecurityType == other.mSecurityType
                && mMaxNumberOfClients == other.mMaxNumberOfClients
                && mAutoShutdownEnabled == other.mAutoShutdownEnabled
                && mShutdownTimeoutMillis == other.mShutdownTimeoutMillis
                && mClientControlByUser == other.mClientControlByUser
                && Objects.equals(mBlockedClientList, other.mBlockedClientList)
                && Objects.equals(mAllowedClientList, other.mAllowedClientList);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSsid, mBssid, mPassphrase, mHiddenSsid,
                mBand, mChannel, mSecurityType, mMaxNumberOfClients, mAutoShutdownEnabled,
                mShutdownTimeoutMillis, mClientControlByUser, mBlockedClientList,
                mAllowedClientList);
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("ssid=").append(mSsid);
        if (mBssid != null) sbuf.append(" \n bssid=").append(mBssid.toString());
        sbuf.append(" \n Passphrase =").append(
                TextUtils.isEmpty(mPassphrase) ? "<empty>" : "<non-empty>");
        sbuf.append(" \n HiddenSsid =").append(mHiddenSsid);
        sbuf.append(" \n Band =").append(mBand);
        sbuf.append(" \n Channel =").append(mChannel);
        sbuf.append(" \n SecurityType=").append(getSecurityType());
        sbuf.append(" \n MaxClient=").append(mMaxNumberOfClients);
        sbuf.append(" \n AutoShutdownEnabled=").append(mAutoShutdownEnabled);
        sbuf.append(" \n ShutdownTimeoutMillis=").append(mShutdownTimeoutMillis);
        sbuf.append(" \n ClientControlByUser=").append(mClientControlByUser);
        sbuf.append(" \n BlockedClientList=").append(mBlockedClientList);
        sbuf.append(" \n AllowedClientList=").append(mAllowedClientList);
        return sbuf.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mSsid);
        dest.writeParcelable(mBssid, flags);
        dest.writeString(mPassphrase);
        dest.writeBoolean(mHiddenSsid);
        dest.writeInt(mBand);
        dest.writeInt(mChannel);
        dest.writeInt(mSecurityType);
        dest.writeInt(mMaxNumberOfClients);
        dest.writeBoolean(mAutoShutdownEnabled);
        dest.writeLong(mShutdownTimeoutMillis);
        dest.writeBoolean(mClientControlByUser);
        dest.writeTypedList(mBlockedClientList);
        dest.writeTypedList(mAllowedClientList);
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
                    in.readInt(), in.readBoolean(), in.readLong(), in.readBoolean(),
                    in.createTypedArrayList(MacAddress.CREATOR),
                    in.createTypedArrayList(MacAddress.CREATOR));
        }

        @Override
        public SoftApConfiguration[] newArray(int size) {
            return new SoftApConfiguration[size];
        }
    };

    /**
     * Return String set to be the SSID for the AP.
     * {@link Builder#setSsid(String)}.
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
     * Returns String set to be passphrase for current AP.
     * {@link Builder#setPassphrase(String, int)}.
     */
    @Nullable
    public String getPassphrase() {
        return mPassphrase;
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
     * Returns band type set to be the band for the AP.
     *
     * One or combination of the following band type:
     * {@link #BAND_2GHZ}, {@link #BAND_5GHZ}, {@link #BAND_6GHZ}.
     *
     * {@link Builder#setBand(int)}.
     *
     * @hide
     */
    @SystemApi
    public @BandType int getBand() {
        return mBand;
    }

    /**
     * Returns Integer set to be the channel for the AP.
     * {@link Builder#setChannel(int)}.
     *
     * @hide
     */
    @SystemApi
    public int getChannel() {
        return mChannel;
    }

    /**
     * Get security type params which depends on which security passphrase to set.
     *
     * @return One of:
     * {@link #SECURITY_TYPE_OPEN},
     * {@link #SECURITY_TYPE_WPA2_PSK},
     * {@link #SECURITY_TYPE_WPA3_SAE_TRANSITION},
     * {@link #SECURITY_TYPE_WPA3_SAE}
     */
    public @SecurityType int getSecurityType() {
        return mSecurityType;
    }

    /**
     * Returns the maximum number of clients that can associate to the AP.
     * {@link Builder#setMaxNumberOfClients(int)}.
     *
     * @hide
     */
    @SystemApi
    public int getMaxNumberOfClients() {
        return mMaxNumberOfClients;
    }

    /**
     * Returns whether auto shutdown is enabled or not.
     * The Soft AP will shutdown when there are no devices associated to it for
     * the timeout duration. See {@link Builder#setAutoShutdownEnabled(boolean)}.
     *
     * @hide
     */
    @SystemApi
    public boolean isAutoShutdownEnabled() {
        return mAutoShutdownEnabled;
    }

    /**
     * Returns the shutdown timeout in milliseconds.
     * The Soft AP will shutdown when there are no devices associated to it for
     * the timeout duration. See {@link Builder#setShutdownTimeoutMillis(long)}.
     *
     * @hide
     */
    @SystemApi
    public long getShutdownTimeoutMillis() {
        return mShutdownTimeoutMillis;
    }

    /**
     * Returns a flag indicating whether clients need to be pre-approved by the user.
     * (true: authorization required) or not (false: not required).
     * {@link Builder#setClientControlByUserEnabled(Boolean)}.
     *
     * @hide
     */
    @SystemApi
    public boolean isClientControlByUserEnabled() {
        return mClientControlByUser;
    }

    /**
     * Returns List of clients which aren't allowed to associate to the AP.
     *
     * Clients are configured using {@link Builder#setBlockedClientList(List)}
     *
     * @hide
     */
    @NonNull
    @SystemApi
    public List<MacAddress> getBlockedClientList() {
        return mBlockedClientList;
    }

    /**
     * List of clients which are allowed to associate to the AP.
     * Clients are configured using {@link Builder#setAllowedClientList(List)}
     *
     * @hide
     */
    @NonNull
    @SystemApi
    public List<MacAddress> getAllowedClientList() {
        return mAllowedClientList;
    }

    /**
     * Returns a {@link WifiConfiguration} representation of this {@link SoftApConfiguration}.
     * Note that SoftApConfiguration may contain configuration which is cannot be represented
     * by the legacy WifiConfiguration, in such cases a null will be returned.
     *
     * <li> SoftAp band in {@link WifiConfiguration.apBand} only supports
     * 2GHz, 5GHz, 2GHz+5GHz bands, so conversion is limited to these bands. </li>
     *
     * <li> SoftAp security type in {@link WifiConfiguration.KeyMgmt} only supports
     * NONE, WPA2_PSK, so conversion is limited to these security type.</li>
     * @hide
     */
    @Nullable
    @SystemApi
    public WifiConfiguration toWifiConfiguration() {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.SSID = mSsid;
        wifiConfig.preSharedKey = mPassphrase;
        wifiConfig.hiddenSSID = mHiddenSsid;
        wifiConfig.apChannel = mChannel;
        switch (mSecurityType) {
            case SECURITY_TYPE_OPEN:
                wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                break;
            case SECURITY_TYPE_WPA2_PSK:
            case SECURITY_TYPE_WPA3_SAE_TRANSITION:
                wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK);
                break;
            default:
                Log.e(TAG, "Convert fail, unsupported security type :" + mSecurityType);
                return null;
        }

        switch (mBand) {
            case BAND_2GHZ:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_2GHZ;
                break;
            case BAND_5GHZ:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_5GHZ;
                break;
            case BAND_2GHZ | BAND_5GHZ:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_ANY;
                break;
            case BAND_ANY:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_ANY;
                break;
            default:
                Log.e(TAG, "Convert fail, unsupported band setting :" + mBand);
                return null;
        }
        return wifiConfig;
    }

    /**
     * Builds a {@link SoftApConfiguration}, which allows an app to configure various aspects of a
     * Soft AP.
     *
     * All fields are optional. By default, SSID and BSSID are automatically chosen by the
     * framework, and an open network is created.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private String mSsid;
        private MacAddress mBssid;
        private String mPassphrase;
        private boolean mHiddenSsid;
        private int mBand;
        private int mChannel;
        private int mMaxNumberOfClients;
        private int mSecurityType;
        private boolean mAutoShutdownEnabled;
        private long mShutdownTimeoutMillis;
        private boolean mClientControlByUser;
        private List<MacAddress> mBlockedClientList;
        private List<MacAddress> mAllowedClientList;

        /**
         * Constructs a Builder with default values (see {@link Builder}).
         */
        public Builder() {
            mSsid = null;
            mBssid = null;
            mPassphrase = null;
            mHiddenSsid = false;
            mBand = BAND_2GHZ;
            mChannel = 0;
            mMaxNumberOfClients = 0;
            mSecurityType = SECURITY_TYPE_OPEN;
            mAutoShutdownEnabled = true; // enabled by default.
            mShutdownTimeoutMillis = 0;
            mClientControlByUser = false;
            mBlockedClientList = new ArrayList<>();
            mAllowedClientList = new ArrayList<>();
        }

        /**
         * Constructs a Builder initialized from an existing {@link SoftApConfiguration} instance.
         */
        public Builder(@NonNull SoftApConfiguration other) {
            Objects.requireNonNull(other);

            mSsid = other.mSsid;
            mBssid = other.mBssid;
            mPassphrase = other.mPassphrase;
            mHiddenSsid = other.mHiddenSsid;
            mBand = other.mBand;
            mChannel = other.mChannel;
            mMaxNumberOfClients = other.mMaxNumberOfClients;
            mSecurityType = other.mSecurityType;
            mAutoShutdownEnabled = other.mAutoShutdownEnabled;
            mShutdownTimeoutMillis = other.mShutdownTimeoutMillis;
            mClientControlByUser = other.mClientControlByUser;
            mBlockedClientList = new ArrayList<>(other.mBlockedClientList);
            mAllowedClientList = new ArrayList<>(other.mAllowedClientList);
        }

        /**
         * Builds the {@link SoftApConfiguration}.
         *
         * @return A new {@link SoftApConfiguration}, as configured by previous method calls.
         */
        @NonNull
        public SoftApConfiguration build() {
            for (MacAddress client : mAllowedClientList) {
                if (mBlockedClientList.contains(client)) {
                    throw new IllegalArgumentException("A MacAddress exist in both client list");
                }
            }
            return new SoftApConfiguration(mSsid, mBssid, mPassphrase,
                    mHiddenSsid, mBand, mChannel, mSecurityType, mMaxNumberOfClients,
                    mAutoShutdownEnabled, mShutdownTimeoutMillis, mClientControlByUser,
                    mBlockedClientList, mAllowedClientList);
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
         * Specifies that this AP should use specific security type with the given ASCII passphrase.
         *
         * @param securityType One of the following security types:
         * {@link #SECURITY_TYPE_OPEN},
         * {@link #SECURITY_TYPE_WPA2_PSK},
         * {@link #SECURITY_TYPE_WPA3_SAE_TRANSITION},
         * {@link #SECURITY_TYPE_WPA3_SAE}.
         * @param passphrase The passphrase to use for sepcific {@code securityType} configuration
         * or null with {@link #SECURITY_TYPE_OPEN}.
         *
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the passphrase length is invalid and
         *         {@code securityType} is not {@link #SECURITY_TYPE_OPEN}
         *         or non-null passphrase and {@code securityType} is
         *         {@link #SECURITY_TYPE_OPEN}.
         */
        @NonNull
        public Builder setPassphrase(@Nullable String passphrase, @SecurityType int securityType) {
            if (securityType == SECURITY_TYPE_OPEN) {
                if (passphrase != null) {
                    throw new IllegalArgumentException(
                            "passphrase should be null when security type is open");
                }
            } else {
                Preconditions.checkStringNotEmpty(passphrase);
                final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();
                if (!asciiEncoder.canEncode(passphrase)) {
                    throw new IllegalArgumentException("passphrase not ASCII encodable");
                }
                if (securityType == SECURITY_TYPE_WPA2_PSK
                        || securityType == SECURITY_TYPE_WPA3_SAE_TRANSITION) {
                    if (passphrase.length() < PSK_MIN_LEN || passphrase.length() > PSK_MAX_LEN) {
                        throw new IllegalArgumentException(
                                "Password size must be at least " + PSK_MIN_LEN
                                + " and no more than " + PSK_MAX_LEN
                                + " for WPA2_PSK and WPA3_SAE_TRANSITION Mode");
                    }
                }
            }
            mSecurityType = securityType;
            mPassphrase = passphrase;
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
         * <li>If not set, defaults to {@link #BAND_2GHZ}.</li>
         *
         * @param band One or combination of the following band type:
         * {@link #BAND_2GHZ}, {@link #BAND_5GHZ}, {@link #BAND_6GHZ}.
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
         * {@link #setBand(int)}.
         *
         * The channel auto selection will offload to driver when
         * {@link SoftApCapability#areFeaturesSupported(
         * SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD)}
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
         * {@link SoftApCapability#areFeaturesSupported(int)}
         * with {@link SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT} to determine whether
         * or not this feature is supported.
         *
         * @param maxNumberOfClients maximum client number of the AP.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setMaxNumberOfClients(@IntRange(from = 0) int maxNumberOfClients) {
            if (maxNumberOfClients < 0) {
                throw new IllegalArgumentException("maxNumberOfClients should be not negative");
            }
            mMaxNumberOfClients = maxNumberOfClients;
            return this;
        }

        /**
         * Specifies whether auto shutdown is enabled or not.
         * The Soft AP will shut down when there are no devices connected to it for
         * the timeout duration.
         *
         * <p>
         * <li>If not set, defaults to true</li>
         *
         * @param enable true to enable, false to disable.
         * @return Builder for chaining.
         *
         * @see #setShutdownTimeoutMillis(long)
         */
        @NonNull
        public Builder setAutoShutdownEnabled(boolean enable) {
            mAutoShutdownEnabled = enable;
            return this;
        }

        /**
         * Specifies the shutdown timeout in milliseconds.
         * The Soft AP will shut down when there are no devices connected to it for
         * the timeout duration.
         *
         * Specify a value of 0 to have the framework automatically use default timeout
         * setting which defined in {@link R.integer.config_wifi_framework_soft_ap_timeout_delay}
         *
         * <p>
         * <li>If not set, defaults to 0</li>
         * <li>The shut down timeout will apply when {@link #setAutoShutdownEnabled(boolean)} is
         * set to true</li>
         *
         * @param timeoutMillis milliseconds of the timeout delay.
         * @return Builder for chaining.
         *
         * @see #setAutoShutdownEnabled(boolean)
         */
        @NonNull
        public Builder setShutdownTimeoutMillis(@IntRange(from = 0) long timeoutMillis) {
            if (timeoutMillis < 0) {
                throw new IllegalArgumentException("Invalid timeout value");
            }
            mShutdownTimeoutMillis = timeoutMillis;
            return this;
        }

        /**
         * Configure the Soft AP to require manual user control of client association.
         * If disabled (the default) then any client which isn't in the blocked list
         * {@link #getBlockedClientList()} can associate to this Soft AP using the
         * correct credentials until the Soft AP capacity is reached (capacity is hardware, carrier,
         * or user limited - using {@link #setMaxNumberOfClients(int)}).
         *
         * If manual user control is enabled then clients will be accepted, rejected, or require
         * a user approval based on the configuration provided by
         * {@link #setBlockedClientList(List)} and {@link #setAllowedClientList(List)}.
         *
         * <p>
         * This method requires hardware support. Hardware support can be determined using
         * {@link WifiManager.SoftApCallback#onCapabilityChanged(SoftApCapability)} and
         * {@link SoftApCapability#areFeaturesSupported(int)}
         * with {@link SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT}
         *
         * <p>
         * If the method is called on a device without hardware support then starting the soft AP
         * using {@link WifiManager#startTetheredHotspot(SoftApConfiguration)} will fail with
         * {@link WifiManager#SAP_START_FAILURE_UNSUPPORTED_CONFIGURATION}.
         *
         * <p>
         * <li>If not set, defaults to false (i.e The authoriztion is not required).</li>
         *
         * @param enabled true for enabling the control by user, false otherwise.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setClientControlByUserEnabled(boolean enabled) {
            mClientControlByUser = enabled;
            return this;
        }


        /**
         * This method together with {@link setClientControlByUserEnabled(boolean)} control client
         * connections to the AP. If client control by user is disabled using the above method then
         * this API has no effect and clients are allowed to associate to the AP (within limit of
         * max number of clients).
         *
         * If client control by user is enabled then this API configures the list of clients
         * which are explicitly allowed. These are auto-accepted.
         *
         * All other clients which attempt to associate, whose MAC addresses are on neither list,
         * are:
         * <ul>
         * <li>Rejected</li>
         * <li>A callback {@link WifiManager.SoftApCallback#onBlockedClientConnecting(WifiClient)}
         * is issued (which allows the user to add them to the allowed client list if desired).<li>
         * </ul>
         *
         * @param allowedClientList list of clients which are allowed to associate to the AP
         *                          without user pre-approval.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setAllowedClientList(@NonNull List<MacAddress> allowedClientList) {
            mAllowedClientList = new ArrayList<>(allowedClientList);
            return this;
        }

        /**
         * This API configures the list of clients which are blocked and cannot associate
         * to the Soft AP.
         *
         * <p>
         * This method requires hardware support. Hardware support can be determined using
         * {@link WifiManager.SoftApCallback#onCapabilityChanged(SoftApCapability)} and
         * {@link SoftApCapability#areFeaturesSupported(int)}
         * with {@link SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT}
         *
         * <p>
         * If the method is called on a device without hardware support then starting the soft AP
         * using {@link WifiManager#startTetheredHotspot(SoftApConfiguration)} will fail with
         * {@link WifiManager#SAP_START_FAILURE_UNSUPPORTED_CONFIGURATION}.
         *
         * @param blockedClientList list of clients which are not allowed to associate to the AP.
         * @return Builder for chaining.
         */
        @NonNull
        public Builder setBlockedClientList(@NonNull List<MacAddress> blockedClientList) {
            mBlockedClientList = new ArrayList<>(blockedClientList);
            return this;
        }
    }
}
