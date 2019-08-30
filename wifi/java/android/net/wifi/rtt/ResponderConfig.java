/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.net.wifi.ScanResult.InformationElement.EID_HT_CAPABILITIES;
import static android.net.wifi.ScanResult.InformationElement.EID_VHT_CAPABILITIES;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.aware.PeerHandle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Defines the configuration of an IEEE 802.11mc Responder. The Responder may be an Access Point
 * (AP), a Wi-Fi Aware device, or a manually configured Responder.
 * <p>
 * A Responder configuration may be constructed from a {@link ScanResult} or manually (with the
 * data obtained out-of-band from a peer).
 *
 * @hide
 */
@SystemApi
public final class ResponderConfig implements Parcelable {
    private static final String TAG = "ResponderConfig";
    private static final int AWARE_BAND_2_DISCOVERY_CHANNEL = 2437;

    /** @hide */
    @IntDef({RESPONDER_AP, RESPONDER_STA, RESPONDER_P2P_GO, RESPONDER_P2P_CLIENT, RESPONDER_AWARE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResponderType {
    }

    /**
     * Responder is an AP.
     */
    public static final int RESPONDER_AP = 0;
    /**
     * Responder is a STA.
     */
    public static final int RESPONDER_STA = 1;
    /**
     * Responder is a Wi-Fi Direct Group Owner (GO).
     */
    public static final int RESPONDER_P2P_GO = 2;
    /**
     * Responder is a Wi-Fi Direct Group Client.
     */
    public static final int RESPONDER_P2P_CLIENT = 3;
    /**
     * Responder is a Wi-Fi Aware device.
     */
    public static final int RESPONDER_AWARE = 4;


    /** @hide */
    @IntDef({
            CHANNEL_WIDTH_20MHZ, CHANNEL_WIDTH_40MHZ, CHANNEL_WIDTH_80MHZ, CHANNEL_WIDTH_160MHZ,
            CHANNEL_WIDTH_80MHZ_PLUS_MHZ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ChannelWidth {
    }

    /**
     * Channel bandwidth is 20 MHZ
     */
    public static final int CHANNEL_WIDTH_20MHZ = 0;
    /**
     * Channel bandwidth is 40 MHZ
     */
    public static final int CHANNEL_WIDTH_40MHZ = 1;
    /**
     * Channel bandwidth is 80 MHZ
     */
    public static final int CHANNEL_WIDTH_80MHZ = 2;
    /**
     * Channel bandwidth is 160 MHZ
     */
    public static final int CHANNEL_WIDTH_160MHZ = 3;
    /**
     * Channel bandwidth is 160 MHZ, but 80MHZ + 80MHZ
     */
    public static final int CHANNEL_WIDTH_80MHZ_PLUS_MHZ = 4;

    /** @hide */
    @IntDef({PREAMBLE_LEGACY, PREAMBLE_HT, PREAMBLE_VHT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PreambleType {
    }

    /**
     * Preamble type: Legacy.
     */
    public static final int PREAMBLE_LEGACY = 0;

    /**
     * Preamble type: HT.
     */
    public static final int PREAMBLE_HT = 1;

    /**
     * Preamble type: VHT.
     */
    public static final int PREAMBLE_VHT = 2;


    /**
     * The MAC address of the Responder. Will be null if a Wi-Fi Aware peer identifier (the
     * peerHandle field) ise used to identify the Responder.
     */
    public final MacAddress macAddress;

    /**
     * The peer identifier of a Wi-Fi Aware Responder. Will be null if a MAC Address (the macAddress
     * field) is used to identify the Responder.
     */
    public final PeerHandle peerHandle;

    /**
     * The device type of the Responder.
     */
    public final int responderType;

    /**
     * Indicates whether the Responder device supports IEEE 802.11mc.
     */
    public final boolean supports80211mc;

    /**
     * Responder channel bandwidth, specified using {@link ChannelWidth}.
     */
    public final int channelWidth;

    /**
     * The primary 20 MHz frequency (in MHz) of the channel of the Responder.
     */
    public final int frequency;

    /**
     * Not used if the {@link #channelWidth} is 20 MHz. If the Responder uses 40, 80 or 160 MHz,
     * this is the center frequency (in MHz), if the Responder uses 80 + 80 MHz, this is the
     * center frequency of the first segment (in MHz).
     */
    public final int centerFreq0;

    /**
     * Only used if the {@link #channelWidth} is 80 + 80 MHz. If the Responder uses 80 + 80 MHz,
     * this is the center frequency of the second segment (in MHz).
     */
    public final int centerFreq1;

    /**
     * The preamble used by the Responder, specified using {@link PreambleType}.
     */
    public final int preamble;

    /**
     * Constructs Responder configuration, using a MAC address to identify the Responder.
     *
     * @param macAddress      The MAC address of the Responder.
     * @param responderType   The type of the responder device, specified using
     *                        {@link ResponderType}.
     * @param supports80211mc Indicates whether the responder supports IEEE 802.11mc.
     * @param channelWidth    Responder channel bandwidth, specified using {@link ChannelWidth}.
     * @param frequency       The primary 20 MHz frequency (in MHz) of the channel of the Responder.
     * @param centerFreq0     Not used if the {@code channelWidth} is 20 MHz. If the Responder uses
     *                        40, 80 or 160 MHz, this is the center frequency (in MHz), if the
     *                        Responder uses 80 + 80 MHz, this is the center frequency of the first
     *                        segment (in MHz).
     * @param centerFreq1     Only used if the {@code channelWidth} is 80 + 80 MHz. If the
     *                        Responder
     *                        uses 80 + 80 MHz, this is the center frequency of the second segment
     *                        (in
     *                        MHz).
     * @param preamble        The preamble used by the Responder, specified using
     *                        {@link PreambleType}.
     */
    public ResponderConfig(@NonNull MacAddress macAddress, @ResponderType int responderType,
            boolean supports80211mc, @ChannelWidth int channelWidth, int frequency, int centerFreq0,
            int centerFreq1, @PreambleType int preamble) {
        if (macAddress == null) {
            throw new IllegalArgumentException(
                    "Invalid ResponderConfig - must specify a MAC address");
        }
        this.macAddress = macAddress;
        this.peerHandle = null;
        this.responderType = responderType;
        this.supports80211mc = supports80211mc;
        this.channelWidth = channelWidth;
        this.frequency = frequency;
        this.centerFreq0 = centerFreq0;
        this.centerFreq1 = centerFreq1;
        this.preamble = preamble;
    }

    /**
     * Constructs Responder configuration, using a Wi-Fi Aware PeerHandle to identify the Responder.
     *
     * @param peerHandle      The Wi-Fi Aware peer identifier of the Responder.
     * @param responderType   The type of the responder device, specified using
     *                        {@link ResponderType}.
     * @param supports80211mc Indicates whether the responder supports IEEE 802.11mc.
     * @param channelWidth    Responder channel bandwidth, specified using {@link ChannelWidth}.
     * @param frequency       The primary 20 MHz frequency (in MHz) of the channel of the Responder.
     * @param centerFreq0     Not used if the {@code channelWidth} is 20 MHz. If the Responder uses
     *                        40, 80 or 160 MHz, this is the center frequency (in MHz), if the
     *                        Responder uses 80 + 80 MHz, this is the center frequency of the first
     *                        segment (in MHz).
     * @param centerFreq1     Only used if the {@code channelWidth} is 80 + 80 MHz. If the
     *                        Responder
     *                        uses 80 + 80 MHz, this is the center frequency of the second segment
     *                        (in
     *                        MHz).
     * @param preamble        The preamble used by the Responder, specified using
     *                        {@link PreambleType}.
     */
    public ResponderConfig(@NonNull PeerHandle peerHandle, @ResponderType int responderType,
            boolean supports80211mc, @ChannelWidth int channelWidth, int frequency, int centerFreq0,
            int centerFreq1, @PreambleType int preamble) {
        this.macAddress = null;
        this.peerHandle = peerHandle;
        this.responderType = responderType;
        this.supports80211mc = supports80211mc;
        this.channelWidth = channelWidth;
        this.frequency = frequency;
        this.centerFreq0 = centerFreq0;
        this.centerFreq1 = centerFreq1;
        this.preamble = preamble;
    }

    /**
     * Constructs Responder configuration. This is an internal-only constructor which specifies both
     * a MAC address and a Wi-Fi PeerHandle to identify the Responder.
     *
     * @param macAddress      The MAC address of the Responder.
     * @param peerHandle      The Wi-Fi Aware peer identifier of the Responder.
     * @param responderType   The type of the responder device, specified using
     *                        {@link ResponderType}.
     * @param supports80211mc Indicates whether the responder supports IEEE 802.11mc.
     * @param channelWidth    Responder channel bandwidth, specified using {@link ChannelWidth}.
     * @param frequency       The primary 20 MHz frequency (in MHz) of the channel of the Responder.
     * @param centerFreq0     Not used if the {@code channelWidth} is 20 MHz. If the Responder uses
     *                        40, 80 or 160 MHz, this is the center frequency (in MHz), if the
     *                        Responder uses 80 + 80 MHz, this is the center frequency of the first
     *                        segment (in MHz).
     * @param centerFreq1     Only used if the {@code channelWidth} is 80 + 80 MHz. If the
     *                        Responder
     *                        uses 80 + 80 MHz, this is the center frequency of the second segment
     *                        (in
     *                        MHz).
     * @param preamble        The preamble used by the Responder, specified using
     *                        {@link PreambleType}.
     * @hide
     */
    public ResponderConfig(@NonNull MacAddress macAddress, @NonNull PeerHandle peerHandle,
            @ResponderType int responderType, boolean supports80211mc,
            @ChannelWidth int channelWidth, int frequency, int centerFreq0, int centerFreq1,
            @PreambleType int preamble) {
        this.macAddress = macAddress;
        this.peerHandle = peerHandle;
        this.responderType = responderType;
        this.supports80211mc = supports80211mc;
        this.channelWidth = channelWidth;
        this.frequency = frequency;
        this.centerFreq0 = centerFreq0;
        this.centerFreq1 = centerFreq1;
        this.preamble = preamble;
    }

    /**
     * Creates a Responder configuration from a {@link ScanResult} corresponding to an Access
     * Point (AP), which can be obtained from {@link android.net.wifi.WifiManager#getScanResults()}.
     */
    public static ResponderConfig fromScanResult(ScanResult scanResult) {
        MacAddress macAddress = MacAddress.fromString(scanResult.BSSID);
        int responderType = RESPONDER_AP;
        boolean supports80211mc = scanResult.is80211mcResponder();
        int channelWidth = translateScanResultChannelWidth(scanResult.channelWidth);
        int frequency = scanResult.frequency;
        int centerFreq0 = scanResult.centerFreq0;
        int centerFreq1 = scanResult.centerFreq1;

        int preamble;
        if (scanResult.informationElements != null && scanResult.informationElements.length != 0) {
            boolean htCapabilitiesPresent = false;
            boolean vhtCapabilitiesPresent = false;
            for (ScanResult.InformationElement ie : scanResult.informationElements) {
                if (ie.id == EID_HT_CAPABILITIES) {
                    htCapabilitiesPresent = true;
                } else if (ie.id == EID_VHT_CAPABILITIES) {
                    vhtCapabilitiesPresent = true;
                }
            }
            if (vhtCapabilitiesPresent) {
                preamble = PREAMBLE_VHT;
            } else if (htCapabilitiesPresent) {
                preamble = PREAMBLE_HT;
            } else {
                preamble = PREAMBLE_LEGACY;
            }
        } else {
            Log.e(TAG, "Scan Results do not contain IEs - using backup method to select preamble");
            if (channelWidth == CHANNEL_WIDTH_80MHZ || channelWidth == CHANNEL_WIDTH_160MHZ) {
                preamble = PREAMBLE_VHT;
            } else {
                preamble = PREAMBLE_HT;
            }
        }

        return new ResponderConfig(macAddress, responderType, supports80211mc, channelWidth,
                frequency, centerFreq0, centerFreq1, preamble);
    }

    /**
     * Creates a Responder configuration from a MAC address corresponding to a Wi-Fi Aware
     * Responder. The Responder parameters are set to defaults.
     */
    public static ResponderConfig fromWifiAwarePeerMacAddressWithDefaults(MacAddress macAddress) {
        /* Note: the parameters are those of the Aware discovery channel (channel 6). A Responder
         * is expected to be brought up and available to negotiate a maximum accuracy channel
         * (i.e. Band 5 @ 80MHz). A Responder is brought up on the peer by starting an Aware
         * Unsolicited Publisher with Ranging enabled.
         */
        return new ResponderConfig(macAddress, RESPONDER_AWARE, true, CHANNEL_WIDTH_20MHZ,
                AWARE_BAND_2_DISCOVERY_CHANNEL, 0, 0, PREAMBLE_HT);
    }

    /**
     * Creates a Responder configuration from a {@link PeerHandle} corresponding to a Wi-Fi Aware
     * Responder. The Responder parameters are set to defaults.
     */
    public static ResponderConfig fromWifiAwarePeerHandleWithDefaults(PeerHandle peerHandle) {
        /* Note: the parameters are those of the Aware discovery channel (channel 6). A Responder
         * is expected to be brought up and available to negotiate a maximum accuracy channel
         * (i.e. Band 5 @ 80MHz). A Responder is brought up on the peer by starting an Aware
         * Unsolicited Publisher with Ranging enabled.
         */
        return new ResponderConfig(peerHandle, RESPONDER_AWARE, true, CHANNEL_WIDTH_20MHZ,
                AWARE_BAND_2_DISCOVERY_CHANNEL, 0, 0, PREAMBLE_HT);
    }

    /**
     * Check whether the Responder configuration is valid.
     *
     * @return true if valid, false otherwise.
     * @hide
     */
    public boolean isValid(boolean awareSupported) {
        if (macAddress == null && peerHandle == null || macAddress != null && peerHandle != null) {
            return false;
        }
        if (!awareSupported && responderType == RESPONDER_AWARE) {
            return false;
        }

        return true;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (macAddress == null) {
            dest.writeBoolean(false);
        } else {
            dest.writeBoolean(true);
            macAddress.writeToParcel(dest, flags);
        }
        if (peerHandle == null) {
            dest.writeBoolean(false);
        } else {
            dest.writeBoolean(true);
            dest.writeInt(peerHandle.peerId);
        }
        dest.writeInt(responderType);
        dest.writeInt(supports80211mc ? 1 : 0);
        dest.writeInt(channelWidth);
        dest.writeInt(frequency);
        dest.writeInt(centerFreq0);
        dest.writeInt(centerFreq1);
        dest.writeInt(preamble);
    }

    public static final @android.annotation.NonNull Creator<ResponderConfig> CREATOR = new Creator<ResponderConfig>() {
        @Override
        public ResponderConfig[] newArray(int size) {
            return new ResponderConfig[size];
        }

        @Override
        public ResponderConfig createFromParcel(Parcel in) {
            boolean macAddressPresent = in.readBoolean();
            MacAddress macAddress = null;
            if (macAddressPresent) {
                macAddress = MacAddress.CREATOR.createFromParcel(in);
            }
            boolean peerHandlePresent = in.readBoolean();
            PeerHandle peerHandle = null;
            if (peerHandlePresent) {
                peerHandle = new PeerHandle(in.readInt());
            }
            int responderType = in.readInt();
            boolean supports80211mc = in.readInt() == 1;
            int channelWidth = in.readInt();
            int frequency = in.readInt();
            int centerFreq0 = in.readInt();
            int centerFreq1 = in.readInt();
            int preamble = in.readInt();

            if (peerHandle == null) {
                return new ResponderConfig(macAddress, responderType, supports80211mc, channelWidth,
                        frequency, centerFreq0, centerFreq1, preamble);
            } else {
                return new ResponderConfig(peerHandle, responderType, supports80211mc, channelWidth,
                        frequency, centerFreq0, centerFreq1, preamble);
            }
        }
    };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ResponderConfig)) {
            return false;
        }

        ResponderConfig lhs = (ResponderConfig) o;

        return Objects.equals(macAddress, lhs.macAddress) && Objects.equals(peerHandle,
                lhs.peerHandle) && responderType == lhs.responderType
                && supports80211mc == lhs.supports80211mc && channelWidth == lhs.channelWidth
                && frequency == lhs.frequency && centerFreq0 == lhs.centerFreq0
                && centerFreq1 == lhs.centerFreq1 && preamble == lhs.preamble;
    }

    @Override
    public int hashCode() {
        return Objects.hash(macAddress, peerHandle, responderType, supports80211mc, channelWidth,
                frequency, centerFreq0, centerFreq1, preamble);
    }

    /** @hide */
    @Override
    public String toString() {
        return new StringBuffer("ResponderConfig: macAddress=").append(macAddress).append(
                ", peerHandle=").append(peerHandle == null ? "<null>" : peerHandle.peerId).append(
                ", responderType=").append(responderType).append(", supports80211mc=").append(
                supports80211mc).append(", channelWidth=").append(channelWidth).append(
                ", frequency=").append(frequency).append(", centerFreq0=").append(
                centerFreq0).append(", centerFreq1=").append(centerFreq1).append(
                ", preamble=").append(preamble).toString();
    }

    /** @hide */
    static int translateScanResultChannelWidth(int scanResultChannelWidth) {
        switch (scanResultChannelWidth) {
            case ScanResult.CHANNEL_WIDTH_20MHZ:
                return CHANNEL_WIDTH_20MHZ;
            case ScanResult.CHANNEL_WIDTH_40MHZ:
                return CHANNEL_WIDTH_40MHZ;
            case ScanResult.CHANNEL_WIDTH_80MHZ:
                return CHANNEL_WIDTH_80MHZ;
            case ScanResult.CHANNEL_WIDTH_160MHZ:
                return CHANNEL_WIDTH_160MHZ;
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return CHANNEL_WIDTH_80MHZ_PLUS_MHZ;
            default:
                throw new IllegalArgumentException(
                        "translateScanResultChannelWidth: bad " + scanResultChannelWidth);
        }
    }
}
