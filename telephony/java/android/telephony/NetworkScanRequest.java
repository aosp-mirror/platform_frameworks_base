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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * Defines a request to peform a network scan.
 *
 * This class defines whether the network scan will be performed only once or periodically until
 * cancelled, when the scan is performed periodically, the time interval is not controlled by the
 * user but defined by the modem vendor.
 * @hide
 */
public final class NetworkScanRequest implements Parcelable {

    // Below size limits for RAN/Band/Channel are for pre-treble modems and will be removed later.
    /** @hide */
    public static final int MAX_RADIO_ACCESS_NETWORKS = 8;
    /** @hide */
    public static final int MAX_BANDS = 8;
    /** @hide */
    public static final int MAX_CHANNELS = 32;

    /** Performs the scan only once */
    public static final int SCAN_TYPE_ONE_SHOT = 0;
    /**
     * Performs the scan periodically until cancelled
     *
     * The modem will start new scans periodically, and the interval between two scans is usually
     * multiple minutes.
     * */
    public static final int SCAN_TYPE_PERIODIC = 1;

    /** Defines the type of the scan. */
    public int scanType;

    /** Describes the radio access technologies with bands or channels that need to be scanned. */
    public RadioAccessSpecifier[] specifiers;

    /**
     * Creates a new NetworkScanRequest with scanType and network specifiers
     *
     * @param scanType The type of the scan
     * @param specifiers the radio network with bands / channels to be scanned
     */
    public NetworkScanRequest(int scanType, RadioAccessSpecifier[] specifiers) {
        this.scanType = scanType;
        this.specifiers = specifiers;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(scanType);
        dest.writeParcelableArray(specifiers, flags);
    }

    private NetworkScanRequest(Parcel in) {
        scanType = in.readInt();
        specifiers = (RadioAccessSpecifier[]) in.readParcelableArray(
                Object.class.getClassLoader(),
                RadioAccessSpecifier.class);
    }

    @Override
    public boolean equals (Object o) {
        NetworkScanRequest nsr;

        try {
            nsr = (NetworkScanRequest) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return (scanType == nsr.scanType
                && Arrays.equals(specifiers, nsr.specifiers));
    }

    @Override
    public int hashCode () {
        return ((scanType * 31)
                + (Arrays.hashCode(specifiers)) * 37);
    }

    public static final Creator<NetworkScanRequest> CREATOR =
            new Creator<NetworkScanRequest>() {
                @Override
                public NetworkScanRequest createFromParcel(Parcel in) {
                    return new NetworkScanRequest(in);
                }

                @Override
                public NetworkScanRequest[] newArray(int size) {
                    return new NetworkScanRequest[size];
                }
            };
}
