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

import java.util.ArrayList;
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
    /** @hide */
    public static final int MAX_MCC_MNC_LIST_SIZE = 20;
    /** @hide */
    public static final int MIN_SEARCH_PERIODICITY_SEC = 5;
    /** @hide */
    public static final int MAX_SEARCH_PERIODICITY_SEC = 300;
    /** @hide */
    public static final int MIN_SEARCH_MAX_SEC = 60;
    /** @hide */
    public static final int MAX_SEARCH_MAX_SEC = 3600;
    /** @hide */
    public static final int MIN_INCREMENTAL_PERIODICITY_SEC = 1;
    /** @hide */
    public static final int MAX_INCREMENTAL_PERIODICITY_SEC = 10;

    /** Performs the scan only once */
    public static final int SCAN_TYPE_ONE_SHOT = 0;
    /**
     * Performs the scan periodically until cancelled
     *
     * The modem will start new scans periodically, and the interval between two scans is usually
     * multiple minutes.
     */
    public static final int SCAN_TYPE_PERIODIC = 1;

    /** Defines the type of the scan. */
    public int scanType;

    /**
     * Search periodicity (in seconds).
     * Expected range for the input is [5s - 300s]
     * This value must be less than or equal to maxSearchTime
     */
    public int searchPeriodicity;

    /**
     * Maximum duration of the periodic search (in seconds).
     * Expected range for the input is [60s - 3600s]
     * If the search lasts this long, it will be terminated.
     */
    public int maxSearchTime;

    /**
     * Indicates whether the modem should report incremental
     * results of the network scan to the client.
     * FALSE – Incremental results are not reported.
     * TRUE (default) – Incremental results are reported
     */
    public boolean incrementalResults;

    /**
     * Indicates the periodicity with which the modem should
     * report incremental results to the client (in seconds).
     * Expected range for the input is [1s - 10s]
     * This value must be less than or equal to maxSearchTime
     */
    public int incrementalResultsPeriodicity;

    /** Describes the radio access technologies with bands or channels that need to be scanned. */
    public RadioAccessSpecifier[] specifiers;

    /**
     * Describes the List of PLMN ids (MCC-MNC)
     * If any PLMN of this list is found, search should end at that point and
     * results with all PLMN found till that point should be sent as response.
     * If list not sent, search to be completed till end and all PLMNs found to be reported.
     * Max size of array is MAX_MCC_MNC_LIST_SIZE
     */
    public ArrayList<String> mccMncs;

    /**
     * Creates a new NetworkScanRequest with scanType and network specifiers
     *
     * @param scanType The type of the scan
     * @param specifiers the radio network with bands / channels to be scanned
     * @param searchPeriodicity Search periodicity (in seconds)
     * @param maxSearchTime Maximum duration of the periodic search (in seconds)
     * @param incrementalResults Indicates whether the modem should report incremental
     *                           results of the network scan to the client
     * @param incrementalResultsPeriodicity Indicates the periodicity with which the modem should
     *                                      report incremental results to the client (in seconds)
     * @param mccMncs Describes the List of PLMN ids (MCC-MNC)
     */
    public NetworkScanRequest(int scanType, RadioAccessSpecifier[] specifiers,
                    int searchPeriodicity,
                    int maxSearchTime,
                    boolean incrementalResults,
                    int incrementalResultsPeriodicity,
                    ArrayList<String> mccMncs) {
        this.scanType = scanType;
        this.specifiers = specifiers;
        this.searchPeriodicity = searchPeriodicity;
        this.maxSearchTime = maxSearchTime;
        this.incrementalResults = incrementalResults;
        this.incrementalResultsPeriodicity = incrementalResultsPeriodicity;
        if (mccMncs != null) {
            this.mccMncs = mccMncs;
        } else {
            this.mccMncs = new ArrayList<>();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(scanType);
        dest.writeParcelableArray(specifiers, flags);
        dest.writeInt(searchPeriodicity);
        dest.writeInt(maxSearchTime);
        dest.writeBoolean(incrementalResults);
        dest.writeInt(incrementalResultsPeriodicity);
        dest.writeStringList(mccMncs);
    }

    private NetworkScanRequest(Parcel in) {
        scanType = in.readInt();
        specifiers = (RadioAccessSpecifier[]) in.readParcelableArray(
                Object.class.getClassLoader(),
                RadioAccessSpecifier.class);
        searchPeriodicity = in.readInt();
        maxSearchTime = in.readInt();
        incrementalResults = in.readBoolean();
        incrementalResultsPeriodicity = in.readInt();
        mccMncs = new ArrayList<>();
        in.readStringList(mccMncs);
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
                && Arrays.equals(specifiers, nsr.specifiers)
                && searchPeriodicity == nsr.searchPeriodicity
                && maxSearchTime == nsr.maxSearchTime
                && incrementalResults == nsr.incrementalResults
                && incrementalResultsPeriodicity == nsr.incrementalResultsPeriodicity
                && (((mccMncs != null)
                && mccMncs.equals(nsr.mccMncs))));
    }

    @Override
    public int hashCode () {
        return ((scanType * 31)
                + (Arrays.hashCode(specifiers)) * 37
                + (searchPeriodicity * 41)
                + (maxSearchTime * 43)
                + ((incrementalResults == true? 1 : 0) * 47)
                + (incrementalResultsPeriodicity * 53)
                + (mccMncs.hashCode() * 59));
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
