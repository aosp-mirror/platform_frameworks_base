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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkUtils;
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
 * Describes the state of any Wifi connection that is active or
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
    @UnsupportedAppUsage
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

    /** @hide **/
    @UnsupportedAppUsage
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
     * The unit in which links speeds are expressed.
     */
    public static final String LINK_SPEED_UNITS = "Mbps";
    private int mLinkSpeed;

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
     * If connected to a network suggestion or specifier, store the package name of the app,
     * else null.
     */
    private String mNetworkSuggestionOrSpecifierPackageName;

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

    /**
     * Average rate of lost transmitted packets, in units of packets per second.
     * @hide
     */
    public double txBadRate;
    /**
     * Average rate of transmitted retry packets, in units of packets per second.
     * @hide
     */
    public double txRetriesRate;
    /**
     * Average rate of successfully transmitted unicast packets, in units of packets per second.
     * @hide
     */
    public double txSuccessRate;
    /**
     * Average rate of received unicast data packets, in units of packets per second.
     * @hide
     */
    public double rxSuccessRate;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public int score;

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
        mLinkSpeed = -1;
        mFrequency = -1;
    }

    /** @hide */
    public void reset() {
        setInetAddress(null);
        setBSSID(null);
        setSSID(null);
        setNetworkId(-1);
        setRssi(INVALID_RSSI);
        setLinkSpeed(-1);
        setTxLinkSpeedMbps(-1);
        setRxLinkSpeedMbps(-1);
        setFrequency(-1);
        setMeteredHint(false);
        setEphemeral(false);
        setOsuAp(false);
        setNetworkSuggestionOrSpecifierPackageName(null);
        txBad = 0;
        txSuccess = 0;
        rxSuccess = 0;
        txRetries = 0;
        txBadRate = 0;
        txSuccessRate = 0;
        rxSuccessRate = 0;
        txRetriesRate = 0;
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
            mNetworkSuggestionOrSpecifierPackageName =
                    source.mNetworkSuggestionOrSpecifierPackageName;
            mOsuAp = source.mOsuAp;
            txBad = source.txBad;
            txRetries = source.txRetries;
            txSuccess = source.txSuccess;
            rxSuccess = source.rxSuccess;
            txBadRate = source.txBadRate;
            txRetriesRate = source.txRetriesRate;
            txSuccessRate = source.txSuccessRate;
            rxSuccessRate = source.rxSuccessRate;
            score = source.score;
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
                return (hex != null) ? hex : WifiSsid.NONE;
            }
        }
        return WifiSsid.NONE;
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
     * Returns the current link speed in {@link #LINK_SPEED_UNITS}.
     * @return the link speed or -1 if there is no valid value.
     * @see #LINK_SPEED_UNITS
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
     * @return the Tx link speed or -1 if there is no valid value.
     */
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
     * @return the Rx link speed or -1 if there is no valid value.
     */
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

    /** {@hide} */
    @UnsupportedAppUsage
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
    public void setNetworkSuggestionOrSpecifierPackageName(@Nullable String packageName) {
        mNetworkSuggestionOrSpecifierPackageName = packageName;
    }

    /** {@hide} */
    public @Nullable String getNetworkSuggestionOrSpecifierPackageName() {
        return mNetworkSuggestionOrSpecifierPackageName;
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
            result = NetworkUtils.inetAddressToInt((Inet4Address)mIpAddress);
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

    /** {@hide} */
    @UnsupportedAppUsage
    public static String removeDoubleQuotes(String string) {
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

        sb.append("SSID: ").append(mWifiSsid == null ? WifiSsid.NONE : mWifiSsid)
                .append(", BSSID: ").append(mBSSID == null ? none : mBSSID)
                .append(", MAC: ").append(mMacAddress == null ? none : mMacAddress)
                .append(", Supplicant state: ")
                .append(mSupplicantState == null ? none : mSupplicantState)
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
        dest.writeDouble(txSuccessRate);
        dest.writeLong(txRetries);
        dest.writeDouble(txRetriesRate);
        dest.writeLong(txBad);
        dest.writeDouble(txBadRate);
        dest.writeLong(rxSuccess);
        dest.writeDouble(rxSuccessRate);
        mSupplicantState.writeToParcel(dest, flags);
        dest.writeInt(mOsuAp ? 1 : 0);
        dest.writeString(mNetworkSuggestionOrSpecifierPackageName);
    }

    /** Implement the Parcelable interface {@hide} */
    @UnsupportedAppUsage
    public static final Creator<WifiInfo> CREATOR =
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
                info.txSuccessRate = in.readDouble();
                info.txRetries = in.readLong();
                info.txRetriesRate = in.readDouble();
                info.txBad = in.readLong();
                info.txBadRate = in.readDouble();
                info.rxSuccess = in.readLong();
                info.rxSuccessRate = in.readDouble();
                info.mSupplicantState = SupplicantState.CREATOR.createFromParcel(in);
                info.mOsuAp = in.readInt() != 0;
                info.mNetworkSuggestionOrSpecifierPackageName = in.readString();
                return info;
            }

            public WifiInfo[] newArray(int size) {
                return new WifiInfo[size];
            }
        };
}
