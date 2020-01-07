/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.IntRange;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.net.NetworkInfo.DetailedState;
import android.net.shared.Inet4AddressUtils;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumMap;
import java.util.Locale;

/**
 * Describes the state of any Wi-Fi connection that is active or
 * is in the process of being set up.
 */
public class WifiInfo implements Parcelable {
    private static final String TAG = "WifiInfo";
    /**
     * This is the map described in the Javadoc comment above. The positions
     * of the elements of the array must correspond to the ordinal values
     * of <code>DetailedState</code>.
     */
    private static final EnumMap<SupplicantState, DetailedState> stateMap =
            new EnumMap<SupplicantState, DetailedState>(SupplicantState.class);

    /**
     * Default MAC address reported to a client that does not have the
     * android.permission.LOCAL_MAC_ADDRESS permission.
     *
     * @hide
     */
    @SystemApi
    public static final String DEFAULT_MAC_ADDRESS = "02:00:00:00:00:00";

    static {
        stateMap.put(SupplicantState.DISCONNECTED, DetailedState.DISCONNECTED);
        stateMap.put(SupplicantState.INTERFACE_DISABLED, DetailedState.DISCONNECTED);
        stateMap.put(SupplicantState.INACTIVE, DetailedState.IDLE);
        stateMap.put(SupplicantState.SCANNING, DetailedState.SCANNING);
        stateMap.put(SupplicantState.AUTHENTICATING, DetailedState.CONNECTING);
        stateMap.put(SupplicantState.ASSOCIATING, DetailedState.CONNECTING);
        stateMap.put(SupplicantState.ASSOCIATED, DetailedState.CONNECTING);
        stateMap.put(SupplicantState.FOUR_WAY_HANDSHAKE, DetailedState.AUTHENTICATING);
        stateMap.put(SupplicantState.GROUP_HANDSHAKE, DetailedState.AUTHENTICATING);
        stateMap.put(SupplicantState.COMPLETED, DetailedState.OBTAINING_IPADDR);
        stateMap.put(SupplicantState.DORMANT, DetailedState.DISCONNECTED);
        stateMap.put(SupplicantState.UNINITIALIZED, DetailedState.IDLE);
        stateMap.put(SupplicantState.INVALID, DetailedState.FAILED);
    }

    private SupplicantState mSupplicantState;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private String mBSSID;
    @UnsupportedAppUsage
    private WifiSsid mWifiSsid;
    private int mNetworkId;

    /**
     * Used to indicate that the RSSI is invalid, for example if no RSSI measurements are available
     * yet.
     * @hide
     */
    @SystemApi
    public static final int INVALID_RSSI = -127;

    /** @hide **/
    public static final int MIN_RSSI = -126;

    /** @hide **/
    public static final int MAX_RSSI = 200;


    /**
     * Received Signal Strength Indicator
     */
    private int mRssi;

    /**
     * Wi-Fi standard for the connection
     */
    private @ScanResult.WifiStandard int mWifiStandard;

    /**
     * The unit in which links speeds are expressed.
     */
    public static final String LINK_SPEED_UNITS = "Mbps";
    private int mLinkSpeed;

    /**
     * Constant for unknown link speed.
     */
    public static final int LINK_SPEED_UNKNOWN = -1;

    /**
     * Tx(transmit) Link speed in Mbps
     */
    private int mTxLinkSpeed;

    /**
     * Rx(receive) Link speed in Mbps
     */
    private int mRxLinkSpeed;

    /**
     * Frequency in MHz
     */
    public static final String FREQUENCY_UNITS = "MHz";
    private int mFrequency;

    @UnsupportedAppUsage
    private InetAddress mIpAddress;
    @UnsupportedAppUsage
    private String mMacAddress = DEFAULT_MAC_ADDRESS;

    /**
     * Whether the network is ephemeral or not.
     */
    private boolean mEphemeral;

    /**
     * Whether the network is trusted or not.
     */
    private boolean mTrusted;

    /**
     * OSU (Online Sign Up) AP for Passpoint R2.
     */
    private boolean mOsuAp;

    /**
     * Fully qualified domain name of a Passpoint configuration
     */
    private String mFqdn;

    /**
     * Name of Passpoint credential provider
     */
    private String mProviderFriendlyName;

    /**
     * If connected to a network suggestion or specifier, store the package name of the app,
     * else null.
     */
    private String mAppPackageName;

    /**
     * Running total count of lost (not ACKed) transmitted unicast data packets.
     * @hide
     */
    public long txBad;
    /**
     * Running total count of transmitted unicast data retry packets.
     * @hide
     */
    public long txRetries;
    /**
     * Running total count of successfully transmitted (ACKed) unicast data packets.
     * @hide
     */
    public long txSuccess;
    /**
     * Running total count of received unicast data packets.
     * @hide
     */
    public long rxSuccess;

    private double mTxBadRate;

    /**
     * Average rate of lost transmitted packets, in units of packets per second.
     * @hide
     */
    @SystemApi
    public double getTxBadRate() {
        return mTxBadRate;
    }

    /** @hide */
    public void setTxBadRate(double txBadRate) {
        mTxBadRate = txBadRate;
    }

    private double mTxRetriesRate;

    /**
     * Average rate of transmitted retry packets, in units of packets per second.
     * @hide
     */
    @SystemApi
    public double getTxRetriesRate() {
        return mTxRetriesRate;
    }

    /** @hide */
    public void setTxRetriesRate(double txRetriesRate) {
        mTxRetriesRate = txRetriesRate;
    }

    private double mTxSuccessRate;

    /**
     * Average rate of successfully transmitted unicast packets, in units of packets per second.
     * @hide
     */
    @SystemApi
    public double getTxSuccessRate() {
        return mTxSuccessRate;
    }

    /** @hide */
    public void setTxSuccessRate(double txSuccessRate) {
        mTxSuccessRate = txSuccessRate;
    }

    private double mRxSuccessRate;

    /**
     * Average rate of received unicast data packets, in units of packets per second.
     * @hide
     */
    @SystemApi
    public double getRxSuccessRate() {
        return mRxSuccessRate;
    }

    /** @hide */
    public void setRxSuccessRate(double rxSuccessRate) {
        mRxSuccessRate = rxSuccessRate;
    }

    /** @hide */
    @UnsupportedAppUsage
    public int score;

    /**
     * The current Wifi score.
     * NOTE: this value should only be used for debugging purposes. Do not rely on this value for
     * any computations. The meaning of this value can and will change at any time without warning.
     * @hide
     */
    @SystemApi
    public int getScore() {
        return score;
    }

    /** @hide */
    public void setScore(int score) {
        this.score = score;
    }

    /**
     * Flag indicating that AP has hinted that upstream connection is metered,
     * and sensitive to heavy data transfers.
     */
    private boolean mMeteredHint;

    /** @hide */
    @UnsupportedAppUsage
    public WifiInfo() {
        mWifiSsid = null;
        mBSSID = null;
        mNetworkId = -1;
        mSupplicantState = SupplicantState.UNINITIALIZED;
        mRssi = INVALID_RSSI;
        mLinkSpeed = LINK_SPEED_UNKNOWN;
        mFrequency = -1;
    }

    /** @hide */
    public void reset() {
        setInetAddress(null);
        setBSSID(null);
        setSSID(null);
        setNetworkId(-1);
        setRssi(INVALID_RSSI);
        setLinkSpeed(LINK_SPEED_UNKNOWN);
        setTxLinkSpeedMbps(LINK_SPEED_UNKNOWN);
        setRxLinkSpeedMbps(LINK_SPEED_UNKNOWN);
        setFrequency(-1);
        setMeteredHint(false);
        setEphemeral(false);
        setOsuAp(false);
        setAppPackageName(null);
        setFQDN(null);
        setProviderFriendlyName(null);
        txBad = 0;
        txSuccess = 0;
        rxSuccess = 0;
        txRetries = 0;
        mTxBadRate = 0;
        mTxSuccessRate = 0;
        mRxSuccessRate = 0;
        mTxRetriesRate = 0;
        score = 0;
    }

    /**
     * Copy constructor
     * @hide
     */
    public WifiInfo(WifiInfo source) {
        if (source != null) {
            mSupplicantState = source.mSupplicantState;
            mBSSID = source.mBSSID;
            mWifiSsid = source.mWifiSsid;
            mNetworkId = source.mNetworkId;
            mRssi = source.mRssi;
            mLinkSpeed = source.mLinkSpeed;
            mTxLinkSpeed = source.mTxLinkSpeed;
            mRxLinkSpeed = source.mRxLinkSpeed;
            mFrequency = source.mFrequency;
            mIpAddress = source.mIpAddress;
            mMacAddress = source.mMacAddress;
            mMeteredHint = source.mMeteredHint;
            mEphemeral = source.mEphemeral;
            mTrusted = source.mTrusted;
            mAppPackageName =
                    source.mAppPackageName;
            mOsuAp = source.mOsuAp;
            mFqdn = source.mFqdn;
            mProviderFriendlyName = source.mProviderFriendlyName;
            txBad = source.txBad;
            txRetries = source.txRetries;
            txSuccess = source.txSuccess;
            rxSuccess = source.rxSuccess;
            mTxBadRate = source.mTxBadRate;
            mTxRetriesRate = source.mTxRetriesRate;
            mTxSuccessRate = source.mTxSuccessRate;
            mRxSuccessRate = source.mRxSuccessRate;
            score = source.score;
            mWifiStandard = source.mWifiStandard;
        }
    }

    /** @hide */
    public void setSSID(WifiSsid wifiSsid) {
        mWifiSsid = wifiSsid;
    }

    /**
     * Returns the service set identifier (SSID) of the current 802.11 network.
     * <p>
     * If the SSID can be decoded as UTF-8, it will be returned surrounded by double
     * quotation marks. Otherwise, it is returned as a string of hex digits.
     * The SSID may be
     * <lt>&lt;unknown ssid&gt;, if there is no network currently connected or if the caller has
     * insufficient permissions to access the SSID.<lt>
     * </p>
     * <p>
     * Prior to {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}, this method
     * always returned the SSID with no quotes around it.
     * </p>
     *
     * @return the SSID.
     */
    public String getSSID() {
        if (mWifiSsid != null) {
            String unicode = mWifiSsid.toString();
            if (!TextUtils.isEmpty(unicode)) {
                return "\"" + unicode + "\"";
            } else {
                String hex = mWifiSsid.getHexString();
                return (hex != null) ? hex : WifiManager.UNKNOWN_SSID;
            }
        }
        return WifiManager.UNKNOWN_SSID;
    }

    /** @hide */
    @UnsupportedAppUsage
    public WifiSsid getWifiSsid() {
        return mWifiSsid;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setBSSID(String BSSID) {
        mBSSID = BSSID;
    }

    /**
     * Return the basic service set identifier (BSSID) of the current access point.
     * <p>
     * The BSSID may be
     * <lt>{@code null}, if there is no network currently connected.</lt>
     * <lt>{@code "02:00:00:00:00:00"}, if the caller has insufficient permissions to access the
     * BSSID.<lt>
     * </p>
     *
     * @return the BSSID, in the form of a six-byte MAC address: {@code XX:XX:XX:XX:XX:XX}
     */
    public String getBSSID() {
        return mBSSID;
    }

    /**
     * Returns the received signal strength indicator of the current 802.11
     * network, in dBm.
     *
     * <p>Use {@link android.net.wifi.WifiManager#calculateSignalLevel} to convert this number into
     * an absolute signal level which can be displayed to a user.
     *
     * @return the RSSI.
     */
    public int getRssi() {
        return mRssi;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setRssi(int rssi) {
        if (rssi < INVALID_RSSI)
            rssi = INVALID_RSSI;
        if (rssi > MAX_RSSI)
            rssi = MAX_RSSI;
        mRssi = rssi;
    }

    /**
     * Sets the Wi-Fi standard
     * @hide
     */
    public void setWifiStandard(@ScanResult.WifiStandard int wifiStandard) {
        mWifiStandard = wifiStandard;
    }

    /**
     * Get connection Wi-Fi standard
     * @return the connection Wi-Fi standard
     */
    public @ScanResult.WifiStandard int getWifiStandard() {
        return mWifiStandard;
    }

    /**
     * Returns the current link speed in {@link #LINK_SPEED_UNITS}.
     * @return the link speed or {@link #LINK_SPEED_UNKNOWN} if link speed is unknown.
     * @see #LINK_SPEED_UNITS
     * @see #LINK_SPEED_UNKNOWN
     */
    public int getLinkSpeed() {
        return mLinkSpeed;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setLinkSpeed(int linkSpeed) {
        mLinkSpeed = linkSpeed;
    }

    /**
     * Returns the current transmit link speed in Mbps.
     * @return the Tx link speed or {@link #LINK_SPEED_UNKNOWN} if link speed is unknown.
     * @see #LINK_SPEED_UNKNOWN
     */
    @IntRange(from = -1)
    public int getTxLinkSpeedMbps() {
        return mTxLinkSpeed;
    }

    /**
     * Update the last transmitted packet bit rate in Mbps.
     * @hide
     */
    public void setTxLinkSpeedMbps(int txLinkSpeed) {
        mTxLinkSpeed = txLinkSpeed;
    }

    /**
     * Returns the current receive link speed in Mbps.
     * @return the Rx link speed or {@link #LINK_SPEED_UNKNOWN} if link speed is unknown.
     * @see #LINK_SPEED_UNKNOWN
     */
    @IntRange(from = -1)
    public int getRxLinkSpeedMbps() {
        return mRxLinkSpeed;
    }

    /**
     * Update the last received packet bit rate in Mbps.
     * @hide
     */
    public void setRxLinkSpeedMbps(int rxLinkSpeed) {
        mRxLinkSpeed = rxLinkSpeed;
    }

    /**
     * Returns the current frequency in {@link #FREQUENCY_UNITS}.
     * @return the frequency.
     * @see #FREQUENCY_UNITS
     */
    public int getFrequency() {
        return mFrequency;
    }

    /** @hide */
    public void setFrequency(int frequency) {
        this.mFrequency = frequency;
    }

    /**
     * @hide
     * TODO: makes real freq boundaries
     */
    public boolean is24GHz() {
        return ScanResult.is24GHz(mFrequency);
    }

    /**
     * @hide
     * TODO: makes real freq boundaries
     */
    @UnsupportedAppUsage
    public boolean is5GHz() {
        return ScanResult.is5GHz(mFrequency);
    }

    /**
     * Record the MAC address of the WLAN interface
     * @param macAddress the MAC address in {@code XX:XX:XX:XX:XX:XX} form
     * @hide
     */
    @UnsupportedAppUsage
    public void setMacAddress(String macAddress) {
        this.mMacAddress = macAddress;
    }

    public String getMacAddress() {
        return mMacAddress;
    }

    /**
     * @return true if {@link #getMacAddress()} has a real MAC address.
     *
     * @hide
     */
    public boolean hasRealMacAddress() {
        return mMacAddress != null && !DEFAULT_MAC_ADDRESS.equals(mMacAddress);
    }

    /**
     * Indicates if we've dynamically detected this active network connection as
     * being metered.
     *
     * @see WifiConfiguration#isMetered(WifiConfiguration, WifiInfo)
     * @hide
     */
    public void setMeteredHint(boolean meteredHint) {
        mMeteredHint = meteredHint;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public boolean getMeteredHint() {
        return mMeteredHint;
    }

    /** {@hide} */
    public void setEphemeral(boolean ephemeral) {
        mEphemeral = ephemeral;
    }

    /**
     * Returns true if the current Wifi network is ephemeral, false otherwise.
     * An ephemeral network is a network that is temporary and not persisted in the system.
     * Ephemeral networks cannot be forgotten, only disabled with
     * {@link WifiManager#disableEphemeralNetwork(String)}.
     *
     * @hide
     */
    @SystemApi
    public boolean isEphemeral() {
        return mEphemeral;
    }

    /** {@hide} */
    public void setTrusted(boolean trusted) {
        mTrusted = trusted;
    }

    /** {@hide} */
    public boolean isTrusted() {
        return mTrusted;
    }

    /** {@hide} */
    public void setOsuAp(boolean osuAp) {
        mOsuAp = osuAp;
    }

    /** {@hide} */
    @SystemApi
    public boolean isOsuAp() {
        return mOsuAp;
    }

    /** {@hide} */
    @SystemApi
    public boolean isPasspointAp() {
        return mFqdn != null && mProviderFriendlyName != null;
    }

    /** {@hide} */
    public void setFQDN(@Nullable String fqdn) {
        mFqdn = fqdn;
    }

    /**
     * Returns the Fully Qualified Domain Name of the network if it is a Passpoint network.
     */
    public @Nullable String getPasspointFqdn() {
        return mFqdn;
    }

    /** {@hide} */
    public void setProviderFriendlyName(@Nullable String providerFriendlyName) {
        mProviderFriendlyName = providerFriendlyName;
    }

    /**
     * Returns the Provider Friendly Name of the network if it is a Passpoint network.
     */
    public @Nullable String getPasspointProviderFriendlyName() {
        return mProviderFriendlyName;
    }

    /** {@hide} */
    public void setAppPackageName(@Nullable String packageName) {
        mAppPackageName = packageName;
    }

    /**
     * If this network was created in response to an app request (e.g. through Network Suggestion
     * or Network Specifier), return the package name of the app that made the request.
     * Null otherwise.
     * @hide
     */
    @SystemApi
    public @Nullable String getAppPackageName() {
        return mAppPackageName;
    }


    /** @hide */
    @UnsupportedAppUsage
    public void setNetworkId(int id) {
        mNetworkId = id;
    }

    /**
     * Each configured network has a unique small integer ID, used to identify
     * the network. This method returns the ID for the currently connected network.
     * <p>
     * The networkId may be {@code -1} if there is no currently connected network or if the caller
     * has insufficient permissions to access the network ID.
     * </p>
     *
     * @return the network ID.
     */
    public int getNetworkId() {
        return mNetworkId;
    }

    /**
     * Return the detailed state of the supplicant's negotiation with an
     * access point, in the form of a {@link SupplicantState SupplicantState} object.
     * @return the current {@link SupplicantState SupplicantState}
     */
    public SupplicantState getSupplicantState() {
        return mSupplicantState;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setSupplicantState(SupplicantState state) {
        mSupplicantState = state;
    }

    /** @hide */
    public void setInetAddress(InetAddress address) {
        mIpAddress = address;
    }

    public int getIpAddress() {
        int result = 0;
        if (mIpAddress instanceof Inet4Address) {
            result = Inet4AddressUtils.inet4AddressToIntHTL((Inet4Address) mIpAddress);
        }
        return result;
    }

    /**
     * @return {@code true} if this network does not broadcast its SSID, so an
     * SSID-specific probe request must be used for scans.
     */
    public boolean getHiddenSSID() {
        if (mWifiSsid == null) return false;
        return mWifiSsid.isHidden();
    }

    /**
     * Map a supplicant state into a fine-grained network connectivity state.
     * @param suppState the supplicant state
     * @return the corresponding {@link DetailedState}
     */
    public static DetailedState getDetailedStateOf(SupplicantState suppState) {
        return stateMap.get(suppState);
    }

    /**
     * Set the <code>SupplicantState</code> from the string name
     * of the state.
     * @param stateName the name of the state, as a <code>String</code> returned
     * in an event sent by {@code wpa_supplicant}.
     */
    @UnsupportedAppUsage
    void setSupplicantState(String stateName) {
        mSupplicantState = valueOf(stateName);
    }

    static SupplicantState valueOf(String stateName) {
        if ("4WAY_HANDSHAKE".equalsIgnoreCase(stateName))
            return SupplicantState.FOUR_WAY_HANDSHAKE;
        else {
            try {
                return SupplicantState.valueOf(stateName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return SupplicantState.INVALID;
            }
        }
    }

    /**
     * Remove double quotes (") surrounding a SSID string, if present. Otherwise, return the
     * string unmodified. Return null if the input string was null.
     * @hide
     */
    @Nullable
    @SystemApi
    public static String removeDoubleQuotes(@Nullable String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        String none = "<none>";

        sb.append("SSID: ").append(mWifiSsid == null ? WifiManager.UNKNOWN_SSID : mWifiSsid)
                .append(", BSSID: ").append(mBSSID == null ? none : mBSSID)
                .append(", MAC: ").append(mMacAddress == null ? none : mMacAddress)
                .append(", Supplicant state: ")
                .append(mSupplicantState == null ? none : mSupplicantState)
                .append(", Wi-Fi standard: ").append(mWifiStandard)
                .append(", RSSI: ").append(mRssi)
                .append(", Link speed: ").append(mLinkSpeed).append(LINK_SPEED_UNITS)
                .append(", Tx Link speed: ").append(mTxLinkSpeed).append(LINK_SPEED_UNITS)
                .append(", Rx Link speed: ").append(mRxLinkSpeed).append(LINK_SPEED_UNITS)
                .append(", Frequency: ").append(mFrequency).append(FREQUENCY_UNITS)
                .append(", Net ID: ").append(mNetworkId)
                .append(", Metered hint: ").append(mMeteredHint)
                .append(", score: ").append(Integer.toString(score));
        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mNetworkId);
        dest.writeInt(mRssi);
        dest.writeInt(mLinkSpeed);
        dest.writeInt(mTxLinkSpeed);
        dest.writeInt(mRxLinkSpeed);
        dest.writeInt(mFrequency);
        if (mIpAddress != null) {
            dest.writeByte((byte)1);
            dest.writeByteArray(mIpAddress.getAddress());
        } else {
            dest.writeByte((byte)0);
        }
        if (mWifiSsid != null) {
            dest.writeInt(1);
            mWifiSsid.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(mBSSID);
        dest.writeString(mMacAddress);
        dest.writeInt(mMeteredHint ? 1 : 0);
        dest.writeInt(mEphemeral ? 1 : 0);
        dest.writeInt(mTrusted ? 1 : 0);
        dest.writeInt(score);
        dest.writeLong(txSuccess);
        dest.writeDouble(mTxSuccessRate);
        dest.writeLong(txRetries);
        dest.writeDouble(mTxRetriesRate);
        dest.writeLong(txBad);
        dest.writeDouble(mTxBadRate);
        dest.writeLong(rxSuccess);
        dest.writeDouble(mRxSuccessRate);
        mSupplicantState.writeToParcel(dest, flags);
        dest.writeInt(mOsuAp ? 1 : 0);
        dest.writeString(mAppPackageName);
        dest.writeString(mFqdn);
        dest.writeString(mProviderFriendlyName);
        dest.writeInt(mWifiStandard);
    }

    /** Implement the Parcelable interface {@hide} */
    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Creator<WifiInfo> CREATOR =
        new Creator<WifiInfo>() {
            public WifiInfo createFromParcel(Parcel in) {
                WifiInfo info = new WifiInfo();
                info.setNetworkId(in.readInt());
                info.setRssi(in.readInt());
                info.setLinkSpeed(in.readInt());
                info.setTxLinkSpeedMbps(in.readInt());
                info.setRxLinkSpeedMbps(in.readInt());
                info.setFrequency(in.readInt());
                if (in.readByte() == 1) {
                    try {
                        info.setInetAddress(InetAddress.getByAddress(in.createByteArray()));
                    } catch (UnknownHostException e) {}
                }
                if (in.readInt() == 1) {
                    info.mWifiSsid = WifiSsid.CREATOR.createFromParcel(in);
                }
                info.mBSSID = in.readString();
                info.mMacAddress = in.readString();
                info.mMeteredHint = in.readInt() != 0;
                info.mEphemeral = in.readInt() != 0;
                info.mTrusted = in.readInt() != 0;
                info.score = in.readInt();
                info.txSuccess = in.readLong();
                info.mTxSuccessRate = in.readDouble();
                info.txRetries = in.readLong();
                info.mTxRetriesRate = in.readDouble();
                info.txBad = in.readLong();
                info.mTxBadRate = in.readDouble();
                info.rxSuccess = in.readLong();
                info.mRxSuccessRate = in.readDouble();
                info.mSupplicantState = SupplicantState.CREATOR.createFromParcel(in);
                info.mOsuAp = in.readInt() != 0;
                info.mAppPackageName = in.readString();
                info.mFqdn = in.readString();
                info.mProviderFriendlyName = in.readString();
                info.mWifiStandard = in.readInt();
                return info;
            }

            public WifiInfo[] newArray(int size) {
                return new WifiInfo[size];
            }
        };
}
