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

package com.android.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.CellInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Defines the incremental network scan result.
 *
 * This class contains the network scan results. When the user starts a new scan, multiple
 * NetworkScanResult may be returned, containing either the scan result or error. When the user
 * stops an ongoing scan, only one NetworkScanResult will be returned to indicate either the scan
 * is now complete or there is some error stopping it.
 * @hide
 */
public final class NetworkScanResult implements Parcelable {

    // Contains only part of the scan result and more are coming.
    public static final int SCAN_STATUS_PARTIAL = 1;

    // Contains the last part of the scan result and the scan is now complete.
    public static final int SCAN_STATUS_COMPLETE = 2;

    // The status of the scan, only valid when scanError = SUCCESS.
    public int scanStatus;

    /**
     * The error code of the scan
     *
     * This is the error code returned from the RIL, see {@link RILConstants} for more details
     */
    public int scanError;

    // The scan results, only valid when scanError = SUCCESS.
    public List<CellInfo> networkInfos;

    /**
     * Creates a new NetworkScanResult with scanStatus, scanError and networkInfos
     *
     * @param scanStatus The status of the scan.
     * @param scanError The error code of the scan.
     * @param networkInfos List of the CellInfo.
     */
    public NetworkScanResult(int scanStatus, int scanError, List<CellInfo> networkInfos) {
        this.scanStatus = scanStatus;
        this.scanError = scanError;
        this.networkInfos = networkInfos;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(scanStatus);
        dest.writeInt(scanError);
        dest.writeParcelableList(networkInfos, flags);
    }

    private NetworkScanResult(Parcel in) {
        scanStatus = in.readInt();
        scanError = in.readInt();
        List<CellInfo> ni = new ArrayList<>();
        in.readParcelableList(ni, Object.class.getClassLoader(), android.telephony.CellInfo.class);
        networkInfos = ni;
    }

    @Override
    public boolean equals (Object o) {
        NetworkScanResult nsr;

        try {
            nsr = (NetworkScanResult) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return (scanStatus == nsr.scanStatus
                && scanError == nsr.scanError
                && networkInfos.equals(nsr.networkInfos));
    }

    @Override
    public String toString() {
        return new StringBuilder()
            .append("{")
            .append("scanStatus=" + scanStatus)
            .append(", scanError=" + scanError)
            .append(", networkInfos=" + networkInfos)
            .append("}")
            .toString();
    }

    @Override
    public int hashCode () {
        return ((scanStatus * 31)
                + (scanError * 23)
                + (Objects.hashCode(networkInfos) * 37));
    }

    public static final Creator<NetworkScanResult> CREATOR =
        new Creator<NetworkScanResult>() {
            @Override
            public NetworkScanResult createFromParcel(Parcel in) {
                return new NetworkScanResult(in);
            }

            @Override
            public NetworkScanResult[] newArray(int size) {
                return new NetworkScanResult[size];
            }
        };
}
