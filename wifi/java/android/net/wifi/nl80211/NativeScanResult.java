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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Raw scan result data from the wificond daemon.
 *
 * @hide
 */
@SystemApi
public final class NativeScanResult implements Parcelable {
    private static final String TAG = "NativeScanResult";

    /** @hide */
    @VisibleForTesting
    public byte[] ssid;
    /** @hide */
    @VisibleForTesting
    public byte[] bssid;
    /** @hide */
    @VisibleForTesting
    public byte[] infoElement;
    /** @hide */
    @VisibleForTesting
    public int frequency;
    /** @hide */
    @VisibleForTesting
    public int signalMbm;
    /** @hide */
    @VisibleForTesting
    public long tsf;
    /** @hide */
    @VisibleForTesting
    @BssCapabilityBits public int capability;
    /** @hide */
    @VisibleForTesting
    public boolean associated;
    /** @hide */
    @VisibleForTesting
    public List<RadioChainInfo> radioChainInfos;

    /**
     * Returns the SSID raw byte array of the AP represented by this scan result.
     *
     * @return A byte array.
     */
    @NonNull public byte[] getSsid() {
        return ssid;
    }

    /**
     * Returns the MAC address (BSSID) of the AP represented by this scan result.
     *
     * @return a MacAddress or null on error.
     */
    @Nullable public MacAddress getBssid() {
        try {
            return MacAddress.fromBytes(bssid);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Illegal argument " + Arrays.toString(bssid), e);
            return null;
        }
    }

    /**
     * Returns the raw bytes of the information element advertised by the AP represented by this
     * scan result.
     *
     * @return A byte array, possibly null or containing an invalid TLV configuration.
     */
    @NonNull public byte[] getInformationElements() {
        return infoElement;
    }

    /**
     * Returns the frequency (in MHz) on which the AP represented by this scan result was observed.
     *
     * @return The frequency in MHz.
     */
    public int getFrequencyMhz() {
        return frequency;
    }

    /**
     * Return the signal strength of probe response/beacon in (100 * dBm).
     *
     * @return Signal strenght in (100 * dBm).
     */
    public int getSignalMbm() {
        return signalMbm;
    }

    /**
     * Return the TSF (Timing Synchronization Function) of the received probe response/beacon.
     * @return
     */
    public long getTsf() {
        return tsf;
    }

    /**
     * Return a boolean indicating whether or not we're associated to the AP represented by this
     * scan result.
     *
     * @return A boolean indicating association.
     */
    public boolean isAssociated() {
        return associated;
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"BSS_CAPABILITY_"},
            value = {BSS_CAPABILITY_ESS,
                    BSS_CAPABILITY_IBSS,
                    BSS_CAPABILITY_CF_POLLABLE,
                    BSS_CAPABILITY_CF_POLL_REQUEST,
                    BSS_CAPABILITY_PRIVACY,
                    BSS_CAPABILITY_SHORT_PREAMBLE,
                    BSS_CAPABILITY_PBCC,
                    BSS_CAPABILITY_CHANNEL_AGILITY,
                    BSS_CAPABILITY_SPECTRUM_MANAGEMENT,
                    BSS_CAPABILITY_QOS,
                    BSS_CAPABILITY_SHORT_SLOT_TIME,
                    BSS_CAPABILITY_APSD,
                    BSS_CAPABILITY_RADIO_MANAGEMENT,
                    BSS_CAPABILITY_DSSS_OFDM,
                    BSS_CAPABILITY_DELAYED_BLOCK_ACK,
                    BSS_CAPABILITY_IMMEDIATE_BLOCK_ACK
            })
    public @interface BssCapabilityBits { }

    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): ESS.
     */
    public static final int BSS_CAPABILITY_ESS = 0x1 << 0;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): IBSS.
     */
    public static final int BSS_CAPABILITY_IBSS = 0x1 << 1;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): CF Pollable.
     */
    public static final int BSS_CAPABILITY_CF_POLLABLE = 0x1 << 2;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): CF-Poll Request.
     */
    public static final int BSS_CAPABILITY_CF_POLL_REQUEST = 0x1 << 3;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): Privacy.
     */
    public static final int BSS_CAPABILITY_PRIVACY = 0x1 << 4;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): Short Preamble.
     */
    public static final int BSS_CAPABILITY_SHORT_PREAMBLE = 0x1 << 5;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): PBCC.
     */
    public static final int BSS_CAPABILITY_PBCC = 0x1 << 6;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): Channel Agility.
     */
    public static final int BSS_CAPABILITY_CHANNEL_AGILITY = 0x1 << 7;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): Spectrum Management.
     */
    public static final int BSS_CAPABILITY_SPECTRUM_MANAGEMENT = 0x1 << 8;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): QoS.
     */
    public static final int BSS_CAPABILITY_QOS = 0x1 << 9;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): Short Slot Time.
     */
    public static final int BSS_CAPABILITY_SHORT_SLOT_TIME = 0x1 << 10;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): APSD.
     */
    public static final int BSS_CAPABILITY_APSD = 0x1 << 11;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): Radio Management.
     */
    public static final int BSS_CAPABILITY_RADIO_MANAGEMENT = 0x1 << 12;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): DSSS-OFDM.
     */
    public static final int BSS_CAPABILITY_DSSS_OFDM = 0x1 << 13;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): Delayed Block Ack.
     */
    public static final int BSS_CAPABILITY_DELAYED_BLOCK_ACK = 0x1 << 14;
    /**
     * BSS capability bit (see IEEE Std 802.11: 9.4.1.4): Immediate Block Ack.
     */
    public static final int BSS_CAPABILITY_IMMEDIATE_BLOCK_ACK = 0x1 << 15;

    /**
     *  Returns the capabilities of the AP repseresented by this scan result as advertised in the
     *  received probe response or beacon.
     *
     *  This is a bit mask describing the capabilities of a BSS. See IEEE Std 802.11: 9.4.1.4: one
     *  of the {@code BSS_CAPABILITY_*} flags.
     *
     * @return a bit mask of capabilities.
     */
    @BssCapabilityBits public int getCapabilities() {
        return capability;
    }

    /**
     * Returns details of the signal received on each radio chain for the AP represented by this
     * scan result in a list of {@link RadioChainInfo} elements.
     *
     * @return A list of {@link RadioChainInfo} - possibly empty in case of error.
     */
    @NonNull public List<RadioChainInfo> getRadioChainInfos() {
        return radioChainInfos;
    }

    /**
     * Construct an empty native scan result.
     */
    public NativeScanResult() { }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** implement Parcelable interface */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeByteArray(ssid);
        out.writeByteArray(bssid);
        out.writeByteArray(infoElement);
        out.writeInt(frequency);
        out.writeInt(signalMbm);
        out.writeLong(tsf);
        out.writeInt(capability);
        out.writeInt(associated ? 1 : 0);
        out.writeTypedList(radioChainInfos);
    }

    /** implement Parcelable interface */
    @NonNull public static final Parcelable.Creator<NativeScanResult> CREATOR =
            new Parcelable.Creator<NativeScanResult>() {
        @Override
        public NativeScanResult createFromParcel(Parcel in) {
            NativeScanResult result = new NativeScanResult();
            result.ssid = in.createByteArray();
            if (result.ssid == null) {
                result.ssid = new byte[0];
            }
            result.bssid = in.createByteArray();
            if (result.bssid == null) {
                result.bssid = new byte[0];
            }
            result.infoElement = in.createByteArray();
            if (result.infoElement == null) {
                result.infoElement = new byte[0];
            }
            result.frequency = in.readInt();
            result.signalMbm = in.readInt();
            result.tsf = in.readLong();
            result.capability = in.readInt();
            result.associated = (in.readInt() != 0);
            result.radioChainInfos = new ArrayList<>();
            in.readTypedList(result.radioChainInfos, RadioChainInfo.CREATOR);
            return result;
        }

        @Override
        public NativeScanResult[] newArray(int size) {
            return new NativeScanResult[size];
        }
    };
}
