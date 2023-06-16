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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * Defines a request to perform a network scan.
 *
 * This class defines whether the network scan will be performed only once or periodically until
 * cancelled, when the scan is performed periodically, the time interval is not controlled by the
 * user but defined by the modem vendor.
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

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        SCAN_TYPE_ONE_SHOT,
        SCAN_TYPE_PERIODIC,
    })
    public @interface ScanType {}

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
    private int mScanType;

    /**
     * Search periodicity (in seconds).
     * Expected range for the input is [5s - 300s]
     * This value must be less than or equal to mMaxSearchTime
     */
    private int mSearchPeriodicity;

    /**
     * Maximum duration of the periodic search (in seconds).
     * Expected range for the input is [60s - 3600s]
     * If the search lasts this long, it will be terminated.
     */
    private int mMaxSearchTime;

    /**
     * Indicates whether the modem should report incremental
     * results of the network scan to the client.
     * FALSE – Incremental results are not reported.
     * TRUE (default) – Incremental results are reported
     */
    private boolean mIncrementalResults;

    /**
     * Indicates the periodicity with which the modem should
     * report incremental results to the client (in seconds).
     * Expected range for the input is [1s - 10s]
     * This value must be less than or equal to mMaxSearchTime
     */
    private int mIncrementalResultsPeriodicity;

    /** Describes the radio access technologies with bands or channels that need to be scanned. */
    @Nullable
    private RadioAccessSpecifier[] mSpecifiers;

    /**
     * Describes the List of PLMN ids (MCC-MNC)
     * If any PLMN of this list is found, search should end at that point and
     * results with all PLMN found till that point should be sent as response.
     * If list not sent, search to be completed till end and all PLMNs found to be reported.
     * Max size of array is MAX_MCC_MNC_LIST_SIZE
     */
    @NonNull
    private ArrayList<String> mMccMncs;

    /**
     * Creates a new NetworkScanRequest with mScanType and network mSpecifiers
     *
     * @param scanType The type of the scan, can be either one shot or periodic
     * @param specifiers the radio network with bands / channels to be scanned
     * @param searchPeriodicity The modem will restart the scan every searchPeriodicity seconds if
     *                          no network has been found, until it reaches the maxSearchTime. Only
     *                          valid when scan type is periodic scan.
     * @param maxSearchTime Maximum duration of the search (in seconds)
     * @param incrementalResults Indicates whether the modem should report incremental
     *                           results of the network scan to the client
     * @param incrementalResultsPeriodicity Indicates the periodicity with which the modem should
     *                                      report incremental results to the client (in seconds),
     *                                      only valid when incrementalResults is true
     * @param mccMncs Describes the list of PLMN ids (MCC-MNC), once any network in the list has
     *                been found, the scan will be terminated by the modem.
     */
    public NetworkScanRequest(int scanType, RadioAccessSpecifier[] specifiers,
                    int searchPeriodicity,
                    int maxSearchTime,
                    boolean incrementalResults,
                    int incrementalResultsPeriodicity,
                    ArrayList<String> mccMncs) {
        this.mScanType = scanType;
        if (specifiers != null) {
            this.mSpecifiers = specifiers.clone();
        } else {
            this.mSpecifiers = null;
        }
        this.mSearchPeriodicity = searchPeriodicity;
        this.mMaxSearchTime = maxSearchTime;
        this.mIncrementalResults = incrementalResults;
        this.mIncrementalResultsPeriodicity = incrementalResultsPeriodicity;
        if (mccMncs != null) {
            this.mMccMncs = (ArrayList<String>) mccMncs.clone();
        } else {
            this.mMccMncs = new ArrayList<>();
        }
    }

    /** Returns the type of the scan. */
    @ScanType
    public int getScanType() {
        return mScanType;
    }

    /** Returns the search periodicity in seconds. */
    public int getSearchPeriodicity() {
        return mSearchPeriodicity;
    }

    /** Returns maximum duration of the periodic search in seconds. */
    public int getMaxSearchTime() {
        return mMaxSearchTime;
    }

    /**
     * Returns whether incremental result is enabled.
     * FALSE – Incremental results is not enabled.
     * TRUE – Incremental results is reported.
     */
    public boolean getIncrementalResults() {
        return mIncrementalResults;
    }

    /** Returns the periodicity in seconds of incremental results. */
    public int getIncrementalResultsPeriodicity() {
        return mIncrementalResultsPeriodicity;
    }

    /** Returns the radio access technologies with bands or channels that need to be scanned. */
    public RadioAccessSpecifier[] getSpecifiers() {
        return mSpecifiers == null ? null : mSpecifiers.clone();
    }

    /**
     * Returns the List of PLMN ids (MCC-MNC) for early termination of scan.
     * If any PLMN of this list is found, search should end at that point and
     * results with all PLMN found till that point should be sent as response.
     */
    public ArrayList<String> getPlmns() {
        return (ArrayList<String>) mMccMncs.clone();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mScanType);
        dest.writeParcelableArray(mSpecifiers, flags);
        dest.writeInt(mSearchPeriodicity);
        dest.writeInt(mMaxSearchTime);
        dest.writeBoolean(mIncrementalResults);
        dest.writeInt(mIncrementalResultsPeriodicity);
        dest.writeStringList(mMccMncs);
    }

    private NetworkScanRequest(Parcel in) {
        mScanType = in.readInt();
        Parcelable[] tempSpecifiers = in.readParcelableArray(Object.class.getClassLoader(),
                RadioAccessSpecifier.class);
        if (tempSpecifiers != null) {
            mSpecifiers = new RadioAccessSpecifier[tempSpecifiers.length];
            for (int i = 0; i < tempSpecifiers.length; i++) {
                mSpecifiers[i] = (RadioAccessSpecifier) tempSpecifiers[i];
            }
        } else {
            mSpecifiers = null;
        }
        mSearchPeriodicity = in.readInt();
        mMaxSearchTime = in.readInt();
        mIncrementalResults = in.readBoolean();
        mIncrementalResultsPeriodicity = in.readInt();
        mMccMncs = new ArrayList<>();
        in.readStringList(mMccMncs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;

        if (!(other instanceof NetworkScanRequest)) return false;

        NetworkScanRequest nsr = (NetworkScanRequest) other;

        return mScanType == nsr.mScanType
                && Arrays.equals(mSpecifiers, nsr.mSpecifiers)
                && mSearchPeriodicity == nsr.mSearchPeriodicity
                && mMaxSearchTime == nsr.mMaxSearchTime
                && mIncrementalResults == nsr.mIncrementalResults
                && mIncrementalResultsPeriodicity == nsr.mIncrementalResultsPeriodicity
                && Objects.equals(mMccMncs, nsr.mMccMncs);
    }

    @Override
    public int hashCode () {
        return ((mScanType * 31)
                + (Arrays.hashCode(mSpecifiers)) * 37
                + (mSearchPeriodicity * 41)
                + (mMaxSearchTime * 43)
                + ((mIncrementalResults == true? 1 : 0) * 47)
                + (mIncrementalResultsPeriodicity * 53)
                + (mMccMncs.hashCode() * 59));
    }

    public static final @android.annotation.NonNull Creator<NetworkScanRequest> CREATOR =
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
