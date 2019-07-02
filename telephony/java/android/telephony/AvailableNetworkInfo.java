/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Defines available network information which includes corresponding subscription id,
 * network plmns and corresponding priority to be used for network selection by Opportunistic
 * Network Service when passed through {@link TelephonyManager#updateAvailableNetworks}
 */
public final class AvailableNetworkInfo implements Parcelable {

    /*
     * Defines number of priority level high.
     */
    public static final int PRIORITY_HIGH = 1;

    /*
     * Defines number of priority level medium.
     */
    public static final int PRIORITY_MED = 2;

    /*
     * Defines number of priority level low.
     */
    public static final int PRIORITY_LOW = 3;

    /**
     * subscription Id of the available network. This value must be one of the entry retrieved from
     * {@link SubscriptionManager#getOpportunisticSubscriptions}
     */
    private int mSubId;

    /**
     * Priority for the subscription id.
     * Priorities are in the range of {@link AvailableNetworkInfo#PRIORITY_LOW} to
     * {@link AvailableNetworkInfo#PRIORITY_HIGH}
     * Among all networks available after network scan, subId with highest priority is chosen
     * for network selection. If there are more than one subId with highest priority then the
     * network with highest RSRP is chosen.
     */
    private int mPriority;

    /**
     * Describes the List of PLMN ids (MCC-MNC) associated with mSubId.
     * Opportunistic Network Service will scan and verify specified PLMNs are available.
     * If this entry is left empty, then the Opportunistic Network Service will not scan the network
     * to validate the network availability.
     */
    private ArrayList<String> mMccMncs;

    /**
     * Returns the frequency bands associated with the {@link #getMccMncs() MCC/MNCs}.
     * Opportunistic network service will use these bands to scan.
     *
     * When no specific bands are specified (empty array or null) CBRS band
     * {@link AccessNetworkConstants.EutranBand.BAND_48} will be used for network scan.
     *
     * See {@link AccessNetworkConstants} for details.
     */
    private ArrayList<Integer> mBands;

    /**
     * Return subscription Id of the available network.
     * This value must be one of the entry retrieved from
     * {@link SubscriptionManager#getOpportunisticSubscriptions}
     * @return subscription id
     */
    public int getSubId() {
        return mSubId;
    }

    /**
     * Return priority for the subscription id.
     * Priorities are in the range of {@link AvailableNetworkInfo#PRIORITY_LOW} to
     * {@link AvailableNetworkInfo#PRIORITY_HIGH}
     * Among all networks available after network scan, subId with highest priority is chosen
     * for network selection. If there are more than one subId with highest priority then the
     * network with highest RSRP is chosen.
     * @return priority level
     */
    public int getPriority() {
        return mPriority;
    }

    /**
     * Return List of PLMN ids (MCC-MNC) associated with the sub ID.
     * Opportunistic Network Service will scan and verify specified PLMNs are available.
     * If this entry is left empty, then the Opportunistic Network Service will not scan the network
     * to validate the network availability.
     * @return list of PLMN ids
     */
    public @NonNull List<String> getMccMncs() {
        return (List<String>) mMccMncs.clone();
    }

    /**
     * Returns the frequency bands that need to be scanned by opportunistic network service
     *
     * The returned value is defined in either of {@link AccessNetworkConstants.GeranBand},
     * {@link AccessNetworkConstants.UtranBand} and {@link AccessNetworkConstants.EutranBand}
     * See {@link AccessNetworkConstants.AccessNetworkType} for details regarding different network
     * types. When no specific bands are specified (empty array or null) CBRS band
     * {@link AccessNetworkConstants.EutranBand#BAND_48} will be used for network scan.
     */
    public @NonNull List<Integer> getBands() {
        return (List<Integer>) mBands.clone();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSubId);
        dest.writeInt(mPriority);
        dest.writeStringList(mMccMncs);
        dest.writeList(mBands);
    }

    private AvailableNetworkInfo(Parcel in) {
        mSubId = in.readInt();
        mPriority = in.readInt();
        mMccMncs = new ArrayList<>();
        in.readStringList(mMccMncs);
        mBands = new ArrayList<>();
        in.readList(mBands, Integer.class.getClassLoader());
    }

    public AvailableNetworkInfo(int subId, int priority, @NonNull List<String> mccMncs,
            @NonNull List<Integer> bands) {
        mSubId = subId;
        mPriority = priority;
        mMccMncs = new ArrayList<String>(mccMncs);
        mBands = new ArrayList<Integer>(bands);
    }

    @Override
    public boolean equals(Object o) {
        AvailableNetworkInfo ani;

        try {
            ani = (AvailableNetworkInfo) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return (mSubId == ani.mSubId
            && mPriority == ani.mPriority
            && (((mMccMncs != null)
            && mMccMncs.equals(ani.mMccMncs)))
            && mBands.equals(ani.mBands));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSubId, mPriority, mMccMncs, mBands);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AvailableNetworkInfo> CREATOR =
            new Creator<AvailableNetworkInfo>() {
                @Override
                public AvailableNetworkInfo createFromParcel(Parcel in) {
                    return new AvailableNetworkInfo(in);
                }

                @Override
                public AvailableNetworkInfo[] newArray(int size) {
                    return new AvailableNetworkInfo[size];
                }
            };

    @Override
    public String toString() {
        return ("AvailableNetworkInfo:"
            + " mSubId: " + mSubId
            + " mPriority: " + mPriority
            + " mMccMncs: " + Arrays.toString(mMccMncs.toArray())
            + " mBands: " + Arrays.toString(mBands.toArray()));
    }
}
