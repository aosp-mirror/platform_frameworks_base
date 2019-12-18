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

package android.net.wifi.wificond;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Raw scan result data from the wificond daemon.
 *
 * @hide
 */
@SystemApi
public final class NativeScanResult implements Parcelable {
    private static final int CAPABILITY_SIZE = 16;

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
    public BitSet capability;
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
     * Returns raw bytes representing the MAC address (BSSID) of the AP represented by this scan
     * result.
     *
     * @return a byte array, possibly null or containing the incorrect number of bytes for a MAC
     * address.
     */
    @NonNull public byte[] getBssid() {
        return bssid;
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

    /**
     *  Returns the capabilities of the AP repseresented by this scan result as advertised in the
     *  received probe response or beacon.
     *
     *  This is a bit mask describing the capabilities of a BSS. See IEEE Std 802.11: 8.4.1.4:
     *    Bit 0 - ESS
     *    Bit 1 - IBSS
     *    Bit 2 - CF Pollable
     *    Bit 3 - CF-Poll Request
     *    Bit 4 - Privacy
     *    Bit 5 - Short Preamble
     *    Bit 6 - PBCC
     *    Bit 7 - Channel Agility
     *    Bit 8 - Spectrum Mgmt
     *    Bit 9 - QoS
     *    Bit 10 - Short Slot Time
     *    Bit 11 - APSD
     *    Bit 12 - Radio Measurement
     *    Bit 13 - DSSS-OFDM
     *    Bit 14 - Delayed Block Ack
     *    Bit 15 - Immediate Block Ack
     *
     * @return a bit mask of capabilities.
     */
    @NonNull public BitSet getCapabilities() {
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
     * @hide
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
        int capabilityInt = 0;
        for (int i = 0; i < CAPABILITY_SIZE; i++) {
            if (capability.get(i)) {
                capabilityInt |= 1 << i;
            }
        }
        out.writeInt(capabilityInt);
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
            int capabilityInt = in.readInt();
            result.capability = new BitSet(CAPABILITY_SIZE);
            for (int i = 0; i < CAPABILITY_SIZE; i++) {
                if ((capabilityInt & (1 << i)) != 0) {
                    result.capability.set(i);
                }
            }
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
