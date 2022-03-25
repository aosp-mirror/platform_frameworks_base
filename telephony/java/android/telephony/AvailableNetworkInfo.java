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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.RadioAccessSpecifier;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    /** @hide */
    @IntDef({
        PRIORITY_HIGH,
        PRIORITY_MED,
        PRIORITY_LOW,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AvailableNetworkInfoPriority {}
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
    private @AvailableNetworkInfoPriority int mPriority;

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
     *
     * @deprecated use {@link #mRadioAccessSpecifiers} instead
     */
    @Deprecated
    private ArrayList<Integer> mBands;

    /**
     * Returns a list of {@link RadioAccessSpecifier} associated with the available network.
     * Opportunistic network service will use this to determine which bands to scan for.
     *
     * If this entry is left empty, {@link RadioAcccessSpecifier}s with {@link AccessNetworkType}s
     * of {@link AccessNetworkConstants.AccessNetworkType.EUTRAN} and {@link
     * AccessNetworkConstants.AccessNetworkType.NGRAN} with bands 48 and 71 on each will be assumed
     * by Opportunistic network service for a network scan.
     */
    private ArrayList<RadioAccessSpecifier> mRadioAccessSpecifiers;

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
    @AvailableNetworkInfoPriority
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

    /**
     * Returns a list of {@link RadioAccessSpecifier} associated with the available network.
     * Opportunistic network service will use this to determine which bands to scan for.
     *
     * @return the access network type associated with the available network.
     */
    public @NonNull List<RadioAccessSpecifier> getRadioAccessSpecifiers() {
        return (List<RadioAccessSpecifier>) mRadioAccessSpecifiers.clone();
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
        dest.writeList(mRadioAccessSpecifiers);
    }

    private AvailableNetworkInfo(Parcel in) {
        mSubId = in.readInt();
        mPriority = in.readInt();
        mMccMncs = new ArrayList<>();
        in.readStringList(mMccMncs);
        mBands = new ArrayList<>();
        in.readList(mBands, Integer.class.getClassLoader(), java.lang.Integer.class);
        mRadioAccessSpecifiers = new ArrayList<>();
        in.readList(mRadioAccessSpecifiers, RadioAccessSpecifier.class.getClassLoader(), android.telephony.RadioAccessSpecifier.class);
    }

    public AvailableNetworkInfo(int subId, int priority, @NonNull List<String> mccMncs,
            @NonNull List<Integer> bands) {
        this(subId, priority, mccMncs, bands,
                new ArrayList<RadioAccessSpecifier>());
    }

    /** @hide */
    private AvailableNetworkInfo(int subId, @AvailableNetworkInfoPriority int priority,
            @NonNull List<String> mccMncs, @NonNull List<Integer> bands,
            @NonNull List<RadioAccessSpecifier> radioAccessSpecifiers) {
        mSubId = subId;
        mPriority = priority;
        mMccMncs = new ArrayList<String>(mccMncs);
        mBands = new ArrayList<Integer>(bands);
        mRadioAccessSpecifiers = new ArrayList<RadioAccessSpecifier>(radioAccessSpecifiers);
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
            && mBands.equals(ani.mBands))
            && mRadioAccessSpecifiers.equals(ani.getRadioAccessSpecifiers());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSubId, mPriority, mMccMncs, mBands, mRadioAccessSpecifiers);
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
            + " mBands: " + Arrays.toString(mBands.toArray())
            + " mRadioAccessSpecifiers: " + Arrays.toString(mRadioAccessSpecifiers.toArray()));
    }

    /**
     * Provides a convenient way to set the fields of a {@link AvailableNetworkInfo} when
     * creating a new instance.
     *
     * <p>The example below shows how you might create a new {@code AvailableNetworkInfo}:
     *
     * <pre><code>
     *
     * AvailableNetworkInfo aNI = new AvailableNetworkInfo.Builder(subId)
     *     .setPriority(AvailableNetworkInfo.PRIORITY_MED)
     *     .setRadioAccessSpecifiers(radioAccessSpecifiers)
     *     .setMccMncs(mccMncs)
     *     .build();
     * </code></pre>
     */
    public static final class Builder {
        private int mSubId = Integer.MIN_VALUE;
        private @AvailableNetworkInfoPriority int mPriority = AvailableNetworkInfo.PRIORITY_LOW;
        private ArrayList<String> mMccMncs = new ArrayList<>();
        private ArrayList<RadioAccessSpecifier> mRadioAccessSpecifiers = new ArrayList<>();

        /**
         *
         */
        /**
         * Creates an AvailableNetworkInfo Builder with specified subscription id.
         *
         * @param subId of the availableNetwork.
         */
        public Builder(int subId) {
            mSubId = subId;
        }

        /**
         * Sets the priority for the subscription id.
         *
         * @param priority of the subscription id. See {@link AvailableNetworkInfo#getPriority} for
         * more details
         * @return the original Builder object.
         */
        public @NonNull Builder setPriority(@AvailableNetworkInfoPriority int priority) {
            if (priority > AvailableNetworkInfo.PRIORITY_LOW
                    || priority < AvailableNetworkInfo.PRIORITY_HIGH) {
                throw new IllegalArgumentException("A valid priority must be set");
            }
            mPriority = priority;
            return this;
        }

        /**
         * Sets the list of mccmncs associated with the subscription id.
         *
         * @param mccMncs nonull list of mccmncs. An empty List is still accepted. Please read
         * documentation in {@link AvailableNetworkInfo} to see consequences of an empty List.
         * @return the original Builder object.
         */
        public @NonNull Builder setMccMncs(@NonNull List<String> mccMncs) {
            Objects.requireNonNull(mccMncs, "A non-null List of mccmncs must be set. An empty "
                    + "List is still accepted. Please read documentation in "
                    + "AvailableNetworkInfo to see consequences of an empty List.");
            mMccMncs = new ArrayList<>(mccMncs);
            return this;
        }

        /**
         * Sets the list of mccmncs associated with the subscription id.
         *
         * @param radioAccessSpecifiers nonull list of radioAccessSpecifiers. An empty List is still
         * accepted. Please read documentation in {@link AvailableNetworkInfo} to see
         * consequences of an empty List.
         * @return the original Builder object.
         */
        public @NonNull Builder setRadioAccessSpecifiers(
                @NonNull List<RadioAccessSpecifier> radioAccessSpecifiers) {
            Objects.requireNonNull(radioAccessSpecifiers, "A non-null List of "
                    + "RadioAccessSpecifiers must be set. An empty List is still accepted. Please "
                    + "read documentation in AvailableNetworkInfo to see consequences of an "
                    + "empty List.");
            mRadioAccessSpecifiers = new ArrayList<>(radioAccessSpecifiers);
            return this;
        }

        /**
         * @return an AvailableNetworkInfo object with all the fields previously set by the Builder.
         */
        public @NonNull AvailableNetworkInfo build() {
            if (mSubId == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("A valid subId must be set");
            }

            return new AvailableNetworkInfo(mSubId, mPriority, mMccMncs, new ArrayList<>(),
                    mRadioAccessSpecifiers);
        }
    }
}
