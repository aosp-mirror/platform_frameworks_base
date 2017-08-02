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

import android.os.Parcelable;
import android.os.Parcel;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkUtils;
import android.text.TextUtils;

import java.lang.Math;
import java.net.InetAddress;
import java.net.Inet4Address;
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
    private String mBSSID;
    private WifiSsid mWifiSsid;
    private int mNetworkId;

    /** @hide **/
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
     * Link speed in Mbps
     */
    public static final String LINK_SPEED_UNITS = "Mbps";
    private int mLinkSpeed;

    /**
     * Frequency in MHz
     */
    public static final String FREQUENCY_UNITS = "MHz";
    private int mFrequency;

    private InetAddress mIpAddress;
    private String mMacAddress = DEFAULT_MAC_ADDRESS;

    private boolean mEphemeral;

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
     * Average rate of lost transmitted packets, in units of packets per 5 seconds.
     * @hide
     */
    public double txBadRate;
    /**
     * Average rate of transmitted retry packets, in units of packets per 5 seconds.
     * @hide
     */
    public double txRetriesRate;
    /**
     * Average rate of successfully transmitted unicast packets, in units of packets per 5 seconds.
     * @hide
     */
    public double txSuccessRate;
    /**
     * Average rate of received unicast data packets, in units of packets per 5 seconds.
     * @hide
     */
    public double rxSuccessRate;

    private static final long RESET_TIME_STAMP = Long.MIN_VALUE;
    private static final long FILTER_TIME_CONSTANT = 3000;
    /**
     * This factor is used to adjust the rate output under the new algorithm
     * such that the result is comparable to the previous algorithm.
     * This actually converts from unit 'packets per second' to 'packets per 5 seconds'.
     */
    private static final long OUTPUT_SCALE_FACTOR = 5;
    private long mLastPacketCountUpdateTimeStamp;

    /**
     * @hide
     */
    public int badRssiCount;

    /**
     * @hide
     */
    public int linkStuckCount;

    /**
     * @hide
     */
    public int lowRssiCount;

    /**
     * @hide
     */
    public int score;

    /**
     * @hide
     */
    public void updatePacketRates(WifiLinkLayerStats stats, long timeStamp) {
        if (stats != null) {
            long txgood = stats.txmpdu_be + stats.txmpdu_bk + stats.txmpdu_vi + stats.txmpdu_vo;
            long txretries = stats.retries_be + stats.retries_bk
                    + stats.retries_vi + stats.retries_vo;
            long rxgood = stats.rxmpdu_be + stats.rxmpdu_bk + stats.rxmpdu_vi + stats.rxmpdu_vo;
            long txbad = stats.lostmpdu_be + stats.lostmpdu_bk
                    + stats.lostmpdu_vi + stats.lostmpdu_vo;

            if (mLastPacketCountUpdateTimeStamp != RESET_TIME_STAMP
                    && mLastPacketCountUpdateTimeStamp < timeStamp
                    && txBad <= txbad
                    && txSuccess <= txgood
                    && rxSuccess <= rxgood
                    && txRetries <= txretries) {
                    long timeDelta = timeStamp - mLastPacketCountUpdateTimeStamp;
                    double lastSampleWeight = Math.exp(-1.0 * timeDelta / FILTER_TIME_CONSTANT);
                    double currentSampleWeight = 1.0 - lastSampleWeight;

                    txBadRate = txBadRate * lastSampleWeight
                        + (txbad - txBad) * OUTPUT_SCALE_FACTOR * 1000 / timeDelta
                        * currentSampleWeight;
                    txSuccessRate = txSuccessRate * lastSampleWeight
                        + (txgood - txSuccess) * OUTPUT_SCALE_FACTOR * 1000 / timeDelta
                        * currentSampleWeight;
                    rxSuccessRate = rxSuccessRate * lastSampleWeight
                        + (rxgood - rxSuccess) * OUTPUT_SCALE_FACTOR * 1000 / timeDelta
                        * currentSampleWeight;
                    txRetriesRate = txRetriesRate * lastSampleWeight
                        + (txretries - txRetries) * OUTPUT_SCALE_FACTOR * 1000/ timeDelta
                        * currentSampleWeight;
            } else {
                txBadRate = 0;
                txSuccessRate = 0;
                rxSuccessRate = 0;
                txRetriesRate = 0;
            }
            txBad = txbad;
            txSuccess = txgood;
            rxSuccess = rxgood;
            txRetries = txretries;
            mLastPacketCountUpdateTimeStamp = timeStamp;
        } else {
            txBad = 0;
            txSuccess = 0;
            rxSuccess = 0;
            txRetries = 0;
            txBadRate = 0;
            txSuccessRate = 0;
            rxSuccessRate = 0;
            txRetriesRate = 0;
            mLastPacketCountUpdateTimeStamp = RESET_TIME_STAMP;
        }
    }


    /**
     * This function is less powerful and used if the WifiLinkLayerStats API is not implemented
     * at the Wifi HAL
     * @hide
     */
    public void updatePacketRates(long txPackets, long rxPackets) {
        //paranoia
        txBad = 0;
        txRetries = 0;
        txBadRate = 0;
        txRetriesRate = 0;
        if (txSuccess <= txPackets && rxSuccess <= rxPackets) {
            txSuccessRate = (txSuccessRate * 0.5)
                    + ((double) (txPackets - txSuccess) * 0.5);
            rxSuccessRate = (rxSuccessRate * 0.5)
                    + ((double) (rxPackets - rxSuccess) * 0.5);
        } else {
            txBadRate = 0;
            txRetriesRate = 0;
        }
        txSuccess = txPackets;
        rxSuccess = rxPackets;
    }

        /**
         * Flag indicating that AP has hinted that upstream connection is metered,
         * and sensitive to heavy data transfers.
         */
    private boolean mMeteredHint;

    /** @hide */
    public WifiInfo() {
        mWifiSsid = null;
        mBSSID = null;
        mNetworkId = -1;
        mSupplicantState = SupplicantState.UNINITIALIZED;
        mRssi = INVALID_RSSI;
        mLinkSpeed = -1;
        mFrequency = -1;
        mLastPacketCountUpdateTimeStamp = RESET_TIME_STAMP;
    }

    /** @hide */
    public void reset() {
        setInetAddress(null);
        setBSSID(null);
        setSSID(null);
        setNetworkId(-1);
        setRssi(INVALID_RSSI);
        setLinkSpeed(-1);
        setFrequency(-1);
        setMeteredHint(false);
        setEphemeral(false);
        txBad = 0;
        txSuccess = 0;
        rxSuccess = 0;
        txRetries = 0;
        txBadRate = 0;
        txSuccessRate = 0;
        rxSuccessRate = 0;
        txRetriesRate = 0;
        lowRssiCount = 0;
        badRssiCount = 0;
        linkStuckCount = 0;
        score = 0;
        mLastPacketCountUpdateTimeStamp = RESET_TIME_STAMP;
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
            mFrequency = source.mFrequency;
            mIpAddress = source.mIpAddress;
            mMacAddress = source.mMacAddress;
            mMeteredHint = source.mMeteredHint;
            mEphemeral = source.mEphemeral;
            txBad = source.txBad;
            txRetries = source.txRetries;
            txSuccess = source.txSuccess;
            rxSuccess = source.rxSuccess;
            txBadRate = source.txBadRate;
            txRetriesRate = source.txRetriesRate;
            txSuccessRate = source.txSuccessRate;
            rxSuccessRate = source.rxSuccessRate;
            mLastPacketCountUpdateTimeStamp =
                source.mLastPacketCountUpdateTimeStamp;
            score = source.score;
            badRssiCount = source.badRssiCount;
            lowRssiCount = source.lowRssiCount;
            linkStuckCount = source.linkStuckCount;
        }
    }

    /** @hide */
    public void setSSID(WifiSsid wifiSsid) {
        mWifiSsid = wifiSsid;
    }

    /**
     * Returns the service set identifier (SSID) of the current 802.11 network.
     * If the SSID can be decoded as UTF-8, it will be returned surrounded by double
     * quotation marks. Otherwise, it is returned as a string of hex digits. The
     * SSID may be &lt;unknown ssid&gt; if there is no network currently connected,
     * or if the caller has insufficient permissions to access the SSID.
     * @return the SSID
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
    public WifiSsid getWifiSsid() {
        return mWifiSsid;
    }

    /** @hide */
    public void setBSSID(String BSSID) {
        mBSSID = BSSID;
    }

    /**
     * Return the basic service set identifier (BSSID) of the current access point.
     * The BSSID may be {@code null} if there is no network currently connected.
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
    public void setRssi(int rssi) {
        if (rssi < INVALID_RSSI)
            rssi = INVALID_RSSI;
        if (rssi > MAX_RSSI)
            rssi = MAX_RSSI;
        mRssi = rssi;
    }

    /**
     * Returns the current link speed in {@link #LINK_SPEED_UNITS}.
     * @return the link speed.
     * @see #LINK_SPEED_UNITS
     */
    public int getLinkSpeed() {
        return mLinkSpeed;
    }

    /** @hide */
    public void setLinkSpeed(int linkSpeed) {
        this.mLinkSpeed = linkSpeed;
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
    public boolean is5GHz() {
        return ScanResult.is5GHz(mFrequency);
    }

    /**
     * @hide
     * This returns txSuccessRate in packets per second.
     */
    public double getTxSuccessRatePps() {
        return txSuccessRate / OUTPUT_SCALE_FACTOR;
    }

    /**
     * @hide
     * This returns rxSuccessRate in packets per second.
     */
    public double getRxSuccessRatePps() {
        return rxSuccessRate / OUTPUT_SCALE_FACTOR;
    }

    /**
     * Record the MAC address of the WLAN interface
     * @param macAddress the MAC address in {@code XX:XX:XX:XX:XX:XX} form
     * @hide
     */
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
    public boolean getMeteredHint() {
        return mMeteredHint;
    }

    /** {@hide} */
    public void setEphemeral(boolean ephemeral) {
        mEphemeral = ephemeral;
    }

    /** {@hide} */
    public boolean isEphemeral() {
        return mEphemeral;
    }

    /** @hide */
    public void setNetworkId(int id) {
        mNetworkId = id;
    }

    /**
     * Each configured network has a unique small integer ID, used to identify
     * the network when performing operations on the supplicant. This method
     * returns the ID for the currently connected network.
     * @return the network ID, or -1 if there is no currently connected network
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

        sb.append("SSID: ").append(mWifiSsid == null ? WifiSsid.NONE : mWifiSsid).
            append(", BSSID: ").append(mBSSID == null ? none : mBSSID).
            append(", MAC: ").append(mMacAddress == null ? none : mMacAddress).
            append(", Supplicant state: ").
            append(mSupplicantState == null ? none : mSupplicantState).
            append(", RSSI: ").append(mRssi).
            append(", Link speed: ").append(mLinkSpeed).append(LINK_SPEED_UNITS).
            append(", Frequency: ").append(mFrequency).append(FREQUENCY_UNITS).
            append(", Net ID: ").append(mNetworkId).
            append(", Metered hint: ").append(mMeteredHint).
            append(", score: ").append(Integer.toString(score));
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
        dest.writeInt(score);
        dest.writeDouble(txSuccessRate);
        dest.writeDouble(txRetriesRate);
        dest.writeDouble(txBadRate);
        dest.writeDouble(rxSuccessRate);
        dest.writeInt(badRssiCount);
        dest.writeInt(lowRssiCount);
        mSupplicantState.writeToParcel(dest, flags);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiInfo> CREATOR =
        new Creator<WifiInfo>() {
            public WifiInfo createFromParcel(Parcel in) {
                WifiInfo info = new WifiInfo();
                info.setNetworkId(in.readInt());
                info.setRssi(in.readInt());
                info.setLinkSpeed(in.readInt());
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
                info.score = in.readInt();
                info.txSuccessRate = in.readDouble();
                info.txRetriesRate = in.readDouble();
                info.txBadRate = in.readDouble();
                info.rxSuccessRate = in.readDouble();
                info.badRssiCount = in.readInt();
                info.lowRssiCount = in.readInt();
                info.mSupplicantState = SupplicantState.CREATOR.createFromParcel(in);
                return info;
            }

            public WifiInfo[] newArray(int size) {
                return new WifiInfo[size];
            }
        };
}
