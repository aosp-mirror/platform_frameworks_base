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

package android.net.apf;

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;

import com.android.internal.R;

/**
 * APF program support capabilities.
 *
 * @hide
 */
@SystemApi
@TestApi
public class ApfCapabilities {
    /**
     * Version of APF instruction set supported for packet filtering. 0 indicates no support for
     * packet filtering using APF programs.
     */
    public final int apfVersionSupported;

    /**
     * Maximum size of APF program allowed.
     */
    public final int maximumApfProgramSize;

    /**
     * Format of packets passed to APF filter. Should be one of ARPHRD_*
     */
    public final int apfPacketFormat;

    public ApfCapabilities(
            int apfVersionSupported, int maximumApfProgramSize, int apfPacketFormat) {
        this.apfVersionSupported = apfVersionSupported;
        this.maximumApfProgramSize = maximumApfProgramSize;
        this.apfPacketFormat = apfPacketFormat;
    }

    @Override
    public String toString() {
        return String.format("%s{version: %d, maxSize: %d, format: %d}", getClass().getSimpleName(),
                apfVersionSupported, maximumApfProgramSize, apfPacketFormat);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof  ApfCapabilities)) return false;
        final ApfCapabilities other = (ApfCapabilities) obj;
        return apfVersionSupported == other.apfVersionSupported
                && maximumApfProgramSize == other.maximumApfProgramSize
                && apfPacketFormat == other.apfPacketFormat;
    }

    /**
     * Returns true if the APF interpreter advertises support for the data buffer access opcodes
     * LDDW and STDW.
     *
     * Full LDDW and STDW support is present from APFv4 on.
     */
    public boolean hasDataAccess() {
        return apfVersionSupported >= 4;
    }

    /**
     * @return Whether the APF Filter in the device should filter out IEEE 802.3 Frames.
     */
    public static boolean getApfDrop8023Frames(Context context) {
        return context.getResources().getBoolean(R.bool.config_apfDrop802_3Frames);
    }

    /**
     * @return An array of blacklisted EtherType, packets with EtherTypes within it will be dropped.
     */
    public static int[] getApfEthTypeBlackList(Context context) {
        return context.getResources().getIntArray(R.array.config_apfEthTypeBlackList);
    }
}
